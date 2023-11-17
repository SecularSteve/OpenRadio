/*
 * Copyright 2023 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.model.parser

import com.google.firebase.firestore.QueryDocumentSnapshot
import com.yuriy.openradio.shared.model.media.MediaStream
import com.yuriy.openradio.shared.model.media.RadioStation
import com.yuriy.openradio.shared.model.media.setVariant
import com.yuriy.openradio.shared.service.location.LocationService
import com.yuriy.openradio.shared.utils.AppUtils

class FeaturedParserLayerFirestone : FeaturedParserLayer {

    override fun getRadioStation(snapshot: QueryDocumentSnapshot): RadioStation {
        val featured = snapshot.toObject(FeaturedRadioStation::class.java)
        val radioStation = RadioStation.makeDefaultInstance(featured.uuid)
        radioStation.name = featured.name
        radioStation.homePage = featured.webUrl
        radioStation.country = LocationService.COUNTRY_CODE_TO_NAME[featured.countryCode] ?: AppUtils.EMPTY_STRING
        radioStation.countryCode = featured.countryCode
        radioStation.imageUrl = featured.logoUrl
        radioStation.setVariant(MediaStream.BIT_RATE_DEFAULT, featured.streamUrl)
        return radioStation
    }

    /**
     * Empty constructor is needed for deserialization by Firestone SDK.
     */
    @Suppress("unused")
    private data class FeaturedRadioStation(
        val uuid: String,
        val countryCode: String,
        val logoUrl: String,
        val name: String,
        val streamM3u: String,
        val streamPls: String,
        val streamUrl: String,
        val webUrl: String
    ) {
        constructor() : this("", "", "", "", "", "", "", "")
    }
}