/*
 * Copyright 2014 - 2023 The "Open Radio" Project. Author: Chernyshov Yuriy
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
import androidx.annotation.MainThread
import androidx.media.utils.MediaConstants
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.session.CommandButton
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
import com.yuriy.openradio.shared.broadcast.BTConnectionReceiver
import com.yuriy.openradio.shared.broadcast.BecomingNoisyReceiver
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommon
import com.yuriy.openradio.shared.extentions.isEnded
import com.yuriy.openradio.shared.extentions.isPlayEnabled
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
import com.yuriy.openradio.shared.model.net.UrlLayer
import com.yuriy.openradio.shared.model.storage.AppPreferencesManager
import com.yuriy.openradio.shared.model.timer.SleepTimerListener
import com.yuriy.openradio.shared.service.location.Country
import com.yuriy.openradio.shared.service.player.OpenRadioPlayer
import com.yuriy.openradio.shared.utils.AnalyticsUtils
import com.yuriy.openradio.shared.utils.AppLogger
import com.yuriy.openradio.shared.utils.AppUtils
import com.yuriy.openradio.shared.utils.IntentUtils
import com.yuriy.openradio.shared.utils.MediaItemBuilder
import com.yuriy.openradio.shared.utils.MediaItemHelper
import com.yuriy.openradio.shared.utils.NetUtils
import com.yuriy.openradio.shared.utils.PackageValidator
import com.yuriy.openradio.shared.utils.SafeToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.TreeSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

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
    private var mBrowser: MediaSession.ControllerInfo? = null

    private val mPackageValidator by lazy {
        PackageValidator(applicationContext, R.xml.allowed_media_browser_callers)
    }

    private var mIsPackageValid = false

    /**
     * Track selected Radio Station.
     */
    private var mActiveRS = RadioStation.INVALID_INSTANCE
    private val mUiScope: CoroutineScope
    private val mScope: CoroutineScope
    private val mCommandScope: CoroutineScope

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

    private val mStartIds: ConcurrentLinkedQueue<Int>
    private var mCurrentParentId = AppUtils.EMPTY_STRING
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

    private lateinit var mFavoriteCommands: List<CommandButton>

    /**
     * Default constructor.
     */
    init {
        TAG = "ORS[" + hashCode() + "]"
        DependencyRegistryCommon.inject(this)
        mStartIds = ConcurrentLinkedQueue()
        mUiScope = CoroutineScope(Dispatchers.Main)
        mScope = CoroutineScope(Dispatchers.IO)
        mCommandScope = CoroutineScope(Dispatchers.IO)
    }

    interface ResultListener {

        fun onResult(
            items: List<MediaItem> = ArrayList(),
            radioStations: Set<RadioStation> = TreeSet(),
            pageNumber: Int = UrlLayer.FIRST_PAGE_INDEX
        )
    }

    fun configureWith(presenter: OpenRadioServicePresenter) {
        mPresenter = presenter
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return if ("android.media.session.MediaController" == controllerInfo.packageName
            || mPackageValidator.isKnownCaller(applicationInfo, controllerInfo.packageName, controllerInfo.uid)
        ) {
            mSession
        } else null
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("$TAG on create")
        if (AppPreferencesManager.lastKnownRadioStationEnabled(applicationContext)) {
            setActiveRS(mPresenter.getLastRadioStation())
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

        mFavoriteCommands = listOf(
            CommandButton.Builder()
                .setDisplayName(getString(R.string.favorite))
                .setEnabled(true)
                .setIconResId(R.drawable.ic_favorite_off)
                .setSessionCommand(SessionCommand(CMD_FAVORITE_OFF, Bundle()))
                .build(),
            CommandButton.Builder()
                .setDisplayName(getString(R.string.favorite))
                .setEnabled(true)
                .setIconResId(R.drawable.ic_favorite_on)
                .setSessionCommand(SessionCommand(CMD_FAVORITE_ON, Bundle()))
                .build()
        )

        updateFavoriteState()
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
        mPresenter.close()
        mSession.run {
            release()
            if (player.playbackState != Player.STATE_IDLE) {
                player.release()
            }
        }
    }

    private fun maybeNotifyRootChanged() {
        // To force update Favorite menu tab.
        var mediaId = MediaId.MEDIA_ID_ROOT
        if (mCurrentParentId == MediaId.MEDIA_ID_FAVORITES_LIST && mPresenter.getAllFavorites().isNotEmpty()) {
            // To force update Favorites list.
            mediaId = MediaId.MEDIA_ID_FAVORITES_LIST
        }
        notifyChildrenChanged(mediaId)
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

    private fun updateFavoriteState() {
        if (mActiveRS != RadioStation.INVALID_INSTANCE) {
            val command =
                if (mPresenter.isRadioStationFavorite(mActiveRS)) mFavoriteCommands[1] else mFavoriteCommands[0]
            mSession.setCustomLayout(listOf(command))
        }
    }

    private fun notifyChildrenChanged(mediaId: String) {
        mBrowser?.let {
            mBrowseTree.invalidate(mediaId)
            mSession.notifyChildrenChanged(it, mediaId, 250, null)
        }
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
        // TODO:
        //getStorage(mActiveRS.id).getById(mActiveRS.id).setVariantFixed(MediaStream.BIT_RATE_DEFAULT, urls[0])
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
        return mBrowseTree.getRadioStationByMediaId(mediaId)
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
        if (mPresenter.isMobileNetwork() &&
            mPresenter.getUseMobile().not()
        ) {
            SafeToast.showAnyThread(
                applicationContext,
                getString(R.string.mobile_network_disabled)
            )
            return
        }
        mPlayer.prepare()
        mPlayer.play()
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
            mPlayer.volume = masterVolume / 100.0f
        }
    }

    /**
     * Handle a request to pause radio stream with reason provided.
     */
    private fun handlePauseRequest() {
        mUiScope.launch { mPlayer.pause() }
    }

    private fun closeService() {
        handleStopRequest()
        stopSelfResultInt()
    }

    /**
     * Handle a request to stop music.
     */
    private fun handleStopRequest() {
        mUiScope.launch { mPlayer.stop() }
    }

    /**
     * This method executed in separate thread.
     *
     * @param command
     * @param intent
     */
    private fun handleMessageInternal(command: String, intent: Intent) {
        AppLogger.i("$TAG handle msg, cmd $command $intent, pkg valid $mIsPackageValid")
    }

    private fun togglePlayableItem() {
        when {
            mPlayer.isPlaying -> mPlayer.pause()
            mPlayer.isPlayEnabled -> mPlayer.play()
            mPlayer.isEnded -> mPlayer.seekTo(C.TIME_UNSET)
            else -> {
                AppLogger.e(
                    "$TAG unhandled toggle case " +
                            "playing:${mPlayer.isPlaying} " +
                            "play enabled:${mPlayer.isPlayEnabled} " +
                            "play ended:${mPlayer.isEnded} "
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

    @MainThread
    private fun maybeCreateInitialPlaylist() {
        val rs = mActiveRS
        if (rs == RadioStation.INVALID_INSTANCE) {
            return
        }
        val mediaItemCount = mPlayer.mediaItemCount
        if (mediaItemCount != 0) {
            return
        }
        val rsPlayable = MediaItemBuilder.buildPlayable(rs)
        val list = mutableListOf<MediaItem>()
        val radioStations = TreeSet<RadioStation>()
        // Create a NPL:
        mScope.launch {
            AppLogger.d("$TAG create init npl")
            var radios = mPresenter.getAllFavorites()
            if (radios.isEmpty().not()) {
                AppLogger.d("$TAG create init npl fill with fav")
                radioStations.addAll(radios)
            } else {
                AppLogger.d("$TAG create init npl fill with recent")
                radios = mPresenter.getRecentlyAddedStations()
                radioStations.addAll(radios)
            }
            AppLogger.d("$TAG create init npl with ${radioStations.size}")
            var isActiveStationFound = false
            for (item in radioStations) {
                val playable = MediaItemBuilder.buildPlayable(item)
                if (item.id == rs.id) {
                    isActiveStationFound = true
                }
                list.add(playable)
            }
            if (isActiveStationFound.not()) {
                list.add(0, rsPlayable)
            }
        }.invokeOnCompletion {
            mUiScope.launch {
                // This is needed for Media Browser when framework will require media item by id and the view will contain
                // Browsables only:
                mBrowseTree[rs.id] = BrowseTree.BrowseData(list, radioStations)
                val pos = list.indexOf(rsPlayable)
                mPlayer.setMediaItems(list, pos, C.TIME_UNSET)
                handlePlayRequestUiThread()
            }
        }
    }


    /**
     * Listener for Exo Player events.
     */
    private inner class PlayerListener : OpenRadioPlayer.Listener {

        override fun onHandledError(error: PlaybackException) {
            this@OpenRadioService.onHandledError(error)
        }

        override fun onReady(mediaItem: MediaItem) {
            mScope.launch {
                mBrowseTree.getRadioStationByMediaId(mediaItem.mediaId).let {
                    if (mediaItem.mediaMetadata.isPlayable == false) {
                        return@let
                    }
                    // TODO: Investigate why metadata with valid fields has empty media id.
                    if (it == RadioStation.INVALID_INSTANCE) {
                        AppLogger.w("$TAG playable has empty media id $it")
                        return@let
                    }
                    setActiveRS(it)
                    mPresenter.setLastRadioStation(it)
                    mUiScope.launch {
                        // Update custom commands:
                        updateFavoriteState()
                    }
                }
            }
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
                    getString(R.string.mobile_network_disabled)
                )
                callPause()
                return
            }
            callPlayFromNetworkConnected()
        }
    }

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

        private val mSessionCmdSuccess = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        private val mSessionCmdNotSupported =
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            AppLogger.d("$TAG [$browser] Subscribe to $parentId")
            mBrowser = browser
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String
        ): ListenableFuture<LibraryResult<Void>> {
            AppLogger.d("$TAG [$browser] Unsubscribe from $parentId")
            mBrowser = browser
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        @UnstableApi
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            AnalyticsUtils.logMessage(
                "$TAG [$browser] GetLibraryRoot for clientPkgName=${browser.packageName}, clientUid=${browser.uid}"
            )
            mBrowser = browser
            // By default, all known clients are permitted to search, but only tell unknown callers
            // about search if permitted by the [BrowseTree].
            mIsPackageValid = mPackageValidator.isKnownCaller(applicationInfo, browser.packageName, browser.uid)
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
            AppLogger.d("$TAG [$browser] GetChildren for $parentId page $page pageSize $pageSize")
            mBrowser = browser
            return callWhenSourceReady(parentId, page, pageSize) {
                val list = mBrowseTree[parentId] ?: ImmutableList.of()
                val sublist = list.subList(it, list.size)
                AppLogger.d("$TAG GetChildren for $parentId page $page return ${sublist.size}|${list.size} from pos:$it")
                LibraryResult.ofItemList(
                    sublist,
                    LibraryParams.Builder().build()
                )
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            AppLogger.d("$TAG [$browser] GetItem for $mediaId")
            mBrowser = browser
            val item = mBrowseTree.getMediaItemByMediaId(mediaId) ?: MediaItem.EMPTY
            if (item.mediaId == null || item.mediaId == MediaItem.DEFAULT_MEDIA_ID) {
                AnalyticsUtils.logMessage("GetItem for $mediaId failed")
            }
            return Futures.immediateFuture(
                LibraryResult.ofItem(item, LibraryParams.Builder().build())
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            AppLogger.d("$TAG [$browser] Search $query")
            mBrowser = browser
            return callWhenSearchReady(query) {
                mSession.notifySearchResultChanged(browser, query, it, params)
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            mBrowser = browser
            val list = mBrowseTree[query] ?: mutableListOf()
            // TODO:
            //val fromIndex = max(page * pageSize, list.size - 1)
            //val toIndex = max(fromIndex + pageSize, list.size)
            AppLogger.d("$TAG [$browser] GetSearchResult $query")
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    list,
                    LibraryParams.Builder().build()
                )
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            AppLogger.d("$TAG AddMediaItems ${mediaItems.size}")
            // TODO: Do we need this? SetMediaItems invoked earlier.
            if (mediaItems.size != 1) {
                return Futures.immediateFuture(
                    mediaItems.map { mBrowseTree.getMediaItemByMediaId(it.mediaId)!! }.toMutableList()
                )
            }
            return Futures.immediateFuture(
                mBrowseTree.getMediaItemsByMediaId(mediaItems[0].mediaId)
            )
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            AppLogger.d("$TAG Connect $controller")
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands =
                connectionResult.availableSessionCommands
                    .buildUpon()
                    // Add custom commands
                    .add(SessionCommand(CMD_FAVORITE_ON, Bundle()))
                    .add(SessionCommand(CMD_FAVORITE_OFF, Bundle()))
                    .add(SessionCommand(CMD_NET_CHANGED, Bundle()))
                    .add(SessionCommand(CMD_CLEAR_CACHE, Bundle()))
                    .add(SessionCommand(CMD_MASTER_VOLUME_CHANGED, Bundle()))
                    .add(SessionCommand(CMD_STOP_SERVICE, Bundle()))
                    .add(SessionCommand(CMD_TOGGLE_LAST_PLAYED_ITEM, Bundle()))
                    .add(SessionCommand(CMD_UPDATE_SORT_IDS, Bundle()))
                    .add(SessionCommand(CMD_UPDATE_TREE, Bundle()))
                    .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands, connectionResult.availablePlayerCommands
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            AppLogger.d("$TAG SetMediaItems ${mediaItems.size} $startIndex")

            // TODO: It is weird that selection from the list of playable result in items of size 1, unless
            //       there is additional configuration somewhere to specify to load a whole playlist.

            if (mediaItems.size != 1) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            val mediaId = mediaItems[0].mediaId
            val mItems = mBrowseTree.getMediaItemsByMediaId(mediaId)
            var pos = 0
            for ((idx, i) in mItems.withIndex()) {
                if (i.mediaId == mediaId) {
                    pos = idx
                    break
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mItems, pos, C.TIME_UNSET)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            AppLogger.d("$TAG CustomCommand ${customCommand.customAction}")
            return when (customCommand.customAction) {
                CMD_NET_CHANGED -> {
                    if (mPresenter.isMobileNetwork() &&
                        mPresenter.getUseMobile().not()
                    ) {
                        SafeToast.showAnyThread(
                            applicationContext,
                            getString(R.string.mobile_network_disabled)
                        )
                        handlePauseRequest()
                    }
                    return mSessionCmdSuccess
                }

                CMD_FAVORITE_OFF -> {
                    // Transition station to Favorite ON
                    session.setCustomLayout(listOf(mFavoriteCommands[1]))
                    if (handleFavorite(args, true).not()) {
                        return mSessionCmdNotSupported
                    }
                    return mSessionCmdSuccess
                }

                CMD_FAVORITE_ON -> {
                    // Transition station to Favorite OFF
                    session.setCustomLayout(listOf(mFavoriteCommands[0]))
                    if (handleFavorite(args, false).not()) {
                        return mSessionCmdNotSupported
                    }
                    return mSessionCmdSuccess
                }

                CMD_STOP_SERVICE -> {
                    closeService()
                    return mSessionCmdSuccess
                }

                CMD_TOGGLE_LAST_PLAYED_ITEM -> {
                    togglePlayableItem()
                    return mSessionCmdSuccess
                }

                CMD_UPDATE_SORT_IDS -> {
                    val mediaId = args.getString(OpenRadioStore.EXTRA_KEY_MEDIA_IDS)
                    val sortId = args.getInt(OpenRadioStore.EXTRA_KEY_SORT_IDS, 0)
                    val categoryMediaId =
                        args.getString(OpenRadioStore.EXTRA_KEY_MEDIA_ID) ?: MediaId.MEDIA_ID_ROOT
                    if (mediaId.isNullOrEmpty()) {
                        return mSessionCmdNotSupported
                    }
                    mPresenter.updateSortIds(
                        mediaId, sortId, categoryMediaId,
                    )
                    notifyChildrenChanged(categoryMediaId)
                    return mSessionCmdSuccess
                }

                CMD_CLEAR_CACHE -> {
                    handleClearCache(applicationContext)
                    return mSessionCmdSuccess
                }

                CMD_MASTER_VOLUME_CHANGED -> {
                    handleMasterVolumeChanged(
                        applicationContext,
                        args.getInt(OpenRadioStore.EXTRA_KEY_MASTER_VOLUME, MASTER_VOLUME_DEFAULT)
                    )
                    return mSessionCmdSuccess
                }

                CMD_UPDATE_TREE -> {
                    notifyChildrenChanged(mCurrentParentId)
                    return mSessionCmdSuccess
                }

                else -> {
                    mSessionCmdNotSupported
                }
            }
        }

        private fun <T> callWhenSourceReady(
            parentId: String,
            page: Int,
            pageSize: Int,
            action: (position: Int) -> T
        ): ListenableFuture<T> {
            val isSameCatalogue = AppUtils.isSameCatalogue(parentId, mCurrentParentId)
            mCurrentParentId = parentId
            var position = 0
            if (page == 0 && mBrowseTree[parentId] != null) {
                return Futures.immediateFuture(action(position))
            }
            val id = MediaId.getId(parentId, AppUtils.EMPTY_STRING)
            val command = mPresenter.getMediaItemCommand(id)
            AppLogger.d("$TAG get source for $id by $command")
            val defaultCountryCode = mPresenter.getCountryCode()
            // If Parent Id contains Country Code - use it in the API.
            val countryCode = MediaId.getCountryCode(parentId, defaultCountryCode)
            val conditionVariable = ConditionVariable()
            val dependencies = MediaItemCommandDependencies(
                applicationContext, mPresenter, countryCode, parentId,
                isSameCatalogue, mIsRestoreState, Bundle(), mCommandScope,
                object : ResultListener {

                    override fun onResult(items: List<MediaItem>, radioStations: Set<RadioStation>, pageNumber: Int) {
                        AppLogger.d("$TAG loaded [$pageNumber:${items.size}] for $parentId")
                        if (pageNumber == 0) {
                            mBrowseTree[parentId] =
                                BrowseTree.BrowseData(ArrayList(items), radioStations.toMutableSet())
                            mUiScope.launch { maybeCreateInitialPlaylist() }
                        } else {
                            position = mBrowseTree[parentId]?.size ?: 0
                            mBrowseTree.append(
                                parentId,
                                BrowseTree.BrowseData(ArrayList(items), radioStations.toMutableSet())
                            )
                        }
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
                action(position)
            }
        }

        private fun <T> callWhenSearchReady(
            query: String,
            action: (size: Int) -> T
        ): ListenableFuture<T> {
            val conditionVariable = ConditionVariable()
            var size = 0
            val id = MediaId.MEDIA_ID_SEARCH_FROM_SERVICE
            val command = mPresenter.getMediaItemCommand(id)
            val dependencies = MediaItemCommandDependencies(
                applicationContext, mPresenter, Country.COUNTRY_CODE_DEFAULT, id,
                false, mIsRestoreState, AppUtils.makeSearchQueryBundle(query), mCommandScope,

                object : ResultListener {
                    override fun onResult(items: List<MediaItem>, radioStations: Set<RadioStation>, pageNumber: Int) {
                        mBrowseTree[query] = BrowseTree.BrowseData(ArrayList(items), radioStations.toMutableSet())
                        size = radioStations.size
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
                AppLogger.w("$TAG skipping unmatched parentId: $id")
            }
            return mExecutorService.submit<T> {
                conditionVariable.block()
                action(size)
            }
        }

        private fun handleFavorite(args: Bundle, isFavorite: Boolean): Boolean {
            var mediaId = OpenRadioStore.extractMediaId(args)
            var rs = getRadioStationByMediaId(mediaId)
            // This can happen when Favorite changed from automotive UI.
            // The assumption is this event can be delivered from the now playing item only.
            if (rs.isInvalid()) {
                if (mActiveRS.id == mediaId) {
                    rs = RadioStation.makeCopyInstance(mActiveRS)
                }
                // Lastly, get the current playing station.
                if (rs.isInvalid()) {
                    rs = RadioStation.makeCopyInstance(mActiveRS)
                    mediaId = rs.id
                }
                // We failed both cases, something went wrong ...
                if (rs.isInvalid()) {
                    return false
                }
            }
            // Update Favorites Radio station: whether add it or remove it from the storage
            mPresenter.updateRadioStationFavorite(rs, isFavorite)
            val mediaItem = mBrowseTree.getMediaItemByMediaId(mediaId)
            if (mediaItem == null) {
                AppLogger.e("Can't handle fav. for $mediaId $rs")
                return false
            }
            MediaItemHelper.updateFavoriteField(mediaItem.mediaMetadata, isFavorite)
            val currentMediaItem = mPlayer.currentMediaItem
            if (currentMediaItem?.mediaId == mediaId) {
                MediaItemHelper.updateFavoriteField(currentMediaItem.mediaMetadata, isFavorite)
            }
            mPlayer.invalidateMetaData()
            maybeNotifyRootChanged()
            return true
        }
    }

    companion object {

        const val CMD_FAVORITE_ON = "com.yuriy.openradio.COMMAND.FAVORITE_ON"
        const val CMD_FAVORITE_OFF = "com.yuriy.openradio.COMMAND.FAVORITE_OFF"
        const val CMD_NET_CHANGED = "com.yuriy.openradio.COMMAND.NET_CHANGED"
        const val CMD_STOP_SERVICE = "com.yuriy.openradio.COMMAND.STOP_SERVICE"
        const val CMD_TOGGLE_LAST_PLAYED_ITEM = "com.yuriy.openradio.COMMAND.TOGGLE_LAST_PLAYED_ITEM"
        const val CMD_UPDATE_SORT_IDS = "com.yuriy.openradio.COMMAND.UPDATE_SORT_IDS"
        const val CMD_CLEAR_CACHE = "com.yuriy.openradio.COMMAND.CLEAR_CACHE"
        const val CMD_MASTER_VOLUME_CHANGED = "com.yuriy.openradio.COMMAND.MASTER_VOLUME_CHANGED"
        const val CMD_UPDATE_TREE = "com.yuriy.openradio.COMMAND.UPDATE_TREE"

        private lateinit var TAG: String

        private const val API_CALL_TIMEOUT_MS = 3_000L

        const val MASTER_VOLUME_DEFAULT = 100
    }
}
