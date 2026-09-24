package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.R
import com.v2ray.ang.auth.AccountSession
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.SubscriptionSyncManager
import com.v2ray.ang.auth.dto.PaymentDto
import com.v2ray.ang.auth.dto.PaymentInitDto
import com.v2ray.ang.auth.dto.PaymentOutcome
import com.v2ray.ang.auth.dto.PaymentRequestDto
import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.auth.dto.emailSignInWorks
import com.v2ray.ang.databinding.ActivityAccountBinding
import com.v2ray.ang.databinding.DialogTopUpBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SubscriptionNaming
import com.v2ray.ang.ui.component.Haptic
import com.v2ray.ang.ui.component.TelegramFlow
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.pressFeedback
import com.v2ray.ang.ui.component.restoreChecked
import com.v2ray.ang.util.AvatarManager
import com.v2ray.ang.util.reducedMotion
import com.v2ray.ang.viewmodel.AccountViewModel
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * Account HUB, hosted IN-PLACE as the Account bottom-nav tab (no sliding Activity, no toolbar).
 * Mirrors the Settings tab: a compact profile row (name / balance / top-up), a single
 * subscription summary (active sub OR "нет активной подписки", never both), and grouped entry cards
 * that open the devices / buy / history sub-screens. Purchases open a provider checkout in a Custom
 * Tab; a PAID result only ever arrives via webhook, so on return we re-poll rather than assume success.
 */
class AccountFragment : Fragment() {

    // Nullable binding so the view refs are released in onDestroyView (avoids leaking the whole
    // account view tree while the tab is detached).
    private var _binding: ActivityAccountBinding? = null
    private val binding get() = _binding!!

    /**
     * ACTIVITY-SCOPED, so this is the very instance Главная is already using — see
     * `HomeFragment.accountViewModel`. Both tabs live at once (they are hidden, never replaced) and
     * both refresh on resume; with one instance apiece that was two identical account fetches per
     * foreground. It also means this tab opens onto data that is already there.
     */
    private val viewModel: AccountViewModel by activityViewModels()

    /**
     * The shell's shared server state, scoped to the ACTIVITY — the same instance the Главная and
     * Серверы lists read. Signing out removes the account's подписки and their серверы from the
     * store, and this is what makes the lists on the other tabs stop showing them (A1).
     */
    private val mainViewModel: MainViewModel by activityViewModels()

    /** Adapter backing the subscription carousel (one page per sub). */
    private lateinit var subAdapter: SubscriptionPagerAdapter

    /** Last subscription list published; the first page is the active/root sub. */
    private var currentSubs: List<SubInfoDto> = emptyList()
    private var latestProfile: UserProfileDto? = null

    /** Carousel page the hero time block describes. Every sub has its own expiry. */
    private var selectedSubIndex: Int = 0

    // True until the FIRST real load result lands (a non-null profile, a non-empty sub list, or an
    // error). Gates the loading skeleton so a genuinely-empty account resolves to the empty state
    // rather than spinning forever, while a cold open (and a retry) still shows the silhouette.
    private var pendingFirstLoad = true

    private var pendingPayment = false
    private var pollJob: Job? = null

    // Sign-out. The wipe is local and usually lands in a few milliseconds, so the row's trailing
    // spinner is scheduled 300ms out and cancelled if the work finishes first — a spinner that
    // flashes for one frame reads as a glitch (00-rules.md 7.3).
    private var signOutSpinnerJob: Job? = null

    // The retry bar from a failed sign-out, held so a wipe that finishes late (the watchdog gave
    // up, the work did not) can take back a message that has stopped being true.
    private var signOutBar: Snackbar? = null

    // Last logged-in/out value this view has rendered. Null until the first emission, so the
    // StateFlow's initial replay is not mistaken for a transition. Drives the reset that keeps a
    // dropped session (sign-out, or a 401 on the identity endpoint) from leaving the previous
    // user's name, balance and subscriptions painted on a fragment that is never recreated.
    private var renderedLoggedIn: Boolean? = null

    // Set when the session drops; consumed on the next sign-in so the tab cold-loads (skeleton,
    // then real data) instead of re-showing whatever it last rendered.
    private var needsColdLoad = false

    // Last balance figure actually shown, so a re-render counts UP from it instead of hard-swapping.
    // Null until the first paint (which sets the value instantly — no spin on open).
    private var lastBalance: Double? = null
    private var balanceAnimator: ValueAnimator? = null

    // Set right before a purchase/top-up call so the NEXT error emission is surfaced as the real
    // "Ошибка оплаты" diagnostic dialog instead of the friendly toast. Cleared on success.
    private var awaitingPaymentError = false

    /**
     * THE TELEGRAM SIGN-IN, RUN ON THIS TAB AND NOWHERE ELSE.
     *
     * The same object Главная's start screen uses ([TelegramFlow]) — one flow, two callers. It
     * raises the flow overlay over this tab, opens Telegram and keeps the wait here; no Activity is
     * pushed, so there is no second screen to be thrown onto and no second choice to make.
     *
     * The two things a flow cannot know are wired here: what it is allowed to wait for, and where a
     * failure is written. There used to be a third — where its «обнови подписки» went — and it is
     * gone because the errand was: the подписка of the account just signed into is written by
     * `HomeFragment.onLoggedIn`, on every path, and walking the LOCAL подписки on top of that is
     * either nothing to do or the same fetch twice. @see TelegramFlow.Host
     */
    private val telegramFlow = TelegramFlow(object : TelegramFlow.Host {
        override suspend fun awaitSubscriptionImport() {
            // The shell's own bounded wait, so the overlay stays up until the подписка is REALLY
            // in — the tab must not be handed back empty for the second and a half the import
            // takes. Signed-in data then lands on it through the account-state collector below.
            (activity as? MainHost)?.awaitSubscriptionImport()
        }

        override fun onFailed(message: Int) = showSignedOutError(message)
    })

    // Gallery picker for a custom avatar. GetContent grants a one-shot read grant, which is
    // enough since we copy the bytes into app storage immediately. Registered at construction
    // (valid for a Fragment), never after STARTED.
    private val pickAvatar =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> onAvatarPicked(uri) }

    /**
     * «Почта» под «Способы входа», launched for a RESULT rather than fired and forgotten.
     *
     * The address is proved on the panel, so what changes when the errand succeeds changes on the
     * SERVER: nothing this tab already holds knows about it, and the tab does not reload on resume
     * (only a signed-out → signed-in transition cold-loads it). Without this, a link the user has
     * just opened would leave the row that sent them there still reading «Не привязан».
     *
     * **The result code is deliberately not consulted.** `RESULT_OK` covers the success, but the
     * OTHER way this screen ends is a panel that knows something this tab does not: «Почта уже
     * привязана» to a row that believes there is no address, or a `PASSWORD_REQUIRED` to a form
     * that believes there is no password. Every one of those endings is answered by the same move
     * — re-read the profile — so the cheap unconditional form is also the correct one, at the cost
     * of one GET after a return that changed nothing.
     *
     * It also carries the password step's outcome: `hasPassword` decides whether the NEXT «Сменить
     * почту» asks for the current password, and it has just changed on the server.
     */
    private val linkEmail =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshProfile()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = ActivityAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.scrollRoot.clipToPadding = false
        applyListInsets()
        setupPager()
        wireActions()
        // The payment-method pick arrives as DATA, re-delivered to whichever view is alive when it
        // lands (D11). The sheet used to call a lambda that had captured this fragment's binding;
        // rotating with the sheet open and then picking a method invoked it against a view tree
        // that no longer existed.
        parentFragmentManager.setFragmentResultListener(
            REQUEST_TOP_UP_METHOD,
            viewLifecycleOwner,
        ) { _, bundle -> onTopUpMethodPicked(bundle) }
        observeState()
        renderHeroState()
        loadAll()
    }

    /**
     * Bottom room for the overlaid bottom bar, ASKED FOR RATHER THAN GUESSED.
     *
     * It was 96dp of hard-coded density — 56 of bar plus 16 of air plus **24 of a system inset
     * written into the number**. That last 24 is not a constant: it is a gesture bar on one phone,
     * nothing at all on another, and about 48dp on a three-button navigation bar. The figure
     * happened to be right on the owner's device and would have put «Выйти из аккаунта» under the
     * navigation bar on a three-button one — the same defect the settings list has already had.
     *
     * [MainHost.listBottomInset] is the shell's own answer (the real window inset + the 56dp bar +
     * breathing room), computed once from the window insets and shared by every tab that owns a
     * scrolling surface. The 16dp floor is what the card sits on before the first inset is handed
     * out — this tab is created lazily and can well be built after that moment, which is why the
     * shell calls this again from its inset listener.
     */
    fun applyListInsets() {
        val b = _binding ?: return
        val floor = resources.getDimensionPixelSize(R.dimen.space_16)
        val inset = (activity as? MainHost)?.listBottomInset ?: 0
        b.scrollRoot.updatePadding(bottom = maxOf(inset, floor))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        balanceAnimator?.cancel()
        balanceAnimator = null
        signOutSpinnerJob?.cancel()
        signOutSpinnerJob = null
        signOutBar?.dismiss()
        signOutBar = null
        _binding = null
    }

    /**
     * Wires the traffic-ring carousel: one page per подписка, a space_12 gap between pages, and a
     * page-change callback that moves the dot selection. Neighbour-peek padding and the dots
     * themselves are (re)applied per list in [renderSubscriptions] since they depend on the page
     * count.
     *
     * The adapter takes no callbacks any more. After the §5 redesign a page draws only the traffic
     * ring; the name, the badge, the срок, «Пополнить»/«Продлить» and the auto-renew row are stated
     * once on the tab and re-bound for the visible page by [renderSelectedSub].
     */
    private fun setupPager() {
        subAdapter = SubscriptionPagerAdapter()
        binding.vpSubscriptions.apply {
            adapter = subAdapter
            offscreenPageLimit = 1
            clipToPadding = false
            // THE NEIGHBOUR-PEEK IS A CONSTANT, AND IT IS SET ONCE, HERE.
            //
            // It used to be written on every render as `if (count > 1) space_16 else 0`, i.e. the
            // page's width changed by 32dp the moment a second подписка landed — mid-load, under
            // the ring, on the frame the owner is watching. §5's rule for this object is that it
            // does not move: «кольцо стоит на месте с первого кадра, меняется только заливка».
            // A padding that never changes cannot move it, and with a single подписка it shows the
            // same thing a zero padding did, because there is no neighbour to peek at.
            val peek = resources.getDimensionPixelSize(R.dimen.space_16)
            setPadding(peek, 0, peek, 0)
            // THE SAME THREE SWITCHES ГЛАВНАЯ'S CAROUSEL NEEDED, on the same object in another tab.
            // Both reports the owner filed against the home card are reproducible here — this pager
            // was simply not the one on screen when he wrote them:
            //
            // «сам тулбар подписки … почему-то можно растягивать и тянуть влево или вправо» — from
            // Android 12 a RecyclerView answers a drag past its own edge with the stretch
            // over-scroll, so on a carousel of one every horizontal drag is over-scroll and the ring
            // is rubber. Set on the inner RecyclerView, never on the pager: `overScrollMode` on the
            // pager reaches a container that does not scroll.
            //
            // «видишь у них рамки появляются по бокам … а не обрезаться» — ViewPager2 lays the inner
            // RecyclerView out INSIDE the peek padding above, the neighbouring page is laid out
            // beside it (outside that box), and a ViewGroup clips its children by default. The
            // straight vertical cut 16dp in from each edge is that clip, not a border.
            //
            // clipChildren goes off on the INNER view only. The pager keeps it on, so the overflow
            // is still trimmed at the pager's own bounds and nothing walks further up — the distance
            // between that and SelectPopup.unclipAncestors drawing through the status bar is one
            // step of the same walk.
            //
            // itemAnimator is stripped for the same reason Главная strips it. Today it is already
            // gone — setPageTransformer(non-null) below saves the pager's animator and nulls it, and
            // unlike Главная's this call is unconditional and never handed a null to restore it. The
            // line is here so that a later `if (count > 1)` around the transformer cannot quietly
            // hand the animator back, which is exactly how the defect returned on the other tab.
            (getChildAt(0) as? RecyclerView)?.let { inner ->
                inner.itemAnimator = null
                inner.overScrollMode = View.OVER_SCROLL_NEVER
                inner.clipChildren = false
            }
            setPageTransformer(
                CompositePageTransformer().apply {
                    addTransformer(MarginPageTransformer(resources.getDimensionPixelSize(R.dimen.space_12)))
                },
            )
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    selectedSubIndex = position
                    updateDotSelection(position)
                    // Everything below the ring describes the подписка the user is looking at, not
                    // whichever one happens to be first: the name, the badge, the срок and the
                    // auto-renew row all move with the page.
                    renderSelectedSub()
                }
            })
        }
    }

    /**
     * EVERY TAP ON THIS TAB GOES THROUGH [onSingleClick] (П-31, R9). Not one of them was guarded
     * before, and this is the screen where an ungated double tap costs the most: two of these open
     * a checkout, one opens a top-up dialog twice over itself, and one signs the user out. The
     * guard is 500ms per view and it is the ONLY sanctioned way to attach a listener under `ui`.
     *
     * The haptic set is closed by 00-rules.md 8.10 and this tab uses exactly two of it: PRESS on
     * the sign-in, the switch flip and the sign-out, which are the taps that change something;
     * nothing else here vibrates, navigation included. «Войти через Telegram» is in that set
     * because it no longer navigates — it starts the errand, and its twin on the start screen
     * fires the same haptic for the same reason.
     */
    private fun wireActions() {
        binding.btnTopUp.onSingleClick { showTopUpDialog() }
        // The balance chip is the same action as «Пополнить»: a figure sitting next to a «+» has
        // promised the tap, and refusing it there would make the glyph decoration.
        binding.rowBalance.onSingleClick { showTopUpDialog() }
        // «Продлить» goes where buying goes — the tariff screen owns duration, extra devices and
        // the payment method, so the tab hands off rather than growing a second checkout.
        binding.btnSubRenew.onSingleClick { openSubScreen(BuyTariffActivity::class.java) }
        // §5: «нажимается вся строка», not the switch. The switch is not clickable and not
        // focusable in the layout, so this is the ONE hit target the row has.
        binding.rowSubAutorenew.onSingleClick(Haptic.TICK) { toggleAutoRenew() }
        binding.avatarContainer.onSingleClick { showAvatarOptions() }
        binding.imgAvatarEdit.onSingleClick { showAvatarOptions() }
        binding.rowDevices.onSingleClick { openSubScreen(DeviceManagementActivity::class.java) }
        binding.rowBuy.onSingleClick { openSubScreen(BuyTariffActivity::class.java) }
        binding.rowHistory.onSingleClick { openSubScreen(PaymentHistoryActivity::class.java) }
        binding.rowLogout.onSingleClick(Haptic.PRESS) { confirmSignOut() }
        // Empty-state CTA: same destination as the buy row.
        binding.btnBuyFirst.onSingleClick { openSubScreen(BuyTariffActivity::class.java) }
        // Signed-out hero. Telegram first, exactly as on the start screen — the same two doors in
        // the same order, so someone who has seen one recognises the other. MODE_SITE's form is
        // where registration lives («Создать аккаунт»), which is why there is no third button.
        //
        // «ВСЁ ДОЛЖНО ПРОИСХОДИТЬ НА ВКЛАДКЕ АККАУНТ», and that is a rule about SCREENS, not about
        // which screen. Two rounds were spent making the pushed screen better — first it was the
        // gate, which asked the question this block had already answered; then MODE_TELEGRAM_START,
        // which answered it but still pushed an Activity that carried «Открыть Telegram» and «Войти
        // через сайт» on it. Both are the same defect: the tap left this tab. So it stops leaving.
        // [startTelegramSignIn] runs the flow HERE, under the overlay, with Telegram opening over
        // it — the very machinery Главная has used all along, now called from two places instead of
        // living in one.
        //
        // «Войти по почте» still opens LoginActivity, and correctly so: that one is a FORM, i.e.
        // genuinely another screen, and nothing about it duplicates the tap that opened it. The
        // gate and MODE_TELEGRAM_START are untouched and still reachable — «Привязать Telegram»
        // below goes through the gate, and every other caller of LoginActivity is unchanged.
        binding.btnSignedOutTelegram.onSingleClick(Haptic.PRESS) { startTelegramSignIn() }
        binding.btnSignedOutSite.onSingleClick { openLogin(LoginActivity.MODE_SITE) }
        // Cold-load error: re-run the initial load (and re-show the skeleton while it retries).
        binding.btnRetryLoad.onSingleClick {
            pendingFirstLoad = true
            viewModel.clearError()
            renderHeroState()
            loadAll()
        }

        // THE PRESS RESPONSE ON EVERYTHING THAT CARRIES TEXT (handoff README §11 grabl 1). The
        // scale rung is already on these views from their Row styles; what only Kotlin can add is
        // the hardware layer that keeps a label from re-rasterising on the 200ms rebound. The
        // background step down the ramp stays where it belongs — @drawable/bg_row.
        listOf(
            binding.rowBuy,
            binding.rowDevices,
            binding.rowHistory,
            binding.rowLogout,
            binding.rowSubAutorenew,
        ).forEach { it.pressFeedback(R.anim.press_row) }
        binding.rowBalance.pressFeedback(R.anim.press_button)
    }

    private fun openSubScreen(target: Class<*>) {
        startActivity(Intent(requireContext(), target))
    }

    /**
     * «ВОЙТИ ЧЕРЕЗ TELEGRAM» — IN PLACE, WITH NO SCREEN PUSHED. The whole of the owner's report.
     *
     * The overlay goes up over this tab, Telegram opens over the overlay, and the wait, the retry
     * («Открыть Telegram», on the overlay itself), the failure and the success all happen here.
     *
     * BACK CANCELS AT EVERY POINT OF THE WAIT and lands back on this block: [FlowOverlay] owns a
     * back callback for the whole of its life and hands it to [TelegramFlow.cancel], which drops
     * the poll, removes the overlay and releases Главная. The block underneath was never touched,
     * so there is nothing to restore — cancelling REVEALS it rather than rebuilding it.
     *
     * The one thing the flow will not do is invent a backend: with none configured there is
     * nowhere to sign in, and the site form is the surface that says so in its own words.
     */
    private fun startTelegramSignIn() {
        val window = activity ?: return
        if (telegramFlow.isRunning) return
        // A previous attempt's reason is not this attempt's state.
        showSignedOutError(null)
        if (!telegramFlow.start(window)) openLogin(LoginActivity.MODE_SITE)
    }

    /**
     * The signed-out block's failure line — the tab's answer to a flow that ended in a reason
     * rather than a session. Pass null to take it back.
     *
     * Written the moment the flow reports, i.e. while the overlay is still fading: the overlay
     * leaving IS the reveal, and a second animation under a dissolving full-screen layer would be
     * motion nobody can see. Guarded on the binding because the reason can land on a tab whose view
     * has gone.
     */
    private fun showSignedOutError(@StringRes message: Int?) {
        val b = _binding ?: return
        if (message == null) {
            b.tvSignedOutError.isVisible = false
            return
        }
        b.tvSignedOutError.setText(message)
        b.tvSignedOutError.isVisible = true
    }

    /**
     * Opens [LoginActivity] on the door the tapped button names.
     *
     * ON THE SIGNED-OUT BLOCK IT IS «ВОЙТИ ПО ПОЧТЕ» AND NOTHING ELSE — a form is genuinely
     * another screen, so pushing one is not the defect the Telegram button had. The same door is
     * where [startTelegramSignIn] falls back to when there is no backend to sign in against.
     *
     * The «Способы входа» rows are NOT its users any more: they belong to accounts that are signed
     * in, and a sign-in screen shown to somebody who is already signed in closes itself. They take
     * [openTelegramLink] and [openEmailLink], both of which attach to the session instead.
     */
    private fun openLogin(mode: String) {
        startActivity(
            Intent(requireContext(), LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_MODE, mode)
        )
    }

    /**
     * Turns auto-renew on or off for the подписка whose card the switch belongs to.
     *
     * `AccountViewModel.toggleAutoRenew` / `togglePrimaryAutoRenew` were written, tested against
     * two real endpoints, and had no call site anywhere in the UI — the desktop has carried the
     * toggle since its account rework and Android had no way to reach it at all.
     *
     * The root подписка has no id on the id-less primary endpoint, so it takes its own call; a
     * secondary is addressed by [SubInfoDto.id]. On failure the switch is put back where it was
     * and the user is told, because a control that silently reverts on the next refresh is worse
     * than one that says it did not work. The list is reloaded on success so the caption's next
     * charge line comes from the backend rather than from an optimistic guess.
     */
    /**
     * The auto-renew row's tap. §5 makes the whole row the target, so the switch is a read-out
     * rather than a control: it is flipped here optimistically, the request follows, and either the
     * reload repaints it from the backend's answer or [setAutoRenew]'s error branch puts it back.
     */
    private fun toggleAutoRenew() {
        val b = _binding ?: return
        val sub = currentSubs.getOrNull(selectedSubIndex) ?: currentSubs.firstOrNull() ?: return
        val next = !b.switchSubAutorenew.isChecked
        b.switchSubAutorenew.isChecked = next
        setAutoRenew(sub, next)
    }

    private fun setAutoRenew(sub: SubInfoDto, enabled: Boolean) {
        val onError: (ApiError) -> Unit = {
            if (_binding != null) {
                toast(R.string.account_sub_autorenew_failed)
                // Put the switch back where it was: a control that silently reverts on the next
                // refresh is worse than one that says it did not work.
                renderSelectedSub()
            }
        }
        // The reload is the view model's now — and it refreshes the PROFILE too, which is where the
        // root подписка's auto-renew flag actually lives. Reloading only the subscription list here
        // re-merged against a stale cached profile and put the switch straight back, which is the
        // whole of «отключение авто списания не работает». See AccountViewModel.reloadAfterAutoRenew.
        val onDone: () -> Unit = {}
        if (sub.type.equals(SubscriptionSyncManager.TYPE_ROOT, ignoreCase = true)) {
            viewModel.togglePrimaryAutoRenew(enabled, onError, onDone)
        } else {
            // Unreachable from the UI now: `SubscriptionPagerAdapter.bindAutoRenew` hides the whole
            // row when a secondary подписка has no id, so a switch that can only fail is never
            // offered. Kept as the guard it always should have been, rather than as the place the
            // impossible case was discovered — one flip, one revert and one error message after
            // the fact.
            val id = sub.id.takeIf { it.isNotBlank() } ?: return onError(ApiError.Network(null))
            viewModel.toggleAutoRenew(id, enabled, onError, onDone)
        }
    }

    /**
     * NOTHING IS FETCHED WITHOUT A SESSION. All five of these are authenticated endpoints, and the
     * tab is reachable signed out now — someone who added a departament подписка from the clipboard
     * gets the Account tab too. Firing them tokenless returns 401, and a 401 on the identity call
     * runs [AccountSession.wipe]: the tab would have signed the visitor out of an account they were
     * never in, and taken the empty hero with it. The signed-out state renders from no data at all,
     * so there is nothing to load for it.
     */
    private fun loadAll() {
        if (!AccountSession.isLoggedIn()) return
        viewModel.refreshProfile()
        viewModel.loadSubscriptions()
        viewModel.loadPublicConfig()
        // Needed to resolve the active sub's tariff badge name (Base/Plus) from its tariffId.
        viewModel.loadTariffs()
        // Feeds the history row's trailing last-payment date.
        viewModel.loadPayments()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.profile.collect { renderProfile(it); renderHeroState() } }
                launch { viewModel.subscriptions.collect { renderSubscriptions(it) } }
                // Re-render when the tariff catalog arrives so the badge resolves. The ring pages
                // do not read the catalog, so the carousel is left alone.
                launch { viewModel.tariffs.collect { renderHeroState() } }
                // Refresh the device figures when the real connected-device count resolves.
                launch { viewModel.deviceCount.collect { renderDevicesRowValue() } }
                launch { viewModel.payments.collect { renderHistoryValue(it) } }
                // A top-up in flight owns the «Пополнить» control until the provider answers, so
                // a slow connection cannot be mistaken for a dead button and paid for twice (D10).
                launch { viewModel.paymentInFlight.collect { renderTopUpBusy(it) } }
                // Skeleton is driven by loading (+ the first-load gate) via renderHeroState.
                launch { viewModel.loading.collect { renderHeroState() } }
                // The OTHER half of the first-load gate, and it needs its own collector: when the
                // list resolves to nothing, `subscriptions` republishes the same empty list, a
                // StateFlow does not re-emit an equal value, and renderSubscriptions never runs —
                // so without this the ring's slot would hold the skeleton forever on an account
                // that genuinely has no подписка.
                launch { viewModel.subsResolved.collect { renderHeroState() } }
                launch { viewModel.error.collect { renderError(it) } }
                // The tab's fragment is added once and then only shown/hidden, so it outlives the
                // session. Without this, signing out (or a 401) would leave the previous account's
                // data painted, and signing in as somebody else would show it to them.
                launch { viewModel.account.collect { onAccountState(it) } }
            }
        }
    }

    /**
     * Reacts to logged-in/out TRANSITIONS only; the StateFlow's initial replay just records the
     * current value, because [onViewCreated] has already kicked off the first load.
     */
    private fun onAccountState(state: AccountSession.AccountState) {
        val loggedIn = state is AccountSession.AccountState.LoggedIn
        val previous = renderedLoggedIn
        renderedLoggedIn = loggedIn
        if (previous == null || previous == loggedIn) return
        if (loggedIn) {
            // EVERY signed-out → signed-in transition cold-loads, and `needsColdLoad` no longer
            // gates it. That flag was set only by [onSessionCleared], so it described "the session
            // dropped and came back" — and the tab is now openable while signed OUT, where the
            // first sign-in is the transition that has never had a load behind it. The initial
            // replay is still skipped by `previous == null`, so this cannot double up with
            // [onViewCreated]'s load.
            needsColdLoad = false
            pendingFirstLoad = true
            renderHeroState()
            loadAll()
        } else {
            onSessionCleared()
        }
    }

    /**
     * Blanks every rendered value after the session goes away. Covers both routes to a dropped
     * session: the user signing out here, and [AccountSession.wipe] firing from the repository
     * when the identity endpoint returns 401.
     */
    private fun onSessionCleared() {
        endSignOutBusy()
        // The session did go away, so a "не удалось выйти" bar from a watchdog that fired early is
        // now a lie about the screen the user is looking at. Take it back.
        signOutBar?.dismiss()
        signOutBar = null
        // Idempotent, and the only thing that clears the ViewModel on the 401 route (an explicit
        // sign-out has already done it by the time we get here).
        viewModel.clearAccountData()
        pollJob?.cancel()
        pollJob = null
        pendingPayment = false
        awaitingPaymentError = false
        binding.tvPending.visibility = View.GONE
        // The empty hero, not the skeleton: there is nothing loading and nothing to wait for, and
        // a pulse animator left looping on a tab the user can no longer open is pure battery.
        pendingFirstLoad = false
        needsColdLoad = true
        latestProfile = null
        currentSubs = emptyList()
        selectedSubIndex = 0
        // The signed-out block is about to come back, and it says WHY when there is a why. A
        // session the user ended explains itself — they tapped «Выйти» and confirmed it. One the
        // server ended does not: the seven-day token simply ran out, and without a line about it
        // the tab just goes blank and the account appears to have fallen off. The sentence also
        // carries the fact that matters most here, that nothing was deleted along with it.
        //
        // Otherwise null, because a reason left over from the last sign-in attempt would be the
        // first thing on the block and would be describing nothing.
        showSignedOutError(
            if (AccountSession.consumeTokenDeathNotice()) R.string.auth_err_session_expired else null
        )
        subAdapter.submit(emptyList())
        binding.llSubDots.removeAllViews()
        binding.llSubDots.isVisible = false
        binding.tvRowValueDevices.visibility = View.GONE
        binding.tvRowValueHistory.visibility = View.GONE
        renderProfile(null)
        renderHeroState()

        // A1. The sign-out wipe has already removed this account's подписки and their серверы from
        // the store (AccountSession.wipe -> SubscriptionSyncManager.removeAllManaged); what it
        // cannot do is repaint a list it does not own. Without this the Главная and Серверы tabs
        // went on showing — and offering to select — серверы that no longer exist anywhere but in
        // an in-memory cache, until something else happened to reload it.
        //
        // Safe on the OTHER route into this method too. An expired token takes
        // AccountSession.endSession, which deliberately deletes nothing; re-reading the store then
        // paints back exactly the same list. That distinction is the whole point of the two
        // methods and this call does not blur it: it re-reads, it never removes.
        mainViewModel.reloadServerList()
    }

    // Context-scoped toast helpers so the ported (Context.toast) calls work from a Fragment.
    private fun toast(message: Int) = requireContext().toast(message)
    private fun toastError(message: Int) = requireContext().toastError(message)
    private fun toastSuccess(message: Int) = requireContext().toastSuccess(message)

    // region rendering

    private fun renderProfile(profile: UserProfileDto?) {
        latestProfile = profile
        if (profile == null) {
            binding.tvUsername.text = ""
            // Null/error state: blank the balance rather than showing a fake "Баланс: 0 ₽". Forget the
            // last figure so a profile arriving later paints instantly instead of counting up from stale.
            // The whole ROW goes with it: now that the balance is a labelled value, leaving the label
            // behind would state «Баланс» followed by nothing, which reads as a broken figure rather
            // than as an absent one.
            balanceAnimator?.cancel()
            lastBalance = null
            binding.tvBalance.text = ""
            binding.rowBalance.isVisible = false
            AvatarManager.setMonogram(binding.tvAvatarInitial, null)
            AvatarManager.applyAvatar(viewLifecycleOwner.lifecycleScope, requireContext(), binding.imgAvatar, binding.tvAvatarInitial, null)
            renderLoginMethods(null)
            return
        }
        // A real profile landed — leave the loading skeleton behind.
        pendingFirstLoad = false
        // Name prefers the Telegram @handle, then the display name, then the account e-mail.
        val handle = profile.telegramUsername?.takeIf { it.isNotBlank() }?.let { "@$it" }
        val display = profile.telegramName?.takeIf { it.isNotBlank() }
        val email = profile.email.takeIf { it.isNotBlank() }
        val primary = handle ?: display ?: email.orEmpty()
        binding.tvUsername.text = primary
        AvatarManager.setMonogram(binding.tvAvatarInitial, primary)
        AvatarManager.applyAvatar(viewLifecycleOwner.lifecycleScope, requireContext(), binding.imgAvatar, binding.tvAvatarInitial, profile)

        renderLoginMethods(profile)

        binding.rowBalance.isVisible = true
        val previousBalance = lastBalance
        if (previousBalance == null || previousBalance == profile.balance) {
            // First paint (or an unchanged re-render): land the figure instantly, no count-up spin.
            binding.tvBalance.text = getString(R.string.account_balance_inline, formatMoney(profile.balance, profile.currency))
        } else {
            animateMoney(binding.tvBalance, previousBalance, profile.balance, profile.currency)
        }
        lastBalance = profile.balance
        // THE REFERRAL CODE IS NOT DRAWN ON THIS TAB. §5's bands are профиль → кольцо → тариф →
        // кнопки → автопродление → Управление → Выйти, and a «Реф-код …» chip wedged between the
        // first two was the owner's complaint, twice. `profile.referralCode` still arrives, the
        // referral endpoints are still wired (AccountRepository.getReferralStats) — the tab simply
        // has nowhere in its layout that shows it.
    }

    /**
     * «СПОСОБЫ ВХОДА» — one card, two rows, one rule.
     *
     * Each row names a method, states which account it is attached to, and IS the way to attach it
     * while it is not: «в аккаунте добавь кнопку где способы входа туда сайт, чтобы можно было
     * привязать почту с сайта … все в том же стиле как и кнопка телеграма там».
     *
     * THE «ПОЧТА» ROW LEADS SOMEWHERE IN BOTH OF ITS STATES. Empty, it attaches an address
     * ([LoginActivity.MODE_LINK_EMAIL]); filled, it replaces one ([LoginActivity.MODE_CHANGE_EMAIL])
     * instead of being the dead read-out it was. It used to open [LoginActivity.MODE_SITE] in the
     * empty case, from the days when proving an address meant signing in on the site, and for the
     * person who actually taps it that did NOTHING: they are signed in through Telegram, and the
     * sign-in screen finishes on sight of a session.
     *
     * …and a THIRD state between them, because an attached address is not the same thing as a
     * working way in: see the block on [renderLoginMethods]'s e-mail row below.
     *
     * THERE IS NO UNLINK, and none is drawn: the panel has no endpoint that detaches an address (it
     * detaches payment methods, which is a different noun). A control for it would be an invented
     * affordance for a task the product cannot do.
     *
     * THE TELEGRAM ROW GAINS THE SAME AFFORDANCE, and this is the gap the second row exposed: the
     * app's only «Привязать Telegram» sits on the Главная gate, which is not drawn once a подписка
     * exists — i.e. exactly when someone would come here to attach one. It runs the flow that was
     * already built and already used, [LoginActivity.EXTRA_LINK], which attaches to the session
     * instead of signing a second account in.
     *
     * A LINKED ROW IS A READ-OUT, not a dimmed control: no chevron, no press response, no tap. A
     * chevron on a row whose answer is «Привязан · @erlish» promises a screen that does not exist.
     * `clickable` and `focusable` are cleared with it, so the row also stops being a stop for
     * TalkBack's swipe and for a D-pad on TV.
     *
     * @param profile null while the tab has no session data yet — both rows report "not attached",
     * which is also the state a signed-out visitor's blank tab shows.
     */
    private fun renderLoginMethods(profile: UserProfileDto?) {
        val telegramIdentity = if (profile?.telegramLinked == true) {
            profile.telegramUsername?.takeIf { it.isNotBlank() }?.let { "@$it" }
                ?: profile.telegramName?.takeIf { it.isNotBlank() }
                ?: profile.telegramId?.toString()
                // Linked, but the backend named no handle: say the method rather than «Привязан · ».
                ?: getString(R.string.account_login_telegram)
        } else {
            null
        }
        bindLoginMethod(
            row = binding.rowLoginTelegram,
            stateView = binding.tvLoginTelegramState,
            chevron = binding.chevronLoginTelegram,
            stateText = if (telegramIdentity != null) {
                getString(R.string.account_login_telegram_linked, telegramIdentity)
            } else {
                getString(R.string.account_login_telegram_unlinked)
            },
            // Attached Telegram stays a read-out: the panel offers no way to detach it and no way
            // to swap it for another account, so a chevron here would promise a screen that does
            // not exist.
            action = if (telegramIdentity == null) ::openTelegramLink else null,
        )

        // THE «ПОЧТА» ROW HAS THREE STATES, not two, and the third one is the whole point of the
        // password step existing at all. Attaching an address does NOT give the account a password
        // (`verify-link-email` on the panel writes `email` and nothing else), so an account can sit
        // with an address it cannot sign in with — and this row used to say «Привязан» over it and
        // offer to change the address, which is fixing the wrong thing.
        //
        // `hasPassword`, not `canSetPassword()`: what the row reports is whether e-mail sign-in
        // WORKS, and it works with any password the account has. The panel's own set-password gate
        // is a different question, asked in one place — after an errand, where the step is offered.
        val email = profile?.email?.takeIf { it.isNotBlank() }
        val signInWorks = profile?.emailSignInWorks() == true
        bindLoginMethod(
            row = binding.rowLoginSite,
            stateView = binding.tvLoginSiteState,
            chevron = binding.chevronLoginSite,
            stateText = when {
                email == null -> getString(R.string.account_login_telegram_unlinked)
                signInWorks -> getString(R.string.account_login_telegram_linked, email)
                else -> getString(R.string.account_login_email_no_password, email)
            },
            action = when {
                email == null -> ::openEmailLink
                // The missing half first: offering to change an address nobody can sign in with
                // answers a question the user has not asked.
                !signInWorks -> ::openSetPassword
                else -> ::openChangeEmail
            },
        )
    }

    /**
     * One row of «Способы входа»: what it reports and where it goes, both decided by the caller.
     *
     * [action] null means the row is a read-out — no chevron, no press response, no tap, and not a
     * stop for TalkBack's swipe. It is INDEPENDENT of what the row says, and that is the change
     * this method has been through twice: «Почта» is attached and still leads somewhere, and it
     * leads to two DIFFERENT places depending on whether sign-in by e-mail actually works. Deriving
     * either the sentence or the destination from "has an identity" was only ever adequate while
     * e-mail had one state and nowhere to go.
     */
    private fun bindLoginMethod(
        row: View,
        stateView: TextView,
        chevron: View,
        stateText: CharSequence,
        action: (() -> Unit)?,
    ) {
        stateView.text = stateText
        chevron.isVisible = action != null
        row.isClickable = action != null
        row.isFocusable = action != null
        if (action == null) {
            row.setOnClickListener(null)
        } else {
            row.onSingleClick { action() }
            row.pressFeedback(R.anim.press_row)
        }
        // The row's background is a selector with a fade; a recycled or re-bound row must not
        // dissolve its previous look into this one (the rule found three times in this project).
        row.jumpDrawablesToCurrentState()
    }

    /** «Почта» when the account has none: the form that sends the «привяжите почту» letter. */
    private fun openEmailLink() = openEmailErrand(LoginActivity.MODE_LINK_EMAIL)

    /** «Почта» when it has one that works: the form that sends the letter to a NEW address. */
    private fun openChangeEmail() = openEmailErrand(LoginActivity.MODE_CHANGE_EMAIL)

    /** «Почта» when the address is there but nothing can be signed in with it: the missing half. */
    private fun openSetPassword() = openEmailErrand(LoginActivity.MODE_SET_PASSWORD)

    private fun openEmailErrand(mode: String) {
        linkEmail.launch(
            Intent(requireContext(), LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_MODE, mode)
        )
    }

    /**
     * «Привязать Telegram» — the flow that already exists, run against the session that is already
     * signed in, so the backend attaches instead of logging a second account in. Mirrors
     * `HomeFragment.openTelegramLink`.
     */
    private fun openTelegramLink() {
        startActivity(
            Intent(requireContext(), LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_MODE, LoginActivity.MODE_TELEGRAM)
                .putExtra(LoginActivity.EXTRA_LINK, true)
        )
    }

    /**
     * Counts the balance figure UP from [from] to [to] over ~motion_reveal, ease_out_quart, formatting
     * every frame through the same money formatter so the ₽ sign and tabular figures stay put. Any
     * in-flight count-up is cancelled first so rapid balance changes don't stack. Reduced motion (or a
     * no-op delta): the final value lands instantly.
     */
    private fun animateMoney(textView: TextView, from: Double, to: Double, currency: String) {
        balanceAnimator?.cancel()
        // Whole-ruble balances count on integers so the tabular figure never flickers decimals mid-flight;
        // fractional balances keep their 2-decimal shape the formatter already applies.
        val wholeOnly = from % 1.0 == 0.0 && to % 1.0 == 0.0
        val show = { value: Double ->
            val shown = if (wholeOnly) Math.round(value).toDouble() else value
            textView.text = getString(R.string.account_balance_inline, formatMoney(shown, currency))
        }
        if (textView.reducedMotion() || from == to) {
            show(to)
            return
        }
        balanceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = resources.getInteger(R.integer.motion_reveal).toLong()
            interpolator = AnimationUtils.loadInterpolator(textView.context, R.interpolator.ease_out_quart)
            addUpdateListener { anim ->
                show(from + (to - from) * anim.animatedFraction)
            }
            start()
        }
    }

    private fun renderSubscriptions(list: List<SubInfoDto>) {
        currentSubs = list
        // A shorter list must not leave the hero pointing past its end.
        if (selectedSubIndex >= list.size) selectedSubIndex = 0
        if (list.isNotEmpty()) pendingFirstLoad = false
        subAdapter.submit(list)

        val count = list.size
        // The peek padding is NOT written here — it is a constant, applied once in [setupPager].
        // Re-deriving it from the page count is what made the ring's page 32dp narrower the moment
        // the second подписка arrived, which is geometry moving while the tab loads.
        //
        // The dots are rebuilt only when their NUMBER changes; a second emission of the same list
        // (the cache, then the network) used to tear down every dot view and build it again for an
        // identical row.
        val wantedDots = if (count > 1) count else 0
        if (binding.llSubDots.childCount != wantedDots) buildDots(count) else updateDotSelection(selectedSubIndex)
        binding.llSubDots.isVisible = count > 1
        // A carousel of one is not a carousel: there is nowhere to swipe, so the gesture only
        // produces the drag the owner asked to be rid of. Written on every render rather than once
        // in setupPager, because the count is what decides it and the second подписка has to be able
        // to turn the gesture back on in the same pass that adds its dot.
        binding.vpSubscriptions.isUserInputEnabled = count > 1
        // No page measuring any more: after §5 a page IS the 172dp ring and nothing else, so the
        // pager's height is that constant and the layout states it. The old probe inflated a
        // whole card per render to arrive at a number the layout already knew.

        renderDevicesRowValue()
        // Fetch the REAL connected-device count for the ACTIVE sub and pre-warm AccountCache so the
        // Devices sub-screen opens instantly. Cache-first inside.
        activeSub()?.remnawaveUuid?.takeIf { it.isNotBlank() }?.let { viewModel.loadDevices(it) }

        renderHeroState()
    }

    /**
     * The account's ACTIVE подписка, asked for by type rather than by position.
     *
     * The merge puts the root first, so `first()` was right — until the round where it is not.
     * `/client/subscription` is what identifies the active подписка and `/client/subscription/all`
     * is the only source of the secondaries; when the first of those answers with a payload whose
     * shape we cannot read, the merge has no root to place and the list STARTS with a secondary.
     * Everything that then asked for «первая» silently described the wrong подписка: the device
     * allowance «N / M» came from a secondary's slots, and the uuid the Devices screen was
     * pre-warmed with was that secondary's too.
     *
     * Falling back to the first entry keeps the old answer for the case it was written for — an
     * account whose root simply is the only подписка — while a list that genuinely has no root
     * still describes something rather than nothing.
     */
    private fun activeSub(): SubInfoDto? =
        currentSubs.firstOrNull { it.type.equals(SubscriptionSyncManager.TYPE_ROOT, ignoreCase = true) }
            ?: currentSubs.firstOrNull()

    /**
     * Fills the management «Устройства» row's trailing «N / M» slot from the active подписка, or
     * hides it.
     *
     * `?: 0` USED TO STAND IN FOR "NOT LOADED YET", and it printed «0 / 3» — a figure that is not
     * unknown, it is WRONG, and it is wrong in the most alarming direction on a screen about a
     * device allowance. `GET /client/devices` lands a second or two after the tab opens, so that
     * is what the row said for the first second of every visit. The slot is now empty until the
     * count is real, and this method is already re-run when it arrives (observeState collects
     * `deviceCount`), so the value appears the instant it is known.
     */
    private fun renderDevicesRowValue() {
        val sub = activeSub()
        val usedDevices = viewModel.deviceCount.value
        if (sub == null || usedDevices == null) {
            binding.tvRowValueDevices.visibility = View.GONE
            return
        }
        val unlimitedDevices = sub.subscription?.raw()?.isUnlimitedDevices() == true
        val totalDevicesStr = if (unlimitedDevices) getString(R.string.account_unlimited) else sub.totalDevices.toString()
        binding.tvRowValueDevices.text = "$usedDevices / $totalDevicesStr"
        binding.tvRowValueDevices.visibility = View.VISIBLE
    }

    /**
     * Rebuilds the carousel page dots to match [count] pages (nothing shown for 0/1 page). Each dot
     * is a small View backed by dot_inactive/dot_active, sized dot_size / dot_size_active, with a
     * space_4 gap between them; the current page's dot is the active accent.
     */
    private fun buildDots(count: Int) {
        val container = binding.llSubDots
        container.removeAllViews()
        if (count <= 1) return
        val size = resources.getDimensionPixelSize(R.dimen.dot_size)
        val activeSize = resources.getDimensionPixelSize(R.dimen.dot_size_active)
        val gap = resources.getDimensionPixelSize(R.dimen.space_4)
        val current = binding.vpSubscriptions.currentItem
        for (i in 0 until count) {
            val selected = i == current
            val dim = if (selected) activeSize else size
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dim, dim).apply {
                    if (i > 0) marginStart = gap
                }
                setBackgroundResource(if (selected) R.drawable.dot_active else R.drawable.dot_inactive)
            }
            container.addView(dot)
        }
    }

    /** Swaps the dot backgrounds/sizes so only [position]'s dot reads as active. */
    private fun updateDotSelection(position: Int) {
        val container = binding.llSubDots
        val size = resources.getDimensionPixelSize(R.dimen.dot_size)
        val activeSize = resources.getDimensionPixelSize(R.dimen.dot_size_active)
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i)
            val selected = i == position
            dot.setBackgroundResource(if (selected) R.drawable.dot_active else R.drawable.dot_inactive)
            val dim = if (selected) activeSize else size
            dot.layoutParams = dot.layoutParams.apply {
                width = dim
                height = dim
            }
        }
    }

    /** The five mutually-exclusive hero children. */
    private enum class Hero { SIGNED_OUT, SKELETON, EMPTY, CAROUSEL, ERROR }

    /**
     * Shows EXACTLY ONE hero child (the other four go GONE):
     *  - SIGNED_OUT when there is no session — checked FIRST, because every other state
     *    describes an account and there is none;
     *  - CAROUSEL when there are subscriptions to show;
     *  - SKELETON while the FIRST load is still in flight (no profile yet);
     *  - ERROR on a cold-load failure (no profile, an error, not loading);
     *  - EMPTY otherwise (loaded, no subscriptions, no cold-load error).
     *
     * SIGNED_OUT also strips the bands that only mean something with a session: the profile
     * row, the two money buttons, «Управление», «Способы входа» and «Выйти». The tab is
     * reachable without a login now (someone who added a departament подписка from the
     * clipboard), and offering «Купить подписку» or «Выйти из аккаунта» to a visitor with no
     * account is offering controls that cannot work.
     *
     * THE COLD LOAD IS NOT OVER UNTIL THE SUBSCRIPTIONS HAVE ANSWERED, and that is the whole of
     * «вместо кольца с трафиком появляется какой-то прямоугольник, потом только кольцо».
     *
     * The rectangle was never a skeleton. `group_sub_skeleton` is, and always was, the ring's own
     * empty track at the ring's own 172dp — the fix the previous round made, and it holds. What the
     * owner watched was the sequence: the profile is ONE request and the subscription list is TWO,
     * so the profile lands first; [renderProfile] cleared `pendingFirstLoad` on its arrival;
     * `subs` was still the seed `emptyList()` because nothing had come back for it yet — and this
     * `when` fell straight through to EMPTY, which is a bordered card saying «нет активной
     * подписки». Rectangle, in the ring's slot, for as long as the second and third requests took,
     * and then the ring. The state machine was answering a question it did not have the data for.
     *
     * So the gate is now BOTH halves of the first load: the profile must have landed AND the list
     * must have RESOLVED ([AccountViewModel.subsResolved] — resolved, not merely empty, which is a
     * distinction the list flow itself cannot express). While either is outstanding the slot holds
     * the ring, motionless, exactly as §5 asks. An error still ends the wait: [renderError] clears
     * `pendingFirstLoad`, and a failed subscription fetch resolves the flag too.
     */
    private fun renderHeroState() {
        val subs = currentSubs
        val profile = latestProfile
        val error = viewModel.error.value
        val loading = viewModel.loading.value
        val firstLoadPending = pendingFirstLoad || !viewModel.subsResolved.value
        val coldLoading = firstLoadPending || loading
        val signedIn = AccountSession.isLoggedIn()
        val state = when {
            !signedIn -> Hero.SIGNED_OUT
            subs.isNotEmpty() -> Hero.CAROUSEL
            coldLoading && (profile == null || firstLoadPending) -> Hero.SKELETON
            profile == null && error != null -> Hero.ERROR
            else -> Hero.EMPTY
        }
        // The whole hero slot leaves in SIGNED_OUT: the sign-in block is no longer one of its
        // children (it is the tab's own centred band now), so leaving the slot behind would keep
        // its 20dp top margin in a composition whose entire point is being centred.
        binding.heroSlot.isVisible = state != Hero.SIGNED_OUT
        binding.groupSubSkeleton.isVisible = state == Hero.SKELETON
        binding.groupSubEmpty.isVisible = state == Hero.EMPTY
        binding.groupSubCarousel.isVisible = state == Hero.CAROUSEL
        binding.groupAccountError.isVisible = state == Hero.ERROR

        // Everything that needs a session. GONE and not disabled: a greyed-out «Выйти из
        // аккаунта» still claims there is an account to leave.
        val sessionBands = state != Hero.SIGNED_OUT
        // The sign-in offer and the two weighted spacers that float it are one unit: a spacer left
        // behind would take its share of the viewport with nothing in it.
        binding.groupSignedOut.isVisible = !sessionBands
        binding.signedOutLead.isVisible = !sessionBands
        binding.signedOutTrail.isVisible = !sessionBands
        binding.rowProfile.isVisible = sessionBands
        binding.llSubActions.isVisible = sessionBands
        binding.tvManageTitle.isVisible = sessionBands
        binding.cardManage.isVisible = sessionBands
        binding.tvLoginMethodsTitle.isVisible = sessionBands
        binding.cardLoginMethods.isVisible = sessionBands
        binding.cardLogout.isVisible = sessionBands

        renderSelectedSub()
    }

    /**
     * EVERYTHING BELOW THE RING, FOR THE PAGE THAT IS SHOWING (handoff README §5.3–§5.5).
     *
     * The redesign lays the tab out as one vertical spine and the carousel keeps only the traffic
     * ring, so the подписка's name, its tariff badge, its срок and its auto-renew row are single
     * views on this screen that follow the visible page. This is what [SubscriptionPagerAdapter]
     * used to do per page, and doing it once is the point: «Пополнить» is an account-level action
     * and repeating it on every page offered the same thing N times.
     *
     * The two buttons are NOT hidden when there is no подписка. «Пополнить» tops up a balance that
     * exists either way, and «Продлить» opens the same tariff screen the empty state's CTA does —
     * a row of controls that disappears is how a user concludes the account cannot be paid for.
     */
    private fun renderSelectedSub() {
        val b = _binding ?: return
        val sub = (currentSubs.getOrNull(selectedSubIndex) ?: currentSubs.firstOrNull())
            ?.takeIf { b.groupSubCarousel.isVisible }
        if (sub == null) {
            b.tvSubName.isVisible = false
            b.tvTariffBadge.isVisible = false
            b.cardAutorenew.isVisible = false
            renderTimeBlock()
            return
        }

        // Name: THE SAME RANKING THE CARD ON ГЛАВНАЯ USES, and for the same reason.
        //
        // This line read `displayName ?: tariffDisplayName ?: defaultLabel`, and the middle term is
        // the generic service label «departament vpn» — one string on every подписка of this
        // deployment. It is refused for the tariff badge two blocks below (`tariffBadgeName`), it is
        // refused by the import (`SubscriptionSyncManager`), and it is in
        // `SubscriptionNaming.PLACEHOLDERS` — yet here it outranked everything but the nickname. So
        // Главная named the подписка «🍀 erlish» and Аккаунт named it «departament vpn».
        //
        // The resolver also reaches the провайдер's own `profile-title`, which lives on the LOCAL
        // подписка and is not in this payload at all, and it ends in «Подписка» — so the line is
        // still never blank.
        b.tvSubName.isVisible = true
        b.tvSubName.text = SubscriptionNaming.titleOf(requireContext(), sub)

        // Badge resolution order: catalog by tariffId, then catalog by the renewing price-option id
        // (correct after a Base→Plus upgrade), then the sub's own non-generic display name.
        // Null/blank HIDES the badge so a WRONG tariff is never shown.
        val badge = viewModel.tariffNameFor(sub.tariffId)
            ?: viewModel.tariffNameForPriceOptionId(sub.tariffPriceOptionId)
            ?: sub.tariffBadgeName()
        if (badge.isNullOrBlank()) {
            b.tvTariffBadge.isVisible = false
        } else {
            b.tvTariffBadge.text = badge
            b.tvTariffBadge.isVisible = true
        }

        renderAutoRenew(sub)
        renderTimeBlock()
    }

    /**
     * The auto-renew row: the switch's position and the line that says what it will do next.
     *
     * THE ROW IS HIDDEN WHEN THE TOGGLE CANNOT BE HONOURED. `id` is the path segment the secondary
     * endpoint needs, and with it blank the switch would flip under the finger and then report a
     * failure it could have known about before it was ever offered. The ROOT подписка is addressed
     * by its own id-less endpoint and never needs one. A control that cannot act is not disabled,
     * it is absent — a disabled switch still claims the feature exists for this подписка.
     *
     * No listener juggling here, and that is the payoff of §5's «нажимается вся строка»: the switch
     * has no listener at all, so writing its state can never be mistaken for a user decision the
     * way a re-bound `setChecked` on a recycled holder could.
     */
    private fun renderAutoRenew(sub: SubInfoDto) {
        val b = _binding ?: return
        val actionable =
            sub.type.equals(SubscriptionSyncManager.TYPE_ROOT, ignoreCase = true) || sub.id.isNotBlank()
        b.cardAutorenew.isVisible = actionable
        if (!actionable) return
        // Read back from the account, never chosen here (the row owns the tap), so the position is
        // restored rather than played — this render is the tab's first frame. @see restoreChecked
        b.switchSubAutorenew.restoreChecked(sub.autoRenewEnabled)
        b.tvSubAutorenew.text = when {
            !sub.autoRenewEnabled -> getString(R.string.account_sub_autorenew_off)
            sub.renewalPrice != null && !sub.expireAtIso.isNullOrBlank() -> getString(
                R.string.account_sub_autorenew_next,
                formatIsoDate(sub.expireAtIso),
                formatMoney(sub.renewalPrice, sub.tariffCurrency.orEmpty()),
            )

            else -> getString(R.string.account_sub_autorenew_on)
        }
    }

    /**
     * The tab's ONE Display figure, and it is time — not money (23-account-rework.md 1.2). The user
     * opens Аккаунт because the подписка is running out; the wallet answers that question only
     * indirectly, so the balance dropped to a labelled value and this took the hero slot.
     *
     * The silhouette is CONSTANT in every variant that has a date — label, figure + word, optional
     * detail — and only the COLOUR changes with health, so the card does not reflow as an account
     * ages. Above 30 days it states the date and stops; from 8 to 30 it adds the count as a detail
     * line; at 7 and below the count BECOMES the figure and the date drops to the detail, because
     * that is the window in which time turns into a decision.
     *
     * Perpetual and unknown have no date to set, so they render two text lines instead and the tab
     * shows no Display figure at all — the only two states in which that is true.
     */
    private fun renderTimeBlock() {
        val b = _binding ?: return
        val sub = currentSubs.getOrNull(selectedSubIndex) ?: currentSubs.firstOrNull()
        if (sub == null || !b.groupSubCarousel.isVisible) {
            b.llTimeBlock.isVisible = false
            return
        }
        b.llTimeBlock.isVisible = true

        val onSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val amber = resolveThemeColor(R.attr.warning)
        val red = resolveThemeColor(R.attr.colorDestructiveText)

        val date = parseYmd(sub.expireAtIso)
        val daysLeft = date?.let { daysUntil(it) }

        // Two-line variants: nothing to set as a figure.
        if (date == null || daysLeft == null) {
            renderTimeLines(R.string.account_time_unknown, R.string.account_time_unknown_sub, onSurface)
            return
        }
        if (date.year >= PERPETUAL_YEAR || daysLeft > PERPETUAL_DAYS) {
            renderTimeLines(R.string.account_time_perpetual, R.string.account_time_perpetual_sub, onSurface)
            return
        }

        b.tvTimeLabel.isVisible = true
        b.tvTimeFigure.isVisible = true
        b.tvTimeWord.isVisible = true

        val dateWord = monthWord(date)
        when {
            daysLeft < 0 -> {
                b.tvTimeLabel.setText(R.string.account_time_expired)
                b.tvTimeLabel.setTextColor(red)
                b.tvTimeFigure.text = date.day.toString()
                b.tvTimeWord.text = dateWord
                b.tvTimeDetail.isVisible = false
                tintFigure(red)
            }

            daysLeft == 0 -> {
                b.tvTimeLabel.setText(R.string.account_time_today)
                b.tvTimeLabel.setTextColor(amber)
                b.tvTimeFigure.text = date.day.toString()
                b.tvTimeWord.text = dateWord
                b.tvTimeDetail.setText(R.string.account_time_detail_today)
                b.tvTimeDetail.isVisible = true
                tintFigure(amber)
            }

            daysLeft <= URGENT_DAYS -> {
                b.tvTimeLabel.setText(R.string.account_time_left)
                b.tvTimeLabel.setTextColor(amber)
                b.tvTimeFigure.text = daysLeft.toString()
                b.tvTimeWord.text = dayWord(daysLeft)
                b.tvTimeDetail.text = getString(R.string.account_time_detail_until, longDate(date))
                b.tvTimeDetail.isVisible = true
                tintFigure(amber)
            }

            else -> {
                // THE DESIGN'S OWN SENTENCE, and the only branch it writes: §5.3 puts
                // «Действует до 04.06.2099» under the tariff name — one line, numeric date, no
                // split figure. The label carries all of it and the figure stands down, which is
                // exactly the shape renderTimeLines already uses for the date-less variants.
                b.tvTimeLabel.text = getString(R.string.account_time_valid_until, shortDate(date))
                b.tvTimeLabel.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                b.tvTimeFigure.isVisible = false
                b.tvTimeWord.isVisible = false
                if (daysLeft <= COUNTDOWN_FROM_DAYS) {
                    val count = dayWord(daysLeft)
                    b.tvTimeDetail.text = getString(R.string.account_time_detail_left, daysLeft, count)
                    b.tvTimeDetail.isVisible = true
                } else {
                    b.tvTimeDetail.isVisible = false
                }
            }
        }
    }

    /** The date-less variants: a Title line and a Subtitle line, no figure, no label. */
    private fun renderTimeLines(titleRes: Int, subtitleRes: Int, color: Int) {
        val b = _binding ?: return
        b.tvTimeLabel.isVisible = false
        b.tvTimeFigure.isVisible = false
        b.tvTimeWord.isVisible = true
        b.tvTimeWord.setText(titleRes)
        b.tvTimeWord.setTextColor(color)
        b.tvTimeDetail.setText(subtitleRes)
        b.tvTimeDetail.isVisible = true
    }

    /** Colour is the state channel here, so the figure and its word always move together. */
    private fun tintFigure(color: Int) {
        val b = _binding ?: return
        b.tvTimeFigure.setTextColor(color)
        b.tvTimeWord.setTextColor(color)
    }

    /**
     * «день / дня / дней» for [count], chosen by the Russian rule in code rather than by a
     * `<plurals>` set (23-account-rework.md 4.4).
     *
     * The tab's copy is Russian for every user, but the app follows the SYSTEM locale until the
     * user picks one (`SettingsManager.getLocale` → `Language.AUTO`), and `getQuantityString`
     * selects its form from the CONFIGURATION locale, not from the folder the words live in. On an
     * English-locale phone that applied English rules — which have only `one` and `other` — to
     * Russian words, so 5, 6 and 7 all came out «дня» («Осталось 5 дня») across most of the very
     * window this hero exists to serve, and 21 came out «Осталось 21 дня». Selecting here is
     * locale-proof, and it is the same rule the desktop's `Plural.cs` runs, so the two clients say
     * the same word for the same number.
     */
    private fun dayWord(count: Int): String {
        val forms = resources.getStringArray(R.array.account_day_forms)
        if (forms.isEmpty()) return ""
        val n = Math.abs(count)
        val hundreds = n % 100
        val units = n % 10
        val index = when {
            hundreds in 11..14 -> MANY
            units == 1 -> ONE
            units in 2..4 -> FEW
            else -> MANY
        }
        return forms.getOrNull(index) ?: forms.last()
    }

    /**
     * The word beside the figure: a genitive month, plus the year when it is not this one. Set in
     * the UI face, never the figure face — Space Grotesk carries no Cyrillic at all
     * (23-account-rework.md 4.2), so a Russian month in it would render as fallback glyphs.
     */
    private fun monthWord(date: Ymd): String {
        val months = resources.getStringArray(R.array.account_months_genitive)
        val name = months.getOrNull(date.month - 1) ?: return date.year.toString()
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        return if (date.year == thisYear) name else getString(R.string.account_month_year, name, date.year)
    }

    /** «3 августа» — one phrase, one face, for the detail line. */
    private fun longDate(date: Ymd): String = getString(R.string.account_date_long, date.day, monthWord(date))

    /**
     * «04.06.2099» — the numeric date the design writes after «Действует до», zero-padded so a
     * column of dates on the tab keeps the same width whatever the day is. Not localised on
     * purpose: it is the same dd.MM.yyyy the payment history and the auto-renew line already
     * print, and three date shapes on one tab is what a user reads as three different things.
     */
    private fun shortDate(date: Ymd): String =
        String.format(Locale.US, "%02d.%02d.%04d", date.day, date.month, date.year)

    private fun resolveThemeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /* THE SKELETON DOES NOT PULSE ANY MORE, and that is the whole of «оно ... подрагивает».
       It used to loop its alpha 0.45↔1.0 forever on a 900ms REVERSE animator; on a screen whose
       loading placeholder IS the object being loaded, that reads as the ring flickering rather
       than as progress. What it draws now — @drawable/bg_acc_skeleton_ring at the ring's exact
       172dp — is what CircularProgressIndicator draws at progress 0, so the hand-off from
       "loading" to "loaded" changes the arc's FILL and nothing else: no size change, no fade, no
       animator left running on a tab the user has navigated away from. */

    /** Fills the history row's trailing slot with the most recent payment date, or hides it. */
    private fun renderHistoryValue(payments: List<PaymentDto>) {
        val latestIso = payments.maxByOrNull { it.createdAt }?.createdAt
        val date = formatIsoDate(latestIso)
        if (date.isBlank()) {
            binding.tvRowValueHistory.visibility = View.GONE
        } else {
            binding.tvRowValueHistory.text = date
            binding.tvRowValueHistory.visibility = View.VISIBLE
        }
    }

    private fun renderError(error: ApiError?) {
        if (error == null) {
            renderHeroState()
            return
        }
        pendingFirstLoad = false
        // A COLD-LOAD failure (nothing to show yet) is surfaced by the error hero card, NOT a toast;
        // keep the error set so the card stays until the user retries. A payment/top-up diagnostic
        // still wins even in that state.
        val coldLoad = latestProfile == null && currentSubs.isEmpty()
        if (coldLoad && !awaitingPaymentError) {
            renderHeroState()
            return
        }
        // A purchase/top-up just failed: show the REAL backend reason so it can be screenshotted.
        // Everything else keeps the friendly RU toast.
        if (awaitingPaymentError) {
            awaitingPaymentError = false
            showPaymentErrorDialog(error)
        } else {
            toastError(messageFor(error))
        }
        viewModel.clearError()
    }

    /** Dialog with the raw HTTP code + sanitized backend detail for a failed payment. */
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_payment_error_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun messageFor(error: ApiError): Int = when (error) {
        is ApiError.ServiceUnavailable -> R.string.account_error_service_unavailable
        is ApiError.Network -> R.string.account_error_network
        is ApiError.Unauthorized -> R.string.account_error_unauthorized
        is ApiError.RateLimited -> R.string.account_error_rate_limited
        is ApiError.Timeout -> R.string.account_error_timeout
        else -> R.string.account_error_generic
    }

    // endregion

    // region actions

    /**
     * The ONE top-up dialog, opened from both of its entry points: the «Пополнить» button under
     * the ring and the balance chip in the profile row. Both call this — there is no second copy,
     * so a change to the field or the confirm lands on both by construction.
     *
     * THE BUTTONS ARE IN THE VIEW, not in the AlertDialog's button bar. The bar draws Material's
     * own text buttons, which is the one place in this flow that would still look like a system
     * dialog after «Оплатить» and «Пополнить» were brought onto the account's contour pill. Doing
     * it here also means the amount can be VALIDATED WITHOUT CLOSING: a bad figure now says so and
     * leaves the dialog open with the number still in it, where the old positive button dismissed
     * first and complained afterwards, so the user had to type it all again.
     */
    private fun showTopUpDialog() {
        val dialogBinding = DialogTopUpBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_top_up_title)
            .setView(dialogBinding.root)
            .create()
        dialogBinding.btnTopUpCancel.onSingleClick { dialog.dismiss() }
        dialogBinding.btnTopUpConfirm.onSingleClick {
            val amount = dialogBinding.etTopUp.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount != null && amount > 0.0) {
                dialog.dismiss()
                showPaymentMethodSheet(amount)
            } else {
                toastError(R.string.account_top_up_invalid)
            }
        }
        dialog.show()
    }

    /**
     * Lets the user pick a Platega payment method (СБП / карта) for the entered top-up amount.
     * The balance option is deliberately withheld (balanceLabel = null): a top-up ADDS to the
     * balance, so paying it FROM the balance would be circular.
     *
     * The amount rides ALONG with the pick rather than being captured in a callback, so the charge
     * is right even if this fragment's view is rebuilt while the sheet is open (D11).
     */
    private fun showPaymentMethodSheet(amount: Double) {
        val methods = viewModel.publicConfig.value?.plategaMethods?.map { it.id to it.label } ?: emptyList()
        if (methods.isEmpty()) {
            toastError(R.string.account_checkout_no_browser)
            return
        }
        PaymentMethodSheet.show(
            parentFragmentManager,
            REQUEST_TOP_UP_METHOD,
            getString(R.string.account_top_up_title),
            null,
            methods,
            Bundle().apply { putDouble(EXTRA_TOP_UP_AMOUNT, amount) },
        )
    }

    private fun onTopUpMethodPicked(result: Bundle) {
        val id = result.getString(PaymentMethodSheet.RESULT_METHOD_ID) ?: return
        val amount = result.getDouble(EXTRA_TOP_UP_AMOUNT, 0.0)
        if (amount <= 0.0) {
            toastError(R.string.account_top_up_invalid)
            return
        }
        if (viewModel.paymentInFlight.value) return
        if (id == PaymentMethodSheet.ID_BALANCE) {
            viewModel.payWithBalance(PaymentRequestDto(amount = amount, currency = "RUB")) { outcome ->
                // «Баланс пополнён» is a claim that the money moved, so it is said only when the
                // backend says so. A payment the provider is still settling gets its own line —
                // the reload below then brings the real figure in when it lands.
                if (outcome == PaymentOutcome.PENDING) {
                    toast(R.string.account_pay_pending)
                } else {
                    toastSuccess(R.string.account_top_up_success)
                }
                viewModel.refreshProfile()
                viewModel.loadSubscriptions()
            }
        } else {
            awaitingPaymentError = true
            viewModel.buy(
                PaymentRequestDto(amount = amount, currency = "RUB", paymentMethod = id.toIntOrNull()),
                onInit = ::openCheckout,
            )
        }
    }

    /**
     * «Пополнить» while a charge is in flight: it stops taking taps and says what it is doing, so
     * a slow provider is never mistaken for a dead control (D10). The request itself refuses to
     * run twice regardless — see [AccountViewModel.paymentInFlight].
     */
    private fun renderTopUpBusy(busy: Boolean) {
        val b = _binding ?: return
        b.btnTopUp.isEnabled = !busy
        b.btnTopUp.text = getString(if (busy) R.string.account_pay_in_progress else R.string.account_top_up)
    }

    // region sign out

    /**
     * 00-rules.md 7.5 prefers act-plus-undo over a confirmation, and this is the case it exempts:
     * signing out is not usefully undoable. "Отменить" would have to re-import the subscription
     * servers, restart their auto-update workers and re-establish the tunnel it had just torn
     * down, from a token it has already destroyed. So a confirm dialog is correct here, and it
     * follows the library's rules: the primary action is the verb it performs, «Выйти», never
     * «OK» and never a Да/Нет pair; cancel sits on the left and holds focus, so an Enter on a
     * keyboard or a TV remote can never sign somebody out by reflex.
     *
     * The body has two shapes because the tab does: it says the tunnel will drop only when there
     * is a tunnel to drop.
     */
    private fun confirmSignOut() {
        val body = if (viewModel.isTunnelRunning()) {
            R.string.account_logout_body_connected
        } else {
            R.string.account_logout_body
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_logout_title)
            .setMessage(body)
            .setNegativeButton(R.string.account_logout_cancel, null)
            .setPositiveButton(R.string.account_row_logout) { _, _ -> beginSignOut() }
            .create()
        dialog.show()
        // Departament.Dialog.Button.Destructive (styles.xml) is Departament.Dialog.Button plus
        // exactly this colour. MaterialAlertDialog styles all three buttons from the theme
        // overlay, so applying the one delta here beats replacing the button bar with a custom
        // view to get a per-button style.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            setTextColor(MaterialColors.getColor(this, R.attr.colorDestructiveText))
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }

    /**
     * Runs the sign-out. Acknowledgement is immediate (the dialog closes and the row stops taking
     * taps, so the control is never left pressed with no response); the trailing spinner is
     * scheduled 300ms out and usually never appears, because the work is local (00-rules.md 7.3).
     *
     * There is no success branch here on purpose: the session flipping to LoggedOut is what ends
     * the busy state, via [onSessionCleared], and the visible change is the confirmation. A
     * Snackbar saying "you signed out" on top of a screen that just emptied itself is noise.
     */
    private fun beginSignOut() {
        val b = _binding ?: return
        b.rowLogout.isClickable = false
        b.rowLogout.contentDescription = getString(R.string.account_logout_progress)
        signOutSpinnerJob?.cancel()
        signOutSpinnerJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300L)
            _binding?.pbLogout?.isVisible = true
        }
        viewModel.logout(onFailure = ::onSignOutFailed)
    }

    /** Clears the busy state. Safe to call when the view is already gone. */
    private fun endSignOutBusy() {
        signOutSpinnerJob?.cancel()
        signOutSpinnerJob = null
        _binding?.let {
            it.pbLogout.isVisible = false
            it.rowLogout.isClickable = true
            it.rowLogout.contentDescription = getString(R.string.account_row_logout)
        }
    }

    /**
     * The sign-out did not complete: either the wipe threw, or it stopped making progress long
     * enough that [AccountViewModel.logout]'s watchdog stopped waiting on it. Both leave the user
     * signed in with nothing half-removed that a retry cannot finish (every step of the wipe is
     * idempotent), and both read the same from here, so they get one message and one action
     * rather than a diagnosis the user cannot act on differently.
     *
     * This is the branch that keeps a stalled sign-out from being a spinner with no end: the row
     * goes back to being tappable, the spinner stops, and the way forward is on screen.
     *
     * 00-rules.md 1.4.8: a failure the user can act on is a Snackbar with an action, never a
     * Toast. Anchored above the bottom bar so the action is reachable. Held in [signOutBar] so a
     * late-finishing wipe can dismiss it instead of leaving a contradiction on screen.
     */
    private fun onSignOutFailed() {
        endSignOutBusy()
        val b = _binding ?: return
        val bar = Snackbar.make(b.root, R.string.account_logout_failed, Snackbar.LENGTH_LONG)
            .setAction(R.string.account_retry) { beginSignOut() }
        // Only anchor to a bar that is actually on screen; the bottom nav is hidden during
        // onboarding, and anchoring to a gone view drops the snackbar off the bottom edge.
        activity?.findViewById<View>(R.id.bottom_nav)?.takeIf { it.isVisible }
            ?.let { bar.setAnchorView(it) }
        signOutBar = bar
        bar.show()
    }

    // endregion

    // region avatar

    private fun showAvatarOptions() {
        val hasCustom = AvatarManager.hasCustomAvatar(requireContext())
        val items = if (hasCustom) {
            arrayOf(getString(R.string.account_avatar_gallery), getString(R.string.account_avatar_remove))
        } else {
            arrayOf(getString(R.string.account_avatar_gallery))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_change_avatar)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchAvatarPicker()
                    1 -> {
                        AvatarManager.clearCustomAvatar(requireContext())
                        AvatarManager.applyAvatar(viewLifecycleOwner.lifecycleScope, requireContext(), binding.imgAvatar, binding.tvAvatarInitial, latestProfile)
                        toast(R.string.account_avatar_updated)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchAvatarPicker() {
        try {
            pickAvatar.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            toastError(R.string.account_avatar_error)
        }
    }

    /**
     * The picked photo is decoded, down-sampled and re-compressed to JPEG before it is stored, and
     * ALL of that used to run on the main thread — on whatever a modern camera produces, i.e. tens
     * of megapixels read through a ContentResolver. That is the frame budget for a dropped
     * hundred-odd milliseconds at best and an ANR at worst, for a cosmetic action.
     *
     * It moves to IO, and the result comes back on the main thread to paint. The gallery has
     * already closed by the time this runs, so nothing on screen is waiting on it; the picker's
     * read grant is one-shot but it lasts for the life of this callback's coroutine, which is what
     * the copy needs. `viewLifecycleOwner` scopes it, so a tab torn down mid-copy simply stops.
     */
    private fun onAvatarPicked(uri: Uri?) {
        if (uri == null) return
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { AvatarManager.saveCustomAvatar(context, uri) }
            val b = _binding ?: return@launch
            if (saved) {
                AvatarManager.applyAvatar(viewLifecycleOwner.lifecycleScope, requireContext(), b.imgAvatar, b.tvAvatarInitial, latestProfile)
                toast(R.string.account_avatar_updated)
            } else {
                toastError(R.string.account_avatar_error)
            }
        }
    }

    // endregion

    // endregion

    // region checkout

    /** Opens the provider checkout URL. Never logs the URL. */
    private fun openCheckout(init: PaymentInitDto) {
        // The payment request itself succeeded (a checkout URL was issued): drop the diagnostic arm.
        awaitingPaymentError = false
        val url = init.paymentUrl
        if (url.isBlank()) {
            toastError(R.string.account_checkout_no_browser)
            return
        }
        val uri = Uri.parse(url)
        pendingPayment = true
        try {
            CustomTabsIntent.Builder().build().launchUrl(requireContext(), uri)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: ActivityNotFoundException) {
                pendingPayment = false
                toastError(R.string.account_checkout_no_browser)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingPayment) startPaymentPolling()
        // BACK FROM TELEGRAM. The user confirming in another app is the only thing that moves the
        // wait's second step, and this is the tab's version of the window-focus signal the start
        // screen uses. It is a no-op unless a flow is running and the link has actually been
        // opened, so returning from a checkout — the other thing that leaves and comes back —
        // cannot be mistaken for it.
        telegramFlow.onReturn()
    }

    /**
     * After returning from a checkout, re-poll subscriptions/profile a few times over ~48s. The
     * backend confirms PAID only via webhook — the tab returning proves nothing.
     */
    private fun startPaymentPolling() {
        if (pollJob?.isActive == true) return
        binding.tvPending.visibility = View.VISIBLE
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(6) {
                viewModel.loadSubscriptions()
                viewModel.refreshProfile()
                delay(8000L)
            }
            pendingPayment = false
            binding.tvPending.visibility = View.GONE
        }
    }

    // endregion

    private companion object {
        /** Fragment-result key for the top-up method pick. */
        const val REQUEST_TOP_UP_METHOD = "account_top_up_method"

        /** The amount travels with the pick, so a rebuilt view still charges the right sum. */
        const val EXTRA_TOP_UP_AMOUNT = "extra_top_up_amount"

        /** Mirror of the desktop's IsEffectivelyPerpetual: a sentinel expiry, not a real date. */
        const val PERPETUAL_YEAR = 2099
        const val PERPETUAL_DAYS = 3650

        /** At and below this the day COUNT becomes the hero figure (23-account-rework.md 4.3). */
        const val URGENT_DAYS = 7

        /** Above this a count is machine output — the card states the date and stops. */
        const val COUNTDOWN_FROM_DAYS = 30

        /** Indices into R.array.account_day_forms; the array is ordered ONE, FEW, MANY. */
        const val ONE = 0
        const val FEW = 1
        const val MANY = 2
    }
}

/** A calendar date, which is the granularity the user reads an expiry at. */
private data class Ymd(val year: Int, val month: Int, val day: Int)

/**
 * Takes the DATE part of an ISO-8601 timestamp and reads it as a local calendar date.
 *
 * Deliberately not an instant: the card says «Активна до 3 августа», and the day a подписка ends is
 * what the user is counting, not the wall-clock moment inside it. Parsing the offset would buy
 * precision nobody reads and a timezone bug on every device that is not on the backend's clock.
 */
private fun parseYmd(iso: String?): Ymd? {
    if (iso.isNullOrBlank()) return null
    val parts = iso.substringBefore('T').substringBefore(' ').split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    return Ymd(year, month, day)
}

/**
 * Whole days from today to [target] in the device's own zone: negative once it is past, 0 on the
 * day itself. Both ends are snapped to midnight and the quotient is rounded, so the hour a DST
 * change adds or removes cannot move a boundary by a day.
 */
private fun daysUntil(target: Ymd): Int {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayMs = cal.timeInMillis
    cal.set(target.year, target.month - 1, target.day, 0, 0, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val targetMs = cal.timeInMillis
    return Math.round((targetMs - todayMs).toDouble() / 86_400_000.0).toInt()
}

/**
 * THE FRACTION IS SEPARATED BY A COMMA, because the interface is Russian and everything else on
 * this tab already does it: the ring writes «2,0 ТБ» and «1,9 ТБ» from
 * [SubscriptionPagerAdapter.formatBytes], which composes the number under an explicit locale and
 * then swaps the point. A sum that came out «135.29 ₽» beside them is the single detail that makes
 * a screen read as untranslated.
 *
 * The composition stays under [Locale.US] on purpose — the grouping and the digit shapes must not
 * follow the phone's locale, which can be Farsi or Bengali — and only the decimal mark is swapped,
 * which is the same two-step the byte formatter uses.
 */
private fun formatMoney(amount: Double, currency: String): String {
    val n = if (amount % 1.0 == 0.0) amount.toLong().toString()
    else String.format(Locale.US, "%.2f", amount).replace('.', ',')
    return "$n ${currencySymbol(currency)}"
}

/**
 * Maps a currency CODE to its display symbol. This is a RUB-only product: the backend can return
 * "USD" (or a blank/unknown value) for accounts created via the site, but the balance is always in
 * rubles, so "RUB", "", "USD" and anything unrecognised all render as the ruble sign. Only genuinely
 * distinct currencies keep their own symbol.
 */
private fun currencySymbol(currency: String): String = when (currency.trim().uppercase(Locale.US)) {
    "EUR" -> "€"
    "KZT" -> "₸"
    "UAH" -> "₴"
    // "RUB", "", "USD" and any unknown value → treat as rubles.
    else -> "₽"
}

private fun formatIsoDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val datePart = iso.substringBefore('T')
    val parts = datePart.split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else datePart
}
