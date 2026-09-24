package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityRoutingSettingBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SelectPopup
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-20 `settings/routing` - «Маршрутизация». H3 header, R1 rhythm.
 *
 * The functional change is that **five actions came out of hiding**. «Добавить правило», «Готовые
 * наборы», «Импорт из буфера», «Импорт из QR-кода» and «Экспорт» lived in an `onCreateOptionsMenu`
 * overflow - an affordance no other screen in this app has, on an ActionBar that A-38 removes. Four
 * of them are now rows in a named group and the fifth is the header's one trailing action. Not one
 * of them was dropped.
 *
 * «Стратегия доменов» stops being an `android.app.AlertDialog.setItems` picker (§7.4 bans the
 * single-choice dialog outright) and becomes the cycle-in-place affordance: three values, an unfold
 * glyph, and the current one printed in the row.
 */
class RoutingSettingActivity : HelperBaseActivity() {

    private val binding by lazy { ActivityRoutingSettingBinding.inflate(layoutInflater) }
    private val viewModel: RoutingSettingsViewModel by viewModels()
    private lateinit var adapter: RoutingSettingRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    private val domainStrategies: Array<out String> by lazy {
        resources.getStringArray(R.array.routing_domain_strategy)
    }
    private val presetRulesets: Array<out String> by lazy {
        resources.getStringArray(R.array.preset_rulesets)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // NO TOOLBAR ACTION. §7's lekalo is «кнопка назад → заголовок → группы карточек» and
        // carries none; the prototype puts «Добавить правило» in a card under the rules, where
        // it has a name instead of being a «+» the user has to interpret. Same function, and
        // one fewer target in the header.
        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.routing_title),
            activity = this,
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.mainContent)

        adapter = RoutingSettingRecyclerAdapter(viewModel, ActivityAdapterListener())
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter)).also {
            it.attachToRecyclerView(binding.recyclerView)
        }

        bindDomainStrategyRow()
        bindActionRows()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    // ------------------------------------------------------------ strategy

    private fun currentDomainStrategy(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
            ?: domainStrategies.first()

    /**
     * «Доменная стратегия» — handoff README §6, «Выбор из списка — окошко у значения».
     *
     * It used to CYCLE: one tap advanced AsIs -> IPIfNonMatch -> IPOnDemand and the user had to
     * tap three times to see what the third option even was. §6 names this row among the nine that
     * open the select popup instead, so the whole list is visible where the value already is, and
     * a value two steps away costs one tap rather than two.
     *
     * `select_popup_w_default` and not a bespoke width: §6's width table stops at the six rows it
     * measured, and this is one of the three it does not name (TOKENS.md).
     */
    private fun bindDomainStrategyRow() {
        val current = currentDomainStrategy()
        RowBinder.bind(
            root = binding.rowDomainStrategy.root,
            title = getString(R.string.routing_domain_strategy),
            glyph = R.drawable.ic_globe_24dp,
            value = current,
            // The caret, not a chevron: a chevron promises a screen and this row opens none.
            trailing = RowBinder.Trailing.Glyph(
                icon = R.drawable.ic_arrow_drop_down,
                contentDescription = getString(R.string.routing_strategy_cd),
            ),
            onClick = {
                SelectPopup.show(
                    anchor = binding.rowDomainStrategy.root,
                    options = domainStrategies.toList(),
                    selectedIndex = domainStrategies.indexOf(current).coerceAtLeast(0),
                    valueView = binding.rowDomainStrategy.rowValue,
                    caret = binding.rowDomainStrategy.rowTrailingGlyph,
                ) { picked ->
                    MmkvManager.encodeSettings(
                        AppConfig.PREF_ROUTING_DOMAIN_STRATEGY,
                        domainStrategies[picked],
                    )
                    bindDomainStrategyRow()
                }
            },
        )
        // Its card cannot clip (the popup would be sliced off at the bottom edge), so the row
        // carries the card's corner itself. One row in the card: all four corners.
        RowBinder.edge(binding.rowDomainStrategy.root, RowBinder.Edge.ONLY)
    }

    // ------------------------------------------------------------- actions

    private fun bindActionRows() {
        // §7's accent action row: the name IS the affordance, so no chevron and no button. And
        // no tile either - the prototype gives this one row no glyph at all, so its label starts
        // on the 16dp gutter, which is what makes it read as an action rather than a setting.
        RowBinder.bind(
            root = binding.rowAddRule.root,
            title = getString(R.string.routing_action_add),
            tone = RowBinder.RowTone.ACCENT,
            trailing = RowBinder.Trailing.None,
            onClick = { addRule() },
        )
        RowBinder.bind(
            root = binding.rowPresets.root,
            title = getString(R.string.routing_action_presets),
            glyph = R.drawable.ic_routing_24dp,
            trailing = RowBinder.Trailing.None,
            onClick = { importPredefined() },
        )
        RowBinder.bind(
            root = binding.rowImportClipboard.root,
            title = getString(R.string.routing_action_import_clipboard),
            glyph = R.drawable.ic_dl_copy,
            trailing = RowBinder.Trailing.None,
            onClick = { importFromClipboard() },
        )
        RowBinder.bind(
            root = binding.rowImportQrcode.root,
            title = getString(R.string.routing_action_import_qrcode),
            glyph = R.drawable.ic_scan_24dp,
            trailing = RowBinder.Trailing.None,
            onClick = { importQRcode() },
        )
        RowBinder.bind(
            root = binding.rowExport.root,
            title = getString(R.string.routing_action_export),
            glyph = R.drawable.ic_share_24dp,
            trailing = RowBinder.Trailing.None,
            onClick = { export2Clipboard() },
        )
    }

    private fun addRule() {
        SubPage.open(this, Intent(this, RoutingEditActivity::class.java))
    }

    /**
     * Every import REPLACES the rule list, so every import asks first. This is one of the few
     * confirmations §7.5 keeps: the change is not undoable and it silently rewrites work the user
     * did by hand. The buttons carry verbs, not «OK».
     */
    private fun confirmReplace(onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(R.string.routing_import_confirm)
            .setPositiveButton(R.string.editor_confirm_replace) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    private fun importPredefined() {
        EditorActionsSheet(this, getString(R.string.routing_action_presets)).apply {
            presetRulesets.forEachIndexed { index, name ->
                action(label = name, glyph = R.drawable.ic_routing_24dp) {
                    confirmReplace {
                        lifecycleScope.launch(Dispatchers.IO) {
                            SettingsManager.resetRoutingRulesetsFromPresets(
                                this@RoutingSettingActivity,
                                index,
                            )
                            withContext(Dispatchers.Main) {
                                refreshData()
                                toastSuccess(R.string.routing_import_done)
                            }
                        }
                    }
                }
            }
        }.show()
    }

    private fun importFromClipboard() = confirmReplace {
        val clipboard = runCatching { Utils.getClipboard(this) }.getOrElse {
            LogUtil.e(AppConfig.TAG, "Failed to get clipboard content", it)
            toastError(R.string.routing_import_failed)
            return@confirmReplace
        }
        applyRulesets(clipboard)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) confirmReplace { applyRulesets(scanResult) }
        }
    }

    private fun applyRulesets(payload: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = SettingsManager.resetRoutingRulesets(payload)
            withContext(Dispatchers.Main) {
                refreshData()
                if (ok) toastSuccess(R.string.routing_import_done) else toastError(R.string.routing_import_failed)
            }
        }
    }

    private fun export2Clipboard() {
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            toastError(R.string.routing_export_empty)
        } else {
            Utils.setClipboard(this, JsonUtil.toJson(rulesetList))
            toast(R.string.notice_copied)
        }
    }

    // -------------------------------------------------------------- states

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()

        val isEmpty = viewModel.getAll().isEmpty()
        binding.recyclerView.isVisible = !isEmpty
        binding.labelRules.isVisible = !isEmpty
        if (isEmpty) {
            EmptyStateBinder.bind(
                root = binding.emptyState.root,
                glyph = R.drawable.ic_routing_24dp,
                title = getString(R.string.routing_empty_title),
                line = getString(R.string.routing_empty_line),
                actionLabel = getString(R.string.routing_action_add),
                emphasis = EmptyStateBinder.Emphasis.PRIMARY,
                onAction = { addRule() },
            )
        } else {
            EmptyStateBinder.hide(binding.emptyState.root)
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            SubPage.open(
                this@RoutingSettingActivity,
                Intent(this@RoutingSettingActivity, RoutingEditActivity::class.java)
                    .putExtra("position", position),
            )
        }

        override fun onRemove(guid: String, position: Int) {}


        override fun onRefreshData() {
            refreshData()
        }
    }
}
