package com.v2ray.ang.helper

import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast

/**
 * Helper for requesting permissions.
 */
class PermissionHelper(private val activity: AppCompatActivity) {
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            permissionCallback?.invoke(isGranted)
            permissionCallback = null
        }

    /**
     * Check the permission and request it if not granted.
     *
     * @param permissionType the type of permission
     * @param onGranted called when permission is granted (called immediately if already granted)
     */
    fun request(permissionType: PermissionType, onGranted: () -> Unit) {
        val permission = permissionType.getPermission()
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            permissionCallback = { isGranted ->
                if (isGranted) {
                    onGranted()
                } else {
                    // The sentence alone, without the permission's technical label glued onto the
                    // end of it. `permissionType.getLabel()` is upstream's own name for the
                    // Android permission and reads as machine text beside a Russian sentence —
                    // `NoticePolicy` would refuse the concatenation anyway, and losing it costs
                    // nothing: the user just answered the system dialog that named it.
                    activity.toast(R.string.toast_permission_denied)
                }
            }
            permissionLauncher.launch(permission)
        }
    }
}