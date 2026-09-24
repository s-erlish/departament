package com.v2ray.ang.ui

import android.os.Bundle
import android.text.TextUtils
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.extension.v2RayApplication
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RussianAppsPreset
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SelectPopup
import com.v2ray.ang.ui.component.SkeletonBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.PerAppProxyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * A-18 `settings/perapp` - «Прокси по приложениям». H3 header, R1 rhythm.
 *
 * The two audit findings this screen was called out for:
 *
 * - **The boolean was a bare checkbox in a card grid.** Every app is now a `Row.Toggle` in one
 *   grouped list, so the whole row toggles and the switch matches every other boolean in the app.
 * - **There was no grouping and no search on screen.** Search lived in an ActionBar `SearchView`
 *   that A-38 deletes along with the ActionBar; it is now a real 56dp field that filters in place.
 *   The list is grouped «Ваши приложения» / «Системные приложения» instead of prefixing system apps
 *   with `** ` in Kotlin.
 *
 * And the states the screen simply did not have: a skeleton while the package manager resolves the
 * list (slow on a cold start, and the screen used to be blank for all of it), «Ничего не найдено»
 * for a search that matched nothing, and a load failure that offers «Повторить» instead of logging
 * and showing an empty screen.
 *
 * **The mode is one row, not two switches.** `PREF_PER_APP_PROXY` and `PREF_BYPASS_APPS` are two
 * booleans encoding three states, and the old screen showed them as two independent switches - one
 * of which was meaningless while the other was off, with the explanation hidden behind a second
 * tappable target that fired a toast. They are one `Row.Value` here, opening §6's select popup on
 * all three modes. Both preferences keep their existing meaning and their existing keys.
 *
 * **NEITHER ROW ON THIS SCREEN CARRIES A SUBTITLE** (owner report 0.4.9: «там где режим, надо под
 * ним текст убрать… и у российских приложений тоже текст убери ниже»). Both explanations ran past
 * the row's two lines and arrived as «Отмеченные — напрямую, остальные…» and «Госуслуги, банки, Mir
 * Pay, Ozon, Wildberries, Яндекс, операторы — и…»: a subtitle clipped mid-sentence promises an
 * explanation and then withholds it, which is strictly worse than the row that never offered one.
 * The value «Кроме выбранных» is the mode's own name and already says it, and the набор's contents
 * are one tap away behind «Показать приложения набора» in the actions sheet. Both rows are §6's
 * 61dp «без подписи» height now instead of 77.
 */
class PerAppProxyActivity : BaseActivity() {

    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }

    private val viewModel: PerAppProxyViewModel by viewModels()
    private val adapter by lazy {
        PerAppProxyAdapter(
            scope = lifecycleScope,
            isSelected = viewModel::contains,
            onToggle = { packageName ->
                viewModel.toggle(packageName)
                updateMeta()
            },
        )
    }

    private var appsAll: List<AppInfo> = emptyList()
    private var query: String = ""
    private var loadFailed = false

    /**
     * «Показать приложения набора»: the list below is restricted to the preset's packages.
     *
     * This is how the preset's CONTENTS are made visible, and it is deliberately not a second
     * screen. The user sees the same rows they already understand — real icon, real name, working
     * switch — and can still overrule any single one of them while the filter is up. A preset the
     * user cannot see inside is indistinguishable from a hard-coded list, which is exactly what the
     * owner asked this not to be.
     */
    private var presetFilter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.pa_title),
            activity = this,
            actionIcon = R.drawable.ic_more_vert_24dp,
            actionDescription = getString(R.string.editor_more_actions),
            onAction = { showListActions() },
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.recyclerView)

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.search.searchInput.setHint(R.string.perapp_search_hint)
        binding.search.searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            applyFilter()
        }

        bindModeRow()
        bindPresetRow()
        loadApps()
    }

    // ---------------------------------------------------------------- mode

    /**
     * The three modes, in the order the popup offers them - widest first, then the two narrowings.
     * The pair of booleans behind them is unchanged; this is a presentation of the existing
     * preferences, not a new setting, and [Mode.ordinal] is the index [SelectPopup] marks and
     * returns.
     *
     * [hintRes] is no longer shown: the row lost its subtitle in 0.4.9 (see the class doc). It is
     * kept rather than deleted because it is the only thing still holding `perapp_mode_*_hint`
     * together with the mode it belongs to — the strings survive the removal on purpose, and a
     * hint that has drifted away from its mode is worse than no hint at all when one is wanted
     * again (the sheet in [showListActions] is where it would go).
     */
    private enum class Mode(val labelRes: Int, val hintRes: Int) {
        ALL(R.string.perapp_mode_all, R.string.perapp_mode_all_hint),
        SELECTED(R.string.perapp_mode_selected, R.string.perapp_mode_selected_hint),
        EXCEPT(R.string.perapp_mode_except, R.string.perapp_mode_except_hint),
    }

    private fun currentMode(): Mode = when {
        !MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false) -> Mode.ALL
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false) -> Mode.EXCEPT
        else -> Mode.SELECTED
    }

    private fun applyMode(mode: Mode) {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, mode != Mode.ALL)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, mode == Mode.EXCEPT)
        SettingsChangeManager.makeRestartService()
        bindModeRow()
    }

    /**
     * «Режим» — handoff README §6, «Выбор из списка — окошко у значения».
     *
     * It used to CYCLE, and that is what the owner objected to: «у режима надо тоже сделать такую
     * же всплывающую менюшку, как у днс пинга и у плюсика, чтобы можно было выбрать, а то кликом
     * это странно». A tap advanced the value one step, so the two modes the user was not on cost
     * one and two taps, the list of what was even available was never shown, and the row's promise
     * («this changes here») was true while its behaviour («you may choose») was not. Every other
     * value picker in the product - DNS, Пинг, Доменная стратегия, and the «+» on Главная - opens
     * [SelectPopup]; this one now does too, with the current mode marked.
     *
     * `select_popup_w_default` and not a bespoke width: §6's width table measures the nine pickers
     * on Настройки, and this «Режим» is not one of them - `select_popup_w_mode` was sized to «Только
     * прокси», which is three characters shorter than «Только выбранные» (TOKENS.md).
     */
    private fun bindModeRow() {
        val mode = currentMode()
        RowBinder.bind(
            root = binding.rowMode.root,
            title = getString(R.string.perapp_mode_row),
            // NO SUBTITLE (0.4.9). «Отмеченные — напрямую, остальные…» never fitted, and the value
            // on the right of this same row is the mode's name, which is the short form of it.
            // The prototype's «Режим обхода» carries the globe and the row under it a different
            // glyph: §5.3 makes a group tiled only when its glyphs DIFFER, and two identical
            // tiles down the left edge is the generated-settings tell.
            glyph = R.drawable.ic_globe_24dp,
            value = getString(mode.labelRes),
            // The CARET, and now it means what it draws: a caret is «the list of values opens
            // here», where a chevron would promise a screen. It also turns 180° while the popup
            // is up, which is the half of §6's interaction the row itself owns.
            trailing = RowBinder.Trailing.Glyph(
                icon = R.drawable.ic_arrow_drop_down,
                contentDescription = null,
            ),
            onClick = {
                SelectPopup.show(
                    anchor = binding.rowMode.root,
                    options = Mode.entries.map { getString(it.labelRes) },
                    selectedIndex = mode.ordinal,
                    valueView = binding.rowMode.rowValue,
                    caret = binding.rowMode.rowTrailingGlyph,
                ) { picked -> applyMode(Mode.entries[picked]) }
            },
        )
        // The card cannot clip (§11 grabl 4 - the popup would be sliced off at its bottom edge), so
        // the two rows inside it carry the card's corners themselves.
        RowBinder.edge(binding.rowMode.root, RowBinder.Edge.TOP)
    }

    // -------------------------------------------------------------- preset

    /**
     * The «Российские приложения» row: one switch, applying and un-applying a NAMED set.
     *
     * NO SUBTITLE (0.4.9). «Госуслуги, банки, Mir Pay, Ozon, Wildberries, Яндекс, операторы — идут
     * напрямую, мимо VPN» is a list, and a list clipped at «— и…» after two lines names four of its
     * members and hides the rest, which reads as an incomplete promise rather than a summary. The
     * набор's real contents stay one tap away, in full and with real icons, behind «Показать
     * приложения набора». The accessible name below still says how many of its apps this phone
     * actually has — a preset that lists forty packages and matches three should say so rather than
     * implying it is doing forty apps' worth of work.
     */
    private fun bindPresetRow() {
        val installed = appsAll.count { RussianAppsPreset.contains(it.packageName) }
        RowBinder.bind(
            root = binding.rowRuPreset.root,
            title = getString(R.string.perapp_ru_preset),
            glyph = R.drawable.ic_per_apps_24dp,
            trailing = RowBinder.Trailing.Toggle(
                checked = RussianAppsPreset.isApplied(),
                onCheckedChange = { on -> togglePreset(on) },
            ),
        )
        binding.rowRuPreset.root.contentDescription = getString(
            R.string.perapp_ru_preset_installed,
            installed,
        )
        // Second half of the un-clipped card above: this row is its bottom edge.
        RowBinder.edge(binding.rowRuPreset.root, RowBinder.Edge.BOTTOM)
    }

    /**
     * Applies or un-applies the preset, and NEVER restarts the tunnel to do it.
     *
     * Applying adds only what is missing; un-applying gives back only what applying took, so an app
     * the user ticked by hand before ever touching the switch survives it (see
     * [RussianAppsPreset.applyTo] / [RussianAppsPreset.removeFrom]).
     *
     * The mode is moved to «Кроме выбранных» when it is «Все приложения», because the selection has
     * no effect at all there and a switch that visibly does nothing is worse than no switch. That
     * one step DOES mark a restart, exactly as the mode row itself does — it is a change of what the
     * tunnel carries, not a preset detail — so the flip is offered only when the user actually asks
     * for the preset, never on the silent first-run seeding.
     */
    private fun togglePreset(on: Boolean) {
        val current = viewModel.getAll()
        if (on) {
            viewModel.applyPresetQuietly(added = RussianAppsPreset.applyTo(current), removed = emptySet())
            if (currentMode() == Mode.ALL) applyMode(Mode.EXCEPT)
            toastSuccess(R.string.perapp_ru_preset_pending)
        } else {
            viewModel.applyPresetQuietly(added = emptySet(), removed = RussianAppsPreset.removeFrom())
        }
        bindPresetRow()
        adapter.refreshSelection()
        updateMeta()
    }

    // ---------------------------------------------------------------- list

    private fun loadApps() {
        loadFailed = false
        showLoadingState()

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching {
                    val collator = Collator.getInstance()
                    AppManagerUtil.loadNetworkAppList(this@PerAppProxyActivity)
                        .sortedWith(compareBy(collator) { it.appName })
                }
            }
            apps.onSuccess {
                appsAll = it
                loadFailed = false
            }.onFailure {
                appsAll = emptyList()
                loadFailed = true
                LogUtil.e(ANG_PACKAGE, "Error loading apps", it)
            }
            bindPresetRow()
            applyFilter()
        }
    }

    private fun applyFilter() {
        val key = query.trim().uppercase()
        // The набор filter and the search compose: showing the набор and then typing narrows within
        // it, rather than one silently cancelling the other.
        val pool = if (presetFilter) {
            appsAll.filter { RussianAppsPreset.contains(it.packageName) }
        } else {
            appsAll
        }
        val visible = if (key.isEmpty()) {
            pool
        } else {
            pool.filter {
                it.appName.uppercase().contains(key) || it.packageName.uppercase().contains(key)
            }
        }
        adapter.submit(
            apps = visible,
            userLabel = getString(R.string.perapp_section_user),
            systemLabel = getString(R.string.perapp_section_system),
        )
        updateMeta()
        showContentState(visible.isEmpty())
    }

    private fun updateMeta() {
        binding.tvMeta.text = getString(
            R.string.perapp_selected_count,
            viewModel.getAll().size,
            appsAll.size,
        )
    }

    // -------------------------------------------------------------- states

    private fun showLoadingState() {
        EmptyStateBinder.hide(binding.emptyState.root)
        binding.recyclerView.isVisible = false
        binding.tvMeta.isVisible = false
        SkeletonBinder.showAfterDelay(binding.skeleton)
    }

    /** Exactly one of list / empty / error is on screen once the load settles (00-rules.md 15). */
    private fun showContentState(isEmpty: Boolean) {
        SkeletonBinder.cancel(binding.skeleton)
        binding.tvMeta.isVisible = !loadFailed
        binding.search.root.isVisible = !loadFailed

        when {
            loadFailed -> {
                binding.recyclerView.isVisible = false
                EmptyStateBinder.bind(
                    root = binding.emptyState.root,
                    glyph = R.drawable.ic_dl_info,
                    title = getString(R.string.perapp_error_title),
                    line = getString(R.string.perapp_error_line),
                    actionLabel = getString(R.string.editor_retry),
                    emphasis = EmptyStateBinder.Emphasis.TERTIARY,
                    onAction = { loadApps() },
                )
            }

            isEmpty && query.isNotBlank() -> {
                binding.recyclerView.isVisible = false
                EmptyStateBinder.bind(
                    root = binding.emptyState.root,
                    glyph = R.drawable.ic_per_apps_24dp,
                    title = getString(R.string.editor_search_empty_title),
                    line = getString(R.string.editor_search_empty_line),
                    actionLabel = getString(R.string.editor_search_reset),
                    onAction = { binding.search.searchInput.setText("") },
                )
            }

            isEmpty && presetFilter -> {
                binding.recyclerView.isVisible = false
                EmptyStateBinder.bind(
                    root = binding.emptyState.root,
                    glyph = R.drawable.ic_per_apps_24dp,
                    title = getString(R.string.perapp_ru_preset_empty_title),
                    line = getString(R.string.perapp_ru_preset_empty_line),
                    actionLabel = getString(R.string.perapp_ru_preset_show_off),
                    emphasis = EmptyStateBinder.Emphasis.TERTIARY,
                    onAction = {
                        presetFilter = false
                        applyFilter()
                    },
                )
            }

            isEmpty -> {
                binding.recyclerView.isVisible = false
                EmptyStateBinder.bind(
                    root = binding.emptyState.root,
                    glyph = R.drawable.ic_per_apps_24dp,
                    title = getString(R.string.perapp_empty_title),
                    line = getString(R.string.perapp_empty_line),
                )
            }

            else -> {
                EmptyStateBinder.hide(binding.emptyState.root)
                binding.recyclerView.isVisible = true
            }
        }
    }

    // ------------------------------------------------------------- actions

    /**
     * The six list actions. They used to live in a toolbar overflow menu; the seamless sub-page
     * header of §4.8 keeps at most one trailing action, so they live in the one sheet the editors
     * share. Nothing was dropped: select-all now states which way it will go, and its inverse
     * («Снять отметки») is a separate row rather than a second meaning of the same one.
     */
    private fun showListActions() {
        val visible = adapter.apps.map { it.packageName }
        val allSelected = visible.isNotEmpty() && visible.all { viewModel.contains(it) }

        EditorActionsSheet(this, getString(R.string.editor_actions_title))
            .action(
                labelRes = if (allSelected) R.string.perapp_action_clear_all else R.string.perapp_action_select_all,
                enabled = visible.isNotEmpty(),
            ) {
                if (allSelected) viewModel.removeAll(visible) else viewModel.addAll(visible)
                afterBulkChange()
            }
            .action(R.string.perapp_action_invert, enabled = visible.isNotEmpty()) {
                visible.forEach { viewModel.toggle(it) }
                afterBulkChange()
            }
            .action(
                labelRes = if (presetFilter) {
                    R.string.perapp_ru_preset_show_off
                } else {
                    R.string.perapp_ru_preset_show
                },
            ) {
                presetFilter = !presetFilter
                applyFilter()
            }
            .action(
                labelRes = R.string.perapp_action_auto,
                subtitle = getString(R.string.perapp_action_auto_hint),
            ) { selectProxyAppAuto() }
            .action(R.string.perapp_action_import) { importProxyApp() }
            .action(R.string.perapp_action_export) { exportProxyApp() }
            .show()
    }

    /**
     * A bulk change implies the feature is wanted: the old screen called `allowPerAppProxy()` after
     * every one of these, and that behaviour is kept - but only when the mode is «Все приложения»,
     * where the list would otherwise have no effect at all.
     */
    private fun afterBulkChange() {
        if (currentMode() == Mode.ALL) {
            applyMode(Mode.SELECTED)
        }
        adapter.refreshSelection()
        updateMeta()
    }

    private fun selectProxyAppAuto() {
        showLoadingState()
        binding.recyclerView.isVisible = false

        val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 5000))
                    ?.takeIf { it.isNotEmpty() }
                    ?: HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = url,
                            timeout = 5000,
                            httpPort = SettingsManager.getHttpPort(),
                            proxyUsername = SettingsManager.getSocksUsername(),
                            proxyPassword = SettingsManager.getSocksPassword(),
                        )
                    ).orEmpty()
            }
            selectProxyApp(content, force = true)
            afterBulkChange()
            applyFilter()
            toastSuccess(R.string.editor_done)
        }
    }

    private fun importProxyApp() {
        val content = Utils.getClipboard(applicationContext)
        if (TextUtils.isEmpty(content)) return
        selectProxyApp(content, force = false)
        afterBulkChange()
        toastSuccess(R.string.editor_done)
    }

    private fun exportProxyApp() {
        val header = (currentMode() == Mode.EXCEPT).toString()
        val payload = viewModel.getAll().fold(header) { acc, pkg -> acc + System.lineSeparator() + pkg }
        Utils.setClipboard(applicationContext, payload)
        toastSuccess(R.string.editor_done)
    }

    private fun selectProxyApp(content: String, force: Boolean): Boolean {
        try {
            val proxyApps = content.ifEmpty {
                Utils.readTextFromAssets(v2RayApplication, "proxy_package_name")
            }
            if (proxyApps.isEmpty()) return false

            viewModel.clear()
            val bypassing = currentMode() == Mode.EXCEPT
            appsAll.forEach { app ->
                val listed = inProxyApps(proxyApps, app.packageName, force)
                if (listed != bypassing) {
                    viewModel.add(app.packageName)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun inProxyApps(proxyApps: String, packageName: String, force: Boolean): Boolean {
        if (force) {
            if (packageName == WEBVIEW_PACKAGE) return false
            if (packageName.startsWith(GOOGLE_PACKAGE_PREFIX)) return true
        }
        return proxyApps.contains(packageName)
    }

    override fun onDestroy() {
        SkeletonBinder.cancel(binding.skeleton)
        super.onDestroy()
    }

    private companion object {
        const val WEBVIEW_PACKAGE = "com.google.android.webview"
        const val GOOGLE_PACKAGE_PREFIX = "com.google"
    }
}
