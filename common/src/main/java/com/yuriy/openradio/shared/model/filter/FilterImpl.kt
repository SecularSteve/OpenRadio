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

package com.yuriy.openradio.shared.model.filter

import com.yuriy.openradio.shared.model.media.RadioStation

/**
 * Default implementation of the [Filter] interface.
 */
class FilterImpl : Filter {

    private val mData = arrayOf(
        Item("stream.radiojar.com/z4qyckhr9druv", "radiosindia.com")
    )

    override fun filter(radioStation: RadioStation): Boolean {
        var result = false
        for (item in mData) {
            val variant = radioStation.mediaStream.getVariant(0)
            if (variant.url.contains(item.mStreamUrl) || radioStation.homePage.contains(item.homePage)) {
                result = true
                break
            }
        }
        return result
    }

    private data class Item(val mStreamUrl: String, val homePage: String)
}
