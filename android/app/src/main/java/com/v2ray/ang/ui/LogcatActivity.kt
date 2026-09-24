package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.RowBinder
import com.v2ray.ang.ui.component.SkeletonBinder
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.LogcatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A-35 `settings/about/log` - «Журнал». H3 header, R3 rhythm.
 *
 * The screen shipped with a toast on entry - «Please pull down to refresh!», in English, on a
 * Russian-only product - and an empty list behind it. It loads its own content now, shows a skeleton
 * while it does, and has the empty state it never had. Search, copy, share and clear all lived in an
 * ActionBar overflow that A-38 removes; search is a field on the screen and the three actions are
 * rows in the editors' shared sheet.
 */
class LogcatActivity : BaseActivity(), SwipeRefreshLayout.OnRefreshListener {

    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }
    private val viewModel: LogcatViewModel by viewModels()
    private lateinit var adapter: LogcatRecyclerAdapter
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.log_title),
            activity = this,
            actionIcon = R.drawable.ic_more_vert_24dp,
            actionDescription = getString(R.string.editor_more_actions),
            onAction = { showLogActions() },
        )
        ToolbarBinder.attachTo(binding.toolbar.root, binding.recyclerView)

        adapter = LogcatRecyclerAdapter(viewModel, ::onLogLongClick)
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.search.searchInput.setHint(R.string.log_search_hint)
        binding.search.searchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            viewModel.filter(query)
            refreshData()
        }

        binding.refreshLayout.setOnRefreshListener(this)
        bindActionRows()
        loadLogs()
    }

    private fun onLogLongClick(log: String): Boolean {
        Utils.setClipboard(this, log)
        toast(R.string.notice_copied)
        return true
    }

    private fun loadLogs() {
        SkeletonBinder.showAfterDelay(binding.skeleton)
        binding.recyclerView.isVisible = false
        EmptyStateBinder.hide(binding.emptyState.root)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { viewModel.loadLogcat() }
            binding.refreshLayout.isRefreshing = false
            refreshData()
        }
    }

    override fun onRefresh() {
        loadLogs()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshData() {
        SkeletonBinder.cancel(binding.skeleton)
        adapter.notifyDataSetChanged()

        val isEmpty = viewModel.getAll().isEmpty()
        binding.recyclerView.isVisible = !isEmpty
        if (!isEmpty) {
            EmptyStateBinder.hide(binding.emptyState.root)
            return
        }
        if (query.isNotBlank()) {
            EmptyStateBinder.bind(
                root = binding.emptyState.root,
                glyph = R.drawable.ic_logcat_24dp,
                title = getString(R.string.editor_search_empty_title),
                line = getString(R.string.editor_search_empty_line),
                actionLabel = getString(R.string.editor_search_reset),
                onAction = { binding.search.searchInput.setText("") },
            )
        } else {
            EmptyStateBinder.bind(
                root = binding.emptyState.root,
                glyph = R.drawable.ic_logcat_24dp,
                title = getString(R.string.log_empty_title),
                line = getString(R.string.log_empty_line),
            )
        }
    }

    /**
     * «Копировать» and «Очистить» as the two accent action rows §7 draws under the slab.
     *
     * They act on the WHOLE log and not on the searched subset - that is what the названия say
     * and it is what the prototype's card means. «Поделиться файлом» is the one action left in
     * the overflow: it is not in the design, so it has no row to live in, and an action reachable
     * from two places is one place too many.
     */
    private fun bindActionRows() {
        RowBinder.bind(
            root = binding.rowCopy.root,
            title = getString(R.string.log_action_copy),
            glyph = R.drawable.ic_copy,
            tone = RowBinder.RowTone.ACCENT,
            trailing = RowBinder.Trailing.None,
            onClick = {
                Utils.setClipboard(this, viewModel.getAll().joinToString("\n"))
                toast(R.string.notice_copied)
            },
        )
        RowBinder.bind(
            root = binding.rowClear.root,
            title = getString(R.string.log_action_clear),
            glyph = R.drawable.ic_logcat_24dp,
            tone = RowBinder.RowTone.ACCENT,
            trailing = RowBinder.Trailing.None,
            onClick = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { viewModel.clearLogcat() }
                    refreshData()
                    toastSuccess(R.string.log_cleared)
                }
            },
        )
    }

    private fun showLogActions() {
        val hasLines = viewModel.getAll().isNotEmpty()
        EditorActionsSheet(this, getString(R.string.editor_actions_title))
            .action(R.string.log_action_share, R.drawable.ic_share_24dp, enabled = hasLines) {
                shareLogcat()
            }
            .show()
    }

    private fun shareLogcat() {
        lifecycleScope.launch {
            val logText = viewModel.getAll().joinToString("\n")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val shareDir = File(cacheDir, "shared_logs").apply { mkdirs() }
                    shareDir.listFiles()?.forEach { it.delete() }

                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                    val logFile = File(shareDir, "departament_logcat_$timestamp.txt")
                    logFile.writeText(logText, Charsets.UTF_8)

                    // One spelling of the FileProvider authority across the app: BackupActivity
                    // already uses BuildConfig.APPLICATION_ID, and "${packageName}.cache" drifts
                    // from it the moment a flavour changes the package.
                    val uri = FileProvider.getUriForFile(
                        this@LogcatActivity,
                        "${BuildConfig.APPLICATION_ID}.cache",
                        logFile,
                    )
                    uri to logFile.name
                }
            }

            val (uri, name) = result.getOrElse {
                toastError(R.string.log_share_failed)
                return@launch
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_TITLE, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, name, uri)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.log_action_share)))
        }
    }

    override fun onDestroy() {
        SkeletonBinder.cancel(binding.skeleton)
        super.onDestroy()
    }
}
