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

package com.yuriy.openradio.automotive

import com.yuriy.openradio.automotive.dependencies.DependencyRegistryAutomotive
import com.yuriy.openradio.shared.MainAppCommon
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommonUi

class MainAppAutomotive: MainAppCommon() {

    override fun onCreate() {
        super.onCreate()
        DependencyRegistryCommonUi.init(applicationContext)
        DependencyRegistryAutomotive.init()
    }
}
