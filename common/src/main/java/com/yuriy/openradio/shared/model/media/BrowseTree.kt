package com.yuriy.openradio.shared.model.media

import androidx.media3.common.MediaItem

class BrowseTree {

    private val mMediaIdToChildren = mutableMapOf<String, MutableList<MediaItem>>()
    private val mMediaIdToChildrenRadioStations = mutableMapOf<String, MutableSet<RadioStation>>()
    private val mMediaIdToMediaItem = mutableMapOf<String, MediaItem>()
    private val mMediaIdToRadioStation = mutableMapOf<String, RadioStation>()

    operator fun get(mediaId: String) = mMediaIdToChildren[mediaId]

    operator fun set(mediaId: String, browseData: BrowseData) {
        mMediaIdToChildren[mediaId] = browseData.children
        mMediaIdToChildrenRadioStations[mediaId] = browseData.radioStations
        browseData.children.forEach { mediaItem ->
            run {
                mMediaIdToMediaItem[mediaItem.mediaId] = mediaItem
            }
        }
        browseData.radioStations.forEach { radioStation ->
            run {
                mMediaIdToRadioStation[radioStation.id] = radioStation
            }
        }
    }

    fun append(mediaId: String, browseData: BrowseData) {
        mMediaIdToChildren[mediaId]?.plusAssign(browseData.children)
        mMediaIdToChildrenRadioStations[mediaId]?.plusAssign(browseData.radioStations)
        browseData.children.forEach { mediaItem ->
            run {
                mMediaIdToMediaItem[mediaItem.mediaId] = mediaItem
            }
        }
        browseData.radioStations.forEach { radioStation ->
            run {
                mMediaIdToRadioStation[radioStation.id] = radioStation
            }
        }
    }

    fun getMediaItemsByMediaId(mediaId: String): MutableList<MediaItem> {
        for (entry in mMediaIdToChildren.entries) {
            if (entry.key == mediaId) {
                return entry.value
            }
        }
        return mutableListOf()
    }

    fun getMediaItemByMediaId(mediaId: String): MediaItem? {
        return mMediaIdToMediaItem[mediaId]
    }

    fun getRadioStationByMediaId(mediaId: String) = mMediaIdToRadioStation[mediaId] ?: RadioStation.INVALID_INSTANCE

    fun invalidate(mediaId: String) {
        mMediaIdToChildren.remove(mediaId)?.forEach {
            mMediaIdToMediaItem.remove(it.mediaId)
        }
        mMediaIdToChildrenRadioStations.remove(mediaId)?.forEach {
            mMediaIdToRadioStation.remove(it.id)
        }
    }

    class BrowseData(val children: MutableList<MediaItem>, val radioStations: MutableSet<RadioStation>)
}
