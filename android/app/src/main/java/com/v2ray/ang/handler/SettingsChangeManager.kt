package com.v2ray.ang.handler

import kotlinx.coroutines.flow.MutableStateFlow

object SettingsChangeManager {
    private val _restartService = MutableStateFlow(false)
    private val _setupGroupTab = MutableStateFlow(false)

    // THE `recreateUi` CHANNEL IS GONE, and it was gone in fact long before it was gone in code:
    // nothing raised it. Upstream signalled a theme change through it; this fork applies the theme
    // where it is chosen — `MmkvPreferenceDataStore.notifySettingChanged` calls
    // `SettingsManager.setNightMode()` (i.e. `AppCompatDelegate.setDefaultNightMode`, which
    // recreates by itself) and `SettingsTabFragment` calls `requireActivity().recreate()` directly.
    // So the flag could never be true, and the `recreate()` branch reading it in
    // `MainActivity.requestActivityLauncher` was unreachable code claiming to be the theme's route.

    // Mark restartService as requiring a restart
    fun makeRestartService() {
        _restartService.value = true
    }

    // Read and clear the restartService flag
    fun consumeRestartService(): Boolean {
        val v = _restartService.value
        _restartService.value = false
        return v
    }

    // Mark reinitGroupTab as requiring tab reinitialization
    fun makeSetupGroupTab() {
        _setupGroupTab.value = true
    }

    // Read and clear the reinitGroupTab flag
    fun consumeSetupGroupTab(): Boolean {
        val v = _setupGroupTab.value
        _setupGroupTab.value = false
        return v
    }
}
