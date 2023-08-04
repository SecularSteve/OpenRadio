/*
 * Copyright 2017-2021 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.model.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.work.await
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommon
import com.yuriy.openradio.shared.service.OpenRadioService
import com.yuriy.openradio.shared.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Created by Chernyshov Yurii
 * At Android Studio
 * On 29/06/17
 * E-Mail: chernyshov.yuriy@gmail.com
 */

/**
 *
 *
 * @param mListener Listener for the media resources events. Acts as proxy between this manager and callee Activity.
 */
@UnstableApi
class MediaResourcesManager(context: Context, className: String, private val mListener: MediaResourceManagerListener) {
    /**
     * Tag string to use in logging message.
     */
    private val mClassName = "MdRsrcsMgr $className"

    /**
     * Browses media content offered by a [android.service.media.MediaBrowserService].
     */
    private lateinit var mMediaBrowser: MediaBrowser

    private val mCoroutineContext = Dispatchers.Main
    private val mScope = CoroutineScope(mCoroutineContext + SupervisorJob())

    private val mPlayerListener = PlayerListener()

    private val mPlayer: Player get() = mMediaBrowser

    private var mNowPlaying: MediaItem? = null

    /**
     * Constructor.
     */
    init {
        mScope.launch {
            AppLogger.d("$mClassName start browser")
            mMediaBrowser =
                MediaBrowser.Builder(
                    context,
                    SessionToken(context, ComponentName(context, OpenRadioService::class.java))
                )
                    .setListener(BrowserListener())
                    .buildAsync()
                    .await()
            mMediaBrowser.addListener(mPlayerListener)
            val root = mMediaBrowser.getLibraryRoot(null).await().value
            AppLogger.d("$mClassName root '${root?.mediaId}'")
            mListener.onConnected()
        }
    }

    private suspend fun getChildren(parentId: String, page: Int = 0): ImmutableList<MediaItem> {
        return mMediaBrowser.getChildren(
            parentId,
            page,
            DependencyRegistryCommon.PAGE_SIZE,
            null
        )
            .await().value ?: ImmutableList.of()
    }

    suspend fun sendCommand(command: String, parameters: Bundle?): Boolean =
        sendCommand(command, parameters) { _, _ -> }

    suspend fun sendCommand(
        command: String,
        parameters: Bundle?,
        resultCallback: ((Int, Bundle?) -> Unit)
    ): Boolean = if (mMediaBrowser.isConnected) {
        val args = parameters ?: Bundle()
        mMediaBrowser.sendCustomCommand(SessionCommand(command, args), args).await().let {
            resultCallback(it.resultCode, it.extras)
        }
        true
    } else {
        false
    }

    fun clean() {
        mMediaBrowser.let {
            it.removeListener(mPlayerListener)
            it.release()
        }
    }

    /**
     * Queries for information about the media items that are contained within the specified id and subscribes to
     * receive updates when they change.
     *
     * @param parentId The id of the parent media item whose list of children will be subscribed.
     * @param callback The callback to receive the list of children.
     */
    fun subscribe(
        parentId: String,
        callback: MediaItemsSubscription?,
        page: Int = 0
    ) {
        AppLogger.i("$mClassName subscribe:$parentId, page:$page")
        mScope.launch {
            callback?.onChildrenLoaded(
                parentId, getChildren(parentId, page).toMutableList()
            )
        }
    }

    /**
     * Gets the root id.<br></br>
     * Note that the root id may become invalid or change when when the browser is disconnected.
     *
     * @return Root Id.
     */
    val root: String
        // TODO:
        get() = mMediaBrowser.getLibraryRoot(null).get()?.value?.mediaId ?: ""

    /**
     * @return Metadata.
     */
    val mediaMetadata: MediaMetadata?
        // TODO:
        get() = null

    val currentMediaItem: MediaItem?
        get() = mPlayer.currentMediaItem

    private fun updateNowPlaying(player: Player) {
        val mediaItem = player.currentMediaItem ?: MediaItem.EMPTY
        if (mediaItem == MediaItem.EMPTY) {
            return
        }
        // The current media item from the CastPlayer may have lost some information.
        val mediaItemFuture = mMediaBrowser.getItem(mediaItem.mediaId)
        mediaItemFuture.addListener(
            { mNowPlaying = mediaItemFuture.get().value },
            MoreExecutors.directExecutor()
        )
    }

    /**
     *
     */
    fun playFromMediaId(mediaItem: MediaItem, parentMediaId: String) {
        val player = mPlayer
        val isPrepared = player.playbackState != Player.STATE_IDLE
        if (isPrepared && mediaItem.mediaId == mNowPlaying?.mediaId) {
            AppLogger.w(
                "Playable item ${mediaItem.mediaId} is active already"
            )
        } else {
            mScope.launch {
                val playlist = getChildren(parentMediaId).let { children ->
                    children.filter {
                        it.mediaMetadata.isPlayable ?: false
                    }
                }.toMutableList()
                if (playlist.isEmpty()) {
                    playlist.add(mediaItem)
                }
                val indexOf = playlist.indexOf(mediaItem)
                val startWindowIndex = if (indexOf >= 0) indexOf else 0
                player.setMediaItems(
                    playlist, startWindowIndex, /* startPositionMs= */ C.TIME_UNSET
                )
                player.prepare()
                player.play()
            }
        }
    }

    private inner class BrowserListener : MediaBrowser.Listener {

        override fun onDisconnected(controller: MediaController) {
            AppLogger.w("TODO: BrowserListener Disconnected")
        }

        override fun onChildrenChanged(
            browser: MediaBrowser,
            parentId: String,
            itemCount: Int,
            params: MediaLibraryService.LibraryParams?
        ) {
            AppLogger.w("TODO: BrowserListener ChildrenChanged for $parentId")
        }
    }

    @UnstableApi
    private inner class PlayerListener : Player.Listener {

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mListener.onMetadataChanged(mediaMetadata)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    updateNowPlaying(mPlayer)
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                mListener.onPlaybackStateChanged(PlaybackState(player.playbackState, player.playWhenReady))
                if (player.playbackState != Player.STATE_IDLE) {
                    //networkFailure.postValue(false)
                }
            }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            when (error?.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    //networkFailure.postValue(true)
                }
            }
        }
    }
}
