/*
 * Copyright 2022 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared

import android.content.Context
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommon
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommonUi
import com.yuriy.openradio.shared.dependencies.SleepTimerModelDependency
import com.yuriy.openradio.shared.model.timer.SleepTimerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class MainAppCommonUi: MainAppCommon(), SleepTimerModelDependency {

    private lateinit var mSleepTimerModel: SleepTimerModel

    override fun configureWith(sleepTimerModel: SleepTimerModel) {
        mSleepTimerModel = sleepTimerModel
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DependencyRegistryCommonUi.init(base)
    }

    override fun onCreate() {
        super.onCreate()
        DependencyRegistryCommon.injectSleepTimerModel(this)
        mAppScope.launch(Dispatchers.IO) {
            mSleepTimerModel.init()
        }
    }
}
