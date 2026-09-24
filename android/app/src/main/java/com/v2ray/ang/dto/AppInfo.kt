package com.v2ray.ang.dto

/**
 * One row of «Прокси по приложениям» / the routing app picker.
 *
 * IT DOES NOT CARRY THE ICON. It used to hold a fully decoded [android.graphics.drawable.Drawable],
 * which meant the list held one launcher bitmap per installed app for as long as the screen was
 * open. The row asks `AppIconLoader` for its icon when it binds; this record is the name, the
 * package and which side of the user/system split it belongs to.
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
    val isSystemApp: Boolean,
    var isSelected: Int
)
