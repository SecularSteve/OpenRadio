/*
 * Copyright 2022, 2023. The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.utils

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yuriy.openradio.R
import com.yuriy.openradio.shared.model.media.Category
import com.yuriy.openradio.shared.model.media.MediaId
import com.yuriy.openradio.shared.model.media.RadioStation
import com.yuriy.openradio.shared.model.media.getStreamBitrate
import com.yuriy.openradio.shared.model.media.getStreamUrlFixed
import com.yuriy.openradio.shared.service.location.Country
import com.yuriy.openradio.shared.service.location.LocationService
import java.util.Locale

object MediaItemBuilder {

    fun buildRootMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildMediaItemListEnded(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_LIST_ENDED)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildChildCategory(context: Context, category: Category): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_child_categories)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_CHILD_CATEGORIES + category.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(category.title)
                    .setSubtitle(category.getDescription(context))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildChildCategories(): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_radio_station_empty)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_CHILD_CATEGORIES)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildFavoritesMenuItem(context: Context): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_stars_black_24dp)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_FAVORITES_LIST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.favorites_list_title))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildRecentMenuItem(context: Context): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_fiber_new_black_24dp)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_RECENT_STATIONS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.new_stations_title))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildBrowseMenuItem(context: Context): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_BROWSE_CAR)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.browse_title))
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildPopularMenuItem(context: Context): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_trending_up_black_24dp)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_POPULAR_STATIONS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.popular_stations_title))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildCategoriesMenuItem(context: Context): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_all_categories)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_ALL_CATEGORIES)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.all_categories_title))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildCountriesMenuItem(context: Context): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_public_black_24dp)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_COUNTRIES_LIST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.countries_list_title))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildCountryMenuItem(context: Context, countryCode: String): MediaItem {
        val identifier = context.resources.getIdentifier(
            "flag_" + countryCode.lowercase(Locale.ROOT),
            "drawable", context.packageName
        )
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, identifier)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_COUNTRY_STATIONS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(LocationService.COUNTRY_CODE_TO_NAME[countryCode])
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildCountryMenuItem(context: Context, country: Country): MediaItem {
        val identifier = context.resources.getIdentifier(
            "flag_" + country.code.lowercase(Locale.ROOT),
            "drawable", context.packageName
        )
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, identifier)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_COUNTRIES_LIST + country.code)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(country.name)
                    .setSubtitle(country.code)
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildDeviceLocalsMenuItem(context: Context): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_locals)
        return MediaItem.Builder()
            .setMediaId(MediaId.MEDIA_ID_LOCAL_RADIO_STATIONS_LIST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setTitle(context.getString(R.string.local_radio_stations_list_title))
                    .setExtras(bundle)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    fun buildPlayable(
        radioStation: RadioStation,
        sortId: Int,
        isFavorite: Boolean = false,
        isLocal: Boolean = false,
        isUpdateLastPlayedField: Boolean = false
    ): MediaItem {
        val bundle = Bundle()
        MediaItemHelper.updateBitrateField(bundle, radioStation.getStreamBitrate())
        MediaItemHelper.updateFavoriteField(bundle, isFavorite)
        MediaItemHelper.updateSortIdField(bundle, sortId)
        MediaItemHelper.updateLocalRadioStationField(bundle, isLocal)
        MediaItemHelper.updateLastPlayedField(bundle, isUpdateLastPlayedField)
        MediaItemHelper.setDrawableId(bundle, R.drawable.ic_radio_station_empty)
        val uri = Uri.parse(radioStation.getStreamUrlFixed())
        return MediaItem.Builder()
            .setMediaId(radioStation.id)
            .setUri(uri)
            .setMimeType(AppUtils.getMimeTypeFromUri(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                    .setTitle(radioStation.name)
                    .setSubtitle(radioStation.country)
                    .setDescription(radioStation.genre)
                    .setArtworkUri(radioStation.imageUri)
                    .setExtras(bundle)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}
