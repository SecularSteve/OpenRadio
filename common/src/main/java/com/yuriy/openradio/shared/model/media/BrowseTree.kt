package com.yuriy.openradio.shared.model.media

import androidx.media3.common.MediaItem

class BrowseTree {

    private val mMediaIdToChildren = mutableMapOf<String, MutableList<MediaItem>>()
    private val mMediaIdToMediaItem = mutableMapOf<String, MediaItem>()

    operator fun get(mediaId: String) = mMediaIdToChildren[mediaId]

    operator fun set(mediaId: String, children: MutableList<MediaItem>) {
        mMediaIdToChildren[mediaId] = children
    }

    fun getMediaItemByMediaId(mediaId: String) = mMediaIdToMediaItem[mediaId]
}
