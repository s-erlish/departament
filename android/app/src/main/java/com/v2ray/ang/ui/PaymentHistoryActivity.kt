package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.R
import com.v2ray.ang.auth.AccountCache
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.dto.PaymentDto
import com.v2ray.ang.databinding.ActivityPaymentHistoryBinding
import com.v2ray.ang.ui.adapter.PaymentsAdapter
import com.v2ray.ang.ui.adapter.paymentsForHistory
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.viewmodel.AccountViewModel
import kotlinx.coroutines.launch

/**
 * Dedicated, full-screen payment history. Replaces the cramped inline "показать ещё" list that
 * lived on the Account tab: here the whole list is shown and scrolls, and the BaseActivity toolbar
 * gives a real back affordance to close the screen.
 */
class PaymentHistoryActivity : BaseActivity() {

    private val binding by lazy { ActivityPaymentHistoryBinding.inflate(layoutInflater) }
    private val viewModel: AccountViewModel by viewModels()
    private val paymentsAdapter = PaymentsAdapter()

    /** Distinguishes "still loading, list legitimately empty" from "loaded, nothing to show". */
    private var loaded = false

    /**
     * True while the list on screen came from [AccountCache] and no network load has run. In this
     * state the [viewModel]'s payments StateFlow still holds its empty seed, which it replays to the
     * collector every time the screen returns to STARTED; those empty emissions must not clobber the
     * cached rows. Pull-to-refresh clears this so a genuinely empty result can render.
     */
    private var showingCache = false

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        // README §7's lekalo draws the title at 24sp/700 UNDER the back control, which
        // activity_base's 16sp MaterialToolbar cannot do — so the header is @layout/view_sub_header
        // inside this screen's own layout. The screen never used the base progress bar; its own
        // @id/progress_history sits over the list, where the rows it is waiting for will appear.
        setContentView(binding.root)
        ToolbarBinder.bind(
            root = binding.toolbar.root,
            title = getString(R.string.history_title),
            activity = this,
        )
        // The list is the scroller again (see activity_payment_history.xml: the NestedScrollView it
        // used to sit in capped it at one viewport of rows), so the hairline listens to the list.
        ToolbarBinder.attachTo(binding.toolbar.root, binding.rvPayments)
        binding.toolbar.toolbarNote.setText(R.string.account_hub_history_sub)
        binding.toolbar.toolbarNote.isVisible = true

        binding.rvPayments.layoutManager = LinearLayoutManager(this)
        binding.rvPayments.adapter = paymentsAdapter

        // Empty-state CTA: send the user straight into the buy flow. Guarded, because this is
        // literally the case SingleClick's own doc names — «the double tap that opens two
        // BuyTariffActivity instances».
        binding.btnHistoryBuy.onSingleClick {
            startActivity(Intent(this, BuyTariffActivity::class.java))
        }

        binding.refreshLayout.setColorSchemeColors(resolveThemeColor(R.attr.iconTintBlue))
        // SwipeRefreshLayout's automatic target is its first child, which is now the frame holding
        // the list rather than the list itself — and a frame can never scroll, so the gesture would
        // fire on a downward swipe taken halfway through the ledger. It is told where the top
        // actually is instead.
        binding.refreshLayout.setOnChildScrollUpCallback { _, _ ->
            binding.rvPayments.canScrollVertically(-1)
        }
        binding.refreshLayout.setOnRefreshListener {
            loaded = false
            // Explicit refresh: leave cache-only mode so a genuinely empty result can render.
            showingCache = false
            viewModel.loadPayments()
        }

        observeState()

        // Serve fresh (< 1h) cached payments instantly and skip the initial network load; the
        // pull-to-refresh above still forces a reload that repopulates the cache.
        val cached = AccountCache.getPayments()
        if (cached != null) {
            loaded = true
            showingCache = true
            binding.progressHistory.visibility = View.GONE
            showPayments(cached)
        } else {
            showHistoryLoading()
            viewModel.loadPayments()
        }
    }

    /**
     * The one place the ledger reaches the screen, cache path and network path alike.
     *
     * [paymentsForHistory] is the owner's ruling — «оставить только те что в обработке и те, что
     * успешны» — applied to the RAW list the API returned; nothing is dropped upstream of here, so
     * the cache, the ViewModel and the account tab's «последний платёж» value all still see every
     * operation the backend sent.
     *
     * AN EMPTY RESULT AFTER THE FILTER IS THE EMPTY STATE, not an error and not a blank screen.
     * This is the fourth instance in this project of an expected answer being reported as a
     * failure, and the filter makes it reachable in a new way: an account whose only operations
     * were cancelled now legitimately has nothing to list, and «Платежей пока нет» with «Купить
     * подписку» is the honest thing to say to it.
     */
    private fun showPayments(all: List<PaymentDto>) {
        val shown = paymentsForHistory(all)
        paymentsAdapter.submit(shown)
        binding.rvPayments.isVisible = shown.isNotEmpty()
        if (shown.isEmpty()) {
            showEmptyBlock(getString(R.string.history_empty), withBuyCta = true)
        } else {
            hideEmptyBlock()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.payments.collect { renderPayments(it) } }
                launch { viewModel.error.collect { renderError(it) } }
            }
        }
    }

    private fun renderPayments(list: List<PaymentDto>) {
        // While showing cached rows (no network load run yet), ignore the ViewModel's empty seed
        // emission — replayed on every return to STARTED — so it can't wipe the cached list.
        if (showingCache && list.isEmpty() && !binding.refreshLayout.isRefreshing) return
        showingCache = false
        loaded = true
        binding.refreshLayout.isRefreshing = false
        binding.progressHistory.visibility = View.GONE
        // The cache keeps the RAW list: it is shared with the account tab, whose «История платежей»
        // row reads the most recent operation of any outcome, and narrowing it here would make one
        // screen's display rule the whole app's data.
        AccountCache.putPayments(list)
        showPayments(list)
    }

    private fun renderError(error: ApiError?) {
        if (error == null) return
        binding.refreshLayout.isRefreshing = false
        binding.progressHistory.visibility = View.GONE
        loaded = true
        // Only surface the error banner in the empty area if we have nothing to show. The buy CTA
        // stays hidden here — this is an error, not a genuine "no payments yet" empty state.
        if (paymentsAdapter.itemCount == 0) {
            // The card goes with it: an outlined box with nothing in it behind a centred sentence
            // is the "empty state drawn on top of the list" look the card was moved to avoid.
            binding.rvPayments.isVisible = false
            showEmptyBlock(getString(messageFor(error)), withBuyCta = false)
        }
        viewModel.clearError()
    }

    private fun messageFor(error: ApiError): Int = when (error) {
        is ApiError.Network, ApiError.Timeout, is ApiError.ServiceUnavailable ->
            R.string.history_error_network

        else -> R.string.history_error_generic
    }

    private fun showHistoryLoading() {
        if (loaded) return
        binding.progressHistory.visibility = View.VISIBLE
        hideEmptyBlock()
    }

    /**
     * Reveals the centred empty/error block ([R.id.ll_empty_state]) with [message]. [withBuyCta]
     * shows the «Купить подписку» button — only for the genuine "no payments yet" empty state, not
     * for errors.
     */
    private fun showEmptyBlock(message: String, withBuyCta: Boolean) {
        binding.tvEmpty.text = message
        binding.tvEmpty.visibility = View.VISIBLE
        binding.btnHistoryBuy.visibility = if (withBuyCta) View.VISIBLE else View.GONE
        binding.llEmptyState.visibility = View.VISIBLE
    }

    private fun hideEmptyBlock() {
        binding.tvEmpty.visibility = View.GONE
        binding.btnHistoryBuy.visibility = View.GONE
        binding.llEmptyState.visibility = View.GONE
    }

    /** Resolves a theme colour attr (e.g. [R.attr.iconTintBlue]) to an ARGB int for the current theme. */
    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
