package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import androidx.core.view.isVisible
import com.v2ray.ang.databinding.ViewRowLineBinding
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.viewmodel.UserAssetViewModel
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One geo file per row (A-25).
 *
 * The old row was a bordered card with a blue icon tile and two 24dp glyphs in 8dp boxes - 36dp
 * targets, below the 48dp floor (§14.2) - and the destructive one sat directly beside the edit one
 * with no separation, so «Удалить» was one mis-tap away from «Изменить». §4.5 allows one trailing
 * element, so both live behind a single overflow that opens the shared sheet, with delete separated
 * by a hairline and carrying the red title.
 *
 * The subtitle states the file's size and date in the Numeric role, or says the file is missing -
 * which is the state that actually matters here, because a `geoip.dat` recorded in settings but
 * absent on disk is exactly why routing silently stops working.
 */
class UserAssetAdapter(
    private val viewModel: UserAssetViewModel,
    private val extDir: File,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<UserAssetAdapter.UserAssetViewHolder>() {

    override fun getItemCount() = viewModel.itemCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserAssetViewHolder =
        UserAssetViewHolder(ViewRowLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: UserAssetViewHolder, position: Int) {
        val item = viewModel.getAsset(position) ?: return
        val context = holder.itemView.context
        val file = extDir.listFiles()?.find { it.name == item.assetUrl.remarks }

        val subtitle = if (file == null) {
            context.getString(R.string.asset_missing)
        } else {
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            "${file.length().toTrafficString()}  ·  ${dateFormat.format(Date(file.lastModified()))}"
        }

        // §6: the hairline runs the full width of the card, and there is none above the first
        // file.
        holder.binding.rowDivider.isVisible = position > 0

        // A locked asset, and one imported from a local file, has no URL to edit.
        val editable = item.assetUrl.locked != true && item.assetUrl.url != "file"

        RowBinder.bind(
            root = holder.binding.row.root,
            title = item.assetUrl.remarks,
            subtitle = subtitle,
            glyph = R.drawable.ic_file_24dp,
            // The row itself does nothing; the overflow owns every action this item has, so the
            // row is not a target (22-components 8.4).
            trailing = RowBinder.Trailing.IconAction(
                icon = R.drawable.ic_more_vert_24dp,
                contentDescription = context.getString(R.string.editor_more_actions),
                onClick = {
                    val index = holder.bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@IconAction
                    EditorActionsSheet(context, item.assetUrl.remarks)
                        .action(
                            labelRes = R.string.asset_action_edit,
                            glyph = R.drawable.ic_edit_24dp,
                            enabled = editable,
                        ) { adapterListener?.onEdit(item.guid, index) }
                        .destructive(R.string.asset_action_delete) {
                            adapterListener?.onRemove(item.guid, index)
                        }
                        .show()
                },
            ),
        )
    }

    class UserAssetViewHolder(val binding: ViewRowLineBinding) :
        RecyclerView.ViewHolder(binding.root)
}
