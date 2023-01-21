/*
 * Copyright 2017 The "Open Radio" Project. Author: Chernyshov Yuriy
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
package com.yuriy.openradio.shared.model.translation

import com.yuriy.openradio.shared.model.media.RadioStation

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 6/4/15
 * E-Mail: chernyshov.yuriy@gmail.com
 *
 * [RadioStationSerializer] is an interface that provides common method to serialize
 * [RadioStation].
 */
interface RadioStationSerializer {
    /**
     * Serialize [RadioStation].
     *
     * @param radioStation [RadioStation] to be serialized.
     * @return String representation of the [RadioStation].
     */
    fun serialize(radioStation: RadioStation): String
}