package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.R
import com.v2ray.ang.databinding.SheetServerActionsBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.ui.component.onSingleClick

/**
 * Incy-styled bottom sheet with per-server actions, shown on long-press of a server row
 * (the S3 redesign removed the inline share/edit/delete buttons from the row).
 *
 * This class only builds and wires the sheet plus enforces the locked-profile guard; every
 * action delegates to the existing MainActivity flows through the callbacks passed in, so no
 * business logic is duplicated here.
 */
class ServerActionsSheet(
    private val context: Context,
    private val profile: ProfileItem,
    private val onShareQr: () -> Unit,
    private val onShareClipboard: () -> Unit,
    private val onEdit: () -> Unit,
    private val onDuplicate: () -> Unit,
    private val onSetDefault: () -> Unit,
    private val onDelete: () -> Unit,
) {

    fun show() {
        val binding = SheetServerActionsBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        // Let our rounded-top surface show through the Material sheet container.
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }

        binding.tvSheetTitle.text =
            profile.remarks.ifBlank { context.getString(R.string.server_actions_title) }

        // Locked profiles (hidden/locked JSON templates): block share/export/edit/duplicate,
        // but keep "set default" and "delete" available.
        val locked = isLocked(profile)
        val shareEditVisibility = if (locked) View.GONE else View.VISIBLE
        binding.rowShareQr.visibility = shareEditVisibility
        binding.rowShareClipboard.visibility = shareEditVisibility
        binding.rowEdit.visibility = shareEditVisibility
        binding.rowDuplicate.visibility = shareEditVisibility

        // dismiss() is not the guard it looks like: the sheet animates out, and a second tap
        // landing during that animation ran the action again — two duplicated серверы from one
        // «Дублировать», two delete confirmations from one «Удалить».
        binding.rowShareQr.onSingleClick { dialog.dismiss(); onShareQr() }
        binding.rowShareClipboard.onSingleClick { dialog.dismiss(); onShareClipboard() }
        binding.rowEdit.onSingleClick { dialog.dismiss(); onEdit() }
        binding.rowDuplicate.onSingleClick { dialog.dismiss(); onDuplicate() }
        binding.rowSetDefault.onSingleClick { dialog.dismiss(); onSetDefault() }
        binding.rowDelete.onSingleClick { dialog.dismiss(); onDelete() }

        dialog.show()
    }

    /**
     * Whether this profile is a locked/hidden JSON template whose config must not be
     * shared, exported, edited or duplicated. Delete stays allowed regardless.
     */
    private fun isLocked(profile: ProfileItem): Boolean {
        return TemplateManager.isLocked(profile)
    }
}
