package com.yuriy.openradio.automotive.dependencies

import com.yuriy.openradio.automotive.ui.AutomotiveSettingsActivity
import com.yuriy.openradio.automotive.ui.AutomotiveSettingsActivityPresenter
import com.yuriy.openradio.automotive.ui.AutomotiveSettingsActivityPresenterImpl
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommon
import com.yuriy.openradio.shared.dependencies.LocationStorageDependency
import com.yuriy.openradio.shared.model.storage.LocationStorage
import java.util.concurrent.atomic.AtomicBoolean

object DependencyRegistryAutomotive : LocationStorageDependency {

    private lateinit var sAutomotiveSettingsActivityPresenter: AutomotiveSettingsActivityPresenter
    private lateinit var sLocationStorage: LocationStorage

    @Volatile
    private var sInit = AtomicBoolean(false)

    /**
     * Init with application's context only!
     */
    fun init() {
        if (sInit.get()) {
            return
        }

        DependencyRegistryCommon.injectLocationStorage(this)
        sAutomotiveSettingsActivityPresenter = AutomotiveSettingsActivityPresenterImpl(
            sLocationStorage
        )

        sInit.set(true)
    }

    override fun configureWith(locationStorage: LocationStorage) {
        sLocationStorage = locationStorage
    }

    fun inject(dependency: AutomotiveSettingsActivity) {
        dependency.configureWith(sAutomotiveSettingsActivityPresenter)
    }
}
