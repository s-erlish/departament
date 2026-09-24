package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.R
import com.v2ray.ang.auth.AccountSession
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.dto.PaymentInitDto
import com.v2ray.ang.auth.dto.PaymentOutcome
import com.v2ray.ang.auth.dto.PaymentRequestDto
import com.v2ray.ang.auth.dto.PriceOptionDto
import com.v2ray.ang.auth.dto.TariffDto
import com.v2ray.ang.auth.dto.TariffGroupDto
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.component.Haptic
import com.v2ray.ang.ui.component.SubPage
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.ToolbarBinder
import com.v2ray.ang.util.reducedMotion
import com.v2ray.ang.viewmodel.AccountViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * «Купить подписку» — the buy-subscription flow. The user picks a tariff, a duration/price
 * option and (optionally) extra devices, then taps the CTA to choose a payment method.
 *
 * Data comes from [AccountViewModel]: [AccountViewModel.tariffs] + [AccountViewModel.loadTariffs]
 * for the catalog and [AccountViewModel.publicConfig] + [AccountViewModel.loadPublicConfig] for
 * the payment methods; [AccountViewModel.profile] provides the wallet balance shown in the sheet.
 *
 * **The selection is held as KEYS, not as objects** (D09/D11). Every re-render of the catalog
 * rebuilds the card views, and a rotation rebuilds the whole activity; an object reference kept
 * across either is a reference to a card nobody can see. Holding `selectedTariffKey` /
 * `selectedOptionKey` and re-deriving the highlighted views from them in [applySelection] means a
 * catalog re-emission (which a payment error causes, because the error is combined into the same
 * render) repaints the same selection instead of quietly dropping it while the checkout card kept
 * offering to charge for it.
 */
class BuyTariffActivity : BaseActivity() {

    private val viewModel: AccountViewModel by viewModels()

    // Selection state — keys, resolved against the live catalog on demand (see the class comment).
    private var selectedTariffKey: String? = null
    private var selectedOptionKey: String? = null
    private var extraDevices: Int = 0

    // The rendered catalog, indexed the same way the selection is keyed.
    private val tariffsByKey = LinkedHashMap<String, TariffDto>()
    private val cardViews = LinkedHashMap<String, MaterialCardView>()
    private val optionContainers = LinkedHashMap<String, LinearLayout>()
    // Per-tariff carets, so opening a tariff needn't rebuild the list.
    private val carets = mutableMapOf<String, ImageView>()
    // Per-tariff name + price, which light up in the accent while the tariff is open.
    private val tariffNames = mutableMapOf<String, TextView>()
    private val tariffPrices = mutableMapOf<String, TextView>()
    // Per-tariff «Текущий» badges, repainted when the account's subscriptions arrive.
    private val tariffBadges = mutableMapOf<String, TextView>()
    // Per-tariff option rows, so the selected row can be highlighted without a rebuild.
    private val optionRows = mutableMapOf<String, MutableList<Pair<PriceOptionDto, View>>>()

    // The disclosure animation each price-option panel is currently running. Keyed by the view so
    // the panel that is CLOSING and the panel that is OPENING — the two halves of one tap — can be
    // in flight at the same time without either cancelling the other, and so a third tap cancels
    // exactly the run it replaces. ViewPropertyAnimator could not hold this: the height is driven
    // by a ValueAnimator, which `view.animate().cancel()` knows nothing about.
    private val panelAnimators = mutableMapOf<View, AnimatorSet>()

    // Shape of the catalog currently on screen. An identical re-emission is repainted, not rebuilt.
    private var renderedSignature: String? = null

    // A purchase/balance payment was just fired: the NEXT error must surface as the
    // "Ошибка оплаты" diagnostic dialog (with the real backend detail), not be swallowed.
    private var awaitingPaymentError = false

    // True once the catalog request has completed at least once, so an empty list can be
    // distinguished from "still loading" (otherwise an empty catalog spins forever).
    private var loaded = false

    // A browser checkout was just launched: on return we re-poll for the webhook-confirmed result.
    private var pendingPayment = false
    private var pollJob: Job? = null

    private lateinit var progressBuy: ProgressBar
    private lateinit var skeleton: LinearLayout
    private lateinit var stateIcon: ImageView
    private lateinit var btnRetry: MaterialButton
    private lateinit var tvPending: TextView
    private lateinit var stateView: TextView
    private lateinit var tariffsContainer: LinearLayout
    private lateinit var checkoutCard: MaterialCardView
    private lateinit var extraDevicesRow: LinearLayout
    private lateinit var tvExtraCount: TextView
    private lateinit var tvExtraCost: TextView
    private lateinit var btnDevMinus: MaterialButton
    private lateinit var btnDevPlus: MaterialButton
    private lateinit var tvTotal: TextView
    private lateinit var btnPay: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        SubPage.installTransitions(this)
        super.onCreate(savedInstanceState)
        // README §7's lekalo puts the title at 24sp/700 UNDER the back control and one sentence of
        // explanation under that — «Выберите тариф и срок», which is also what the section label
        // above the cards used to say and no longer needs to.
        setContentView(R.layout.activity_buy_tariff)
        val header = findViewById<View>(R.id.toolbar)
        ToolbarBinder.bind(
            root = header,
            title = getString(R.string.buy_title),
            activity = this,
            note = getString(R.string.buy_note),
        )
        ToolbarBinder.attachTo(header, findViewById<NestedScrollView>(R.id.main_content))

        progressBuy = findViewById(R.id.progress_buy)
        skeleton = findViewById(R.id.ll_skeleton)
        stateIcon = findViewById(R.id.iv_state_icon)
        btnRetry = findViewById(R.id.btn_retry)
        tvPending = findViewById(R.id.tv_pending)
        stateView = findViewById(R.id.tv_state)
        tariffsContainer = findViewById(R.id.ll_tariffs)
        checkoutCard = findViewById(R.id.card_checkout)
        extraDevicesRow = findViewById(R.id.ll_extra_devices)
        tvExtraCount = findViewById(R.id.tv_extra_devices_count)
        tvExtraCost = findViewById(R.id.tv_extra_devices_cost)
        btnDevMinus = findViewById(R.id.btn_dev_minus)
        btnDevPlus = findViewById(R.id.btn_dev_plus)
        tvTotal = findViewById(R.id.tv_total)
        btnPay = findViewById(R.id.btn_pay)

        savedInstanceState?.let {
            selectedTariffKey = it.getString(STATE_TARIFF_KEY)
            selectedOptionKey = it.getString(STATE_OPTION_KEY)
            extraDevices = it.getInt(STATE_EXTRA_DEVICES, 0)
            pendingPayment = it.getBoolean(STATE_PENDING_PAYMENT, false)
            awaitingPaymentError = it.getBoolean(STATE_AWAITING_ERROR, false)
        }

        // The stepper keeps a RAW listener on purpose, and it is the one control in the product
        // that has to: reaching five extra devices means five taps, and SingleClick's 500ms window
        // would swallow four of them. Nothing duplicates — changeExtraDevices clamps and repaints.
        btnDevMinus.setOnClickListener { changeExtraDevices(-1) }
        btnDevPlus.setOnClickListener { changeExtraDevices(+1) }
        // These two do not. onPayClicked refuses to start a second REQUEST (viewModel
        // .paymentInFlight), but the flag is only raised once a method has been picked, so two taps
        // before the sheet appears opened two PaymentMethodSheets on top of each other. Haptic.PRESS
        // is the purchase-confirm rung (00-rules.md 8.10) and the example in SingleClick's own doc.
        btnPay.onSingleClick(Haptic.PRESS) { onPayClicked() }
        btnRetry.onSingleClick { reload() }

        // The method pick comes back as DATA, on this activity's own FragmentManager, and the
        // listener is registered by whichever instance is alive (D11). The sheet used to hand its
        // answer to a lambda that had captured the activity which opened it — after a rotation that
        // activity is destroyed, and picking a method crashed the app.
        supportFragmentManager.setFragmentResultListener(REQUEST_PAY_METHOD, this) { _, bundle ->
            onMethodPicked(bundle)
        }

        observe()
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TARIFF_KEY, selectedTariffKey)
        outState.putString(STATE_OPTION_KEY, selectedOptionKey)
        outState.putInt(STATE_EXTRA_DEVICES, extraDevices)
        outState.putBoolean(STATE_PENDING_PAYMENT, pendingPayment)
        outState.putBoolean(STATE_AWAITING_ERROR, awaitingPaymentError)
    }

    /** (Re)fetch the catalog + payment config, showing the loading state until the first result. */
    private fun reload() {
        loaded = false
        viewModel.clearError()
        showBuyLoading()
        lifecycleScope.launch {
            viewModel.loadTariffs().join()
            loaded = true
            renderState()
        }
        viewModel.loadPublicConfig()
        // THE CATALOGUE IS PUBLIC, THE ACCOUNT IS NOT. `loadTariffs` and `loadPublicConfig` above
        // answer to anyone; `/client/profile` and `/client/subscription*` need a token, and asked
        // without one they are a round trip that can only come back 401. Nothing here reads the
        // result in that state either — «Текущий» marks the tariff the ACCOUNT owns and there is no
        // account. @see loadAccountState
        loadAccountState()
    }

    /**
     * The two authenticated loads this screen wants, asked for only when there is a session to ask
     * with. Both are latest-wins in the ViewModel, so calling this again is free.
     */
    private fun loadAccountState() {
        if (!AccountSession.isLoggedIn()) return
        viewModel.refreshProfile()
        // What the account already owns, for «Текущий».
        viewModel.loadSubscriptions()
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.tariffs.combine(viewModel.error) { tariffs, error -> tariffs to error }
                .collect { renderState() }
        }
        // Surface payment failures. Without this the СБП/balance flow "does nothing" on error —
        // the request fails silently in the ViewModel and the user never learns why.
        lifecycleScope.launch {
            viewModel.error.collect { error ->
                if (error != null && awaitingPaymentError) {
                    awaitingPaymentError = false
                    showPaymentErrorDialog(error)
                    viewModel.clearError()
                }
            }
        }
        // The in-flight flag lives on the ViewModel, which is retained across a rotation, so the
        // busy CTA comes back busy on the other side of one (D10).
        lifecycleScope.launch {
            viewModel.paymentInFlight.collect { renderPayBusy(it) }
        }
        // §7's «Текущий». The catalog cannot answer which tariff is already owned, and this
        // activity holds its OWN AccountViewModel, so the list starts empty and the badge has to
        // wait for it rather than be decided once at inflation. An account whose subscriptions
        // never arrive shows no badge, which is the honest fallback: a wrong «Текущий» would tell
        // someone they already have what they are about to buy.
        lifecycleScope.launch {
            viewModel.subscriptions.collect { paintCurrentBadges() }
        }
    }

    /** Shows «Текущий» on every card whose tariff the account already subscribes to. */
    private fun paintCurrentBadges() {
        for ((key, badge) in tariffBadges) {
            val tariff = tariffsByKey[key]
            badge.visibility =
                if (tariff != null && isCurrentTariff(tariff)) View.VISIBLE else View.GONE
        }
    }

    // region rendering

    /**
     * Single source of truth for the catalog state. An empty list is only "unavailable" once
     * [loaded] is set — before the first result completes it still reads as "loading", so an
     * empty catalog no longer spins forever.
     */
    private fun renderState() {
        val tariffs = viewModel.tariffs.value
        val error = viewModel.error.value
        val hasAny = tariffs.any { it.tariffs.isNotEmpty() }
        when {
            hasAny -> renderTariffs(tariffs)
            error != null -> showError()
            loaded -> showEmpty()
            else -> showBuyLoading()
        }
    }

    private fun showBuyLoading() {
        // One loading signal, not three. The skeleton silhouette IS the loading affordance, so the
        // circular spinner and the "Загрузка" label are NOT stacked on top of it — that redundancy
        // (spinner + label + skeleton, then a hard pop to real cards) is what read as janky. The
        // glyph + label are reserved for the skeleton-less states (error/empty) below.
        stateIcon.visibility = View.GONE
        btnRetry.visibility = View.GONE
        stateView.visibility = View.GONE
        // Reset alpha in case a prior skeleton→content cross-fade was interrupted by a reload.
        skeleton.alpha = 1f
        skeleton.visibility = View.VISIBLE
    }

    private fun showError() {
        skeleton.visibility = View.GONE
        stateIcon.visibility = View.VISIBLE
        btnRetry.visibility = View.VISIBLE
        stateView.text = getString(R.string.buy_error)
        stateView.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        skeleton.visibility = View.GONE
        stateIcon.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        stateView.text = getString(R.string.buy_empty)
        stateView.visibility = View.VISIBLE
    }

    /**
     * Shape of the catalog: which tariffs, in which order, each with which options. Two renders
     * with the same signature would build byte-identical cards, so the second one repaints the
     * selection instead of tearing the list down and putting it back (D09).
     */
    private fun signatureOf(groups: List<TariffGroupDto>): String =
        groups.joinToString("|") { group ->
            group.tariffs.joinToString(",") { tariff ->
                tariffKey(tariff) + "#" + optionsOf(tariff).joinToString("+") { optionKey(it) }
            }
        }

    private fun renderTariffs(groups: List<TariffGroupDto>) {
        val hasAny = groups.any { it.tariffs.isNotEmpty() }
        if (!hasAny) {
            showEmpty()
            return
        }

        // The state glyph/label never coexist with real content — retire them immediately.
        stateIcon.visibility = View.GONE
        btnRetry.visibility = View.GONE
        stateView.visibility = View.GONE

        val signature = signatureOf(groups)
        if (signature == renderedSignature && tariffsContainer.childCount > 0) {
            // Same catalog, re-emitted. This is the path a payment error takes: the error is
            // combined into this render, and rebuilding here is exactly what used to reset every
            // card to neutral while the checkout card went on offering the invisible selection.
            skeleton.visibility = View.GONE
            applySelection()
            return
        }

        // Was the skeleton what the user is currently looking at? Only then do we cross-fade;
        // a plain re-render (the catalog flow re-emits after content is already up) swaps in place.
        val fromSkeleton = skeleton.visibility == View.VISIBLE

        // Build the real cards FIRST so the fade reveals a fully-rendered list, not a blank frame.
        // Rebuild once; selection is then mutated in place.
        tariffsContainer.removeAllViews()
        // Every panel about to be discarded takes its animation with it: an AnimatorSet still
        // writing layoutParams on a detached view is work for nothing, and the map would hold the
        // old view tree alive until the next rebuild.
        panelAnimators.values.forEach { it.cancel() }
        panelAnimators.clear()
        tariffsByKey.clear()
        cardViews.clear()
        optionContainers.clear()
        carets.clear()
        tariffNames.clear()
        tariffPrices.clear()
        tariffBadges.clear()
        optionRows.clear()
        val inflater = LayoutInflater.from(this)

        groups.forEach { group ->
            group.tariffs.forEach { tariff ->
                addTariffCard(inflater, tariffsContainer, tariff)
            }
        }
        renderedSignature = signature

        // Re-apply whatever was selected before the rebuild (a rotation, or the first render after
        // a restore). A key that no longer exists in the catalog resolves to nothing and the
        // checkout card closes, which is the honest outcome.
        applySelection()

        if (fromSkeleton) {
            crossFadeSkeletonToContent()
        } else {
            // Already on content: no animation, just make sure everything is at full opacity.
            skeleton.visibility = View.GONE
            skeleton.alpha = 1f
            tariffsContainer.alpha = 1f
        }
    }

    /**
     * Gentle skeleton → content hand-off: fade the placeholder silhouette out while the real tariff
     * list fades in over [R.integer.motion_state], instead of a hard visibility pop. Gated by
     * reduced motion (via [reducedMotion], which reads the OS ANIMATOR_DURATION_SCALE like every
     * other animated screen in the app): when motion is off we swap instantly.
     */
    private fun crossFadeSkeletonToContent() {
        if (skeleton.reducedMotion()) {
            skeleton.visibility = View.GONE
            skeleton.alpha = 1f
            tariffsContainer.alpha = 1f
            return
        }

        val duration = resources.getInteger(R.integer.motion_state).toLong()
        val easeOut = AnimationUtils.loadInterpolator(this, R.interpolator.ease_out_quart)

        skeleton.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(easeOut)
            .withEndAction {
                skeleton.visibility = View.GONE
                skeleton.alpha = 1f
            }
            .start()

        tariffsContainer.alpha = 0f
        tariffsContainer.animate().alpha(1f).setDuration(duration).setInterpolator(easeOut).start()
    }

    private fun addTariffCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        tariff: TariffDto,
    ) {
        val card = inflater.inflate(R.layout.item_buy_tariff, parent, false) as MaterialCardView
        val header = card.findViewById<View>(R.id.header_tariff)
        val tvName = card.findViewById<TextView>(R.id.tv_tariff_name)
        val tvInfo = card.findViewById<TextView>(R.id.tv_tariff_info)
        val ivCaret = card.findViewById<ImageView>(R.id.iv_tariff_caret)
        val tvBadge = card.findViewById<TextView>(R.id.tv_tariff_badge)
        val tvFrom = card.findViewById<TextView>(R.id.tv_tariff_from)
        val tvPeriod = card.findViewById<TextView>(R.id.tv_tariff_period)
        val llOptions = card.findViewById<LinearLayout>(R.id.ll_price_options)

        // THE GLYPH SLOT IS GONE, not hidden. `tv_group_emoji` was a TextView that both branches
        // set to GONE — the design retired the emoji chrome (1.4.4) — so every tariff row inflated,
        // measured and threw away a view that could never be visible, and the группа's `emoji` was
        // threaded three call frames down to reach it.
        tvName.text = tariff.name

        val trafficStr = if (tariff.isUnlimitedTraffic() || (tariff.trafficLimitBytes ?: 0L) <= 0L) {
            getString(R.string.buy_unlimited)
        } else {
            formatBytes(tariff.trafficLimitBytes ?: 0L)
        }
        tvInfo.text = getString(R.string.buy_tariff_devices, tariff.includedDevices) +
            " · " + getString(R.string.buy_tariff_traffic, trafficStr)


        // Цена входа в тариф: стоимость САМОГО КОРОТКОГО срока, ровно как её задали в панели.
        // Здесь бралась самая выгодная ставка за месяц по всем срокам, и заголовок карточки врал:
        // у тарифа с ценами 150 за месяц и 400 за три стояло «135» — числа, которого нет ни в одном
        // ценнике и за которое нельзя купить. Выгода длинных сроков подписана у самих сроков внутри
        // карточки, где рядом стоит и настоящая цена, и срок, за который её платят.
        // Тариф без пригодных сроков не показывает ничего, а не ноль.
        val entry = entryOption(tariff)
        if (entry == null) {
            tvFrom.visibility = View.GONE
            tvPeriod.visibility = View.GONE
        } else {
            tvFrom.visibility = View.VISIBLE
            tvPeriod.visibility = View.VISIBLE
            tvFrom.text = formatMoney(entry.price, tariff.currency)
            // «в месяц» пишем только когда срок и есть месяц: цена за 90 дней с подписью «в месяц»
            // — то же враньё, другими словами.
            tvPeriod.text = if (entry.durationDays == 30) {
                getString(R.string.buy_per_month)
            } else {
                getString(R.string.buy_option_duration, entry.durationDays)
            }
        }

        val key = tariffKey(tariff)
        tariffsByKey[key] = tariff
        cardViews[key] = card
        optionContainers[key] = llOptions
        carets[key] = ivCaret
        tariffNames[key] = tvName
        tariffPrices[key] = tvFrom
        tariffBadges[key] = tvBadge

        // Build the duration/price option rows (hidden until the tariff is selected).
        val rows = mutableListOf<Pair<PriceOptionDto, View>>()
        val options = optionsOf(tariff)
        options.forEach { option ->
            val row = inflater.inflate(R.layout.item_buy_option, llOptions, false)
            val tvDur = row.findViewById<TextView>(R.id.tv_option_duration)
            val tvPrice = row.findViewById<TextView>(R.id.tv_option_price)
            val tvSaving = row.findViewById<TextView>(R.id.tv_option_saving)
            tvDur.text = getString(R.string.buy_option_duration, option.durationDays)
            tvPrice.text = formatMoney(option.price, tariff.currency)

            // §7: «выгода пишется приглушённо-белым, не зелёным» — the tone is the layout's
            // (Caption on colorOnSurfaceVariant); what belongs here is only whether there IS one.
            val saving = savingOn(tariff, option)
            if (saving == null) {
                tvSaving.visibility = View.GONE
            } else {
                tvSaving.visibility = View.VISIBLE
                tvSaving.text = getString(R.string.buy_saving, formatMoney(saving, tariff.currency))
            }
            row.setOnClickListener { selectOption(tariff, option) }
            llOptions.addView(row)
            rows.add(option to row)
        }
        optionRows[key] = rows

        // Guarded, because the tap TOGGLES now: an ungated double tap on a tariff would open it
        // and close it again, and the second half of that would look like the card refusing to
        // open. Two targets, one action — the header sits inside the card and consumes the tap
        // that lands on it, so only one of the two ever fires per press.
        card.onSingleClick { selectTariff(tariff) }
        header.onSingleClick { selectTariff(tariff) }

        parent.addView(card)
    }

    // endregion

    // region selection

    private fun selectedTariff(): TariffDto? = selectedTariffKey?.let { tariffsByKey[it] }

    private fun selectedOption(): PriceOptionDto? {
        val tariff = selectedTariff() ?: return null
        val key = selectedOptionKey ?: return null
        return optionsOf(tariff).firstOrNull { optionKey(it) == key }
    }

    /**
     * The tariff card's tap is a TOGGLE: «тарифы если раскрывать, то обратно скрывать нельзя
     * почему-то». It could not be closed because a tap on the open card re-applied the same
     * selection, so the disclosure had an opening move and no closing one.
     *
     * THE PROTOTYPE IS THE AUTHORITY HERE and it toggles too — `buildBuy` writes
     * `toggle: () => this.setState({ plan: open ? null : p.key })`, i.e. tapping the open plan
     * sets the open plan to NOTHING. §7's «Открыт всегда один тариф» is therefore the cap ("at
     * most one at a time"), not a floor: zero open is a state the design draws.
     *
     * CLOSING RIDES THE SAME ANIMATION AS SWITCHING. [applySelection] hands every panel to
     * [reveal] on every pass, and `reveal(panel, open = false)` is the very leg that already runs
     * when the other tariff opens — it animates the height down over `motion_expand` and fades
     * over `motion_popup_fade`. Closing the last open panel therefore costs no new code path and
     * cannot regress into the instant collapse that was fixed before it.
     *
     * WHAT THE CLOSE TAKES WITH IT: the chosen term and the extra-device count, because they
     * describe a tariff that is no longer on the table, and the checkout card, which
     * [applySelection] hides as soon as either half of the pair is missing — an «Оплатить 1 290 ₽»
     * under two closed cards would be charging for something the user cannot see.
     *
     * Re-selecting the tariff that is ALREADY selected stays idempotent for its original reason
     * (D09): the branch below only fires from a tap, and a tap on the open card now means "close",
     * which repaints exactly as completely as the re-apply did.
     */
    private fun selectTariff(tariff: TariffDto) {
        val key = tariffKey(tariff)
        if (selectedTariffKey == key) {
            selectedTariffKey = null
            selectedOptionKey = null
            extraDevices = 0
        } else {
            selectedTariffKey = key
            selectedOptionKey = null
            extraDevices = 0
            // A tariff with a single option → preselect it for a shorter flow.
            val options = optionsOf(tariff)
            if (options.size == 1) selectedOptionKey = optionKey(options.first())
        }
        applySelection()
    }

    private fun selectOption(tariff: TariffDto, option: PriceOptionDto) {
        selectedTariffKey = tariffKey(tariff)
        selectedOptionKey = optionKey(option)
        applySelection()
    }

    /**
     * Paints the current selection onto the cards that exist right now: exactly one card carries
     * the accent stroke, its check mark and its open option list; every other card is neutral and
     * closed. The checkout card is shown if and only if a tariff AND an option resolve — so it can
     * never offer to charge for something the user cannot see.
     */
    private fun applySelection() {
        // Drop keys the current catalog no longer contains.
        if (selectedTariff() == null) {
            selectedTariffKey = null
            selectedOptionKey = null
        } else if (selectedOption() == null) {
            selectedOptionKey = null
        }

        val neutral = resolveAttrColor(com.google.android.material.R.attr.colorOutlineVariant)
        val accent = resolveAttrColor(androidx.appcompat.R.attr.colorPrimary)
        val selected = selectedTariffKey

        val onSurface = resolveAttrColor(com.google.android.material.R.attr.colorOnSurface)
        val muted = resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        for ((key, card) in cardViews) {
            val isSelected = key == selected
            // §7: «контур и название загораются акцентом».
            card.strokeColor = if (isSelected) accent else neutral
            card.strokeWidth = if (isSelected) dp(2) else dp(1)
            tariffNames[key]?.setTextColor(if (isSelected) accent else onSurface)
            tariffPrices[key]?.setTextColor(if (isSelected) accent else onSurface)
            carets[key]?.let { caret ->
                caret.imageTintList = ColorStateList.valueOf(if (isSelected) accent else muted)
                turnCaret(caret, isSelected)
            }
            optionContainers[key]?.let { reveal(it, isSelected) }
        }

        paintCurrentBadges()

        val tariff = selectedTariff()
        val option = selectedOption()
        if (selected != null) {
            if (option != null) highlightOption(selected, option) else clearOptionHighlights(selected)
        }

        if (tariff != null && option != null) {
            setupExtraDevices(tariff)
            checkoutCard.visibility = View.VISIBLE
            updateTotal(tariff, option)
        } else {
            checkoutCard.visibility = View.GONE
        }
    }

    private fun highlightOption(key: String, option: PriceOptionDto) {
        optionRows[key]?.forEach { (opt, view) ->
            paintOption(view, optionKey(opt) == optionKey(option))
        }
    }

    private fun clearOptionHighlights(key: String) {
        optionRows[key]?.forEach { (_, view) -> paintOption(view, false) }
    }

    /**
     * §7 marks the chosen term with a dot, not with a check at the end of the row. Both drawables
     * are the same 22dp ring in the same place, so choosing a term changes colour and fill and
     * moves nothing (§11 grabl 2).
     */
    private fun paintOption(row: View, selected: Boolean) {
        row.setBackgroundResource(
            if (selected) R.drawable.bg_buy_option_selected else R.drawable.bg_buy_option
        )
        row.findViewById<ImageView>(R.id.iv_option_dot)?.setImageResource(
            if (selected) R.drawable.ic_option_dot_on else R.drawable.ic_option_dot_off
        )
        row.isSelected = selected
    }

    /**
     * §7's disclosure, and it is SYMMETRIC — «открыт всегда один тариф» means the panel that is
     * leaving and the panel that is arriving move together, both animated.
     *
     * WHAT WAS WRONG. The old version animated ALPHA only. Opening set `visibility = VISIBLE`
     * first, so the card took its full height in the tap frame; closing faded to 0 over 340ms and
     * only then set `GONE`, so the height it was holding vanished in one frame, 340ms after the
     * finger left. Tapping Plus while Base was open therefore played: everything jumps DOWN
     * (Plus opens instantly) … 340ms of nothing … everything jumps UP (Base collapses instantly).
     * That is exactly «бейс раскрытый криво закрывается с пролагом и очень резко без анимации» —
     * the «пролаг» is the delay, the «резко» is the un-animated collapse.
     *
     * WHAT THE PROTOTYPE ACTUALLY DOES, read off its own style rather than off §7's prose:
     *
     *     transition: max-height 340ms cubic-bezier(.25,1,.5,1), opacity 240ms cubic-bezier(...)
     *     transform: rotate(...); transition: transform 300ms cubic-bezier(.25,1,.5,1)
     *
     * i.e. the HEIGHT is what animates, over `motion_expand` 340 on ease_out_quart, with opacity
     * on a SHORTER pass and the caret on `motion_caret` 300 (the repo had the caret on 340 too).
     * A CSS transition runs the same in both directions, so 340 is the closing duration as well —
     * which is what «закрытие симметрично» asks for, and what makes the two panels' heights cancel
     * each other out mid-flight instead of taking turns.
     *
     * The opacity leg takes `motion_popup_fade` 180 rather than the prototype's 240: there is no
     * 240 in motion.xml, 180 is the token that already means "the fade under a longer reveal", and
     * both numbers land well before the height does, which is the property that matters.
     *
     * COST. One `AnimatorSet` per panel (never a postDelayed chain), height driven by an int
     * animator that calls `requestLayout()` on a subtree of one card. NO hardware layer: the
     * view's bounds change every frame, so a layer would be re-allocated and re-rastered on each
     * one — the opposite of the saving it makes on a pure alpha animation.
     *
     * The natural height is measured against the width the card actually has. Before the first
     * layout that width is 0 (the very first `applySelection` after a rebuild), and there the
     * disclosure SNAPS — an opening animation on a card the user has not seen yet is motion with
     * nothing to explain.
     */
    private fun reveal(panel: View, open: Boolean) {
        panelAnimators.remove(panel)?.cancel()

        val alreadyThere = if (open) {
            panel.visibility == View.VISIBLE && panel.alpha == 1f
        } else {
            panel.visibility != View.VISIBLE
        }
        if (alreadyThere) return

        val target = if (open) naturalHeightOf(panel) else 0
        if (panel.reducedMotion() || (open && target <= 0)) {
            settlePanel(panel, open)
            return
        }

        val wasVisible = panel.visibility == View.VISIBLE
        val from = if (wasVisible) panel.height else 0
        val easeOut = AnimationUtils.loadInterpolator(this, R.interpolator.ease_out_quart)
        val params = panel.layoutParams

        panel.visibility = View.VISIBLE
        params.height = from
        panel.layoutParams = params
        // Start states, so a run that begins mid-flight picks up where the cancelled one stopped
        // and a run that begins from rest starts at the end the other direction settled on.
        if (open && !wasVisible) panel.alpha = 0f
        if (!open && panel.alpha == 0f) panel.alpha = 1f

        val grow = ValueAnimator.ofInt(from, target).apply {
            duration = resources.getInteger(R.integer.motion_expand).toLong()
            interpolator = easeOut
            addUpdateListener {
                val lp = panel.layoutParams
                lp.height = it.animatedValue as Int
                panel.layoutParams = lp
            }
        }
        val fade = ObjectAnimator.ofFloat(panel, View.ALPHA, panel.alpha, if (open) 1f else 0f).apply {
            duration = resources.getInteger(R.integer.motion_popup_fade).toLong()
            interpolator = easeOut
        }

        val set = AnimatorSet().apply {
            playTogether(grow, fade)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    panelAnimators.remove(panel)
                    // A cancelled run is being replaced by the next one, which sets its own start
                    // state — settling here would fight it.
                    if (!cancelled) settlePanel(panel, open)
                }
            })
        }
        panelAnimators[panel] = set
        set.start()
    }

    /**
     * The end state of a disclosure, also used when motion is off. Height goes back to
     * WRAP_CONTENT so a later price-option rebuild re-measures instead of being pinned to the
     * pixel count this animation happened to finish on.
     */
    private fun settlePanel(panel: View, open: Boolean) {
        val lp = panel.layoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        panel.layoutParams = lp
        panel.alpha = 1f
        panel.visibility = if (open) View.VISIBLE else View.GONE
    }

    /** What [panel] would be tall if it were laid out now, or 0 while its width is unknown. */
    private fun naturalHeightOf(panel: View): Int {
        val parent = panel.parent as? ViewGroup ?: return 0
        val available = parent.width - parent.paddingStart - parent.paddingEnd
        if (available <= 0) return 0
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return panel.measuredHeight
    }

    /** The caret turns 180° over `motion_caret` 300 — the prototype's own transform duration. */
    private fun turnCaret(caret: ImageView, open: Boolean) {
        val target = if (open) 180f else 0f
        if (caret.reducedMotion()) {
            caret.rotation = target
            return
        }
        if (caret.rotation == target) return
        caret.animate()
            .rotation(target)
            .setDuration(resources.getInteger(R.integer.motion_caret).toLong())
            .setInterpolator(AnimationUtils.loadInterpolator(this, R.interpolator.ease_out_quart))
            .start()
    }

    private fun setupExtraDevices(tariff: TariffDto) {
        val max = tariff.maxExtraDevices
        if (max <= 0) {
            extraDevicesRow.visibility = View.GONE
        } else {
            extraDevicesRow.visibility = View.VISIBLE
        }
        extraDevices = extraDevices.coerceIn(0, maxOf(0, max))
        renderExtraDevices(tariff)
    }

    private fun changeExtraDevices(delta: Int) {
        val tariff = selectedTariff() ?: return
        val max = tariff.maxExtraDevices
        extraDevices = (extraDevices + delta).coerceIn(0, maxOf(0, max))
        renderExtraDevices(tariff)
        selectedOption()?.let { updateTotal(tariff, it) }
    }

    private fun renderExtraDevices(tariff: TariffDto) {
        tvExtraCount.text = getString(R.string.buy_extra_devices_count, extraDevices)

        // Make the stepper bounds visible: dim + disable the button that can't move further.
        // A payment in flight freezes the stepper too — the amount being charged is already fixed,
        // so a control that appears to change it would be lying.
        val busy = viewModel.paymentInFlight.value
        val max = maxOf(0, tariff.maxExtraDevices)
        setStepperEnabled(btnDevMinus, !busy && extraDevices > 0)
        setStepperEnabled(btnDevPlus, !busy && extraDevices < max)

        val cost = extraDevices * tariff.pricePerExtraDevice
        if (extraDevices > 0 && cost > 0.0) {
            tvExtraCost.text = getString(
                R.string.buy_extra_devices_cost,
                formatMoney(cost, tariff.currency),
            )
            tvExtraCost.visibility = View.VISIBLE
        } else {
            tvExtraCost.visibility = View.GONE
        }
    }

    /**
     * The single source of truth for the price: the option price plus the cost of any extra
     * devices. This exact value is both shown as «Итого» and sent as the charged `amount`, so the
     * displayed total and the amount the user is charged can never drift apart. When
     * [extraDevices] is 0 (or [TariffDto.pricePerExtraDevice] is 0) this is just `option.price`.
     */
    private fun currentTotal(tariff: TariffDto, option: PriceOptionDto): Double =
        option.price + extraDevices * tariff.pricePerExtraDevice

    private fun updateTotal(tariff: TariffDto, option: PriceOptionDto) {
        val total = currentTotal(tariff, option)
        tvTotal.text = formatMoney(total, tariff.currency)
        // §7: «кнопка внизу подстраивается: "Оплатить 1 290 ₽"». The amount on the control is the
        // amount it will charge, so the stepper above cannot change one without the other.
        if (!viewModel.paymentInFlight.value) {
            btnPay.text = getString(R.string.buy_pay_amount, formatMoney(total, tariff.currency))
            btnPay.contentDescription = btnPay.text
        }
    }

    // endregion

    // region checkout

    /**
     * The CTA's in-flight state (D10). The screen used to change in NO way between the tap and the
     * provider's answer — on a slow connection that is a button which appears not to have worked,
     * and the second tap is a second charge. Now the button says what it is doing, stops taking
     * taps, and the declared indicator beside it finally does the job it was declared for.
     */
    private fun renderPayBusy(busy: Boolean) {
        btnPay.isEnabled = !busy
        progressBuy.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            btnPay.text = getString(R.string.account_pay_in_progress)
            btnPay.contentDescription = btnPay.text
        }
        selectedTariff()?.let { tariff ->
            renderExtraDevices(tariff)
            // Restores «Оплатить N ₽» when the charge settles; the amount is still the truth.
            if (!busy) selectedOption()?.let { updateTotal(tariff, it) }
        }
    }

    private fun onPayClicked() {
        // Belt to the ViewModel's braces: the request itself refuses to start twice, and the CTA
        // refuses to ask.
        if (viewModel.paymentInFlight.value) return

        paintCurrentBadges()

        val tariff = selectedTariff()
        val option = selectedOption()
        if (tariff == null || option == null) {
            toast(R.string.buy_select_option_first)
            return
        }

        val methods = viewModel.publicConfig.value?.plategaMethods?.map { it.id to it.label } ?: emptyList()
        if (methods.isEmpty()) {
            toastError(R.string.buy_no_methods)
            return
        }

        val profile = viewModel.profile.value
        val balanceLabel = profile?.let { formatMoney(it.balance, it.currency) }

        // Everything the charge needs travels WITH the pick, so the request is never rebuilt from
        // selection state that a rotation reset while the sheet was open (D11).
        val payload = Bundle().apply {
            putString(EXTRA_TARIFF_ID, tariff.id)
            putString(EXTRA_OPTION_ID, option.id)
            putInt(EXTRA_EXTRA_DEVICES, extraDevices)
            putDouble(EXTRA_AMOUNT, currentTotal(tariff, option))
            putString(EXTRA_CURRENCY, tariff.currency.ifBlank { "RUB" })
        }

        PaymentMethodSheet.show(
            supportFragmentManager,
            REQUEST_PAY_METHOD,
            getString(R.string.buy_pick_method_title),
            balanceLabel,
            methods,
            payload,
        )
    }

    private fun onMethodPicked(result: Bundle) {
        val methodId = result.getString(PaymentMethodSheet.RESULT_METHOD_ID) ?: return
        if (viewModel.paymentInFlight.value) return

        val tariffId = result.getString(EXTRA_TARIFF_ID).orEmpty()
        val optionId = result.getString(EXTRA_OPTION_ID).orEmpty()
        val deviceCount = result.getInt(EXTRA_EXTRA_DEVICES, 0).takeIf { it > 0 }
        // Charge exactly the displayed «Итого» (option price + extra devices), never just the
        // bare option price — otherwise the extra-device cost is silently dropped from checkout.
        val amount = result.getDouble(EXTRA_AMOUNT, 0.0)
        val currency = result.getString(EXTRA_CURRENCY)?.ifBlank { null } ?: "RUB"
        if (tariffId.isBlank() || amount <= 0.0) {
            toastError(R.string.buy_select_option_first)
            return
        }

        // Arm the diagnostic: whichever request we fire, a failure now becomes a visible dialog.
        awaitingPaymentError = true
        val req = PaymentRequestDto(
            tariffId = tariffId,
            tariffPriceOptionId = optionId.ifBlank { null },
            deviceCount = deviceCount,
            amount = amount,
            currency = currency,
            paymentMethod = if (methodId == PaymentMethodSheet.ID_BALANCE) null else methodId.toIntOrNull(),
        )

        if (methodId == PaymentMethodSheet.ID_BALANCE) {
            viewModel.payWithBalance(req) { outcome ->
                awaitingPaymentError = false
                // «Подписка оплачена» and closing the screen is a claim the подписка now exists.
                // The backend answers with a settlement status and it is read now (see
                // AccountViewModel.payWithBalance): a charge the provider is still settling says so
                // and the screen STAYS, so the user can watch it land instead of returning to a tab
                // that has not changed. A refusal never reaches here at all — it comes back as the
                // payment-error dialog.
                if (outcome == PaymentOutcome.PENDING) {
                    toast(R.string.account_pay_pending)
                    viewModel.refreshProfile()
                    viewModel.loadSubscriptions()
                } else {
                    toastSuccess(R.string.buy_success)
                    finish()
                }
            }
        } else {
            viewModel.buy(req) { init ->
                awaitingPaymentError = false
                openCheckout(init)
            }
        }
    }

    /** Dialog with a designed, code-free reason for a failed payment. */
    private fun showPaymentErrorDialog(error: ApiError) {
        val code = when (error) {
            is ApiError.Unauthorized -> "401/403"
            is ApiError.Server -> error.code.toString()
            is ApiError.RateLimited -> "429"
            is ApiError.ServiceUnavailable -> "502/503"
            is ApiError.NotFound -> "404"
            is ApiError.Gone -> "410"
            is ApiError.Timeout -> "timeout"
            is ApiError.Network -> "network"
            else -> "—"
        }
        val detail = when (error) {
            is ApiError.Unauthorized -> error.detail
            is ApiError.Server -> error.detail
            else -> null
        }
        val body = if (detail.isNullOrBlank()) {
            getString(R.string.account_payment_error_body_nodetail, code)
        } else {
            getString(R.string.account_payment_error_body, code, detail)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_payment_error_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Opens the provider checkout URL, mirroring AccountFragment.openCheckout. */
    private fun openCheckout(init: PaymentInitDto) {
        val url = init.paymentUrl
        if (url.isBlank()) {
            toastError(R.string.buy_no_browser)
            return
        }
        val uri = Uri.parse(url)
        pendingPayment = true
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
            toast(R.string.buy_checkout_return)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                toast(R.string.buy_checkout_return)
            } catch (e2: ActivityNotFoundException) {
                pendingPayment = false
                toastError(R.string.buy_no_browser)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingPayment) startPaymentPolling()
    }

    /**
     * After returning from a browser checkout, re-poll profile/subscriptions a few times over ~40s
     * while showing a pending hint. The backend only confirms PAID via webhook, so the tab returning
     * proves nothing — mirrors [AccountFragment.startPaymentPolling].
     */
    private fun startPaymentPolling() {
        if (pollJob?.isActive == true) return
        // A POLL WITHOUT A SESSION IS TEN REQUESTS THAT CANNOT BE ANSWERED. Both endpoints below
        // are authenticated, and the hint over them promises news about an account nobody is signed
        // in to — so there is nothing to wait for and nothing to show. @see loadAccountState
        if (!AccountSession.isLoggedIn()) {
            pendingPayment = false
            return
        }
        tvPending.visibility = View.VISIBLE
        pollJob = lifecycleScope.launch {
            repeat(5) {
                loadAccountState()
                delay(8000L)
            }
            pendingPayment = false
            tvPending.visibility = View.GONE
        }
    }

    // endregion

    // region helpers

    private fun optionsOf(tariff: TariffDto): List<PriceOptionDto> {
        val options = tariff.priceOptions.sortedBy { it.sortOrder }
        return if (options.isNotEmpty()) {
            options
        } else {
            // Fall back to the tariff's own duration/price as a single synthetic option.
            listOf(PriceOptionDto(id = tariff.id, durationDays = tariff.durationDays, price = tariff.price))
        }
    }

    /**
     * The cheapest per-month rate this tariff is sold at, or null when it is sold at none.
     *
     * §7: «список сроков у каждого тарифа свой — приходит с сервера, не зашивать». So the figure
     * on the collapsed card is computed from [optionsOf] rather than stored: whatever terms the
     * catalog returns are the terms this compares. A term shorter than a month still divides by
     * its real length, so a weekly plan is not advertised at a monthly price.
     */
    private fun entryOption(tariff: TariffDto): PriceOptionDto? =
        optionsOf(tariff)
            .filter { it.durationDays > 0 && it.price > 0.0 }
            .minByOrNull { it.durationDays }

    /**
     * «Выгода N ₽» for [option]: what the same span would cost at the SHORTEST term's rate, minus
     * what it costs at this one. Null when there is no shorter term to compare against or the
     * saving rounds to nothing — a discount that does not exist is not written as «Выгода 0 ₽».
     */
    private fun savingOn(tariff: TariffDto, option: PriceOptionDto): Double? {
        if (option.durationDays <= 0 || option.price <= 0.0) return null
        val baseline = optionsOf(tariff)
            .filter { it.durationDays > 0 && it.price > 0.0 }
            .minByOrNull { it.durationDays }
            ?: return null
        if (optionKey(baseline) == optionKey(option)) return null
        val atBaselineRate = baseline.price / baseline.durationDays * option.durationDays
        val saving = atBaselineRate - option.price
        return saving.takeIf { it >= 1.0 }
    }

    /**
     * True for the tariff the account is already on (§7's «Текущий»). The catalog cannot answer
     * this — it lists what is for sale — so it is matched against the subscriptions the account
     * reports, by the same tariffId those subscriptions renew on.
     */
    private fun isCurrentTariff(tariff: TariffDto): Boolean {
        val id = tariff.id.takeIf { it.isNotBlank() } ?: return false
        return viewModel.subscriptions.value.any { it.tariffId == id }
    }

    private fun tariffKey(tariff: TariffDto): String =
        tariff.id.ifBlank { tariff.name }

    private fun optionKey(option: PriceOptionDto): String =
        option.id.ifBlank { "${option.durationDays}/${option.price}" }

    private fun setStepperEnabled(button: MaterialButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // endregion

    companion object {
        private const val REQUEST_PAY_METHOD = "buy_pay_method"

        /**
         * The divisor behind «в месяц». 30.44 and not 30: over a year the round number drifts by
         * five days, which is enough to advertise an annual plan at a rate it is not sold at.
         */
        private const val DAYS_PER_MONTH = 30.44

        private const val EXTRA_TARIFF_ID = "extra_tariff_id"
        private const val EXTRA_OPTION_ID = "extra_option_id"
        private const val EXTRA_EXTRA_DEVICES = "extra_extra_devices"
        private const val EXTRA_AMOUNT = "extra_amount"
        private const val EXTRA_CURRENCY = "extra_currency"

        private const val STATE_TARIFF_KEY = "state_tariff_key"
        private const val STATE_OPTION_KEY = "state_option_key"
        private const val STATE_EXTRA_DEVICES = "state_extra_devices"
        private const val STATE_PENDING_PAYMENT = "state_pending_payment"
        private const val STATE_AWAITING_ERROR = "state_awaiting_error"

        /**
         * THE FRACTION IS SEPARATED BY A COMMA. This screen printed «135.29 ₽» and «236.76 ₽» with
         * a POINT, next to a ring on the account tab writing «2,0 ТБ» — one screen in two
         * languages. The number is still composed under [Locale.US] (the grouping and the digit
         * shapes must not follow a phone set to Farsi or Bengali) and only the decimal mark is
         * swapped, which is what `SubscriptionPagerAdapter.formatBytes` already does.
         */
        private fun formatMoney(amount: Double, currency: String): String {
            val n = if (amount % 1.0 == 0.0) amount.toLong().toString()
            else String.format(Locale.US, "%.2f", amount).replace('.', ',')
            return if (currency.isBlank()) n else "$n ${currencySymbol(currency)}"
        }

        /** Maps an ISO currency code to a trailing symbol: RUB→₽, USD→$, EUR→€, KZT→₸, UAH→₴. */
        private fun currencySymbol(currency: String): String = when (currency.uppercase(Locale.US)) {
            "RUB" -> "₽"
            "USD" -> "$"
            "EUR" -> "€"
            "KZT" -> "₸"
            "UAH" -> "₴"
            else -> currency
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 Б"
            val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
            var value = bytes.toDouble()
            var idx = 0
            while (value >= 1024.0 && idx < units.size - 1) {
                value /= 1024.0
                idx++
            }
            // Same comma as the money above and as the account tab's ring: «2,0 ТБ», never «2.0».
            val formatted = if (idx == 0) value.toLong().toString()
            else String.format(Locale.US, "%.1f", value).replace('.', ',')
            return "$formatted ${units[idx]}"
        }
    }
}
