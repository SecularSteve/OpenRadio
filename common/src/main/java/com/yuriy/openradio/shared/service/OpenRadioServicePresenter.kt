package com.yuriy.openradio.shared.service

import android.content.Context
import com.yuriy.openradio.shared.model.media.EqualizerLayer
import com.yuriy.openradio.shared.model.media.RemoteControlListener
import com.yuriy.openradio.shared.model.net.NetworkMonitorListener
import com.yuriy.openradio.shared.model.timer.SleepTimerModel
import com.yuriy.openradio.shared.model.translation.MediaIdBuilder
import com.yuriy.openradio.shared.model.translation.MediaIdBuilderDefault
import com.yuriy.openradio.shared.vo.Category
import com.yuriy.openradio.shared.vo.Country
import com.yuriy.openradio.shared.vo.RadioStation

interface OpenRadioServicePresenter {

    fun startNetworkMonitor(context: Context, listener: NetworkMonitorListener)

    fun stopNetworkMonitor(context: Context)

    fun isMobileNetwork(): Boolean

    fun getUseMobile(): Boolean

    fun getStationsInCategory(categoryId: String, pageNumber: Int): List<RadioStation>

    fun getStationsByCountry(countryCode: String, pageNumber: Int): List<RadioStation>

    fun getRecentlyAddedStations(): List<RadioStation>

    fun getPopularStations(): List<RadioStation>

    fun getSearchStations(query: String, mediaIdBuilder: MediaIdBuilder = MediaIdBuilderDefault()): List<RadioStation>

    fun getAllCategories(): List<Category>

    fun getAllCountries(): List<Country>

    fun getAllFavorites(): List<RadioStation>

    fun getAllDeviceLocal(): List<RadioStation>

    fun getLastRadioStation(): RadioStation

    fun getEqualizerLayer(): EqualizerLayer

    fun getRadioStationsComparator(): Comparator<RadioStation>

    fun setLastRadioStation(radioStation: RadioStation)

    fun getCountryCode(): String

    fun isRadioStationFavorite(radioStation: RadioStation): Boolean

    fun updateRadioStationFavorite(radioStation: RadioStation)

    fun updateRadioStationFavorite(radioStation: RadioStation, isFavorite: Boolean)

    fun updateSortIds(mediaId: String, sortId: Int, categoryMediaId: String)

    fun getSleepTimerModel(): SleepTimerModel

    /**
     * Clear resources related to service provider, such as persistent or in memory storage, etc ...
     */
    fun clear()

    /**
     * Close resources related to service provider, such as connections, streams, etc ...
     */
    fun close()

    fun setRemoteControlListener(value: RemoteControlListener)

    fun removeRemoteControlListener(value: RemoteControlListener)
}
