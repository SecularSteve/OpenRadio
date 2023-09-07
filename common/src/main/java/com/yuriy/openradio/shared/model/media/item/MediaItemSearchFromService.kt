/*
 * Copyright 2015-2022 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.model.media.item

import com.yuriy.openradio.shared.model.media.MediaId
import com.yuriy.openradio.shared.model.media.item.MediaItemCommand.IUpdatePlaybackState
import com.yuriy.openradio.shared.model.translation.MediaIdBuilder
import com.yuriy.openradio.shared.utils.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 8/31/15
 * E-Mail: chernyshov.yuriy@gmail.com
 *
 * [MediaItemSearchFromService] is concrete implementation of the [MediaItemCommand] that
 * designed to prepare data to display radio stations from the search collection.
 */
class MediaItemSearchFromService : IndexableMediaItemCommand() {

    override fun execute(
        playbackStateListener: IUpdatePlaybackState,
        dependencies: MediaItemCommandDependencies
    ) {
        super.execute(playbackStateListener, dependencies)
        if (dependencies.isSavedInstance) {
            deliverResult(dependencies)
            return
        }
        dependencies.mScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(MediaItemCommand.CMD_TIMEOUT_MS) {
                handleDataLoaded(
                    playbackStateListener,
                    dependencies,
                    dependencies.presenter.getSearchStations(
                        AppUtils.getSearchQueryFromBundle(dependencies.options),
                        object : MediaIdBuilder {

                            override fun build(value: String): String {
                                return MediaId.makeSearchId(value)
                            }
                        }
                    )
                )
            } ?: dependencies.resultListener.onResult()
        }
    }
}
