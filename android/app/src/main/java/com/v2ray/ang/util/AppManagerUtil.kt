package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.v2ray.ang.dto.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManagerUtil {
    /**
     * The installed apps the per-app screens list — names and packages, NO ICONS.
     *
     * The донор's version called `applicationInfo.loadIcon(packageManager)` here, once per app, and
     * stored every result in the returned list. Building the list therefore decoded a launcher
     * bitmap for every app on the phone before the first row could be drawn, and then held all of
     * them for as long as the screen stayed open. Icons are loaded per row, on demand, by
     * [AppIconLoader].
     *
     * `loadLabel` stays: the list is SORTED by the label, so every one of them is needed before
     * anything can be shown, and a label is a string.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val apps = ArrayList<AppInfo>(packages.size)

            for (pkg in packages) {
                val applicationInfo = pkg.applicationInfo ?: continue

                val appName = applicationInfo.loadLabel(packageManager).toString()
                val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM > 0

                apps.add(AppInfo(appName, pkg.packageName, isSystemApp, 0))
            }

            return@withContext apps
        }
}
