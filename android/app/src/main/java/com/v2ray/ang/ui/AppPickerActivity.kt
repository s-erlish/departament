package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAppPickerBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.SkeletonBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * A-19 - the app picker a routing rule opens for its «Приложения» field.
 *
 * It is the same list as A-18 and now says so: the same grouped `Row.Toggle` list through
 * [PerAppProxyAdapter], the same on-screen search, the same skeleton and the same empty states. What
 * differs is who owns the selection (an in-memory set returned as an activity result, not the
 * per-app preference) and the unnamed-traffic pseudo-entry pinned at the top, which is a real
 * routing target and not an app.
 *
 * The screen used to be a bare `RecyclerView` with its search and its two bulk actions in an
 * ActionBar menu; A-38 takes the ActionBar away, so the search is a field and the bulk actions are
 * rows in the shared sheet.
 */
class AppPickerActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SELECTED_PACKAGES = "selected_packages"
        private const val EXTRA_PICKER_TITLE = "picker_title"

        fun createIntent(
            context: Context,
            selectedPackages: Collection<String> = emptyList(),
            title: String? = null
        ): Intent = Intent(context, AppPickerActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_SELECTED_PACKAGES, ArrayList(selectedPackages))
            title?.let { putExtra(EXTRA_PICKER_TITLE, it) }
        }

        fun getSelectedPackages(intent: Intent?): List<String> {
            return intent?.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES).orEmpty()
        }
    }

    private val binding by lazy { ActivityAppPickerBinding.inflate(layoutInflater) }
    private val initialSelectedPackages by lazy {
        intent.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES).orEmpty()
    }
    private val selectedPackages = LinkedHashSet<String>()
    private val adapter by lazy {
        PerAppProxyAdapter(
            scope = lifecycleScope,
            isSelected = selectedPackages::contains,
            onToggle = { packageName ->
                if (!selectedPackages.remove(packageName)) selectedPackages.add(packageName)
                updateMeta()
            },
        )
    }

    private var appsAll: List<AppInfo> = emptyList()
    private var query: String = ""
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        selectedPackages.addAll(initialSelectedPackages)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = resolveScreenTitle(),
            activity = this,
            actionIcon = R.drawable.ic_more_vert_24dp,
            actionDescription = getString(R.string.editor_more_actions),
            onAction = { showListActions() },
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.recyclerView)

        // Without this the RecyclerView never lays out - it logs «No layout manager attached;
        // skipping layout» and draws an empty screen, which is what this list did after the
        // rebuild. The layout declares no app:layoutManager, so it has to be set here.
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.search.searchInput.setHint(R.string.perapp_search_hint)
        binding.search.searchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter()
        }

        loadApps()
    }

    /**
     * The picker has no «Готово» button on purpose: leaving the screen IS confirming, and the
     * result is written here so the toolbar back arrow, the system Back gesture and the predictive
     * Back animation all commit the same selection.
     */
    override fun finish() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putStringArrayListExtra(EXTRA_SELECTED_PACKAGES, ArrayList(selectedPackages.sorted()))
            }
        )
        super.finish()
    }

    /**
     * The «неопознанные приложения» pseudo-entry: traffic from a uid no installed package claims.
     *
     * It no longer carries a drawable of its own — `AppIconLoader` answers this package with the
     * platform help glyph, on the same path every other row uses.
     */
    private fun createSpecialItemUnidentified(): AppInfo = AppInfo(
        appName = getString(R.string.perapp_unknown_app),
        packageName = AppConfig.UNIDENTIFIED_PACKAGE,
        isSystemApp = false,
        isSelected = 0
    )

    private fun loadApps() {
        loadFailed = false
        EmptyStateBinder.hide(binding.emptyState.root)
        binding.recyclerView.isVisible = false
        binding.tvMeta.isVisible = false
        SkeletonBinder.showAfterDelay(binding.skeleton)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val collator = Collator.getInstance()
                    val sorted = AppManagerUtil.loadNetworkAppList(this@AppPickerActivity)
                        .sortedWith(compareBy(collator) { it.appName })
                    listOf(createSpecialItemUnidentified()) + sorted
                }
            }
            result.onSuccess { appsAll = it }.onFailure {
                appsAll = emptyList()
                loadFailed = true
                LogUtil.e(AppConfig.TAG, "Failed to load app list", it)
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val key = query.trim().uppercase()
        val visible = if (key.isEmpty()) {
            appsAll
        } else {
            appsAll.filter {
                it.appName.uppercase().contains(key) || it.packageName.uppercase().contains(key)
            }
        }
        adapter.submit(
            apps = visible,
            // The pseudo-entry is not «ваше приложение», so the first group stays unheaded here and
            // only the system block earns a header.
            userLabel = null,
            systemLabel = getString(R.string.perapp_section_system),
        )
        updateMeta()
        showContentState(visible.isEmpty())
    }

    private fun updateMeta() {
        binding.tvMeta.text = getString(R.string.perapp_picker_selected, selectedPackages.size)
    }

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

            isEmpty -> {
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

            else -> {
                EmptyStateBinder.hide(binding.emptyState.root)
                binding.recyclerView.isVisible = true
            }
        }
    }

    private fun showListActions() {
        val visible = adapter.apps.map { it.packageName }
        val allSelected = visible.isNotEmpty() && visible.all { selectedPackages.contains(it) }

        EditorActionsSheet(this, getString(R.string.editor_actions_title))
            .action(
                labelRes = if (allSelected) R.string.perapp_action_clear_all else R.string.perapp_action_select_all,
                enabled = visible.isNotEmpty(),
            ) {
                if (allSelected) selectedPackages.removeAll(visible.toSet()) else selectedPackages.addAll(visible)
                adapter.refreshSelection()
                updateMeta()
            }
            .action(R.string.perapp_action_invert, enabled = visible.isNotEmpty()) {
                visible.forEach {
                    if (!selectedPackages.remove(it)) selectedPackages.add(it)
                }
                adapter.refreshSelection()
                updateMeta()
            }
            .show()
    }

    override fun onDestroy() {
        SkeletonBinder.cancel(binding.skeleton)
        super.onDestroy()
    }

    private fun resolveScreenTitle(): String {
        return intent.getStringExtra(EXTRA_PICKER_TITLE) ?: getString(R.string.perapp_picker_title)
    }
}
