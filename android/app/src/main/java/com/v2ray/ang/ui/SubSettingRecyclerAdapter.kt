package com.v2ray.ang.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ViewRowCardBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.SubscriptionNaming
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel

/**
 * One subscription group per row (A-17).
 *
 * The old row carried FOUR targets - the row, an edit glyph, a share glyph, a delete glyph, plus a
 * switch - in a card, and hid three of them with `INVISIBLE` when the group had no URL, which left
 * the geometry holding space for controls that were not there. §4.5 allows one trailing element, so
 * everything the group can do lives behind one overflow: enable, edit, QR, copy, and delete under a
 * hairline in the destructive tone.
 *
 * The state a user needs at a glance is in the subtitle instead: whether the group is off, when it
 * last updated, and its URL - or «Локальная группа» when it has none, which is the case the old row
 * expressed by hiding half of itself.
 */
class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.SubViewHolder>(), ItemTouchHelperAdapter {

    /** Set by the activity: the overflow needs the activity's dialogs and sheets, not the adapter's. */
    var onOverflow: ((subId: String, position: Int) -> Unit)? = null

    override fun getItemCount() = viewModel.getAll().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubViewHolder =
        SubViewHolder(ViewRowCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: SubViewHolder, position: Int) {
        val entry = viewModel.getAll()[position]
        val subItem = entry.subscription
        val context = holder.itemView.context

        val unnamedLocalGroup = isUnnamedLocalGroup(subItem)
        val title = displayName(context, subItem)

        val subtitle = when {
            // Title already says it; repeating it under itself is noise.
            unnamedLocalGroup -> null
            subItem.url.isBlank() -> context.getString(R.string.subs_local)
            else -> {
                val updated = subItem.lastUpdated.takeIf { it > 0 }
                    ?.let { context.getString(R.string.subs_updated, Utils.formatTimestamp(it)) }
                    ?: context.getString(R.string.subs_never_updated)
                listOfNotNull(
                    context.getString(R.string.subs_disabled).takeIf { !subItem.enabled },
                    updated,
                ).joinToString(" · ")
            }
        }

        RowBinder.bind(
            root = holder.binding.row.root,
            title = title,
            subtitle = subtitle,
            glyph = R.drawable.ic_subscriptions_24dp,
            trailing = RowBinder.Trailing.IconAction(
                icon = R.drawable.ic_more_vert_24dp,
                contentDescription = context.getString(R.string.editor_more_actions),
                onClick = {
                    val index = holder.bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) onOverflow?.invoke(entry.guid, index)
                },
            ),
        )
    }

    inner class SubViewHolder(val binding: ViewRowCardBinding) :
        RecyclerView.ViewHolder(binding.root), ItemTouchHelperViewHolder {

        override fun onItemSelected() {
            itemView.isActivated = true
        }

        override fun onItemClear() {
            itemView.isActivated = false
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }

    companion object {
        /** The remark `SettingsManager` gives the linkless container it recreates. Not a name. */
        private const val PLACEHOLDER_REMARK = "Default"

        /**
         * The store keeps ONE linkless container for hand-added servers, and recreates it under the
         * remark «Default» the moment the last real подписка is deleted
         * (`SettingsManager.removeSubscriptionWithDefault`).
         */
        private fun isUnnamedLocalGroup(item: SubscriptionItem): Boolean =
            item.url.isBlank() &&
                (item.remarks.isBlank() || item.remarks.equals(PLACEHOLDER_REMARK, ignoreCase = true))

        /**
         * What to call this подписка on screen.
         *
         * Left as it was, the recreated container drew the raw remark «Default», so a user who had
         * just deleted his only подписка saw a row appear where it had been and concluded nothing
         * was deleted - which is how «удалять почему-то я тоже не могу подписки» looks from the
         * outside. The container is named for what it is instead, and the same name is used by the
         * row, by the actions sheet and by the delete confirmation, so all three agree.
         *
         * EVERYTHING ELSE GOES THROUGH [SubscriptionNaming], and it did not: this row printed the
         * RAW REMARK. Two consequences, both visible.
         *
         *  - A подписка pasted from the clipboard is stored with a BLANK remark on purpose — the
         *    провайдер's own `profile-title` is adopted into it on the first fetch. Paste one with
         *    no network and that fetch fails, so this row drew a title of «» — a nameless row with
         *    a glyph and «Никогда не обновлялась» under it. The delete confirmation and the edit
         *    screen's bar quoted the same empty string.
         *  - An install upgraded from a build that stored «import sub» drew «import sub» here while
         *    Главная, which does resolve the name, drew the real one. Two surfaces, two names for
         *    one подписка.
         *
         * The resolver already ranks the провайдер's title above the remark and refuses the
         * placeholders, and it ends in «Подписка» rather than an empty string — which is the same
         * answer the card gives, so the two agree by construction.
         */
        fun displayName(context: Context, item: SubscriptionItem): String =
            if (isUnnamedLocalGroup(item)) {
                context.getString(R.string.subs_local)
            } else {
                SubscriptionNaming.titleOf(context, item)
            }
    }
}
