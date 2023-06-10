/*
 * Copyright 2014 - 2022 The "Open Radio" Project. Author: Chernyshov Yuriy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yuriy.openradio.shared.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.yuriy.openradio.R
import com.yuriy.openradio.shared.broadcast.AppLocalBroadcast
import com.yuriy.openradio.shared.broadcast.BTConnectionReceiver
import com.yuriy.openradio.shared.broadcast.BecomingNoisyReceiver
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommon
import com.yuriy.openradio.shared.model.media.BrowseTree
import com.yuriy.openradio.shared.model.media.MediaId
import com.yuriy.openradio.shared.model.media.MediaStream
import com.yuriy.openradio.shared.model.media.RadioStation
import com.yuriy.openradio.shared.model.media.RemoteControlListener
import com.yuriy.openradio.shared.model.media.getStreamUrlFixed
import com.yuriy.openradio.shared.model.media.isInvalid
import com.yuriy.openradio.shared.model.media.item.MediaItemCommand
import com.yuriy.openradio.shared.model.media.item.MediaItemCommandDependencies
import com.yuriy.openradio.shared.model.media.setVariantFixed
import com.yuriy.openradio.shared.model.net.NetworkMonitorListener
import com.yuriy.openradio.shared.model.storage.AppPreferencesManager
import com.yuriy.openradio.shared.model.storage.RadioStationsStorage
import com.yuriy.openradio.shared.model.timer.SleepTimerListener
import com.yuriy.openradio.shared.service.player.OpenRadioPlayer
import com.yuriy.openradio.shared.utils.AnalyticsUtils
import com.yuriy.openradio.shared.utils.AppLogger
import com.yuriy.openradio.shared.utils.AppUtils
import com.yuriy.openradio.shared.utils.IntentUtils
import com.yuriy.openradio.shared.utils.MediaItemBuilder
import com.yuriy.openradio.shared.utils.NetUtils
import com.yuriy.openradio.shared.utils.PackageValidator
import com.yuriy.openradio.shared.utils.PlayerUtils
import com.yuriy.openradio.shared.utils.SafeToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.TreeSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 12/13/14
 * E-Mail: chernyshov.yuriy@gmail.com
 */
@UnstableApi
class OpenRadioService : MediaLibraryService() {

    /**
     * Player to play Radio stream.
     */
    private val mPlayer by lazy {
        OpenRadioPlayer(
            applicationContext, PlayerListener(), mPresenter.getEqualizerLayer()
        )
    }

    /**
     * Media Session.
     */
    private lateinit var mSession: MediaLibrarySession

    private val mPackageValidator by lazy {
        PackageValidator(applicationContext, R.xml.allowed_media_browser_callers)
    }

    private var mIsPackageValid = false

    private val mDelayedStopHandler: Handler

    /**
     * Track selected Radio Station.
     */
    private var mActiveRS = RadioStation.INVALID_INSTANCE
    private val mUiScope: CoroutineScope
    private val mScope: CoroutineScope
    private val mCommandScope: CoroutineScope
    private val mRestoreComplete = AtomicBoolean(false)

    private val mBTConnectionReceiver = BTConnectionReceiver(

        object : BTConnectionReceiver.Listener {
            override fun onSameDeviceConnected() {
                handleBTSameDeviceConnected()
            }

            override fun onDisconnected() {
                callPause()
            }
        }
    )
    private var mNoisyAudioStreamReceiver = BecomingNoisyReceiver(

        object : BecomingNoisyReceiver.Listener {
            override fun onAudioBecomingNoisy() {
                callPause()
            }
        }
    )
    private val mNetMonitorListener = NetworkMonitorListenerImpl()

    /**
     * Processes Messages sent to it from onStartCommand() that indicate which command to process.
     */
    @Volatile
    private lateinit var mServiceHandler: ServiceHandler

    /**
     * Storage of Radio Stations to browse.
     */
    private val mBrowseStorage: RadioStationsStorage
    private val mLatestPlaylist = TreeSet<RadioStation>()

    /**
     * Storage of Radio Stations queried from Search.
     */
    private val mSearchStorage: RadioStationsStorage
    private val mStorageListener: RadioStationsStorage.Listener
    private val mStartIds: ConcurrentLinkedQueue<Int>
    private var mCurrentParentId = AppUtils.EMPTY_STRING

    /**
     * Current media player state.
     */
    @Volatile
    private var mPlayerState = Player.STATE_IDLE
    private var mIsRestoreState = false

    private lateinit var mPresenter: OpenRadioServicePresenter
    private val mSleepTimerListener = SleepTimerListenerImpl()
    private val mRemoteControlListener = RemoteControlListenerImpl()

    private val mBrowseTree: BrowseTree by lazy {
        BrowseTree()
    }

    private val mExecutorService by lazy {
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    }

    /**
     * Default constructor.
     */
    init {
        TAG = "ORS[" + hashCode() + "]"
        DependencyRegistryCommon.inject(this)
        mStartIds = ConcurrentLinkedQueue()
        mStorageListener = StorageListener()
        mBrowseStorage = RadioStationsStorage()
        mSearchStorage = RadioStationsStorage()
        mDelayedStopHandler = DelayedStopHandler()
        mUiScope = CoroutineScope(Dispatchers.Main)
        mScope = CoroutineScope(Dispatchers.IO)
        mCommandScope = CoroutineScope(Dispatchers.IO)
    }

    interface ResultListener {

        fun onResult(items: List<MediaItem> = ArrayList(), pageNumber: Int = 1)
    }

    private inner class StorageListener : RadioStationsStorage.Listener {

        override fun onClear() {
//            mUiScope.launch {
//                mPlayer.clearItems()
//            }
        }

        override fun onAdd(item: RadioStation, position: Int) {
//            mUiScope.launch {
//                mPlayer.add(item, position)
//            }
        }

        override fun onAddAll(set: Set<RadioStation>) {
//            mUiScope.launch {
//                mPlayer.addItems(set)
//            }
        }

        override fun onUpdate(item: RadioStation) {
//            mUiScope.launch {
//                mPlayer.updateItem(item)
//            }
        }
    }

    @SuppressLint("HandlerLeak")
    private inner class DelayedStopHandler : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            if (mPlayer.isPlaying) {
                return
            }
            closeService()
        }
    }

    fun configureWith(presenter: OpenRadioServicePresenter) {
        mPresenter = presenter
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return if ("android.media.session.MediaController" == controllerInfo.packageName
            || mPackageValidator.isKnownCaller(controllerInfo.packageName, controllerInfo.uid)
        ) {
            mSession
        } else null
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("$TAG on create")
        mRestoreComplete.set(false)
        if (AppPreferencesManager.lastKnownRadioStationEnabled(applicationContext)) {
            mScope.launch {
                setActiveRS(mPresenter.getLastRadioStation())
                restoreActivePlaylist()
                mRestoreComplete.set(true)
            }
        } else {
            mRestoreComplete.set(true)
        }
        registerReceivers()
        // Create and start a background HandlerThread since by
        // default a Service runs in the UI Thread, which we don't
        // want to block.
        val thread = HandlerThread("ORS-Thread")
        thread.start()
        // Looper associated with the HandlerThread.
        val looper = thread.looper
        // Get the HandlerThread's Looper and use it for our Handler.
        mServiceHandler = ServiceHandler(looper)
        mPlayer.updatePlayer()
        mSession = with(
            MediaLibrarySession.Builder(
                this, mPlayer, ServiceCallback()
            )
        ) {
            setId(packageName)
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@OpenRadioService,
                        0,
                        sessionIntent,
                        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE
                        else PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            build()
        }

//        mMediaSessionConnector.setPlaybackPreparer(PlaybackPreparer())
//        mMediaSessionConnector.setCustomActionProviders(
//            object : MediaSessionConnector.CustomActionProvider {
//
//                override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
//                    if (action != CUSTOM_ACTION_THUMBS_UP) {
//                        return
//                    }
//                    mPresenter.updateRadioStationFavorite(mActiveRS)
//                    maybeNotifyRootCarChanged()
//                }
//
//                override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
//                    if (mActiveRS.isInvalid()) {
//                        return null
//                    }
//                    var favoriteIcon = R.drawable.ic_favorite_off
//                    if (mPresenter.isRadioStationFavorite(mActiveRS)) {
//                        favoriteIcon = R.drawable.ic_favorite_on
//                    }
//                    return PlaybackStateCompat.CustomAction
//                        .Builder(
//                            CUSTOM_ACTION_THUMBS_UP,
//                            this@OpenRadioService.getString(R.string.favorite),
//                            favoriteIcon
//                        )
//                        .build()
//                }
//            }
//        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i("$TAG on start $intent ${IntentUtils.intentBundleToString(intent)}, id:$startId")
        mStartIds.add(startId)
        if (intent != null) {
            sendMessage(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i("$TAG on destroy")
        mIsPackageValid = false
        mUiScope.cancel("Cancel on destroy")
        mScope.cancel("Cancel on destroy")
        unregisterReceivers()
        if (this::mServiceHandler.isInitialized) {
            mServiceHandler.looper.quit()
        }
        mDelayedStopHandler.removeCallbacksAndMessages(null)
        mPresenter.close()
        mSession.run {
            release()
            if (player.playbackState != Player.STATE_IDLE) {
                //player.removeListener(playerListener)
                player.release()
            }
        }
        // Service is being killed, so make sure we release our resources
        handleStopRequestUiThread()
        mPlayer.release()
    }

//    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
//        val id = MediaId.MEDIA_ID_SEARCH_FROM_SERVICE
//        val command = mPresenter.getMediaItemCommand(id)
//        val dependencies = MediaItemCommandDependencies(
//            applicationContext, result, mPresenter, Country.COUNTRY_CODE_DEFAULT, id,
//            false, mIsRestoreState, AppUtils.makeSearchQueryBundle(query), mCommandScope,
//            object : ResultListener {
//                override fun onResult(set: Set<RadioStation>, pageNumber: Int) {
//                    if (pageNumber == 0) {
//                        mSearchStorage.clear()
//                    }
//                    mSearchStorage.addAll(set)
//                }
//            }
//        )
//        if (command != null) {
//            command.execute(
//                object : MediaItemCommand.IUpdatePlaybackState {
//
//                    override fun updatePlaybackState(error: String) {
//                        AppLogger.e("$TAG update playback state error $error")
//                    }
//                },
//                dependencies
//            )
//        } else {
//            AppLogger.w("$TAG skipping unmatched parentId: $id")
//            result.sendResult(null)
//        }
//    }

    private fun restoreActivePlaylist() {
        val pl = mPresenter.getAllFavorites()
        val rs = mActiveRS
        var found = false
        if (rs.isInvalid().not()) {
            for (item in pl) {
                if (item.id == rs.id) {
                    found = true
                    break
                }
            }
        }
        mLatestPlaylist.clear()
        if (found.not() && rs.isInvalid().not()) {
            mLatestPlaylist.add(rs)
        }
        mLatestPlaylist.addAll(pl)
    }

    private fun maybeNotifyRootCarChanged() {
        if (DependencyRegistryCommon.isCar.not()) {
            return
        }
        // To force update Favorite menu tab.
        var mediaId = MediaId.MEDIA_ID_ROOT
        if (mCurrentParentId == MediaId.MEDIA_ID_FAVORITES_LIST && mPresenter.getAllFavorites().isNotEmpty()) {
            // To force update Favorites list.
            mediaId = MediaId.MEDIA_ID_FAVORITES_LIST
        }
        //notifyChildrenChanged(mediaId)
    }

    private fun registerReceivers() {
        mBTConnectionReceiver.register(applicationContext)
        mNoisyAudioStreamReceiver.register(applicationContext)
        mPresenter.startNetworkMonitor(applicationContext, mNetMonitorListener)
        mPresenter.getSleepTimerModel().addSleepTimerListener(mSleepTimerListener)
        mPresenter.setRemoteControlListener(mRemoteControlListener)
    }

    private fun unregisterReceivers() {
        mBTConnectionReceiver.unregister(applicationContext)
        mNoisyAudioStreamReceiver.unregister(applicationContext)
        mPresenter.stopNetworkMonitor(applicationContext)
        mPresenter.getSleepTimerModel().removeSleepTimerListener(mSleepTimerListener)
        mPresenter.removeRemoteControlListener()
    }

    /**
     * @param intent
     */
    private fun sendMessage(intent: Intent) {
        if (this::mServiceHandler.isInitialized.not()) {
            return
        }
        // Create a Message that will be sent to ServiceHandler.
        val message = mServiceHandler.makeMessage(intent)
        // Send the Message to ServiceHandler.
        mServiceHandler.sendMessage(message)
    }

    /**
     * @param exception
     */
    private fun onHandledError(exception: PlaybackException) {
        AppLogger.e("$TAG player handled exception", exception)
        val throwable = exception.cause
        if (throwable is UnrecognizedInputFormatException) {
            handleUnrecognizedInputFormatException()
        }
    }

    /**
     * Handles exception related to unrecognized url. Try to parse url deeply to extract actual stream one from
     * playlist.
     */
    private fun handleUnrecognizedInputFormatException() {
        val playlistUrl = mActiveRS.getStreamUrlFixed()
        AnalyticsUtils.logMessage("UnrecognizedInputFormat:$playlistUrl")
        handleStopRequest()
        mScope.launch(Dispatchers.IO) {
            withTimeout(API_CALL_TIMEOUT_MS) {
                if (playlistUrl.isEmpty()) {
                    AppLogger.e("HandleUnrecognizedInputFormatException with empty URL")
                    return@withTimeout
                }
                val urls = NetUtils.extractUrlsFromPlaylist(applicationContext, playlistUrl)
                mUiScope.launch {
                    // Silently clear last references and try to restart:
                    handlePlayListUrlsExtracted(urls)
                }
            }
        }
    }

    private fun handlePlayListUrlsExtracted(urls: Array<String>) {
        if (urls.isEmpty()) {
            handleStopRequest()
            return
        }
        if (mActiveRS.isInvalid()) {
            handleStopRequest()
            return
        }
        mActiveRS.setVariantFixed(MediaStream.BIT_RATE_DEFAULT, urls[0])
        getStorage(mActiveRS.id).getById(mActiveRS.id).setVariantFixed(MediaStream.BIT_RATE_DEFAULT, urls[0])
        mStorageListener.onUpdate(mActiveRS)
        handlePlayRequest()
    }

    /**
     * Return Radio Station object by provided Media Id.
     *
     * @param mediaId Media Id of the station.
     *
     * @return [RadioStation] or `null`.
     */
    private fun getRadioStationByMediaId(mediaId: String?): RadioStation {
        if (mediaId.isNullOrEmpty()) {
            AppLogger.e("$TAG get rs by invalid id")
            return RadioStation.INVALID_INSTANCE
        }
        var radioStation = getStorage(mediaId).getById(mediaId)
        if (radioStation.isInvalid()) {
            radioStation = mActiveRS
        }
        // TODO: Why it can be invalid ??
        return radioStation
    }

    /**
     * Return specific storage of Radio Stations based on the current browse case: normal Browse or Search.
     *
     * @param mediaId Media Id to use to detect correct storage type.
     *
     * @return Storage of Radio Stations.
     */
    private fun getStorage(mediaId: String): RadioStationsStorage {
        return if (MediaId.isFromSearch(mediaId)) {
            mSearchStorage
        } else {
            mBrowseStorage
        }
    }

    /**
     * Releases resources used by the service for playback. This includes the
     * "foreground service" status.
     */
    private fun relaxResources() {
        // stop being a foreground service
        stopForeground(true)
        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null)
        mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY.toLong())
    }

    /**
     * Handle a request to play Radio Station.
     */
    private fun handlePlayRequest() {
        mUiScope.launch {
            handlePlayRequestUiThread()
        }
    }

    private fun handlePlayRequestUiThread() {
        if (this::mSession.isInitialized.not()) {
            AppLogger.e("$TAG handle play request with null media session")
            return
        }
        if (mPresenter.isMobileNetwork() &&
            mPresenter.getUseMobile().not()
        ) {
            SafeToast.showAnyThread(
                applicationContext,
                applicationContext.getString(R.string.mobile_network_disabled)
            )
            return
        }
        mDelayedStopHandler.removeCallbacksAndMessages(null)

//        if (mSession.isActive.not()) {
//            mSession.isActive = true
//        }

        // Release everything.
        relaxResources()

        // TODO:
        //mPlayer.reset()
        //mPlayer.prepare(mActiveRS.id)
    }

    private fun handleClearCache(context: Context) {
        mScope.launch {
            mPresenter.clear()
            SafeToast.showAnyThread(context, getString(R.string.clear_completed))
        }
    }

    private fun handleMasterVolumeChanged(context: Context, masterVolume: Int) {
        AppPreferencesManager.setMasterVolume(context, masterVolume)
        mUiScope.launch {
            mPlayer.setVolume(masterVolume / 100.0f)
        }
    }

    /**
     * Handle a request to pause radio stream with reason provided.
     */
    private fun handlePauseRequest() {
        mUiScope.launch { handlePauseRequestUiThread() }
    }

    /**
     * Handle a request to pause radio stream with reason provided in UI thread.
     */
    private fun handlePauseRequestUiThread() {
        if (mPlayerState == Player.STATE_READY) {
            // Pause media player and cancel the 'foreground service' state.
            mPlayer.pause()
            // While paused, give up audio focus.
            relaxResources()
        }
    }

    /**
     * Handle a request to stop music in UI thread.
     */
    private fun handleStopRequestUiThread() {
        if (mPlayerState == Player.STATE_IDLE || mPlayerState == Player.STATE_ENDED) {
            return
        }
        // Let go of all resources...
        relaxResources()
    }

    private fun closeService() {
        handleStopRequest()
        stopSelfResultInt()
    }

    /**
     * Handle a request to stop music.
     */
    private fun handleStopRequest() {
        mUiScope.launch { handleStopRequestUiThread() }
    }

    private fun onResult(set: Set<RadioStation>, pageNumber: Int) {
        AppLogger.d("${set.size} children loaded for $mCurrentParentId")
        while (mRestoreComplete.get().not()) {
            Thread.sleep(100)
        }
        AppLogger.d("${set.size} children loaded, restore compete for $mCurrentParentId")
        if (set.isEmpty().not()) {
            if (pageNumber == 0) {
                mBrowseStorage.clear()
            }
            mBrowseStorage.addAll(set)
        }
        mUiScope.launch {
            val count = mPlayer.mediaItemCount
            // Case of the first start. Need to provide a playlist as well so the user can browse.
            if (count <= 0) {
                // Fill more items based on category.
                when (mCurrentParentId) {
                    MediaId.MEDIA_ID_FAVORITES_LIST -> {
                        AppLogger.d("-- load more favorites")
                    }

                    MediaId.MEDIA_ID_LOCAL_RADIO_STATIONS_LIST -> {
                        AppLogger.d("-- load more locals")
                    }

                    else -> {
                        AppLogger.d("-- load more recent")
                    }
                }
                mBrowseStorage.addAll(mLatestPlaylist)
                mStorageListener.onAddAll(mLatestPlaylist)
                handlePlayRequest()
            }
        }
    }

    /**
     * Consume Radio Station by it's ID.
     *
     * @param mediaId ID of the Radio Station.
     */
    private fun handlePlayFromMediaId(mediaId: String) {
        //if (mediaId == MediaSession.QueueItem.UNKNOWN_ID.toString()) {
        //    AppLogger.e("$TAG media id is invalid")
        //    return
        //}
        setActiveRS(getStorage(mediaId).getById(mediaId))
        AppLogger.i("$TAG handle play $mActiveRS")
        // Play Radio Station
        handlePlayRequest()
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            return
        }
        mScope.launch(Dispatchers.IO) {
            withTimeout(API_CALL_TIMEOUT_MS) {
                try {
                    executePerformSearch(query)
                } catch (e: Exception) {
                    AppLogger.e("$TAG can not perform search for '$query'", e)
                }
            }
        }
    }

    /**
     * Execute actual search.
     *
     * @param query Search query.
     */
    private fun executePerformSearch(query: String) {
        val list = mPresenter.getSearchStations(query)
        if (list.isEmpty()) {
            SafeToast.showAnyThread(applicationContext, applicationContext.getString(R.string.no_search_results))
            return
        }
        mUiScope.launch {
            getStorage(mActiveRS.id).clearAndCopy(list)
            handlePlayRequest()
        }
    }

    /**
     * This method executed in separate thread.
     *
     * @param command
     * @param intent
     */
    private fun handleMessageInternal(command: String, intent: Intent) {
        val context = applicationContext
        AppLogger.i("$TAG handle msg, cmd $command $intent, pkg valid $mIsPackageValid")
        when (command) {
            OpenRadioStore.VALUE_NAME_GET_RADIO_STATION_COMMAND -> {
                val description = OpenRadioStore.extractMediaDescription(intent) ?: return
                var rs = getRadioStationByMediaId(description.mediaId)
                // This can the a case when last known Radio Station is playing.
                // In this case it is not in a list of radio stations.
                // If it exists, let's compare its id with the id provided by intent.
                if (rs.isInvalid()) {
                    if (mActiveRS.id == description.mediaId) {
                        rs = RadioStation.makeCopyInstance(mActiveRS)
                    }
                    // We failed both cases, something went wrong ...
                    if (rs.isInvalid()) {
                        return
                    }
                }
                // Update Favorites Radio station: whether add it or remove it from the storage
                val isFavorite = OpenRadioStore.getIsFavoriteFromIntent(intent)
                mPresenter.updateRadioStationFavorite(rs, isFavorite)
                maybeNotifyRootCarChanged()
            }

            OpenRadioStore.VALUE_NAME_NETWORK_SETTINGS_CHANGED -> {
                if (mPresenter.isMobileNetwork() &&
                    mPresenter.getUseMobile().not()
                ) {
                    SafeToast.showAnyThread(
                        context,
                        context.getString(R.string.mobile_network_disabled)
                    )
                    handlePauseRequest()
                    return
                }
            }

            OpenRadioStore.VALUE_NAME_UPDATE_TREE -> {
                //notifyChildrenChanged(mCurrentParentId)
            }

            OpenRadioStore.VALUE_NAME_CLEAR_CACHE -> {
                handleClearCache(context)
            }

            OpenRadioStore.VALUE_NAME_MASTER_VOLUME_CHANGED -> {
                handleMasterVolumeChanged(
                    context,
                    intent.getIntExtra(OpenRadioStore.EXTRA_KEY_MASTER_VOLUME, MASTER_VOLUME_DEFAULT)
                )
            }

            OpenRadioStore.VALUE_NAME_REMOVE_BY_ID -> {
                mStorageListener.onClear()
                getStorage(mActiveRS.id).removeById(
                    intent.getStringExtra(OpenRadioStore.EXTRA_KEY_MEDIA_ID) ?: AppUtils.EMPTY_STRING
                )
                mStorageListener.onAddAll(getStorage(mActiveRS.id).all)
            }

            OpenRadioStore.VALUE_NAME_NOTIFY_CHILDREN_CHANGED -> {
//                notifyChildrenChanged(
//                    intent.getStringExtra(OpenRadioStore.EXTRA_KEY_PARENT_ID) ?: MediaId.MEDIA_ID_ROOT
//                )
            }

            OpenRadioStore.VALUE_NAME_UPDATE_SORT_IDS -> {
                val mediaId = intent.getStringExtra(OpenRadioStore.EXTRA_KEY_MEDIA_IDS)
                val sortId = intent.getIntExtra(OpenRadioStore.EXTRA_KEY_SORT_IDS, 0)
                val categoryMediaId =
                    intent.getStringExtra(OpenRadioStore.EXTRA_KEY_MEDIA_ID) ?: MediaId.MEDIA_ID_ROOT
                if (mediaId.isNullOrEmpty()) {
                    return
                }
                mPresenter.updateSortIds(
                    mediaId, sortId, categoryMediaId,
                )
//                notifyChildrenChanged(categoryMediaId)
            }

            OpenRadioStore.VALUE_NAME_TOGGLE_LAST_PLAYED_ITEM -> {
                togglePlayableItem()
            }

            OpenRadioStore.VALUE_NAME_STOP_SERVICE -> {
                mUiScope.launch {
                    closeService()
                }
            }

            else -> AppLogger.w("$TAG unknown command:$command")
        }
    }

    private fun togglePlayableItem() {
        when (mPlayerState) {
            Player.STATE_BUFFERING,
            Player.STATE_READY -> {
                handlePauseRequest()
            }

            Player.STATE_IDLE,
            Player.STATE_ENDED -> {
                handlePlayRequest()
            }

            else -> {
                AppLogger.w(
                    "$TAG unhandled playback state:${PlayerUtils.playerStateToString(mPlayerState)}"
                )
            }
        }
    }

    private fun stopSelfResultInt() {
        AppLogger.i("$TAG stop self with size of ${mStartIds.size}")
        while (mStartIds.isEmpty().not()) {
            val id = mStartIds.poll() ?: continue
            val result = stopSelfResult(id)
            AppLogger.i("$TAG service " + (if (result) "stopped" else "not stopped") + " for $id")
        }
    }

    /**
     * Handles event when Bluetooth connected to same device within application lifetime.
     */
    private fun handleBTSameDeviceConnected() {
        val autoPlay = AppPreferencesManager.isBtAutoPlay(applicationContext)
        if (autoPlay.not()) {
            return
        }
        handlePlayRequest()
    }

    private fun callPause() {
        handlePauseRequest()
    }

    private fun callPlayFromNetworkConnected() {
        if (mPlayer.isStoppedByNetwork().not()) {
            return
        }
        handlePlayRequest()
    }

    private fun setActiveRS(value: RadioStation) {
        if (value.isInvalid()) {
            AppLogger.w("$TAG cant set invalid ars")
            return
        }
        mActiveRS = value
        AppLogger.i("$TAG set ars $mActiveRS")
    }

    /**
     * Listener for Exo Player events.
     */
    private inner class PlayerListener : OpenRadioPlayer.Listener {

        override fun onError() {
            handleStopRequest()
        }

        override fun onHandledError(error: PlaybackException) {
            this@OpenRadioService.onHandledError(error)
        }

        override fun onReady() {
            if (mActiveRS.isInvalid()) {
                AppLogger.w("$TAG prepare with invalid rs")
                return
            }
            mPresenter.setLastRadioStation(mActiveRS)
        }

        override fun onIndexOnQueueChanges(value: Int) {
            setActiveRS(getStorage(mActiveRS.id).getAt(value))
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                AppLocalBroadcast.createIntentCurrentIndexOnQueue(value)
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            mPlayerState = playbackState
        }

        override fun onStartForeground(notificationId: Int, notification: Notification) {
            val ibo = AppUtils.isIgnoringBatteryOptimizations(applicationContext)
            AnalyticsUtils.logMessage("Going to strt frgrd srv w ibo:$ibo")
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, this@OpenRadioService.javaClass)
            )
            AnalyticsUtils.logMessage("Going to strt frgrd")
            startForeground(notificationId, notification)
            AnalyticsUtils.logMessage("Frgrd started")
        }

        override fun onStopForeground(removeNotification: Boolean) {
            AppLogger.i("$TAG stop as foreground")
            stopForeground(removeNotification)
            stopSelf()
        }

        override fun onCloseApp() {
            closeService()
        }
    }

    /**
     * An inner class that inherits from Handler and uses its
     * handleMessage() hook method to process Messages sent to
     * it from onStartCommand().
     */
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        /**
         * A factory method that creates a Message that contains
         * information of the command to perform.
         */
        fun makeMessage(intent: Intent): Message {
            val message = Message.obtain()
            message.obj = intent
            return message
        }

        /**
         * Hook method that process command sent from service.
         */
        override fun handleMessage(message: Message) {
            val intent = message.obj as Intent
            val bundle = intent.extras ?: return
            val command = bundle.getString(OpenRadioStore.KEY_NAME_COMMAND_NAME)
            if (command.isNullOrEmpty()) {
                return
            }
            handleMessageInternal(command, intent)
        }
    }

    private inner class NetworkMonitorListenerImpl : NetworkMonitorListener {

        override fun onConnectivityChange(isConnected: Boolean) {
            if (isConnected.not()) {
                return
            }
            if (mPresenter.isMobileNetwork() &&
                mPresenter.getUseMobile().not()
            ) {
                SafeToast.showAnyThread(
                    applicationContext,
                    applicationContext.getString(R.string.mobile_network_disabled)
                )
                callPause()
                return
            }
            callPlayFromNetworkConnected()
        }
    }

//    private inner class PlaybackPreparer : MediaSessionConnector.PlaybackPreparer {
//
//        private val mTag = "PlbckPreparer"
//
//        override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
//            AppLogger.d("$mTag command:$command ${IntentUtils.bundleToString(extras)}")
//            return false
//        }
//
//        /**
//         * Open Radio supports preparing (and playing) from search, as well as media ID, so those
//         * capabilities are declared here.
//         *
//         * TODO: Add support for ACTION_PREPARE and ACTION_PLAY, which mean "prepare/play something".
//         */
//        override fun getSupportedPrepareActions(): Long =
//            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
//                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
//                    PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
//                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
//
//        override fun onPrepare(playWhenReady: Boolean) {
//            AppLogger.d("$mTag prepare, play:$playWhenReady, cat:$mCurrentParentId")
//            if (playWhenReady.not()) {
//                return
//            }
//            if (mActiveRS.isInvalid()) {
//                AppLogger.w("$mTag no active rs found after restore")
//                return
//            }
//            handlePlayRequest()
//        }
//
//        override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
//            AppLogger.d(
//                "$mTag prepare from media id:$mediaId, play:$playWhenReady, ${IntentUtils.bundleToString(extras)}"
//            )
//            mStorageListener.onClear()
//            mStorageListener.onAddAll(getStorage(mediaId).all)
//            handlePlayFromMediaId(mediaId)
//        }
//
//        /**
//         * This method is used by the Google Assistant to respond to requests such as:
//         * - Play Geisha from Wake Up on Open Radio
//         * - Play electronic music on Open Radio
//         * - Play music on Open Radio
//         */
//        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
//            AppLogger.d("$mTag prepare from search '$query'")
//            performSearch(query)
//        }
//
//        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
//            AppLogger.d(
//                "$mTag prepare from uri:$uri, play:$playWhenReady, ${IntentUtils.bundleToString(extras)}"
//            )
//        }
//    }

    private inner class SleepTimerListenerImpl : SleepTimerListener {

        override fun onComplete() {
            AppLogger.i("$TAG on sleep timer completed")
            mUiScope.launch {
                closeService()
            }
        }
    }

    private inner class RemoteControlListenerImpl : RemoteControlListener {

        override fun onMediaPlay() {
            handlePlayRequest()
        }

        override fun onMediaPlayPause() {
            togglePlayableItem()
        }

        override fun onMediaPauseStop() {
            handlePauseRequest()
        }
    }

    private inner class ServiceCallback : MediaLibrarySession.Callback {

        @UnstableApi
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            AnalyticsUtils.logMessage(
                "$TAG GetLibraryRoot for clientPkgName=${browser.packageName}, clientUid=${browser.uid}"
            )
            // By default, all known clients are permitted to search, but only tell unknown callers
            // about search if permitted by the [BrowseTree].
            mIsPackageValid = mPackageValidator.isKnownCaller(browser.packageName, browser.uid)
            val rootExtras = Bundle().apply {
                putBoolean(
                    MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED,
                    mIsPackageValid
                )
            }
            //mIsRestoreState = OpenRadioStore.getRestoreState(rootHints)
            val libraryParams = LibraryParams.Builder().setExtras(rootExtras).build()
            val rootMediaItem = if (!mIsPackageValid) {
                MediaItem.EMPTY
            } else if (params?.isRecent == true) {
                // TODO:
                MediaItemBuilder.buildRootMediaItem()
            } else {
                MediaItemBuilder.buildRootMediaItem()
            }
            return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            AppLogger.d("$TAG GetChildren for $parentId page $page pageSize $pageSize")
            return callWhenSourceReady(parentId) {
                LibraryResult.ofItemList(
                    mBrowseTree[parentId] ?: ImmutableList.of(),
                    LibraryParams.Builder().build()
                )
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            AppLogger.d("$TAG GetItem for $mediaId")
            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    mBrowseTree.getMediaItemByMediaId(mediaId) ?: MediaItem.EMPTY,
                    LibraryParams.Builder().build()
                )
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            AppLogger.d("$TAG Search $query")
            //val searchResult = musicSource.search(query, params?.extras ?: Bundle())
            //mSession.notifySearchResultChanged(browser, query, searchResult.size, params)
            return Futures.immediateFuture(
                LibraryResult.ofVoid()
            )
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
//            return callWhenMusicSourceReady {
//                val searchResult = musicSource.search(query, params?.extras ?: Bundle())
//                val fromIndex = max((page - 1) * pageSize, searchResult.size - 1)
//                val toIndex = max(fromIndex + pageSize, searchResult.size)
//                LibraryResult.ofItemList(searchResult.subList(fromIndex, toIndex), params)
//            }
            AppLogger.d("$TAG GetSearchResult $query")
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    ImmutableList.of(),
                    LibraryParams.Builder().build()
                )
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            AppLogger.d("$TAG AddMediaItems $mediaItems")
            return Futures.immediateFuture(
                mediaItems.map { mBrowseTree.getMediaItemByMediaId(it.mediaId)!! }.toMutableList()
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            AppLogger.d("$TAG CustomCommand $customCommand")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        private fun <T> callWhenSourceReady(parentId: String, action: () -> T): ListenableFuture<T> {
            if (mBrowseTree[parentId] != null) {
                return Futures.immediateFuture(action())
            }

            val command = mPresenter.getMediaItemCommand(MediaId.getId(parentId, AppUtils.EMPTY_STRING))
            val defaultCountryCode = mPresenter.getCountryCode()
            // If Parent Id contains Country Code - use it in the API.
            val countryCode = MediaId.getCountryCode(parentId, defaultCountryCode)
            val conditionVariable = ConditionVariable()
            val dependencies = MediaItemCommandDependencies(
                applicationContext, mPresenter, countryCode, mCurrentParentId,
                false, mIsRestoreState, Bundle(), mCommandScope,
                object : ResultListener {

                    override fun onResult(items: List<MediaItem>, pageNumber: Int) {
                        AppLogger.d("$TAG loaded ${items.size} items for $parentId")
                        mBrowseTree[parentId] = ArrayList(items)
                        conditionVariable.open()
                    }
                }
            )
            if (command != null) {
                command.execute(
                    object : MediaItemCommand.IUpdatePlaybackState {

                        override fun updatePlaybackState(error: String) {
                            AppLogger.e("$TAG update playback state error $error")
                        }
                    },
                    dependencies
                )
            } else {
                AppLogger.w("$TAG skipping unmatched parentId: $parentId")
            }
            return mExecutorService.submit<T> {
                conditionVariable.block()
                action()
            }
        }
    }

    companion object {
        private lateinit var TAG: String

        /**
         * Action to thumbs up a media item
         */
        private const val CUSTOM_ACTION_THUMBS_UP = "com.yuriy.openradio.share.service.THUMBS_UP"

        /**
         * Delay stop service by using a handler.
         */
        private const val STOP_DELAY = 30_000

        private const val API_CALL_TIMEOUT_MS = 3_000L

        const val MASTER_VOLUME_DEFAULT = 100
    }
}
