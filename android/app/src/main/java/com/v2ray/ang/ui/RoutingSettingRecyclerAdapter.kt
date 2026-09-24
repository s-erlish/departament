package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ViewRowLineBinding
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.restoreChecked
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * One routing rule per row (A-20).
 *
 * The old row carried three targets in a space 56dp tall: the row itself, a 24dp edit glyph in a
 * 40dp box, and a switch stacked underneath it - all inside a bordered card, inside a 12dp-inset
 * wrapper, with a green tile that encoded nothing. §4.5 allows a ROW exactly one trailing
 * element, so the row is a `Row.Navigation` whose chevron opens the rule form, and «Защищено» and
 * delete live there.
 *
 * «Включено» does NOT: the inline switch is back on the card, beside the row
 * (`view_row_card.xml`'s `row_card_switch`), because `chk_enable` was on this row at 5e8cd54 and
 * moving it into the editor turned a one-tap change into three. Owner rule, 2026-08-02: an element
 * that was on screen and is now gone is a regression. The editor keeps its copy — two homes for one
 * toggle beat a capability that got harder to reach. The switch sits on the CARD rather than in the
 * row's trailing slot precisely so §4.5 still holds for the row itself.
 *
 * The subtitle is what makes the list auditable without opening anything: state first when it is not
 * the default, then what the rule matches, then where it sends the traffic.
 *
 * Long-press still starts a drag. Its feedback used to be `Color.LTGRAY` painted straight onto the
 * item view - a raw literal that is invisible in the light theme and wrong in the mono one. It is
 * now `isActivated`, which `@drawable/bg_row` already draws as the 12% accent fill.
 */
class RoutingSettingRecyclerAdapter(
    private val viewModel: RoutingSettingsViewModel,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<RoutingSettingRecyclerAdapter.RuleViewHolder>(),
    ItemTouchHelperAdapter {

    override fun getItemCount() = viewModel.getAll().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder =
        RuleViewHolder(ViewRowLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        val ruleset = viewModel.getAll()[position]
        val context = holder.itemView.context

        // §6: the hairline runs the full width of the card and there is none above the first
        // row. Bound per item rather than by an ItemDecoration so insert, delete and drag
        // reorder all keep it right.
        holder.binding.rowDivider.isVisible = position > 0

        RowBinder.bind(
            root = holder.binding.row.root,
            title = ruleset.remarks.orEmpty().ifBlank { ruleset.outboundTag.orEmpty() },
            subtitle = summarise(ruleset, context.resources),
            glyph = R.drawable.ic_routing_24dp,
            trailing = RowBinder.Trailing.Chevron,
            onClick = { adapterListener?.onEdit("", holder.bindingAdapterPosition) },
        )

        // THE INLINE ENABLE SWITCH, RESTORED. `item_recycler_routing_setting.xml` carried
        // `chk_enable` on the row at 5e8cd54; the rebuild moved it inside the rule editor, so
        // turning one rule off went from one tap to three. The editor's copy stays — two homes for
        // one toggle beat a capability that got harder to reach — and this is the near one.
        //
        // The listener is cleared before the state is set: this is a recycled view, and assigning
        // `isChecked` while the previous row's listener is still attached would write THAT rule's
        // guid with THIS rule's value.
        //
        // And the state is RESTORED, not played. A recycled holder arrives carrying the last rule's
        // position, so a plain `isChecked` morphs the thumb across on every bind — a 250 ms
        // animated-vector per row, running while the list scrolls. `restoreChecked` jumps to the
        // value and, because it does nothing when the value already matches, leaves the animation
        // from the user's own tap alone when `notifyItemChanged` rebinds the row underneath it.
        val toggle = holder.binding.rowLineSwitch
        toggle.setOnCheckedChangeListener(null)
        toggle.isVisible = true
        toggle.restoreChecked(ruleset.enabled)
        toggle.contentDescription = context.getString(R.string.routing_rule_enabled_cd)
        toggle.setOnCheckedChangeListener { _, checked ->
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
            viewModel.setEnabled(index, checked)
            notifyItemChanged(index)
        }
    }

    /**
     * «google.com, ads · direct» — what the rule MATCHES and where it sends it, and nothing else.
     *
     * «Выключено» used to lead this line whenever the rule was off. The reasoning was that state is
     * worth stating when it is not the default — which is true of a fact the reader cannot otherwise
     * see, and false here: this row carries a switch, two dp to the right, already showing exactly
     * that. So the word restated the control beside it and pushed the rule's actual content along by
     * a segment. The owner, from the device: «если отключить то почему-то пишется выключено, там
     * такого быть не должно».
     *
     * «Заблокировано» stays. A locked rule looks identical to an unlocked one — there is no control
     * anywhere on the row reporting it — so that one IS the case the line is for.
     */
    private fun summarise(ruleset: RulesetItem, res: android.content.res.Resources): CharSequence {
        val matcher = listOfNotNull(
            ruleset.domain?.takeIf { it.isNotEmpty() },
            ruleset.ip?.takeIf { it.isNotEmpty() },
            ruleset.process?.takeIf { it.isNotEmpty() },
        ).firstOrNull()?.joinToString(", ") ?: ruleset.port ?: ruleset.protocol?.joinToString(", ")

        return listOfNotNull(
            res.getString(R.string.routing_rule_locked).takeIf { ruleset.locked == true },
            matcher,
            ruleset.outboundTag,
        ).joinToString(" · ")
    }

    inner class RuleViewHolder(val binding: ViewRowLineBinding) :
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
}
