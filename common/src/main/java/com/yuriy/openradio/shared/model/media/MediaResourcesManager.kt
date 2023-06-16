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
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import androidx.work.await
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommon
import com.yuriy.openradio.shared.extentions.isEnded
import com.yuriy.openradio.shared.extentions.isPlayEnabled
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

    val player: Player get() = mMediaBrowser

    val nowPlaying = MutableLiveData<MediaItem>()
        .apply { postValue(MediaItem.EMPTY) }

    val rootMediaItem = MutableLiveData<MediaItem>()
        .apply { postValue(MediaItem.EMPTY) }

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
            rootMediaItem.postValue(root)
            AppLogger.d("$mClassName root '${root?.mediaId}'")
            mListener.onConnected()
        }
    }

    suspend fun getChildren(parentId: String): ImmutableList<MediaItem> {
        return mMediaBrowser.getChildren(parentId, 0, DependencyRegistryCommon.PAGE_SIZE, null).await().value
            ?: ImmutableList.of()
    }

    fun clean() {
        rootMediaItem.postValue(MediaItem.EMPTY)
        nowPlaying.postValue(MediaItem.EMPTY)
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
        callback: MediaItemsSubscription?
    ) {
        AppLogger.i("$mClassName subscribe:$parentId")
        mScope.launch {
            callback?.onChildrenLoaded(
                parentId, getChildren(parentId).toMutableList()
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
        get() = null

    private fun updateNowPlaying(player: Player) {
        val mediaItem = player.currentMediaItem ?: MediaItem.EMPTY
        if (mediaItem == MediaItem.EMPTY) {
            return
        }
        // The current media item from the CastPlayer may have lost some information.
        val mediaItemFuture = mMediaBrowser.getItem(mediaItem.mediaId)
        mediaItemFuture.addListener(
            Runnable {
                val fullMediaItem = mediaItemFuture.get().value ?: return@Runnable
                nowPlaying.postValue(
                    mediaItem.buildUpon().setMediaMetadata(fullMediaItem.mediaMetadata).build()
                )
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * @param mediaId media id of the item to play.
     */
    fun playFromMediaId(mediaId: String, parentMediaId: String) {
        val nowPlaying = nowPlaying.value
        val player = player ?: return
        val pauseThenPlaying = false

        val isPrepared = player.playbackState != Player.STATE_IDLE
        if (isPrepared && mediaId == nowPlaying?.mediaId) {
            when {
                player.isPlaying ->
                    if (pauseThenPlaying) player.pause() else Unit

                player.isPlayEnabled -> player.play()
                player.isEnded -> player.seekTo(C.TIME_UNSET)
                else -> {
                    AppLogger.w(
                        "Playable item clicked but neither play nor pause are enabled!" +
                                " (mediaId=${mediaId})"
                    )
                }
            }
        } else {
            mScope.launch {
                var playlist: MutableList<MediaItem> = arrayListOf()
                // load the children of the parent if requested
                parentMediaId?.let {
                    playlist = getChildren(parentMediaId).let { children ->
                        children.filter {
                            it.mediaMetadata.isPlayable ?: false
                        }
                    }.toMutableList()
                }
                if (playlist.isEmpty()) {
                    //playlist.add(mediaItem)
                }
                //val indexOf = playlist.indexOf(mediaItem)
                val indexOf = 0
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
            // TODO:
            //release()
        }

        override fun onChildrenChanged(
            browser: MediaBrowser,
            parentId: String,
            itemCount: Int,
            params: MediaLibraryService.LibraryParams?
        ) {
            AppLogger.d("TODO: ChildrenChanged for $parentId")
        }
    }

    private inner class PlayerListener : Player.Listener {

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                //updatePlaybackState(player)
                if (player.playbackState != Player.STATE_IDLE) {
                    //networkFailure.postValue(false)
                }
            }
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            ) {
                updateNowPlaying(player)
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
