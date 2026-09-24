package com.v2ray.ang.enums

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Permission types used in the app, handling API level differences.
 */
enum class PermissionType {
    /** Camera permission (used for scanning QR codes) */
    CAMERA {
        override fun getPermission(): String = Manifest.permission.CAMERA
    },

    /** Notification permission (Android 13+) */
    POST_NOTIFICATIONS {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun getPermission(): String = Manifest.permission.POST_NOTIFICATIONS
    };

    /** Return the actual Android permission string */
    abstract fun getPermission(): String

    // `getLabel()` USED TO STAND HERE and nothing has ever called it. It answered «Camera» and
    // «Notification» — hardcoded English, in an app whose interface is Russian and whose every
    // other user-facing word comes out of res/values/strings.xml. A label that cannot be shown to
    // this product's user is not a label; the copy for a permission rationale belongs in strings,
    // and the screen that needs one will ask for it there.
}