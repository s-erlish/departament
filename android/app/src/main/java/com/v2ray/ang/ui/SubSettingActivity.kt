package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivitySubSettingBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-17 - the subscription list. H3 header, R3 rhythm.
 *
 * Both of its actions were toolbar menu items on the ActionBar A-38 removes: «Добавить» is the
 * header's one trailing action and «Обновить все» is a visible row that reports its own progress.
 * The screen had no empty state at all; it now says what a subscription is for.
 *
 * The per-group overflow replaces the row's four competing targets. «Поделиться» used to open an
 * untranslated `setItems` list («QRcode» / «Export to clipboard»); the two options are named rows in
 * the shared sheet now, in Russian.
 *
 * **A2, «удалять почему-то я тоже не могу подписки на телефоне».** Deletion here was never broken -
 * it was unreachable. This activity is declared in the manifest and was launched from nowhere: the
 * settings tab had rows for auto-update, routing, assets and the provider settings, and no row for
 * the list itself. The entry point is `settings_subs_list` on the Настройки tab
 * (`SettingsTabFragment`), and it is what makes «Удалить подписку» tappable at all.
 *
 * Two things about the delete itself, both visible to the user:
 *
 *  - it **names what it will destroy** - the confirm carries the подписка's own name in the title -
 *    and it always asks. The old branch asked only when `PREF_CONFIRM_REMOVE` was set, and nothing
 *    in this app ever writes that key, so the branch was dead and the delete happened on the tap;
 *  - it **says it happened**. `SettingsManager.removeSubscriptionWithDefault` recreates an empty
 *    «Default» container when the last подписка goes, so the list is never empty; without a message
 *    a user who deletes his only подписка sees a row where his подписка was and concludes nothing
 *    was deleted. [SubSettingRecyclerAdapter] makes that container read as what it is.
 */
class SubSettingActivity : BaseActivity() {

    private val binding by lazy { ActivitySubSettingBinding.inflate(layoutInflater) }
    private val viewModel: SubscriptionsViewModel by viewModels()
    private lateinit var adapter: SubSettingRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private var updatingAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.subs_title),
            activity = this,
            actionIcon = R.drawable.ic_add_24dp,
            actionDescription = getString(R.string.subs_add),
            onAction = { openEditor(null) },
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        adapter = SubSettingRecyclerAdapter(viewModel, ActivityAdapterListener())
        adapter.onOverflow = ::showGroupActions
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter)).also {
            it.attachToRecyclerView(binding.recyclerView)
        }

        bindUpdateAllRow()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun openEditor(subId: String?) {
        SubPage.open(
            this,
            Intent(this, SubEditActivity::class.java).apply {
                subId?.let { putExtra("subId", it) }
            },
        )
    }

    private fun bindUpdateAllRow() {
        RowBinder.bind(
            root = binding.rowUpdateAll.root,
            title = getString(
                if (updatingAll) R.string.subs_updating else R.string.subs_update_all
            ),
            glyph = R.drawable.ic_refresh_24dp,
            trailing = RowBinder.Trailing.None,
            enabled = !updatingAll,
            onClick = if (updatingAll) null else ({ updateAll() }),
        )
    }

    private fun updateAll() {
        updatingAll = true
        bindUpdateAllRow()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = AngConfigManager.updateConfigViaSubAll()
                delay(500L)
                r
            }
            updatingAll = false
            bindUpdateAllRow()

            // «ОБНОВИТЬ ВСЕ» РАНЬШЕ МОЛЧАЛО ОБО ВСЁМ, КРОМЕ «нет подписок».
            //
            // Две ветки из трёх звали `toast(getString(...))` — то есть CharSequence-перегрузку,
            // которая по правилу 4 [NoticePolicy] не показывает НИЧЕГО (у построенной строки нет
            // идентификатора, который политика могла бы проверить), а вторая к тому же была
            // счётчиком и отсеялась бы и по тексту. Обновление без сети выглядело ровно как
            // удавшееся: строка «Обновляем…» возвращалась в «Обновить все» и на этом всё.
            //
            // Правило 1 оставляет успех молчаливым — отметки «обновлено» в строках перерисовались,
            // это и есть подтверждение. Правило 2 требует, чтобы у неудачи было одно предложение с
            // причиной и следующим шагом, и вот оно.
            when {
                result.successCount + result.failureCount + result.skipCount == 0 ->
                    toast(R.string.subs_update_none)

                result.failureCount > 0 && result.successCount == 0 ->
                    toastError(R.string.subs_update_failed)

                result.failureCount > 0 ->
                    toastError(R.string.subs_update_partial)

                result.successCount == 0 ->
                    toast(R.string.subs_update_skipped)
            }
            refreshData()
        }
    }

    // ------------------------------------------------------- group actions

    private fun showGroupActions(subId: String, position: Int) {
        val entry = viewModel.getAll().getOrNull(position) ?: return
        val subItem = entry.subscription
        val hasUrl = subItem.url.isNotBlank()
        // One name for this подписка across the row, this sheet and the delete confirmation.
        val name = SubSettingRecyclerAdapter.displayName(this, subItem)

        EditorActionsSheet(this, name)
            .action(
                labelRes = if (subItem.enabled) R.string.subs_action_disable else R.string.subs_action_enable,
                glyph = R.drawable.ic_power_settings,
                enabled = hasUrl,
            ) {
                subItem.enabled = !subItem.enabled
                viewModel.update(subId, subItem)
                refreshData()
            }
            .action(R.string.subs_action_edit, R.drawable.ic_edit_24dp) { openEditor(subId) }
            .action(R.string.subs_action_qrcode, R.drawable.ic_scan_24dp, enabled = hasUrl) {
                showQrCode(subItem.url)
            }
            .action(R.string.subs_action_copy, R.drawable.ic_copy, enabled = hasUrl) {
                Utils.setClipboard(this, subItem.url)
                toastSuccess(R.string.editor_copied)
            }
            .destructive(R.string.subs_action_delete) {
                confirmRemove(subId, name)
            }
            .show()
    }

    private fun showQrCode(url: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(this))
        ivBinding.ivQcode.setImageBitmap(QRCodeDecoder.createQRCode(url))
        AlertDialog.Builder(this)
            .setTitle(R.string.subs_action_qrcode)
            .setView(ivBinding.root)
            .setPositiveButton(R.string.editor_close) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Deleting a подписка, always confirmed, always naming the подписка in the title so the user
     * knows which one is going. Cancelling changes nothing and reports nothing.
     */
    private fun confirmRemove(subId: String, name: String) {
        val title = name.trim().ifEmpty { getString(R.string.subs_title) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(R.string.subs_delete_confirm)
            .setPositiveButton(R.string.editor_delete) { _, _ -> remove(subId) }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    /**
     * Removes the подписка and says so. The message matters: the store recreates an empty local
     * container when the last подписка is deleted, so «the list still has a row» is not evidence
     * that the delete failed - and this is exactly what the owner read as «удалять не могу».
     */
    private fun remove(subId: String) {
        val removed = viewModel.remove(subId)
        refreshData()
        // Same wording the Главная card uses for the same act - one register, two surfaces.
        // `removed == false` means the guid was already gone (a refresh landed between the tap and
        // the confirm); the list has just been repainted without it, so there is nothing to report.
        if (removed) toastSuccess(R.string.home_sub_deleted)
    }

    // -------------------------------------------------------------- states

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()

        val isEmpty = viewModel.getAll().isEmpty()
        binding.recyclerView.isVisible = !isEmpty
        binding.labelSubs.isVisible = !isEmpty
        if (isEmpty) {
            EmptyStateBinder.bind(
                root = binding.emptyState.root,
                glyph = R.drawable.ic_subscriptions_24dp,
                title = getString(R.string.subs_empty_title),
                line = getString(R.string.subs_empty_line),
                actionLabel = getString(R.string.subs_add),
                emphasis = EmptyStateBinder.Emphasis.PRIMARY,
                onAction = { openEditor(null) },
            )
        } else {
            EmptyStateBinder.hide(binding.emptyState.root)
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) = openEditor(guid)

        override fun onRemove(guid: String, position: Int) {
            val item = viewModel.getAll().firstOrNull { it.guid == guid }?.subscription ?: return
            confirmRemove(guid, SubSettingRecyclerAdapter.displayName(this@SubSettingActivity, item))
        }


        override fun onRefreshData() {
            refreshData()
        }
    }
}
