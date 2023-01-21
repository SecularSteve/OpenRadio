/*
 * Copyright 2017, 2022 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.model

import android.net.Uri
import androidx.core.util.Pair
import com.yuriy.openradio.shared.model.translation.MediaIdBuilder
import com.yuriy.openradio.shared.model.media.Category
import com.yuriy.openradio.shared.model.media.RadioStation
import com.yuriy.openradio.shared.service.location.Country

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 12/14/14
 * E-Mail: chernyshov.yuriy@gmail.com
 *
 * [ModelLayer] is a model interface which provides service behavior of the Open Radio.
 */
interface ModelLayer {
    /**
     * Get a list of all categories.
     *
     * @param uri [Uri] of the request.
     * @return Set of the [Category]s
     */
    fun getAllCategories(uri: Uri): Set<Category>

    /**
     * Get a list of all countries.
     *
     * @return Set of the Countries.
     */
    fun getAllCountries(uri: Uri): Set<Country>

    /**
     * Get a list of Radio Stations by provided Uri.
     *
     * @param uri [Uri] of the request.
     * @return collection of the Radio Stations.
     */
    fun getStations(uri: Uri, mediaIdBuilder: MediaIdBuilder): Set<RadioStation>

    /**
     * Add Radio Station to server.
     *
     * @param uri [Uri] of the request.
     * @param parameters List of parameters to attach to url connection.
     */
    fun addStation(uri: Uri, parameters: List<Pair<String, String>>): Boolean
}
