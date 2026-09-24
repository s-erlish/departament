package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager

class PerAppProxyViewModel : ViewModel() {
    private val blacklist: MutableSet<String> = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.let {
        HashSet(it)
    } ?: HashSet()

    fun contains(packageName: String): Boolean = blacklist.contains(packageName)

    fun getAll(): Set<String> = blacklist.toSet()

    fun add(packageName: String): Boolean {
        val changed = blacklist.add(packageName)
        if (changed) {
            save()
        }
        return changed
    }

    fun remove(packageName: String): Boolean {
        val changed = blacklist.remove(packageName)
        if (changed) {
            save()
        }
        return changed
    }

    fun toggle(packageName: String) {
        if (blacklist.contains(packageName)) {
            remove(packageName)
        } else {
            add(packageName)
        }
    }

    fun addAll(packages: Collection<String>) {
        if (blacklist.addAll(packages)) {
            save()
        }
    }

    fun removeAll(packages: Collection<String>) {
        if (blacklist.removeAll(packages.toSet())) {
            save()
        }
    }

    fun clear() {
        if (blacklist.isNotEmpty()) {
            blacklist.clear()
            save()
        }
    }

    /**
     * Applies [added] and [removed] to the selection WITHOUT asking for a service restart.
     *
     * The «Российские приложения» preset is the only caller, and the omission is the point: the
     * owner asked that applying it must not drop a live tunnel by itself, which is exactly what the
     * desktop's equivalent does. Every other edit on this screen still raises the flag, because a
     * user who ticks one app is expressing an intent about the connection they are looking at; a
     * preset switch is a configuration choice that can wait for the next connection, and the screen
     * says as much.
     */
    fun applyPresetQuietly(added: Collection<String>, removed: Collection<String>) {
        val changed = blacklist.addAll(added) or blacklist.removeAll(removed.toSet())
        if (changed) {
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, blacklist)
        }
    }

    private fun save() {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, blacklist)
        SettingsChangeManager.makeRestartService()
    }
}