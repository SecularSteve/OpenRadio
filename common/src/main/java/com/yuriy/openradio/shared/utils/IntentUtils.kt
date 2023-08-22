/*
 * Copyright 2017-2021 The "Open Radio" Project. Author: Chernyshov Yuriy
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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 12/20/14
 * E-Mail: chernyshov.yuriy@gmail.com
 */
object IntentUtils {

    /**
     * My profile's url.
     */
    const val AUTHOR_PROFILE_URL = "https://www.linkedin.com/in/yurii-chernyshov"

    const val IVAN_FB_LINK = "https://www.facebook.com/IvanChernyshovRacer"

    const val IVAN_IG_LINK = "https://www.instagram.com/ivan.chernyshov.racer"

    const val GOFUNDME_LINK = "https://www.gofundme.com/f/powering-ivans-path-to-the-podium"

    /**
     * Project's url
     */
    const val PROJECT_HOME_URL = "https://github.com/ChernyshovYuriy/OpenRadio"

    const val EXO_PLAYER_URL = "https://github.com/google/ExoPlayer"

    const val PLAY_LIST_PARSER_URL = "https://github.com/wseemann/JavaPlaylistParser"

    const val OFFLINE_COUNTRIES_URL = "https://github.com/westnordost/countryboundaries"

    const val SWIPE_EFFECT_URL = "https://github.com/xenione/swipe-maker"

    const val REPORT_ISSUE_URL = "https://github.com/ChernyshovYuriy/OpenRadio/issues"

    const val RADIO_BROWSER_URL = "https://www.radio-browser.info"

    const val WEB_RADIO_URL = "https://jcorporation.github.io/webradiodb"

    /**
     * Make intent to navigate to provided url.
     *
     * @param url Url to navigate to.
     * @return [Intent].
     */
    fun makeUrlBrowsableIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    /**
     * Dump content of [Intent]'s [Bundle] into [String].
     *
     * @param intent [Intent] to process.
     * @return [String] representation of [Bundle].
     */
    fun intentBundleToString(intent: Intent?): String {
        return if (intent == null) {
            "Intent[null]"
        } else bundleToString(intent.extras)
    }

    /**
     * Dump content of [Bundle] into [String].
     *
     * @param bundle [Bundle] to process.
     * @return [String] representation of [Bundle].
     */
    fun bundleToString(bundle: Bundle?): String {
        if (bundle == null) {
            return "Bundle[null]"
        }
        val size = try {
            bundle.size()
        } catch (e: Exception) {
            // Address:
            // BadParcelableException: ClassNotFoundException when unmarshalling:
            // com.google.android.apps.docs.common.drivecore.data.CelloEntrySpec
            AppLogger.e("Can not process bundles", e)
        }
        if (size == 0) {
            return "Bundle[]"
        }
        val builder = StringBuilder("Bundle[")
        try {
            for (key in bundle.keySet()) {
                builder.append(key).append(":").append(if (bundle[key] != null) bundle[key] else "NULL")
                builder.append("|")
            }
            builder.delete(builder.length - 1, builder.length)
        } catch (e: Exception) {
            AppLogger.e("Intent's bundle to string", e)
        }
        builder.append("]")
        return builder.toString()
    }

    fun startActivitySafe(context: Context?, intent: Intent) {
        if (context == null) {
            return
        }
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            AppLogger.e("Can not start activity", e)
        }
    }

    fun registerForActivityResultIntrl(
        caller: ActivityResultCaller,
        callback: (data: Intent?) -> Unit
    ): ActivityResultLauncher<Intent> {
        return caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            callback(result.data)
        }
    }

    @Suppress("DEPRECATION")
    inline fun <reified T> getParcelableExtra(key: String, intent: Intent): T? {
        if (intent.hasExtra(key).not()) {
            return null
        }
        return if (AppUtils.hasVersionTiramisu()) {
            intent.getParcelableExtra(key, T::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    inline fun getStringExtra(key: String, intent: Intent): String {
        if (intent.hasExtra(key).not()) {
            return AppUtils.EMPTY_STRING
        }
        return intent.getStringExtra(key) ?: AppUtils.EMPTY_STRING
    }
}
