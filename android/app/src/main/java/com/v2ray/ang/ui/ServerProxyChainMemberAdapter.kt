package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ViewRowBinding
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.ui.component.RowBinder
import java.util.Collections

/**
 * One step of a proxy chain per row.
 *
 * The old row was an `AutoCompleteTextView` with an `ImageButton` glued over its end, a numbered
 * badge, and a third target to remove the step - three controls in one row, where §4.5 allows one.
 * The row is a `Row.Navigation` now: it says which step it is and which server it points at, and
 * tapping it opens the one sheet that both picks the server and removes the step.
 *
 * An unfilled step reads «Выберите сервер» rather than an empty box, so a half-built chain is
 * legible at a glance - which matters, because saving one is refused until every step is filled.
 */
class ServerProxyChainMemberAdapter(
    private val members: MutableList<String>,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<ServerProxyChainMemberAdapter.MemberViewHolder>(), ItemTouchHelperAdapter {

    /** Opens the step's sheet. Set by the activity, which owns the server list and the dialogs. */
    var onStepClick: ((position: Int) -> Unit)? = null

    override fun getItemCount(): Int = members.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder =
        MemberViewHolder(ViewRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        val context = holder.itemView.context
        val remark = members[position].trim()

        RowBinder.bind(
            root = holder.binding.root,
            title = remark.ifEmpty { context.getString(R.string.srv_chain_pick) },
            subtitle = context.getString(R.string.srv_chain_step, position + 1),
            glyph = R.drawable.ic_globe_24dp,
            trailing = RowBinder.Trailing.Chevron,
            onClick = {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onStepClick?.invoke(index)
            },
        )
    }

    fun addRow() {
        members.add("")
        notifyItemInserted(members.lastIndex)
        adapterListener?.onRefreshData()
    }

    fun removeRow(position: Int) {
        if (position !in members.indices) return
        members.removeAt(position)
        notifyItemRemoved(position)
        // Every following step's number changed, so every following row has to be rebound.
        notifyItemRangeChanged(position, members.size - position)
        adapterListener?.onRefreshData()
    }

    fun setRemark(position: Int, remark: String) {
        if (position !in members.indices) return
        members[position] = remark
        notifyItemChanged(position)
        adapterListener?.onRefreshData()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun replaceAll(newMembers: List<String>) {
        members.clear()
        members.addAll(newMembers)
        notifyDataSetChanged()
        adapterListener?.onRefreshData()
    }

    fun getMembers(): List<String> = members.toList()

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition == toPosition) return true
        Collections.swap(members, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
        // Swipe-to-dismiss disabled for this adapter.
    }

    class MemberViewHolder(val binding: ViewRowBinding) :
        RecyclerView.ViewHolder(binding.root), ItemTouchHelperViewHolder {

        // The drag feedback used to be Color.LTGRAY painted onto the item view - a raw literal that
        // is invisible on light and wrong on mono. bg_row already draws the 12% accent fill.
        override fun onItemSelected() {
            itemView.isActivated = true
        }

        override fun onItemClear() {
            itemView.isActivated = false
        }
    }
}
