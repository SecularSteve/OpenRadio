package com.yuriy.openradio.shared.model.media

import androidx.media3.common.MediaItem

class BrowseTree {

    private val mMediaIdToChildren = mutableMapOf<String, MutableList<MediaItem>>()
    private val mMediaIdToChildrenRadioStations = mutableMapOf<String, Set<RadioStation>>()
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

    fun getMediaItemByMediaId(mediaId: String) = mMediaIdToMediaItem[mediaId]

    fun getRadioStationByMediaId(mediaId: String) = mMediaIdToRadioStation[mediaId] ?: RadioStation.INVALID_INSTANCE

    class BrowseData(val children: MutableList<MediaItem>, val radioStations: Set<RadioStation>)
}
