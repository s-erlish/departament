package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.auth.AccountRepository
import com.v2ray.ang.auth.AccountSession
import com.v2ray.ang.auth.AuthTokenStore
import com.v2ray.ang.auth.BackendConfig
import com.v2ray.ang.auth.SubscriptionSyncManager
import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.FragmentHomeBinding
import com.v2ray.ang.databinding.LayoutSubscriptionMetaBarBinding
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.hasUserInfo
import com.v2ray.ang.dto.entities.isExpired
import com.v2ray.ang.dto.entities.isUnlimited
import com.v2ray.ang.dto.entities.trafficFraction
import com.v2ray.ang.dto.entities.usedTraffic
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionNaming
import com.v2ray.ang.ui.component.GateView
import com.v2ray.ang.ui.component.Haptic
import com.v2ray.ang.ui.component.HomeHandoff
import com.v2ray.ang.ui.component.RunningAnimators
import com.v2ray.ang.ui.component.SelectPopup
import com.v2ray.ang.ui.component.SkeletonBinder
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.pressFeedback
import com.v2ray.ang.util.AvatarManager
import com.v2ray.ang.util.FlagUtil
import com.v2ray.ang.util.confirmHaptic
import com.v2ray.ang.util.reducedMotion
import com.v2ray.ang.util.tickHaptic
import com.v2ray.ang.viewmodel.AccountViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Главная — the screen the app opens on.
 *
 * **THIS SCREEN IS A RESTYLE, NOT A REDESIGN**, and the distinction is the reason this file reads
 * the way it does. A wave rebuilt Главная from `docs/design2026/13-start-screen.md`, dropped the
 * inline server list, the subscription card, the session clock and the connect object's rings and
 * motion, and replaced them with two rows that navigate away. The owner ran that build and rejected
 * it — «как выглядела главная по функционалу такая и должна остаться», «все должно быть вернуто с
 * пилюлей и инфой о подписке под кнопкой». That document now carries an OWNER OVERRULE: its scope is
 * void and only its visual language survives.
 *
 * So everything that screen had is here, drawn on the component layer with tokens and the type ramp:
 *
 *  - the top strip — отдача, время сессии, приём — and the add action, on one line;
 *  - the connect object with its three concentric rings, its negotiating breath and sweep, its
 *    one-shot confirm ring, its press scale and its cold-start assemble;
 *  - the country flag and the server identity under it;
 *  - the subscription card carousel — traffic pill, name, auto-update stamp, the operator's notice,
 *    support and Telegram, refresh, ping, pin, delete;
 *  - the server list, inline, which is the ONLY place in the product a server can be picked.
 *
 * **The fragment renders, it does not decide twice.** [resolveState] reads every input once and
 * returns one [HomeState]; [render] applies it and branches on nothing else.
 *
 * **It owns the CONNECT STATE MACHINE, unchanged in behaviour**: the tap handler, the VPN permission
 * prompt, the connect watchdog, the restart-that-waits-for-a-real-stop, the one-shot auto-fallback
 * with its confirmation re-probe. That state machine is why this fragment is attached at LAUNCH
 * rather than the first time its tab is opened (`MainActivity.syncTabFragments`): a hidden fragment
 * is still RESUMED, so the tunnel is still observed and «Перезапустить» after a core-config change
 * still reaches a running core.
 *
 * The tab's view outlives nothing and outlasts every bind — the shell hides and shows tabs rather
 * than replacing them — so every async callback checks [isBindingInitialized] before touching a view
 * and every posted callback is removed in [onDestroyView].
 */
class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    // ==================== The model ====================

    /** The connection as the screen draws it. Exactly one is true at a time. */
    private enum class Conn { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR, NO_SERVER, GATED }

    /**
     * The gate block's shapes. A gate exists ONLY when the subscription card and the server list
     * would have nothing true to show, which on this product means "there is not one server to
     * connect to".
     */
    private enum class Gate { SIGN_IN, ADD_SUBSCRIPTION, BUY, SYNC_SERVERS, SYNC_FAILED }

    /** What the account knows about the subscription. [Unknown] is "still resolving". */
    private sealed interface Sub {
        data object Unknown : Sub
        data object None : Sub
        data class Active(val untilMs: Long?) : Sub
        data class Trial(val untilMs: Long) : Sub
        data class Expiring(val untilMs: Long, val daysLeft: Int) : Sub
        data class Expired(val sinceMs: Long) : Sub
    }

    private enum class Severity { INFO, WARN, ERROR }

    /** The single strip condition, already resolved by priority. Never a list. */
    private data class Condition(
        val text: CharSequence,
        val severity: Severity,
        val actionLabel: CharSequence? = null,
        val action: (() -> Unit)? = null,
    )

    private data class HomeState(
        val conn: Conn,
        val gate: Gate?,
        val accountLoading: Boolean,
        val serverName: String?,
        val serverFlag: String?,
        val serverCount: Int,
        val sub: Sub,
        val condition: Condition?,
        val stale: Boolean,
    )

    // ==================== State this screen keeps ====================

    /**
     * The account's subscriptions, the same `SubInfoDto` list Аккаунт renders — one truth, two
     * surfaces. It is also what gives the subscription card its NAME: when a подписка came from the
     * account, the nickname the account returns is what the card shows.
     *
     * **ACTIVITY-SCOPED, AND THAT IS THE POINT: ONE INSTANCE, NOT TWO.** «One truth, two surfaces»
     * was true of the DATA and false of the object — this was `by viewModels()` and so was
     * `AccountFragment`'s, so each tab had its own [AccountViewModel] with its own repository, its
     * own jobs and its own copy of the account. The tabs are `add`ed and only ever hidden, so both
     * were RESUMED at once and both refreshed on every resume: `/client/profile`,
     * `/subscription/all` and `/client/subscription` went out TWICE within milliseconds of each
     * other, every time the app came to the foreground, and the second answer overwrote the first.
     * Sharing the instance also means the Аккаунт tab opens onto the profile and подписки Главная
     * has already fetched, instead of cold-loading its skeleton on first visit.
     */
    private val accountViewModel: AccountViewModel by activityViewModels()

    private var accountSubs: List<SubInfoDto> = emptyList()
    private var subsResolved = false

    /**
     * The last account fetch failed. The screen then KEEPS its last values and says it could not be
     * refreshed, rather than emptying: an error must not delete data the user had.
     */
    private var subsError = false

    /** The user asked for a server sync. If one completes and there are still none, the gate says so. */
    private var syncRequested = false

    // Tracks the last observed signed-in state so the post-login auto-import fires only on a real
    // logged-out -> logged-in transition, not on every state replay.
    private var accountLoggedIn = AccountSession.isLoggedIn()
    private var accountResolved = !BackendConfig.isConfigured() || !AccountSession.isLoggedIn()

    // The connect machine's own flags. resolveState() reads them; nothing else writes them.
    private var connectLoading = false
    private var tunnelRunning = false
    private var disconnecting = false
    private var tunnelError = false

    /**
     * A TEARDOWN THIS SCREEN ASKED FOR, WITH A START ALREADY QUEUED BEHIND IT.
     *
     *     «также когда переключаешься между серверами, то на момент пишет вот так и красное всё …
     *      а потом подключается и пропадает»
     *
     * Switching servers is stop → start, and the daemon reports the stop the way it reports every
     * stop. The observer in [observeTunnel] had no way to tell the two apart, so `!isRunning &&
     * connectInProgress` — the test that means «the start we asked for did not happen» — was true
     * for the intermediate half of a switch as well. For the fraction of a second between the two
     * halves the object painted the FAILURE: red rings, «Не подключено», «Нажмите, чтобы
     * повторить», and the session clock reset to 00:00:00 by `stopConnectionTimer`.
     *
     * This is the missing piece of intent. It is raised by [restartV2Ray] the moment it asks the
     * daemon to stop, and it is lowered by exactly three things: the tunnel coming back up (the
     * switch worked), the stop timing out (the old tunnel would not go), and the connect watchdog
     * firing (the new one never came). None of the three can be skipped, so the flag cannot strand
     * the control in a busy state — the watchdog is armed by the caller before the switch starts
     * and, unlike before, now survives the intermediate report.
     *
     * It is not a dwell and not a latch: nothing here is timed. A state that the user must not see
     * is one that must not be PAINTED, and the reason it must not be painted is that it is not
     * true — no failure has happened, and the tunnel the clock is counting has not ended yet.
     */
    private var switching = false

    /** What [render] last drew, so a transition can be told from a repaint. */
    private var renderedConn: Conn? = null

    /**
     * Whether the connect object is currently drawn in its CONNECTED dress. Kept apart from
     * [renderedConn] because «Отключение…» is a distinct connection state but the same visual exit:
     * the release plays on the tap, not one repaint later when the daemon finally answers.
     */
    private var visualConnected = false

    // SkeletonBinder.showAfterDelay() re-posts its 300ms timer on every call, so a screen that
    // re-renders while it waits would postpone the skeleton forever. This arms it exactly once.
    private var accountSkeletonArmed = false

    /** True while the strip's exit is still running, so an arriving condition re-shows it. */
    private var stripHiding = false

    /** Which of the slot's two occupants is drawn, so the swap can be crossfaded once. */
    private var renderedGateVisible: Boolean? = null

    /**
     * True while the screen is the START SCREEN and not Главная — the gate's SIGN_IN shape, where
     * everything but the gate block is switched off. Kept as a field because [applyListInsets] is
     * driven by the window's insets rather than by [render] and has to read the same fact.
     * @see paintOnboardingShell
     */
    private var onboardingShell = false

    // The live figures. Null means "no reading yet", which prints a zero rather than an empty box:
    // this strip is the screen's ledger and it is on screen at rest.
    private var downBytesPerSec: Long? = null
    private var upBytesPerSec: Long? = null
    private var pingMs: Int? = null
    private var pingProbeFailures = 0

    // Offline is a live condition, so it is observed rather than polled.
    private var offline = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * The manager the callback above was registered ON, kept so it can be unregistered.
     *
     * [unregisterNetworkCallback] used to look the service up through `context`, which is null the
     * moment the fragment is detached — and it dropped its handle on the callback BEFORE finding
     * out. A callback that is never unregistered is held by the system, and it holds this fragment
     * through `postOffline`, so the whole destroyed hierarchy stayed reachable and the system went
     * on delivering connectivity events to it. The manager is an application-scoped singleton, so
     * holding it costs nothing and cannot leak an Activity.
     */
    private var connectivityForCallback: ConnectivityManager? = null

    /** How many background loads the shell has open — a subscription refresh, an import, an export. */
    private var backgroundLoads = 0

    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Tracks the last delivered running state so a transition can be told from the LiveData value
    // replay after a rotation/theme recreate.
    private var lastRunningState: Boolean? = null

    // True between a connect tap and the definitive running/failed result, so a start that ends in
    // "not running" is reported as a failure rather than a silent revert.
    private var connectInProgress = false

    // ---- The inline server list ----

    /**
     * Главная's own server list. The Серверы destination was removed by the owner on 2026-07-26 and
     * none of its functions moved anywhere, so this adapter is the product's only server list; row
     * actions come from the shell so a row behaves identically wherever it is drawn.
     */
    private var homeAdapter: MainRecyclerAdapter? = null

    /** The subscription card's chevron collapses the list under it. Session state, not a setting. */
    private var homeListCollapsed = false

    /**
     * Whether the list was last drawn open, so [setServerListShown] can tell a genuine open/close
     * from the repaint that merely repeats one. Null until the first paint, which lands on the end
     * state instantly rather than sliding a whole list open on launch.
     */
    private var renderedListShown: Boolean? = null

    // ---- The subscription card carousel ----

    private var homeMetaAdapter: HomeMetaPagerAdapter? = null
    private var homeMetaSubIds: List<String> = emptyList()
    private var homeMetaPage = 0

    // ---- The connect object ----

    // The three rings' strokes live in these drawables; the tint animation retints them in place,
    // which is why there is no runtime-tinted ring drawable file and no raw hex on this screen.
    private var ringOuter: GradientDrawable? = null
    private var ringMid: GradientDrawable? = null
    private var ringInner: GradientDrawable? = null
    private var ringColor = 0
    private var ringAnimator: ValueAnimator? = null

    /** Whether the active ring is currently the arc's track rather than a ring. @see dimRingTrack */
    private var ringTrackDimmed = false

    /**
     * The active ring's opacity RIGHT NOW, between [RING_ALPHA_TRACK] and [OPAQUE].
     *
     * [ringTrackDimmed] is the intent — «the arc is on the ring» — and this is the value on the
     * glass, which travels between the two ends instead of jumping. They are separate because the
     * intent flips in one frame and the movement takes the arc's own clock; a single boolean could
     * only ever express the jump, and the jump is what the owner reported: «слишком резко … возврат
     * полной заливки вокруг кольца».
     */
    private var ringTrackAlpha = OPAQUE

    /** The travel between those two ends. @see dimRingTrack */
    private var ringTrackAnimator: ValueAnimator? = null

    /**
     * The last server name and flag this screen could resolve, so a live tunnel is never left with
     * nothing to call itself. @see resolveState
     */
    private var lastServerName: String? = null
    private var lastServerFlag: String? = null

    /** The negotiating breath: the outer rings swelling while the core talks to a server. */
    private var breathAnimator: ValueAnimator? = null

    /**
     * The connected settle: one 5.5s decay of the rings' brightness after the tunnel comes up, and
     * nothing after it. @see settleRing — this is the whole of what used to be two infinite loops.
     */
    private var ringSettleAnimator: ValueAnimator? = null

    /**
     * The confirmation sonar: the lead ring and its echo, one shot, held so a disconnect or a
     * teardown can stop them and take their hardware layers back. @see emitConfirmRings
     */
    private var confirmAnimator: AnimatorSet? = null

    /**
     * Whether the sweep is on screen, so the arc's wind-up fires on the EDGE into negotiating and
     * not on every repaint that happens to find it already spinning. See [playArcWindUp].
     */
    private var sweepRunning = false

    /**
     * THE ARC'S REVOLUTION — §4's «одна дуга по кругу, 1100 мс», and the whole of it.
     *
     * ONE infinite ObjectAnimator on ROTATION at @integer/motion_spin, linear, exactly as
     * `FlowOverlay.startArc` turns the прогрузка ring. It replaces a Material
     * `CircularProgressIndicator`, which owned its own clock — the revolution lives inside
     * IndeterminateAnimatorDelegate with no attribute and no setter — and whose `disjoint` arc
     * additionally grows and shrinks as it travels, so it was visibly not the design's fixed
     * 19.5% arc. Held so it can be stopped: it is INFINITE, and an infinite animator keeps the
     * compositor awake from a screen nobody is looking at.
     */
    private var sweepSpin: ObjectAnimator? = null

    /**
     * The BOUNDED last stretch of that same revolution — from wherever the arc is to the next
     * quarter mark — which is what the exit rides instead of cancelling the turn outright.
     *
     * Its own handle, because it drives the same ROTATION property as [sweepSpin] and the two must
     * never run together. @see windDownSweep
     */
    private var sweepWindDown: ObjectAnimator? = null

    /** True while the arc's exit is still running, so an arriving load re-shows it. @see hideSweep */
    private var sweepHiding = false

    /**
     * The status pill's current colour and the tween moving it, so a repaint that resolves to the
     * same state costs nothing and two state changes in a row do not run two animators at the
     * pill at once. Null until the first paint, which lands instantly rather than fading in from
     * an arbitrary colour.
     */
    private var pillColor: Int? = null
    private var pillTint: ValueAnimator? = null

    /**
     * THE ENTRANCE (handoff §3, «Сборка главной»). Held so it can be cancelled with the view: it
     * is one AnimatorSet with per-element start delays out to 580ms and NOT a chain of postDelayed
     * runnables, because a chain outlives the screen and fires at views that are already gone.
     */
    private var entranceAnimator: android.animation.AnimatorSet? = null

    /** The entrance plays once per attached view, never on a repaint. @see playHomeEntrance */
    private var entrancePlayed = false

    /**
     * THE HOLD IS STICKY, and this field is the whole of that fix.
     *
     * [holdEntrance] used to stamp the pre-entrance state ONCE and trust it to survive until the
     * overlay came down. It does not survive: while the flow runs, the подписка lands, the server
     * list is rebuilt and [render] runs several times — and render writes RESTING values.
     * `paintConnect` assigns `frame.alpha = 1f` on every repaint, `paintOnboardingShell` switches
     * the whole connect band back on when the gate goes, `paintSlot` fades the subscription slot
     * in, and rows bound after the prime arrive opaque. By the time the overlay left, Главная was
     * whole underneath it.
     *
     * That is what the owner saw. The overlay's exit is a 520ms FADE («Проявление», §3), so those
     * last 520ms progressively revealed a finished screen — «круг от кнопки» — and the frame that
     * removed the overlay then snapped every element back to alpha 0 to start the table: «резко
     * лагающе исчезает и только потом анимация». One transaction was never enough on its own; the
     * screen has to be held in its start values for the WHOLE hold, not just at the beginning of
     * it. So [render] re-primes while this is true, and it is cleared only by [playEntrance].
     */
    private var entranceHeld = false

    // Cached easing curves (loaded once) so the imperative hero motion rides the same ease-out tempo
    // as res/interpolator and res/anim. No bounce.
    private val easeOutQuint by lazy { AnimationUtils.loadInterpolator(requireContext(), R.interpolator.ease_out_quint) }
    private val easeOutQuart by lazy { AnimationUtils.loadInterpolator(requireContext(), R.interpolator.ease_out_quart) }
    private val easeStandard by lazy { AnimationUtils.loadInterpolator(requireContext(), R.interpolator.ease_standard) }
    private val easeInOut by lazy { AnimationUtils.loadInterpolator(requireContext(), R.interpolator.ease_in_out) }

    private val durWindUp get() = resources.getInteger(R.integer.motion_windup).toLong()
    private val durPressIn get() = resources.getInteger(R.integer.motion_press_in).toLong()
    private val durEmphasis get() = resources.getInteger(R.integer.motion_emphasis).toLong()
    private val durState get() = resources.getInteger(R.integer.motion_state).toLong()
    private val durStateExit get() = resources.getInteger(R.integer.motion_state_exit).toLong()
    private val durExpand get() = resources.getInteger(R.integer.motion_expand).toLong()
    private val durReveal get() = resources.getInteger(R.integer.motion_reveal).toLong()
    private val durRevealExit get() = resources.getInteger(R.integer.motion_reveal_exit).toLong()
    private val durAppear get() = resources.getInteger(R.integer.motion_appear).toLong()
    private val durAppearSlow get() = resources.getInteger(R.integer.motion_appear_slow).toLong()
    private val durScreen get() = resources.getInteger(R.integer.motion_screen).toLong()

    // ---- The session clock ----

    /** Epoch millis the current session started, persisted so uptime survives a recreate. */
    private var connectionStartTime = 0L

    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (connectionStartTime == 0L || !isBindingInitialized) return
            val elapsed = ((System.currentTimeMillis() - connectionStartTime) / 1000L).coerceAtLeast(0L)
            binding.tvUptime.text = getString(
                R.string.home_uptime_format,
                elapsed / 3600L,
                (elapsed % 3600L) / 60L,
                elapsed % 60L,
            )
            timerHandler.postDelayed(this, 1000L)
        }
    }

    /** The 30s latency probe of the ACTIVE server, and the only producer of the «мс» figure. */
    private val latencyRunnable = object : Runnable {
        override fun run() {
            if (mainViewModel.isRunning.value != true) return
            mainViewModel.testCurrentServerRealPing()
            timerHandler.postDelayed(this, LATENCY_INTERVAL_MS)
        }
    }

    // Connect watchdog: if a start neither succeeds nor reports a failure within the timeout (e.g.
    // the core/daemon process crashed without broadcasting any state), recover the UI to idle
    // instead of hanging forever on «Подключение…».
    private val connectWatchdogRunnable = Runnable {
        if (mainViewModel.isRunning.value != true) {
            connectInProgress = false
            // A switch whose second half never answered has run out of rope, and THIS one is a real
            // failure: [switching] is dropped here so the observer stops swallowing the daemon's
            // «not running» and the object goes red, exactly as a plain start that stalls does.
            switching = false
            tunnelError = true
            applyRunningState(isLoading = false, isRunning = false)
        }
    }

    /**
     * ОБНОВЛЕНИЕ АККАУНТА ПРИ ВОЗВРАЩЕНИИ - ЧЕРЕЗ ПОЛТОРЫ СЕКУНДЫ, А НЕ В КАДР ПОКАЗА.
     *
     * Каждое возвращение в приложение отправляло три запроса сразу - профиль, `/subscription/all`
     * и `/client/subscription` - в тот самый момент, когда экран возвращается, и их ответы следом
     * перерисовывали строку аккаунта и карточку подписки. На ПК то же самое добавляло рывков к
     * возвращению из трея и отложено там на полторы секунды (`AccountViewModel.RefreshIfIdle`);
     * здесь так же. Первые кадры принадлежат экрану, сеть подождёт.
     *
     * Если приложение за это время снова ушло ([onPause]), запросы не уходят вовсе: ответ некому
     * смотреть, а следующее возвращение спросит заново.
     *
     * Порога простоя здесь нет и не добавлено: как и раньше, обновляется каждое возвращение, только
     * позже. У ПК порог - 15 минут без обновления.
     */
    private val returnRefreshRunnable = Runnable { refreshAccountData() }

    private companion object {
        /** Сколько экран возвращается без сети. Цифра ПК: `AccountViewModel.RefreshIfIdle`. */
        const val RETURN_REFRESH_DELAY_MS = 1_500L

        // KEY_CONNECTION_START used to live here and the screen wrote it. It is now
        // CoreServiceManager.KEY_SESSION_STARTED_AT — same key, written beside the core loop, read
        // here through CoreServiceManager.sessionStartedAt(). See [startConnectionTimer].

        // Upper bound for a connect attempt before the UI gives up and returns to idle.
        const val CONNECT_TIMEOUT_MS = 20000L

        // A restart must not start the new core until the daemon process reports the old one
        // stopped; these bound that wait.
        const val RESTART_STOP_TIMEOUT_MS = 6000L
        const val RESTART_STOP_POLL_MS = 50L

        // The dial to turn if the probe ever costs battery. The design does not change.
        const val LATENCY_INTERVAL_MS = 30_000L

        // Three consecutive failed probes, not one: a single dropped packet is not a dead server.
        const val SILENT_SERVER_FAILURES = 3

        // Under this many days left the subscription is «Истекает».
        const val EXPIRING_DAYS = 3

        // ~2088-01-01 in epoch seconds. Some panels send a date this far out to mean "never".
        const val UNLIMITED_EXPIRE_SECONDS = 3_723_840_000L

        const val DISABLED_ALPHA = 0.38f

        // The three rings are ONE colour at three opacities: outermost faintest, the disc's own
        // brightest. That is the whole gradient this object is allowed, and it is a state channel
        // rather than decoration.
        const val RING_ALPHA_OUTER = 56 // ~22%
        const val RING_ALPHA_MID = 128 // ~50%
        const val OPAQUE = 255

        // THE ACTIVE RING WHILE THE ARC IS ON IT. The prototype paints the negotiating ring
        // `rgba(76,141,255,.30)` — the accent at 30% — and the travelling arc at full strength on
        // top of it. The port painted both at full and they cancelled: a bright arc on a bright
        // ring of the same hue is a ring, and the owner reported exactly that («надо цвет чутка
        // темнее сделать чтобы был акцент и видно, что идёт анимация»). 30% of 255 is 77.
        const val RING_ALPHA_TRACK = 77 // 30%, the prototype's own figure

        // The negotiating breath, on the two outer rings. Same 850ms reverse the old build breathed
        // the (now banned) halo glow with, moved onto the rings where it belongs.
        const val BREATH_PERIOD_MS = 850L
        const val BREATH_ALPHA_MIN = 90

        /** One full revolution — the arc's wind-up lands back where it started, so nothing snaps. */
        const val FULL_TURN_DEGREES = 360f

        /**
         * WHERE THE ARC IS ALLOWED TO STOP: the quarters of the circle.
         *
         * An exit that cancels the turn leaves the arc at an arbitrary angle, and the next
         * appearance starts from a different one — the object never looks the same twice at the one
         * moment it is standing still. A quarter is the coarsest mark that is always within half a
         * second of the arc at the 1100ms tempo, which is what keeps the exit bounded. @see hideSweep
         */
        const val SWEEP_STOP_STEP = 90f

        // Where the connected settle lands. §4 asks for a DECAY, so the rings arrive at full
        // brightness on the moment of connection and come to rest a little under it — far enough
        // to be a fade, near enough that the object is still plainly the connected one. Any lower
        // and a live tunnel reads as dimmer than an idle one, which is backwards.
        const val RING_SETTLED_ALPHA = 200

        // §4's «переход 500 мс» on the traffic meter's fill. It has no token: motion.xml's nearest
        // rungs are 460 and 560, and this number is stated to the millisecond in the handoff.
        const val METER_FILL_MS = 500L

        // The connecting breath also reaches the shield glyph, in unison with the rings: alpha
        // 1 <-> 0.8 (ConnectHeroView.axaml, Path.shieldbreathe). Opacity only — never a transform,
        // so it physically cannot drift out of its own centre.
        const val SHIELD_BREATH_ALPHA_MIN = 0.8f

        // §3's assemble, in its own numbers. The two durations that have tokens use them
        // (motion_appear 460, motion_appear_slow 720, motion_screen 560); 520 has no rung on
        // §8's scale and is written out, as are the travels, which §3 states in dp.
        const val ENTRANCE_MID_MS = 520L
        const val ENTRANCE_DROP = 22f
        const val ENTRANCE_RISE = 26f
        const val ENTRANCE_RING_SCALE = 0.7f
        const val ENTRANCE_ROW_SLIDE = 44f
        const val ENTRANCE_ROWS_DELAY_MS = 700L
        const val ENTRANCE_ROW_STAGGER_MS = 85L

        // THE CONFIRMATION SONAR. The prototype's own figures, kept together with the code that
        // plays them (see [emitConfirmRings]): the lead ring is thrown to 1.6 of the 170dp active
        // ring — 272dp, clear of the whole object — and the echo settles just inside it at 1.5,
        // starting at half the opacity. The tempo and the curve are tokens
        // (@integer/motion_emphasis 600, @interpolator/ease_out_quint, @integer/motion_press_in 70
        // for the echo's beat) and are not restated here.
        const val CONFIRM_LEAD_TO = 1.6f
        const val CONFIRM_ECHO_TO = 1.5f
        const val CONFIRM_ECHO_ALPHA = 0.5f
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            // The system prompt is modal and the user may take as long as he likes over it, so the
            // watchdog was stood down while it was up (see [startVpnWithPermission]). The attempt's
            // clock starts here, when a start is actually going out.
            scheduleConnectWatchdog()
            startV2Ray()
        } else {
            // A CANCELLED ACTION IS NOT A FAILURE. Declining Android's own VPN prompt used to leave
            // the attempt in flight, so the object sat on «Подключение…» and then reported
            // «Не удалось подключиться» for something the user chose to do.
            connectInProgress = false
            tunnelError = false
            cancelConnectWatchdog()
            applyRunningState(isLoading = false, isRunning = false)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeBinding =
        FragmentHomeBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildConnectObject()
        wireHeaderRow()
        seedAccountRow()
        wireConnect()
        wireStatusStrip()
        setupServerList()
        // The carousel decides which подписка's servers the list shows, so it is built second and
        // the list is repainted once it has settled on a page.
        setupHomeMetaPager()
        refreshServerList(-1)
        observeTunnel()
        observeAccount()
        observeNetwork()
        applyListInsets()
        render()
        // THE OVERLAY HAND-OFF (§11 grabl 6), on the contract the flow wave published in
        // ui/component/HomeHandoff.kt: Главная supplies the two callbacks, the flow calls them.
        // prime() parks this screen in its pre-entrance state under the opaque overlay; assemble()
        // is called in the very frame the overlay removes itself, so the teardown and the first
        // frame of the table land in one traversal. Cleared in onDestroyView — HomeHandoff is a
        // singleton and a live lambda in it is a strong reference to this fragment.
        HomeHandoff.onPrime = { holdEntrance() }
        HomeHandoff.onAssemble = { playEntrance() }

        // THE ASSEMBLE, unless somebody is holding it. A flow overlay is up over Главная on exactly
        // one path — first run — and on that path the shell has already armed the hold, so the
        // screen waits in its pre-entrance state until it is released. Every other launch assembles
        // itself here.
        //
        // The shell's flag and HomeHandoff.prime() are BOTH honoured, and they cover different
        // instants: the flag answers for a flow that started before this fragment had a view (the
        // usual order at first run), prime() for one that starts after. Either way holdEntrance()
        // re-arms an entrance that already played, so a late prime is not too late. @see playEntrance
        if (mainHost.homeEntranceHeld) holdEntrance() else playEntrance()
    }

    /**
     * The three controls «Добавить подписку» can be hung off, and the only reason to name them is
     * to close a flyout that belongs to this screen without closing one that does not.
     */
    private fun homeAnchors(): List<View> = listOf(
        binding.btnHomeAdd,
        binding.layoutGate.btnGatePrimary,
        binding.layoutGate.btnGateSecondary,
    )

    /**
     * Re-reads what other entry points may have changed while this tab was up but not in front.
     * Hidden tabs stay RESUMED, so this also runs for a tab that is attached but not on screen —
     * which is exactly what makes it correct the moment it is shown again.
     */
    override fun onResume() {
        super.onResume()
        // The two loops [onPause] stood down. Both are re-armed only for a live tunnel, and both
        // come back with a FRESH reading rather than the one they were showing when the app went
        // away: [startConnectionTimer] re-reads the daemon's session stamp, and [startLatencyProbe]
        // posts its probe immediately instead of waiting out an interval. @see onPause
        if (mainViewModel.isRunning.value == true) {
            startConnectionTimer()
            startLatencyProbe()
        }
        // The FIRST resume after a fresh view has nothing to ask for: [observeAccount] asked, a
        // message ago, and the answers are still in flight. @see observeAccount
        // Every later one asks a moment after the screen is back, not in its first frame.
        // @see returnRefreshRunnable
        if (accountDataFetchedOnCreate) accountDataFetchedOnCreate = false else refreshAccountDataSoon()
        refreshServerSurfaces(-1)
        render()
    }

    /** The return-to-app half of [refreshAccountData]. @see returnRefreshRunnable */
    private fun refreshAccountDataSoon() {
        timerHandler.removeCallbacks(returnRefreshRunnable)
        // Signed out there is nothing to fetch and the answer is local: it is settled now, in the
        // same render as the rest of the resume, rather than a second and a half later.
        if (!BackendConfig.isConfigured() || !AccountSession.isLoggedIn()) {
            refreshAccountData()
            return
        }
        timerHandler.postDelayed(returnRefreshRunnable, RETURN_REFRESH_DELAY_MS)
    }

    /**
     * The ambient loops are the only INFINITE motion on this screen, and an infinite animator keeps
     * the compositor awake whether or not anyone can see it. The app going to the background is the
     * one moment nobody can, so they stop here — the desktop pauses the same layer for the same
     * reason (`ConnectHeroView.axaml.cs`, `_animationsPaused` / MotionSuppressed). [onResume]'s
     * render() puts them back, at whatever tempo the state then calls for.
     *
     * A hidden TAB is not this case: hidden tabs stay RESUMED, and a tab the user is one swipe away
     * from should be alive when he gets there. NEITHER IS A DIALOG ON TOP: the arc's revolution used
     * to stand down here with the rest and answered Android's own VPN prompt by freezing under it.
     * It is asked the stricter question in [onStop] now, and only it — the connected settle below is
     * a 5.5s one-shot rather than an indefinite spinner, so standing it down at a prompt costs a
     * decay nobody is reading rather than a signal somebody is.
     *
     * ## …AND THE MOTION WAS NOT THE ONLY THING THAT KEPT RUNNING
     *
     * The same rule — «нельзя тратить на то, чего никто не видит» — applies to the two REPEATING
     * handlers this screen owns, and neither of them obeyed it: they were armed by the running-state
     * observer and stood down only in [onDestroyView], which for a VPN client is almost never. The
     * app spends its life in the background WITH THE TUNNEL UP; that is the normal state, not the
     * exception, and in it the screen went on paying for both loops with nobody looking:
     *
     *  - [uptimeRunnable] woke the main thread once a SECOND, formatted a h:mm:ss string and wrote
     *    it into a TextView that is not on the glass. 3600 wake-ups an hour for an invisible label.
     *  - [latencyRunnable] fired a real probe every 30s: `MSG_MEASURE_DELAY` crosses into the daemon
     *    and `CoreServiceManager.measureV2rayDelay` sends an HTTP request THROUGH THE TUNNEL (twice
     *    when the first URL misses). That is the radio, not the CPU — 120 round trips an hour to
     *    refresh a figure on a screen that is not being shown. Each answer then broadcast back into
     *    this process and drove a full `render()` of the invisible hierarchy.
     *
     * Nothing is lost by standing them down: [onResume] re-arms both, and both come back with a
     * fresh reading in the same frame — the clock re-reads the daemon's own session stamp
     * (`CoreServiceManager.sessionStartedAt`), so it is exact however long the app was away, and the
     * probe posts immediately rather than after an interval, so the «мс» figure the user comes back
     * to is newer than the one he left. The connect watchdog is deliberately NOT stood down: it is a
     * one-shot deadline under an attempt that is still in flight, and an attempt does not stop being
     * in flight because the user switched apps.
     */
    override fun onPause() {
        stopAmbient()
        // The arc's revolution used to stand down HERE, and «paused» is not «gone». @see onStop
        // The callbacks only — NOT [stopConnectionTimer], which also wipes the session's start
        // instant and blanks the readings. The session is not over; only the screen has gone.
        timerHandler.removeCallbacks(uptimeRunnable)
        stopLatencyProbe()
        // Gone again before the account refresh went out: it does not go out. Every pause is
        // followed by a resume before the screen can be seen again, and that resume asks afresh.
        timerHandler.removeCallbacks(returnRefreshRunnable)
        super.onPause()
    }

    /**
     * ============================================================================
     * THE REVOLUTION STANDS DOWN WHEN THE SCREEN IS GONE — «GONE», NOT «NOT ON TOP».
     * ============================================================================
     *
     *     «когда подключаешься и еще не даёшь разрешение, то там вот эта полоска появляется, ее
     *      быть не должно, в плане там должно быть все продумано, чтобы оно крутилось, а не
     *      останавливалось»
     *
     * The one thing standing between the connect tap and a tunnel is Android's own «Запрос на
     * подключение» — the consent Activity `VpnService.prepare()` hands back (see
     * [startVpnWithPermission]) — and it is DIALOG-THEMED. A dialog-themed activity does not cover
     * the one under it: the shell is paused and STILL ON THE GLASS, in full view behind the prompt.
     * So `onPause` fired while the user was looking straight at Главная, and it cancelled the
     * revolution and stamped the arc back to 0°. What was left on screen was
     * @drawable/ic_connect_arc's fixed 19.7 % of the circumference — one segment of stroke, opaque,
     * perfectly still — under a pill reading «Подключаемся…» and a clock at 00:00:00. That is the
     * photograph, and a busy affordance frozen mid-sweep is the one thing it may never be: it does
     * not say «waiting», it says «hung».
     *
     * IT KEEPS TURNING FOR THE WHOLE WAIT, and that is the honest state as well as the one he asked
     * for. The attempt IS in flight — the user tapped connect and the app is now waiting on HIM
     * rather than on a network — so the object is busy and says so, for as long as he takes. The
     * alternative, holding the object at rest until consent lands, would have the control answer a
     * tap with nothing at all and then start moving on its own later, which is a worse lie.
     *
     * Both answers are already clean and neither needed changing:
     *
     *  - DECLINE lands in [requestVpnPermission]'s else branch, which drops `connectInProgress` and
     *    republishes idle, so the arc winds down through the ordinary exit and the ring fills back.
     *    It now winds down from wherever it actually is instead of from a dead stop at 0°.
     *  - THE WATCHDOG cannot fire at a dialog: [startVpnWithPermission] stands it down BEFORE
     *    launching the prompt and [requestVpnPermission] re-arms it only on RESULT_OK, so the 20s
     *    deadline measures the daemon and never the человек.
     *
     * THE RULE ITSELF IS UNCHANGED — nothing infinite turns while nobody can see it — only the
     * question it is asked. `onStop` is that question: the window is not visible AT ALL. `onPause`
     * only ever meant «something is on top of it». [onResume]'s render() puts the revolution back
     * through showSweep(), which re-arms it without replaying the entrance, and onResume always
     * follows the onStart that answers this.
     */
    override fun onStop() {
        stopSweepSpin()
        super.onStop()
    }

    override fun onDestroyView() {
        timerHandler.removeCallbacks(latencyRunnable)
        timerHandler.removeCallbacks(connectWatchdogRunnable)
        timerHandler.removeCallbacks(uptimeRunnable)
        timerHandler.removeCallbacks(returnRefreshRunnable)
        ringAnimator?.cancel()
        ringAnimator = null
        // The arc's hand-back to the ring. Short, but it writes a stroke into three GradientDrawables
        // that are nulled at the bottom of this method. @see dimRingTrack
        ringTrackAnimator?.cancel()
        ringTrackAnimator = null
        breathAnimator?.cancel()
        breathAnimator = null
        // The sonar holds a hardware layer on each of its two rings for the length of its flight;
        // cancelling it here is what hands those buffers back if the screen goes mid-payoff.
        confirmAnimator?.cancel()
        confirmAnimator = null
        pillTint?.cancel()
        pillTint = null
        // The assemble is the one animation here with start delays measured in whole seconds, so it
        // is the one most likely to still be pending when the screen goes.
        cancelEntrance()
        HomeHandoff.onPrime = null
        HomeHandoff.onAssemble = null
        // The ambient loops are INFINITE, so they are the two that would outlive the view and keep
        // driving the compositor from a destroyed hierarchy if they were ever forgotten here.
        stopAmbient()
        stopSweepSpin()
        // The list's collapse runs on the RecyclerView's own layout height; a set still tweening
        // it when the view goes would write layout params into a detached hierarchy.
        RunningAnimators.cancel(binding.rvHomeServers)
        // The in-flight bar is INDEFINITE by construction, so it is the one surface that would
        // survive this screen if nothing took it down.
        Notice.clearProgress()
        // «Добавить подписку» is a SelectPopup now, and SelectPopup is an object holding the one
        // open flyout: an open one keeps its anchor, and therefore this fragment and its activity,
        // alive past the view it was hung off. It is closed only when the anchor belongs to THIS
        // screen — a popup opened from Настройки is that tab's to close.
        if (homeAnchors().any { SelectPopup.isShowing(it) }) SelectPopup.dismiss()
        ringOuter = null
        ringMid = null
        ringInner = null
        homeAdapter = null
        homeMetaAdapter = null
        unregisterNetworkCallback()
        super.onDestroyView()
    }

    // ==================== What the shell calls ====================

    /**
     * The server cache changed: follow it on the inline list, and on the carousel when the change
     * is one the carousel can see.
     *
     * ONE ROW IS NOT A REASON TO REBUILD A ViewPager2. `index >= 0` names a single server whose
     * stored latency just landed (`MainViewModel.publishMeasurement`, `MSG_MEASURE_CONFIG_SUCCESS`)
     * — one repaint per server of a bulk test, and one every 30s while a tunnel is up. None of that
     * changes a подписка, and the card shows no latency at all, so rebuilding the pages for it was
     * work with no picture attached: pages re-created, holders re-bound, the pager re-laid-out and
     * re-snapped, on the main thread, in bursts. [refreshServerList]'s own note already promised
     * this and the caller above it was doing the opposite.
     *
     * A structural change — an import, a delete, a refresh, the end of a batch — still arrives as
     * -1 and still rebuilds both surfaces, and so does a position the cache no longer resolves.
     *
     * @param index the changed server's index in the cache, or -1 for the whole list.
     */
    fun bindList(index: Int) {
        if (!isBindingInitialized) return
        if (index >= 0) refreshServerList(index) else refreshServerSurfaces(index)
        render()
        // Whether there is a server at all is one of the two inputs to the shell's nav gates.
        mainHost.refreshNavGates()
    }

    /**
     * The server list scrolls with this screen rather than inside itself, so the inset that clears
     * the overlaid bottom bar belongs to the scroll CONTENT. Never smaller than the layout's own
     * bottom breathing room, so a zero inset (before the shell has measured the window) cannot
     * leave the last row flush against the edge.
     *
     * ON THE START SCREEN THERE IS NO BAR TO CLEAR. `listBottomInset` is the system inset plus the
     * 56dp bar plus 16dp of breathing room — around 96dp — and the shell hides the bar outright in
     * exactly the state [paintOnboardingShell] paints (MainActivity.updateBottomNavVisibility).
     * Reserving it anyway would hold 96dp of nothing under a block that is centred against what is
     * left of the viewport, and push the whole composition half that far up the screen. The 24dp
     * floor still stands, and the gate's own trailing spacer is what actually keeps it off the
     * gesture area.
     */
    fun applyListInsets() {
        if (!isBindingInitialized) return
        val floor = resources.getDimensionPixelSize(R.dimen.space_24)
        val inset = if (onboardingShell) 0 else mainHost.listBottomInset
        binding.homeContent.updatePadding(bottom = maxOf(inset, floor))
    }

    /** Repaints after the shell changed which server is selected. */
    fun refreshSelectedServer() {
        if (!isBindingInitialized) return
        homeAdapter?.syncSelection()
        render()
    }

    /**
     * The shell has just written a new selected server. The shell owns the write because more than
     * one surface reads the selection; this mirrors it into the list so exactly one row is ever
     * painted selected, and drops the previous server's latency reading.
     */
    fun onSelectedServerChanged(previous: String?, guid: String) {
        if (!isBindingInitialized) return
        homeAdapter?.setSelectServer(previous, guid)
        pingMs = null
        pingProbeFailures = 0
        render()
    }

    /** A server was removed elsewhere; the inline list follows it. */
    fun removeServerRow(guid: String, position: Int) {
        if (!isBindingInitialized) return
        refreshServerSurfaces(-1)
        render()
        mainHost.refreshNavGates()
    }

    /**
     * Moves an already-running tunnel onto the newly selected server — the action on the shell's
     * «применить» snackbar. Goes through the connect state machine (in-progress flag, connecting
     * visual, watchdog) so a restart that stalls is reported like any other failed start.
     *
     * @param guid the server the offer NAMED. [restartV2Ray] starts whatever `getSelectServer()`
     *   holds, so the selection is re-pointed at that server first: the bar lives for several
     *   seconds and the selection can move under it, and a restart onto a server the message never
     *   mentioned is the one outcome this whole offer exists to prevent.
     */
    fun applySelectionToRunningTunnel(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (selected != guid) {
            MmkvManager.setSelectServer(guid)
            onSelectedServerChanged(selected, guid)
        }
        connectInProgress = true
        tunnelError = false
        if (isBindingInitialized) applyRunningState(isLoading = true, isRunning = true)
        scheduleConnectWatchdog()
        restartV2Ray()
    }

    /** @see MainHost.toggleConnection */
    fun toggleConnection() = handleConnectAction()

    /** @see MainHost.restartConnection */
    fun restartConnection() = restartV2Ray()

    /**
     * @see MainHost.showStatus
     *
     * A transient EVENT, so it is a `Snackbar`, offset above the overlaid bottom navigation with the
     * shell's own inset figure. Persistent CONDITIONS go on the status strip instead.
     *
     * RULE 5 REACHES THIS SURFACE TOO. It is a bar at the bottom of Главная and it is raised by
     * `onLoggedIn` («Подписка привязана») at the exact instant a sign-in flow owns the whole window
     * — the same defect, on the same screen, as the «нет подписок для обновления» the owner caught,
     * and the policy's whole point is that «not now» is decided about the MOMENT rather than about
     * one sentence. It is asked the moment-question only: what this surface says is the connect
     * machine's, not the notice channel's. @see NoticePolicy.allowsNow
     */
    fun showStatus(text: CharSequence) {
        if (!isBindingInitialized) return
        if (!NoticePolicy.allowsNow()) return
        val bar = Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT)
        (bar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin += snackbarBottomOffset()
            bar.view.layoutParams = lp
        }
        bar.show()
    }

    /**
     * How far above the window's bottom edge a transient bar has to start.
     *
     * `listBottomInset` is the footprint of the bottom navigation — system inset + 56dp bar + air —
     * and clearing it is right whenever that bar is on screen. ON THE START SCREEN THE BAR IS NOT
     * THERE: the shell hides it in exactly the state [paintOnboardingShell] paints, so the same
     * figure would float the bar ~96dp up into empty space. The three other bars in the shell
     * (`MainActivity`, `AccountFragment`, `NoticePolicy`) already make this check by anchoring only
     * to a visible nav; this is the same guard in the form this screen can state, since it is the
     * one that knows it is drawing the onboarding shell.
     */
    private fun snackbarBottomOffset(): Int =
        if (onboardingShell) 0 else mainHost.listBottomInset

    /**
     * A background load opened somewhere in the shell (a subscription refresh, an import, an
     * export). It spins the connect object's sweep — the shared indicator this screen has always
     * used for exactly these two things — and reports on the status strip at the LOWEST priority,
     * so a real condition always wins the single strip.
     */
    fun showConnectArc() {
        backgroundLoads++
        if (isBindingInitialized) render()
    }

    /** @see showConnectArc */
    fun hideConnectArc() {
        if (backgroundLoads > 0) backgroundLoads--
        if (isBindingInitialized) render()
    }

    /**
     * ============================================================================
     * THE FRAME A LIST REBUILD IS IN IS NOT A FRAME AN ANIMATION MAY START IN.
     * ============================================================================
     *
     *     «получается пролаг, когда идёт анимация обновления, то потом с пролагом каким-то слишком
     *      резко конец анимации»
     *
     * A finished подписка refresh ends in ONE main-thread message that does all of this: decode
     * every profile out of MMKV (`MainViewModel.updateCache`), rebuild the карусель, call
     * `notifyDataSetChanged` over the result — and then start the arc's exit and, on a flow, §3's
     * whole table. `notifyDataSetChanged` does not do the work; it requests a traversal, and that
     * traversal is the NEXT frame — the same frame the animators were just told to treat as t=0.
     * So frame one of the movement is spent binding thirty rows, frame two arrives 60–80ms later
     * and the interpolator, which is a function of the clock and not of the frames it got, is
     * already a third of the way through. That is the report exactly: a stall, then a jump, then
     * nothing. The animation was never slow — it was never drawn.
     *
     * So the rebuild keeps its frame and the movement gets the next one. [action] runs in the
     * pre-draw of the traversal the rebuild asked for, i.e. after measure, layout and every bind,
     * with only the draw left — and the animator's first tick lands on the following vsync, which
     * is a clean one.
     *
     * THE WAIT IS EXACTLY ONE FRAME AND CANNOT BE MORE, because [action] is usually the thing that
     * balances a counter and a deferral that can be dropped is a leak. Three ways out and all three
     * run it exactly once: no view at all (run now), the pre-draw (the intended one), and the view
     * leaving the window before that frame ever happens. The [invalidate] is what guarantees the
     * frame — a pre-draw listener only fires if something draws, and «nothing changed» is a real
     * outcome of a load.
     */
    private fun afterListSettles(action: () -> Unit) {
        if (!isBindingInitialized) {
            action()
            return
        }
        val root = binding.homeContent
        if (!root.isAttachedToWindow) {
            action()
            return
        }
        var pending = true
        fun once() {
            if (!pending) return
            pending = false
            action()
        }
        val detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                once()
            }
        }
        root.addOnAttachStateChangeListener(detached)
        root.doOnPreDraw {
            root.removeOnAttachStateChangeListener(detached)
            once()
        }
        root.invalidate()
    }

    // ==================== Building the connect object ====================

    /**
     * Builds the three rings, the disc's fill and the frame's ripple/focus foreground from THEME
     * ATTRIBUTES.
     *
     * These are drawables and not drawable files on purpose. The rings' colour is the screen's whole
     * state channel and changes at runtime between five values; the shipped `bg_connect_ring.xml`
     * solved that with six raw hex literals across three theme variants, two of which measured
     * 2.33:1 and 1.29:1 against the ground and failed the 3:1 boundary floor. Built here, every
     * colour is resolved through `?attr` and is therefore correct in blue, light and the mono
     * overlay at once, with no file to keep in three copies.
     *
     * THE FRAME IS THE CONTROL: 214dp of touch target, and @anim/press_connect scales the rings,
     * the disc and the shield together at 0.97, because they are one object.
     */
    private fun buildConnectObject() {
        val frame = binding.connectFrame
        ringColor = idleRingColor()

        // THE FOURTH FACE OF «кольца уходят за невидимый квадрат», and the one the layout could not
        // express. The owner's repro is exact: «при обычном подключении такого нет», only after
        // closing the app from recents and coming back.
        //
        // Three causes for this defect were already known and all three are closed — a container
        // that clips (clipChildren is off on connect_frame → home_content → home_tab_root), a
        // hardware layer given to the container instead of to what moves (it goes on the two rings
        // and comes off in doOnEnd AND doOnCancel), and a legacy view animation resolved by the
        // parent (they are property animators now). This is the fourth: A VIEWGROUP WITH alpha < 1
        // IS DRAWN THROUGH AN OFFSCREEN LAYER THE SIZE OF ITS OWN BOX, and children outside that
        // box are cut off — `clipChildren` has nothing to do with it and cannot switch it off.
        // (RenderProperties::promotedToLayer: alpha < 1 && hasOverlappingRendering.)
        //
        // Which is why it only ever showed on the restore path. §3's table fades this frame's alpha
        // 0 → 1 over 720ms; the daemon answers the registration handshake inside that window; the
        // answer used to be read as a live connect and fired the sonar — so the one moment the
        // rings fly is the one moment the frame is mid-fade and therefore rasterised into a 214dp
        // square. Press the button normally and the frame has been at alpha 1 for minutes.
        //
        // [entranceInFlight] keeps the confirmation out of that window; this line makes the box
        // itself incapable of trimming the rings whatever else ever plays inside it. Nothing is
        // given up: the children of this frame do not overlap — the three rings are its BACKGROUND
        // at radius ≥ 84, the disc is 150 and the two sonar rings are invisible at rest — so
        // per-child alpha and group alpha produce the same pixels here, and the renderer is simply
        // told the truth. Public since API 24; minSdk is 24.
        frame.forceHasOverlappingRendering(false)

        // THE THREE RINGS, at handoff §4's own insets: outer 0 / 1.5dp, middle 10dp / 1.5dp,
        // inner 22dp / 2dp. All three are layers of the frame's background — three empty views
        // would cost three measure passes to draw three ovals, and LayerDrawable insets are
        // exactly the language §4 states the geometry in.
        //
        // The inner one is the ACTIVE ring: it carries the state hue at full strength and it is
        // the circle the negotiating arc rides (connect_sweep, indicatorSize 170 = 214 - 2x22).
        // It used to be the DISC's own edge, which was right at 176-in-224 and wrong at 150-in-214
        // — the disc now sits 10dp inboard of that ring and cannot be its border.
        val frameStroke = ringStroke()
        val activeStroke = resources.getDimensionPixelSize(R.dimen.stroke_emphasis)
        val insetMid = dp(10)
        val insetInner = dp(22)
        ringOuter = ovalStroke(frameStroke)
        ringMid = ovalStroke(frameStroke)
        ringInner = ovalStroke(activeStroke)
        frame.background = LayerDrawable(arrayOf(ringOuter, ringMid, ringInner)).apply {
            setLayerInset(1, insetMid, insetMid, insetMid, insetMid)
            setLayerInset(2, insetInner, insetInner, insetInner, insetInner)
        }
        // The disc: its fill, and nothing else.
        binding.connectDisc.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest))
        }
        applyRingColor(ringColor)

        // THE PRESS IS DEPRESSION AND NOTHING ELSE — no ripple, no lightening, no highlight, and
        // the rings do not react at all. The owner: «при зажатии кнопки она вся выделяется с этими
        // кружками вокруг кнопки... при нажатии почему-то он светлеет, хотя просто должен
        // прогибаться и все без света какого-то».
        //
        // What was here was a RippleDrawable whose mask was a 224dp OVAL — the frame's whole box,
        // rings included — so holding the object washed a 10% accent over the entire assembly and
        // lit the two rings that are supposed to be a pure state channel. That is both halves of
        // what he reported, from one drawable.
        //
        // The geometry it was hiding behind stays, all of it: @anim/press_scale on this frame,
        // @anim/press_dip on the disc and again on both shields, and the press scrim that darkens
        // the well under the glyph. The object still sinks three layers deep; it just does it in
        // the dark now.
        //
        // The FOCUS ring survives as a plain StateListDrawable. It is not press feedback — it is
        // the 2dp accent outline every focusable control in the product draws, and it is the only
        // thing making the app's primary control reachable by keyboard, D-pad and switch access.
        // Losing it to a press fix would trade one defect for a worse one.
        val focus = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(
                        resources.getDimensionPixelSize(R.dimen.stroke_focus),
                        themeColor(androidx.appcompat.R.attr.colorPrimary),
                    )
                },
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        frame.foreground = focus

        // The confirm rings: the same geometry in the accent, emitted exactly once each. The echo
        // carries the same silhouette a hair thinner, so the pair reads as one ring and its wake
        // rather than as two rings racing (ConnectHeroView.axaml: «тот же силуэт, чуть тоньше»).
        // THE OBJECT'S INK, and it is `colorAccentFigure` and not `colorPrimary`. The two are the
        // same colour in both blue themes, by construction. They part company under the mono
        // overlay, where `colorPrimary` is #111214 — right for a filled button, wrong for a 3dp
        // stroke and a glyph: on a white ground it measures 18.8:1 and the whole object stops
        // reading as an accent and starts reading as a hard outline drawing, which is what the
        // owner photographed. `colorAccentFigure` carries the WEIGHT the accent has (5.99:1 in
        // mono light against the blue accent's 5.97:1) instead of the accent's job. See attrs.xml.
        val accent = themeColor(R.attr.colorAccentFigure)
        val confirmStroke = resources.getDimensionPixelSize(R.dimen.stroke_emphasis)
        binding.connectRingPulse.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(confirmStroke, accent)
        }
        binding.connectRingPulseEcho.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((confirmStroke - 1).coerceAtLeast(1), accent)
        }

        // The monogram's circle: the P3 plane, never the accent.
        binding.layoutHomeAccount.tvAvatarInitial.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest))
        }
    }

    private fun ovalStroke(stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(stroke, Color.TRANSPARENT)
    }

    /**
     * The two outer rings' weight: @dimen/stroke_frame, 1.5dp (handoff §4).
     *
     * It used to be `density * 1.5f` computed here, because the stroke scale had no rung for it —
     * stroke_hairline and stroke_control are 1, stroke_focus and stroke_emphasis 2, stroke_ring 3 —
     * and the same number was written out a second time in `bg_server_row.xml`. §4 gives the row's
     * frame and these rings ONE weight, so they now read one token and cannot drift apart.
     *
     * `1` is still the floor: `getDimensionPixelSize` rounds, and at ldpi a rounded 1.5dp comes out
     * 1px anyway — but a 0px stroke is an invisible ring, so the floor states it rather than
     * trusting the rounding.
     */
    private fun ringStroke(): Int =
        resources.getDimensionPixelSize(R.dimen.stroke_frame).coerceAtLeast(1)

    /** dp -> px, for the two ring insets §4 states in dp and no token names. */
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    /** One hue, three opacities. The geometry never changes; only this does. */
    private fun applyRingColor(colour: Int) {
        val frameStroke = ringStroke()
        val activeStroke = resources.getDimensionPixelSize(R.dimen.stroke_emphasis)
        ringOuter?.setStroke(frameStroke, ColorUtils.setAlphaComponent(colour, RING_ALPHA_OUTER))
        ringMid?.setStroke(frameStroke, ColorUtils.setAlphaComponent(colour, RING_ALPHA_MID))
        // The active ring is the arc's TRACK while the arc is on it, and a track is quieter than
        // what runs on it. The figure is read from [ringTrackAlpha] rather than from the boolean, so
        // the step down and the step back up are a movement and not two states. @see dimRingTrack
        val inner = if (ringTrackAlpha >= OPAQUE) {
            colour
        } else {
            ColorUtils.setAlphaComponent(colour, ringTrackAlpha)
        }
        ringInner?.setStroke(activeStroke, inner)
    }

    /**
     * THE RING HANDS ITS BRIGHTNESS TO THE THING THAT MOVES, for exactly as long as something is
     * moving on it.
     *
     * The arc and the ring it travels were the same colour in every state that spins it — while the
     * core negotiates, `connectActiveColor` is `#4C8DFF` and `colorAccentFigure` resolves to the
     * same `#4C8DFF`; while a подписка refreshes on a live tunnel, both are `colorAccentFigure`
     * outright. A bright arc laid over a bright ring of the same hue does not read as motion, and
     * that is the owner's report: «он не ровно по линии вокруг кнопки крутится … надо цвет чутка
     * темнее сделать чтобы был акцент и видно, что идёт анимация».
     *
     * The prototype never had the problem because it never draws both at full: its negotiating ring
     * is `rgba(76,141,255,.30)` and its sweep is the accent. So the difference is restored where the
     * design puts it — on the RING — and the arc keeps the accent it is tinted with. One rule, no
     * new colour, and it works in all three themes because it is an alpha on whatever hue the state
     * already resolved: blue on blue, white on white, and the mono figure on the mono figure.
     *
     * «Темнее» is what an alpha over this product's grounds actually does. In both dark themes 30 %
     * of the hue over `#0B0D10` lands darker than the hue; in the light theme the track goes pale
     * against a white ground, which is the same statement — quieter than the arc — spelled the way
     * a light theme spells it.
     *
     * Restored as the arc stops, so a connected object is the full-strength accent ring §4 asks for
     * and an idle one is its muted static ring.
     *
     * ## AND IT IS A MOVEMENT
     *
     *     «получается пролаг … потом слишком резко конец анимации и возврат полной заливки вокруг
     *      кольца … надо доработать чтобы это было плавно, а не так резко»
     *
     * This used to be a property write: the boolean flipped and `applyRingColor` repainted the
     * stroke in that frame. So the arc left over 165ms while the ring under it came back in one —
     * two events where the eye expects one, and the harder one landed first. [ringTrackAlpha] is
     * what fixed that: the intent flips in a frame, the VALUE travels.
     *
     * [over] is the length of the movement the arc is making at this instant, handed in by
     * [paintConnect] from [showSweep] / [hideSweep]. Zero means «no movement to join» — a repaint
     * that finds the arc already where it belongs — and then this lands instantly, which is also the
     * reduced-motion answer. **It is a gate on both directions and the CLOCK on only one.**
     *
     * ## …AND THE TWO DIRECTIONS ARE NOT THE SAME MOVEMENT
     *
     *     «полное кольцо оно плавно исчезает и начинается анимация обновления, а после конца
     *      обновления подписки кольцо резко обратно возвращается, надо также чтобы оно плавно
     *      возвращалось вокруг кнопки, я про заливку эту»
     *
     * He is precise, and the report is asymmetric: the way IN is right, the way BACK is not. Both
     * ran on `over` and on `ease_standard`, so the number was never the difference — the CATEGORY
     * was.
     *
     * GOING DOWN, THE RING IS HANDING OVER. The arc is arriving on top of it and the eye is on the
     * arc; the ring's fade is the background half of one gesture, so it belongs on the arc's clock
     * (`motion_windup`, 200) and finishes with it. Untouched.
     *
     * COMING BACK, THE RING IS THE ONLY THING LEFT ON SCREEN. The arc is dissolving and there is
     * nothing else to look at, so this is not the tail of an exit — it is an ELEMENT ARRIVING ON A
     * SCREEN THAT IS ALREADY THERE, which motion.xml §8 names and prices: `motion_appear` (460) on
     * `ease_out_quint`. Joined to the exit it inherited two things it should never have had:
     *
     *  - `ease_standard` is cubic-bezier(.2, 0, 0, 1) — HALF the travel in the first fifth of the
     *    window. Over an exit that is what you want, because an exit should be gone. Over the one
     *    object the eye is now locked to it is a step with a tail, which is «резко» exactly.
     *  - [hideSweep] hands over [windDownSweep]'s window, and that window is 165–440ms depending on
     *    WHERE THE ARC HAPPENED TO BE STANDING when the подписка landed. The same gesture came out a
     *    different length on every refresh, and at the floor it was back to full in under 90ms.
     *
     * The two still START in the same frame, which is what makes them one gesture; they simply do
     * not have to END together. The arc leaves, the track it was riding fills back in behind it.
     */
    private fun dimRingTrack(dimmed: Boolean, over: Long = 0L) {
        if (ringTrackDimmed == dimmed) return
        ringTrackDimmed = dimmed
        val target = if (dimmed) RING_ALPHA_TRACK else OPAQUE
        // Two of these in flight would fight over one stroke; the newer intent wins outright.
        ringTrackAnimator?.cancel()
        ringTrackAnimator = null
        if (over <= 0L || !isBindingInitialized || binding.connectFrame.reducedMotion()) {
            ringTrackAlpha = target
            applyRingColor(ringColor)
            return
        }
        // The hand-over rides the arc; the return is an appearance and keeps its own tempo.
        val travel = if (dimmed) over else durAppear
        val curve = if (dimmed) easeStandard else easeOutQuint
        ringTrackAnimator = ValueAnimator.ofInt(ringTrackAlpha, target).apply {
            duration = travel
            interpolator = curve
            addUpdateListener {
                ringTrackAlpha = it.animatedValue as Int
                // `ringColor` is read live: a state tint may be crossing at the same time, and the
                // track's opacity has to ride whatever hue that tween is on rather than freeze one.
                applyRingColor(ringColor)
            }
            start()
        }
    }

    // ==================== THE ENTRANCE (handoff §3, «Сборка главной») ====================

    /**
     * ARMS the entrance without playing it: Главная is stamped into its pre-entrance state and will
     * stay there until [playEntrance] is called.
     *
     * THIS IS ONE HALF OF THE OVERLAY HAND-OFF (§11 grabl 6). Whoever raises a full-screen loading
     * overlay over this screen calls `MainActivity.holdHomeEntrance()` BEFORE the overlay goes up;
     * without it Главная assembles itself behind the overlay, finishes long before the overlay is
     * taken down, and what the user finally sees is the whole screen at once — which is precisely
     * the flash grabl 6 is about.
     *
     * Safe at any point in the fragment's life and safe to call twice.
     */
    fun holdEntrance() {
        if (!isBindingInitialized) return
        entrancePlayed = false
        // THE ORDER OF THESE TWO LINES IS THE FIX, and reversing it is what let the owner see
        // «появляется кнопка потом заново начинается анимация появления кнопок и серверов».
        //
        // [cancelEntrance] cancels the AnimatorSet, and an AnimatorSet that is RUNNING answers
        // cancel() by calling its listeners — whose `onAnimationCancel` is [clearEntrance], which
        // sets `entranceHeld = false`. Raising the flag first therefore lowered it again on any
        // hold taken while an entrance was in flight, which is precisely the hold the flow takes:
        // Главная assembles itself at launch, the user reaches the gate's «Войти через Telegram»
        // and the overlay goes up over a table that is still playing. From then on the hold was a
        // flag nobody was holding — [render] stopped re-priming, every repaint under the overlay
        // wrote resting values, and the overlay's 520ms fade uncovered a finished screen. The frame
        // that removed it then re-primed and played the whole table a second time.
        //
        // So: drop the animator first, and only then declare the hold. AND IT STAYS HELD after
        // that — see [entranceHeld]; setting the start values once was never enough.
        cancelEntrance()
        entranceHeld = true
        primeEntrance()
    }

    /**
     * PLAYS the entrance, and this is the single call the overlay's owner makes.
     *
     * THE OTHER HALF OF GRABL 6, and the reason it is one method rather than two: it stamps the
     * start values and starts the animators SYNCHRONOUSLY, inside this call, so the caller can
     * write
     *
     *     mainActivity.revealHome { overlay.removeFromParent() }
     *
     * and have the overlay's removal and the first frame of the assemble land in the same
     * traversal. Nothing here is posted or delayed — a `postDelayed` between the two is exactly
     * how the screen gets one frame of finished Главная in the middle.
     *
     * Plays ONCE per view. A repaint, a tab switch, a subscription refresh and a rotation all reach
     * this file and none of them is an entrance; [holdEntrance] is the only thing that re-arms it.
     */
    fun playEntrance() {
        if (!isBindingInitialized || entrancePlayed) return
        entrancePlayed = true
        // The hold is over the instant the table starts: from here on a repaint writes resting
        // values because the elements are on their way to them.
        entranceHeld = false
        if (binding.connectFrame.reducedMotion()) {
            clearEntrance()
            return
        }
        primeEntrance()
        val set = AnimatorSet()
        set.playTogether(entranceSteps().flatMap { it.animators() })
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = clearEntrance()
            override fun onAnimationCancel(animation: Animator) = clearEntrance()
        })
        entranceAnimator = set
        set.start()
        playServerRowEntrance()
    }

    /**
     * The seven elements of §3's table, in its own order and with its own numbers.
     *
     * ONE AnimatorSet WITH START DELAYS, NEVER A CHAIN OF postDelayed. A chain outlives the screen:
     * every link is a message on the main looper holding a reference to a view that may be detached
     * by the time it runs, and cancelling it means remembering every token. An AnimatorSet is one
     * handle, [cancelEntrance] drops it in one line, and the delays are declarative.
     */
    private fun entranceSteps(): List<EntranceStep> = listOf(
        // Строка аккаунта — сверху, −22dp, 460 мс, задержка 60 мс.
        EntranceStep(binding.layoutHomeAccount.root, -ENTRANCE_DROP, 1f, durAppear, 60),
        // Кнопка «+» — сверху, −22dp, 460 мс, 130 мс.
        EntranceStep(binding.btnHomeAdd, -ENTRANCE_DROP, 1f, durAppear, 130),
        // NO STEP FOR @id/cta_link_telegram. It briefly had one — it sits in the «+»'s band and on
        // the clipboard path it was the one thing that could show through the overlay's fade while
        // the rest of the screen was parked — but the owner took the banner off Главная entirely
        // on 2026-08-05 and [paintLinkCta] now writes it hidden on every repaint. An entrance for
        // a view that is never on screen is six animators a frame budget does not owe.
        // Кольцо кнопки — из 0.7×, 720 мс, 180 мс. The one element that scales rather than travels:
        // it is the centre of the screen and it arrives by growing into its own place.
        EntranceStep(binding.connectFrame, 0f, ENTRANCE_RING_SCALE, durAppearSlow, 180),
        // THE STATUS PILL ARRIVES WITH THE RING, and the prototype is unambiguous about it: the
        // pill is INSIDE the block that carries `animation:{{ en.ring }}`, so `bloomIn 720ms …
        // 180ms` is its entrance too. It had none here, which meant the one element on the screen
        // that was already at full opacity while everything else assembled was a grey pill reading
        // «Не подключено» — and with the hold now sticky (see [entranceHeld]) that would have been
        // the single thing visible through the overlay's fade.
        //
        // Opacity only, no scale: the prototype scales the ring and the pill together about the
        // GROUP's centre, which a sibling view cannot reproduce — scaling the pill about its own
        // centre would re-rasterise a text label for 720ms, which is §11 грабля 1. The timing is
        // the design's; the transform is the one the design's group geometry actually implies.
        EntranceStep(binding.tvConnectedPill, 0f, 1f, durAppearSlow, 180),
        // Скорости — снизу, +26dp, 520 мс, 420 мс.
        EntranceStep(binding.numericStrip, ENTRANCE_RISE, 1f, ENTRANCE_MID_MS, 420),
        // Название сервера — снизу, +26dp, 520 мс, 500 мс.
        EntranceStep(binding.serverIdentity, ENTRANCE_RISE, 1f, ENTRANCE_MID_MS, 500),
        // Карточка провайдера — снизу, +26dp, 560 мс, 580 мс.
        EntranceStep(binding.subscriptionSlot, ENTRANCE_RISE, 1f, durScreen, 580),
    )

    /**
     * One element of the assemble: where it comes from, how long it takes and when it starts.
     *
     * The travel is in dp and converted here, because §3 states it in dp and a px constant would be
     * a different distance on every phone. The curve is `cubic-bezier(0.22, 1, 0.36, 1)` for all
     * seven rows of the table — @interpolator/ease_out_quint is exactly that curve, so no new
     * interpolator was added.
     */
    private inner class EntranceStep(
        val view: View,
        val fromDp: Float,
        val fromScale: Float,
        val duration: Long,
        val delayMs: Long,
    ) {
        /**
         * Where this element's opacity BELONGS when the assemble is over, which is not always 1.
         *
         * The connect object dims to DISABLED_ALPHA when there is nothing to connect to — gated, no
         * server, disconnecting — and that is written on the same property the entrance fades. An
         * assemble that ended at a flat 1f would light the disabled object up and leave it lit,
         * because paintConnect only rewrites the alpha the next time the state changes. On the
         * first-run path, where there IS no server, that is every launch.
         */
        val restingAlpha: Float
            get() = if (view.isEnabled) 1f else DISABLED_ALPHA

        fun animators(): List<Animator> {
            val from = fromDp * resources.displayMetrics.density
            val moves = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, restingAlpha),
            )
            if (from != 0f) moves += ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, from, 0f)
            if (fromScale != 1f) {
                moves += ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, 1f)
                moves += ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, 1f)
            }
            moves.forEach {
                it.duration = duration
                it.startDelay = delayMs
                it.interpolator = easeOutQuint
            }
            return moves
        }
    }

    /**
     * Строки серверов — «слева, −44dp, 560 мс, 700 мс + 85 мс на каждую».
     *
     * ONLY THE ROWS THAT ARE ON SCREEN WHEN IT STARTS, and that is why this walks the
     * RecyclerView's ATTACHED CHILDREN instead of counting adapter positions. A list of thirty
     * servers has thirty positions and only six of them are visible; staggering by position would
     * hand the thirtieth row 700 + 29 × 85 ≈ 3.2 seconds of delay and then play its slide when the
     * user finally scrolls down to it, which reads as the list hanging. The children of the
     * RecyclerView at this instant ARE the visible set, so the stagger is bounded by what fits on a
     * screen and the rest of the list is simply already in place.
     *
     * It is also why this is not in onBindViewHolder: bind runs on every scroll, so the slide would
     * replay for the rest of the session. The reset is the adapter's
     * (MainRecyclerAdapter.onViewRecycled) — a row recycled mid-slide must not carry a stale
     * translationX into whatever server it is re-used for.
     *
     * doOnPreDraw and not post{}: the list is asked for its children at the last moment before the
     * frame is drawn, which is the first instant they exist. It is one-shot and it detaches itself
     * with the view.
     */
    private fun playServerRowEntrance() {
        val list = binding.rvHomeServers
        list.doOnPreDraw {
            if (!isBindingInitialized) return@doOnPreDraw
            // The LIST's own opacity is what held every row the flow bound while the entrance was
            // parked (see [primeEntrance]): rows arrive from the adapter one at a time and there is
            // no other moment at which all of them can be caught. It is released HERE, inside the
            // pre-draw, in the same pass that stamps the rows' own start values — so no frame can
            // exist in which the list is opaque and its rows have not been parked yet.
            list.alpha = 1f
            val travel = -ENTRANCE_ROW_SLIDE * resources.displayMetrics.density
            list.children.forEachIndexed { index, row ->
                row.animate().cancel()
                row.alpha = 0f
                row.translationX = travel
                row.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setStartDelay(ENTRANCE_ROWS_DELAY_MS + index * ENTRANCE_ROW_STAGGER_MS)
                    .setDuration(durScreen)
                    .setInterpolator(easeOutQuint)
                    .withEndAction {
                        row.translationX = 0f
                        row.alpha = 1f
                    }
                    .start()
            }
        }
    }

    /**
     * Stamps every element of the assemble into its start state.
     *
     * IT ALSO CANCELS WHATEVER WAS MOVING THEM. While the entrance is held, [render] runs — the
     * подписка lands, the gate goes, the slot crossfades — and `paintSlot` starts a
     * ViewPropertyAnimator on the subscription slot's alpha. Stamping a value under a running
     * animator does not stop it: it re-reads the property on its next frame and carries on to 1.
     * So each animator is dropped before its view is parked.
     *
     * THE SERVER LIST IS PARKED AS ONE OBJECT, on the list's own alpha rather than on its rows'.
     * Rows are bound by the adapter over several frames while the flow runs, so a per-row prime
     * catches only the ones that already exist; the list's own opacity catches every row there
     * will ever be. [playServerRowEntrance] releases it in the pre-draw pass that parks the rows.
     */
    private fun primeEntrance() {
        entranceSteps().forEach { step ->
            step.view.animate().cancel()
            step.view.alpha = 0f
            step.view.translationY = step.fromDp * resources.displayMetrics.density
            step.view.scaleX = step.fromScale
            step.view.scaleY = step.fromScale
        }
        binding.rvHomeServers.alpha = 0f
        binding.rvHomeServers.children.forEach { it.alpha = 0f }
        // THE GATE BLOCK IS PARKED WHILE THE SCREEN IS HELD, AND ONLY THEN. It is not one of §3's
        // seven rows and must not become one — the table is the design's and this block is the
        // подписка slot's alternate occupant, arriving through paintSlot's own crossfade.
        //
        // It has to be parked for the HOLD because the release now waits for the server list, so
        // there is a real interval in which the overlay is gone and the table has not started yet.
        // Everything in the table is invisible there; the gate, which was not, would have been the
        // one thing on screen — on the Telegram path the state at that instant is «Загрузить
        // серверы», i.e. this block with a button on it, shown for as long as the fetch takes and
        // then replaced by the подписка card. Exactly the flash the hold exists to prevent.
        //
        // [playEntrance] clears the hold before it primes, so the release does not park it again;
        // [clearEntrance] hands its opacity back either way.
        if (entranceHeld) binding.layoutGate.gate.alpha = 0f
    }

    /**
     * Returns every element to its resting transform.
     *
     * THE TRANSFORMS ARE ALWAYS CLEARED, never left at their end values, because a translationY of
     * "0" that arrived from an animation is not the same thing as a view with no transform: it
     * keeps the view on its own render node and it is what leaks into a recycled row. Runs on both
     * end and cancel.
     */
    private fun clearEntrance() {
        if (!isBindingInitialized) return
        entranceHeld = false
        entranceSteps().forEach { step ->
            step.view.alpha = step.restingAlpha
            step.view.translationY = 0f
            step.view.scaleX = 1f
            step.view.scaleY = 1f
        }
        binding.rvHomeServers.alpha = 1f
        binding.rvHomeServers.children.forEach {
            it.animate().cancel()
            it.alpha = 1f
            it.translationX = 0f
        }
        // …and the gate block, which [primeEntrance] parks alongside them. paintSlot owns its
        // VISIBILITY and its crossfade; this only ever hands its opacity back.
        binding.layoutGate.gate.alpha = 1f
    }

    /** Drops the whole assemble — one handle for the set, one loop for the rows. */
    private fun cancelEntrance() {
        entranceAnimator?.cancel()
        entranceAnimator = null
    }

    /**
     * Whether §3's table is between its first frame and its last — the window in which this screen
     * is not yet a screen the user has been shown.
     *
     * Two readers, and they want the same window for different reasons: the confirmation must not
     * play in it (a state learned during the assemble is a restore, not an event), and the ring's
     * sonar must not be emitted in it (the connect object is mid-fade there, and a ViewGroup with
     * alpha < 1 composites its children into a layer the size of its own box — see
     * [buildConnectObject]).
     */
    private fun entranceInFlight(): Boolean =
        entranceHeld || entranceAnimator?.isRunning == true

    private fun wireConnect() {
        binding.connectFrame.onSingleClick(Haptic.PRESS) { handleConnectAction() }
    }

    private fun wireHeaderRow() {
        binding.layoutHomeAccount.rowAccount.onSingleClick { openAccount() }
        binding.btnHomeAdd.onSingleClick { mainHost.showAddMenu(it) }
        // The restored «Привязать Telegram» banner: the banner itself is the action, the ✕ is the
        // dismissal, and the dismissal is written to MMKV so the offer is made once.
        binding.ctaLinkTelegram.onSingleClick { openTelegramLink() }
        // The ✕ is a TextView, so its press comes from here and not from a stateListAnimator:
        // pressFeedback is the same @anim/press_icon plus the hardware layer that keeps a glyph
        // from twitching as the 12% rebound lands (README §11 грабля 1).
        binding.btnCtaDismiss.pressFeedback(R.anim.press_icon)
        binding.btnCtaDismiss.onSingleClick {
            MmkvManager.encodeSettings(AppConfig.PREF_LINK_TG_CTA_DISMISSED, true)
            binding.ctaLinkTelegram.isVisible = false
        }
    }

    /**
     * Главная's «Привязать Telegram» banner — OFF, BY OWNER INSTRUCTION, 2026-08-05:
     *
     *     «при обычной подписке сверху показывается привяжите телеграм, хотя убрать эту плашку
     *      надо полностью»
     *
     * The banner used to appear for a departament подписка that was PASTED by a user who had never
     * signed in — `BackendConfig.isConfigured() && !isLoggedIn && !dismissed &&
     * SubscriptionOrigin.hasDepartamentSubscription()`. That whole condition is gone, and with it
     * the last reader of `SubscriptionOrigin` on this screen.
     *
     * WHAT IS REMOVED IS THE DISPLAY, NOT THE FEATURE. The block stays in `fragment_home.xml`
     * (already `visibility="gone"` there), its tap and its ✕ stay wired in [wireHeaderRow], and
     * [openTelegramLink] stays the live entry point it has always been — it is still what the gate
     * block's secondary calls in its BUY shape, which is a different screen and a different case.
     * Linking a Telegram account moves to Аккаунт, in its «подписка есть, аккаунт не привязан»
     * state; that is another wave's file.
     *
     * This is still written on every repaint, in one direction, so nothing else can leave the
     * banner on screen.
     */
    private fun paintLinkCta() {
        binding.ctaLinkTelegram.isVisible = false
    }

    /**
     * The inline placement of the shared status strip. The component file is the shell's DOCKED bar
     * — full-bleed, hairline on top — so two properties are adjusted here and nowhere else: the
     * hairline goes, and the bar is clipped to @dimen/radius_control so it reads as a block inside
     * the content rather than as an edge of the window.
     */
    private fun wireStatusStrip() {
        val strip = binding.layoutStatusStrip
        strip.statusStripHairline.isVisible = false
        val radius = resources.getDimension(R.dimen.radius_control)
        strip.statusStripBar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        strip.statusStripBar.clipToOutline = true
        strip.statusStrip.isVisible = false
    }

    // ==================== The inline server list ====================

    /**
     * Builds Главная's server list. Row actions come from [MainHost.serverActions] — one listener
     * for every list in the app — and a long press opens the shell's server-actions sheet, which is
     * the only route to edit, share, QR and delete.
     *
     * Tapping a row SELECTS and never connects; that contract lives in `MainActivity.setSelectServer`
     * and is deliberately not re-implemented here.
     */
    private fun setupServerList() {
        val listAdapter = MainRecyclerAdapter(mainViewModel, mainHost.serverActions)
        listAdapter.onItemLongClick = { guid -> mainHost.showServerActions(guid) }
        homeAdapter = listAdapter
        binding.rvHomeServers.apply {
            setHasFixedSize(false)
            layoutManager = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
            // NO ItemDecoration. The list used to carry @drawable/custom_divider — a 1dp line
            // inset 44dp, drawn between every pair of rows — and the redesign's separator is
            // already on the row itself: @drawable/bg_server_row paints it as an inner top
            // shadow on `state_activated`, which MainRecyclerAdapter sets everywhere except the
            // first row and the selected one (§4: «У первой строки и у выбранной разделителя
            // нет»). Both together drew two hairlines in the same 2dp gap and put one straight
            // through the bottom of the selected row's accent frame, which is the exact failure
            // §11 grabl 3 describes and the reason the separator moved into the background in
            // the first place.
            adapter = listAdapter
        }
        refreshServerList(-1)
    }

    /**
     * Repaints the list from the one cache — showing ONLY the servers of the подписка under the
     * thumb.
     *
     * THE CARD AND THE LIST ARE ONE OBJECT. Every подписка's servers used to be flattened into a
     * single list under a carousel of several cards: «когда добавляешь несколько подписок, сервера
     * все в один список делаются, просто стакаются друг на друга». Swiping to another card changed
     * the header and nothing under it, so the card claimed a подписка the list did not keep.
     * Filtering by the current page makes the card the list's real heading — which is also why
     * section headers stay OFF here: a second heading inside the list would say the same thing
     * twice.
     *
     * Servers with no подписка of their own (local, imported by link) belong to no card, so they
     * show when there is no carousel at all — otherwise they would become unreachable, and a row
     * the user can no longer find is a regression however tidy the grouping looks.
     *
     * This does NOT touch the carousel, because a bulk ping delivers one result per server and
     * rebuilding a ViewPager2 once per server would thrash it for a change no card shows.
     */
    private fun refreshServerList(index: Int) {
        val all = mainViewModel.serversCache
        val pageSubId = homeMetaSubIds.getOrNull(homeMetaPage)
        val shown = if (pageSubId.isNullOrEmpty()) {
            all
        } else {
            all.filter { it.profile.subscriptionId == pageSubId }
        }
        // `index` addresses a row in the UNFILTERED cache (it is the position the ViewModel just
        // touched), so translate it before handing it on — an index resolved against a different
        // list repaints the wrong row.
        val targetGuid = all.getOrNull(index)?.guid
        val shownIndex = targetGuid?.let { guid -> shown.indexOfFirst { it.guid == guid } } ?: -1
        homeAdapter?.setSections(shown, index = shownIndex)
    }

    /**
     * The cache itself changed — an import, a delete, a refresh — so both surfaces follow it.
     *
     * The CAROUSEL goes first and the list second, because the list is now filtered by the page the
     * carousel settles on: rebuilding it the other way round paints one подписка's servers under
     * another подписка's card for a frame, and after a delete it would filter against a подписка
     * that no longer exists.
     */
    private fun refreshServerSurfaces(index: Int) {
        rebuildHomeMeta()
        refreshServerList(index)
    }

    // ==================== The subscription card carousel ====================

    /**
     * One page per подписка. Per-page actions carry the page's own subscription id — pin, delete,
     * support and Telegram act on the card under the thumb — while collapse, ping and refresh are
     * genuinely list-wide.
     */
    private fun setupHomeMetaPager() {
        val pagerAdapter = HomeMetaPagerAdapter(
            bindPage = { meta, subId, sub -> bindMetaBar(meta, subId, sub) },
            onToggleList = { toggleHomeServerList() },
            // "A check is in flight" is the ViewModel's own transient state (`isMeasuring`) and it
            // publishes a repaint itself, so this asks for the test and nothing else. Marking the
            // rows here by writing a negative delay into the STORE is what the shipped build did,
            // and it is exactly what `ServerAffiliationInfo` forbids: a stored negative outlives
            // the run that wrote it and every reader treats it as "unreachable" — including
            // «Удалить недоступные», which then deletes the server.
            onPingAll = { mainViewModel.testAllServers() },
            onRefreshAll = { mainHost.refreshSubscriptions() },
            onTogglePin = { subId -> toggleHomePin(subId) },
            onDeleteSub = { subId -> confirmDeleteSubscription(subId) },
            onOpenSupport = { subId -> openSubUrl(MmkvManager.decodeSubscription(subId)?.supportUrl) },
            onOpenTelegram = { subId -> openSubUrl(MmkvManager.decodeSubscription(subId)?.supportUrl) },
            collapsed = { homeListCollapsed },
        )
        homeMetaAdapter = pagerAdapter
        binding.vpHomeMeta.apply {
            adapter = pagerAdapter
            offscreenPageLimit = 1
            clipToPadding = false
            // THE CARD NEVER ANIMATES ITSELF. This is the root cause of the two defects the owner
            // kept hitting on this card, and both of them are the RecyclerView ItemAnimator that
            // ViewPager2 keeps inside itself:
            //
            //   «моргает тулбар с подпиской, он ваще должен быть статичный»
            //   «сам тулбар с подпиской опять багуется и двигается слева вправо и там заезжает
            //    за экран»
            //
            // A ViewPager2 IS a RecyclerView, and DefaultItemAnimator's CHANGE animation cross-fades
            // a scrap copy of the item over the real one while translating the incoming view from
            // its pre-layout bounds to its post-layout bounds. On a page that fills the viewport,
            // that delta is a whole page width the moment the item COUNT changes under a non-zero
            // scroll offset — which is exactly the owner's repro: add a second подписка, delete it,
            // come back to the first, then collapse the list. The card slides across and off screen.
            //
            // The reason it survived the previous fix is that ViewPager2 owns this animator and
            // hands it back and forth: setPageTransformer(non-null) saves the animator and nulls it,
            // setPageTransformer(null) RESTORES it. rebuildHomeMeta used to swap the transformer in
            // and out with the подписка count, so dropping from two подписки to one handed the
            // animator back and re-armed the bug. Hence both lines below: strip the animator here,
            // once, and never let the transformer be set to null again (see setPageTransformer
            // below, which is now unconditional).
            //
            // THE STRETCH GOES WITH IT, and it is the third report on this card: «сам тулбар
            // подписки … почему-то можно растягивать и тянуть влево или вправо, надо убрать это».
            // From Android 12 a RecyclerView answers a drag past its own edge with the stretch
            // over-scroll — the whole page is pulled sideways and springs back. On a carousel of
            // ONE there is nowhere to go, so every horizontal drag on the card is over-scroll and
            // the card is rubber. It is set on the inner RecyclerView and not on the pager,
            // because ViewPager2 forwards nothing of the sort — `android:overScrollMode` on the
            // pager itself reaches a container that does not scroll.
            //
            // ALWAYS, not just at one подписка: a card that stretches at the ends of a real
            // carousel is the same defect with a smaller repro. Swiping BETWEEN подписки is
            // untouched — that is the pager's own scroll, not over-scroll.
            //
            // …AND THE PAGE STOPS BEING CUT OFF AT THE GUTTER, which is the third report on this
            // one object: «когда листаешь несколько подписок, то видишь у них рамки появляются по
            // бокам, хотя должно быть по другому, а не обрезаться».
            //
            // ViewPager2 lays its inner RecyclerView out INSIDE its own padding (ViewPager2.onLayout
            // → measureChild), so the 16dp gutter above already makes a page narrower than the
            // screen — that half was here. The neighbouring page is then laid out BESIDE it, i.e.
            // outside the RecyclerView's own box, and a ViewGroup clips its children by default:
            // the peeking card was chopped by a vertical line 16dp in from each screen edge, mid
            // word. That straight cut is the «рамка» in his screenshot.
            //
            // ONE SWITCH, AND ONLY ON THE INNER RecyclerView. The pager itself keeps clipChildren
            // ON, so the overflow is still trimmed at the pager's own bounds — the screen edges —
            // and nothing here reaches a scroll container or the window root. That distinction is
            // not academic: `SelectPopup.unclipAncestors` drew the settings screen through the
            // status bar by walking one level too far, and this is the same walk with one step.
            (getChildAt(0) as? RecyclerView)?.let { inner ->
                inner.itemAnimator = null
                inner.overScrollMode = View.OVER_SCROLL_NEVER
                inner.clipChildren = false
            }
            // Neighbour cards peek past the 16dp gutter; a 12dp gap keeps them from touching. With
            // one подписка every page sits at position 0, so the transformer applies a zero offset
            // and costs nothing — which is why it can safely be permanent.
            setPageTransformer(
                CompositePageTransformer().apply {
                    addTransformer(
                        MarginPageTransformer(resources.getDimensionPixelSize(R.dimen.space_12))
                    )
                }
            )
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private var dragged = false

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) dragged = true
                }

                override fun onPageSelected(position: Int) {
                    homeMetaPage = position
                    updateHomeMetaDots(position)
                    // The list under the card belongs to the card: swiping to another подписка
                    // shows that подписка's servers, not the same flat pile under a new heading.
                    refreshServerList(-1)
                    if (dragged) {
                        if (isBindingInitialized) binding.vpHomeMeta.tickHaptic()
                        dragged = false
                    }
                }
            })
        }
        rebuildHomeMeta()
    }

    /**
     * Rebuilds the carousel from the current groups, keeping the user on the same подписка across
     * rebuilds (pin reorders, refresh, deletes) by restoring the page by its subscription id. Page
     * dots and the inter-page gap appear only past one card.
     */
    private fun rebuildHomeMeta() {
        val adapter = homeMetaAdapter ?: return
        val ids = mainViewModel.getProviderGroups().map { it.id }.filter { it.isNotEmpty() }
        val keepSubId = homeMetaSubIds.getOrNull(homeMetaPage)
        val count = ids.size
        val many = count > 1
        // A подписка with no card is a slot with no content: the carousel is only on screen when
        // there is at least one to draw. Local-only servers have no подписка and no card.
        binding.groupHomeMeta.isVisible = count > 0

        // Same подписки, changed contents (a refresh moved the traffic figure and the timestamp):
        // repaint the pages in place rather than replacing them, so the card under the thumb does
        // not jump back to the first one every time a subscription updates.
        if (ids == homeMetaSubIds && adapter.itemCount == count) {
            adapter.repaint()
            measureHomeMetaHeight()
            return
        }
        val shrank = count < homeMetaSubIds.size
        homeMetaSubIds = ids
        adapter.submit(ids)
        // The page transformer is NOT touched here any more, and that is deliberate — it is set
        // once in setupHomeMetaPager and stays. Swapping it with the подписка count is what handed
        // ViewPager2's ItemAnimator back the moment a second подписка was deleted, and the animator
        // is what slid the card off screen on the next repaint. Read the note there.
        //
        // Keep the user on the card they were reading; on the FIRST build there is no such card, so
        // open on the подписка the selected server belongs to rather than always on the first one.
        val restore = keepSubId?.let { ids.indexOf(it) }?.takeIf { it >= 0 }
            ?: ids.indexOf(currentMetaSubId()).takeIf { it >= 0 }
            ?: 0
        homeMetaPage = restore.coerceIn(0, (count - 1).coerceAtLeast(0))
        if (count > 0) {
            binding.vpHomeMeta.setCurrentItem(homeMetaPage, false)
            // AND WHEN THE COUNT SHRANK, ANCHOR THE LIST BY HAND. `setCurrentItem` returns early
            // when the target equals ViewPager2's own `mCurrentItem` and it is idle — which is the
            // common case after a delete (he was reading page 0, page 1 went away, the target is
            // still 0) — so nothing resets the inner RecyclerView, and its LinearLayoutManager
            // re-anchors on whatever stale child it can still find after a structural change. That
            // is how a one-page carousel ends up laid out at a one-page scroll offset, i.e. off
            // screen. `scrollToPosition` sets a pending scroll position, which makes the next
            // layout pass anchor from scratch. Null-safe: if the child is ever not a RecyclerView
            // this is simply a no-op, and the setCurrentItem above still ran.
            if (shrank) (binding.vpHomeMeta.getChildAt(0) as? RecyclerView)?.scrollToPosition(homeMetaPage)
        }
        buildHomeMetaDots(count)
        updateHomeMetaDots(homeMetaPage)
        binding.llHomeMetaDots.isVisible = many
        applyHomeMetaSwipe(many)
        measureHomeMetaHeight()
    }

    /**
     * THE CARD IS ONLY DRAGGABLE WHEN THERE IS SOMEWHERE TO DRAG IT.
     *
     * «сам тулбар подписки где вся инфа о ней на главной почему-то можно растягивать и тянуть влево
     * или вправо, надо убрать это чтобы нельзя было тянуть никуда» — and with one подписка that is
     * literally true: the pager has a single page, so every horizontal drag on the card ends where
     * it started, having moved the one thing on the screen the user cannot act on.
     *
     * THE CAROUSEL ITSELF STAYS (PORT-DELTA П-10). This is the input flag and nothing else: past one
     * подписка the swipe, the dots and the haptic all come back exactly as they were, and the flag
     * is written on every rebuild rather than once at construction, so adding the second подписка
     * turns the gesture on in the same pass that adds its dot.
     */
    private fun applyHomeMetaSwipe(many: Boolean) {
        binding.vpHomeMeta.isUserInputEnabled = many
    }

    /**
     * ViewPager2 cannot wrap_content, so fix its height to the tallest page. Each page's height
     * varies (traffic row, the operator's notice), so measure every подписка's card at the page
     * width and take the max — one stable height, so peeking neighbours stay aligned.
     */
    private fun measureHomeMetaHeight() {
        if (homeMetaSubIds.isEmpty()) return
        binding.vpHomeMeta.doOnPreDraw {
            if (!isBindingInitialized) return@doOnPreDraw
            val pager = binding.vpHomeMeta
            val innerWidth = pager.width - pager.paddingStart - pager.paddingEnd
            if (innerWidth <= 0) return@doOnPreDraw
            val widthSpec = View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val inflater = LayoutInflater.from(requireContext())
            var maxH = 0
            for (id in homeMetaSubIds) {
                val sub = MmkvManager.decodeSubscription(id) ?: continue
                val probe = LayoutSubscriptionMetaBarBinding.inflate(inflater, pager, false)
                (probe.root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
                bindMetaBar(probe, id, sub)
                probe.root.measure(widthSpec, heightSpec)
                maxH = maxOf(maxH, probe.root.measuredHeight)
            }
            if (maxH > 0 && pager.layoutParams.height != maxH) {
                pager.layoutParams = pager.layoutParams.apply { height = maxH }
            }
        }
    }

    /** Rebuilds the page dots to match [count] pages (nothing shown for 0/1 page). */
    private fun buildHomeMetaDots(count: Int) {
        val container = binding.llHomeMetaDots
        container.removeAllViews()
        if (count <= 1) return
        val size = resources.getDimensionPixelSize(R.dimen.dot_size)
        val activeSize = resources.getDimensionPixelSize(R.dimen.dot_size_active)
        val gap = resources.getDimensionPixelSize(R.dimen.space_4)
        for (i in 0 until count) {
            val selected = i == homeMetaPage
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
    private fun updateHomeMetaDots(position: Int) {
        val container = binding.llHomeMetaDots
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

    /**
     * The card's chevron shows and hides the server list under it — never the card's own body, which
     * stays on screen so the traffic figure is always readable.
     */
    private fun toggleHomeServerList() {
        homeListCollapsed = !homeListCollapsed
        // The chevron lives on EVERY page and the collapse is global, so all of them follow it —
        // not just the one under the thumb, which would leave the neighbour pointing the wrong way.
        homeMetaAdapter?.repaint()
        render()
    }

    private fun toggleHomePin(subId: String) {
        val sub = MmkvManager.decodeSubscription(subId) ?: return
        sub.pinned = !sub.pinned
        MmkvManager.encodeSubscription(subId, sub)
        homeMetaAdapter?.notifyItemChanged(homeMetaPage)
        mainViewModel.reloadServerList()
    }

    /**
     * Deleting a подписка, from the card that shows it. The owner reported that подписки could not
     * be deleted on the phone at all — the card that carried this was removed with the rest of the
     * screen, and nothing replaced it.
     */
    private fun confirmDeleteSubscription(subId: String) {
        if (subId.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_sub_delete_title)
            .setMessage(R.string.home_sub_delete_message)
            .setPositiveButton(R.string.home_sub_delete_confirm) { _, _ ->
                MmkvManager.removeSubscription(subId)
                mainViewModel.reloadServerList()
                showStatus(getString(R.string.home_sub_deleted))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opens the подписка's own link, and says so plainly when the device has nothing to open it. */
    private fun openSubUrl(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { showStatus(getString(R.string.home_sub_link_failed)) }
    }

    /**
     * The card's heading — THE ПОДПИСКА'S OWN NAME, and never the name of the service it belongs to.
     *
     * The owner reported the card reading «departament vpn» where his подписка is called «🍀 erlish».
     * That string is the generic service label the backend returns as `tariffDisplayName`; the
     * import used to stamp it into the local remark (`SubscriptionSyncManager`), and a remark
     * outranked the провайдер's own `profile-title` header — which is where the real name lives. The
     * import no longer writes it, and this order no longer lets a generic label win even on an
     * install that already has one stored.
     *
     * The order, and why each step is where it is:
     *
     *  1. the account's `displayName` — the nickname the user set in the cabinet. His instruction,
     *     «чтобы при подтягивании подписки с акка писался ник подписки», is exactly this field.
     *  2. the провайдер's `profile-title` — «🍀 erlish». Authoritative for a подписка the user never
     *     renamed, and the only name a PASTED подписка has at all.
     *  3. the local remark — the same nickname one refresh behind, for the window before the account
     *     or the провайдер has answered.
     *  4. the account's `defaultLabel` — «Подписка #2». A real per-подписка label, but a generated
     *     one, so it sits below anything a human or the провайдер chose.
     *  5. «Подписка».
     *
     * Steps 2 and 3 both refuse a GENERIC name: the two import placeholders, and the service label
     * itself. Editing a подписка is not a feature (OWNER-DECISION-2026-08-02 §5), so a bad automatic
     * name is permanent — the filter is the only thing standing between him and it.
     */
    private fun metaTitle(subId: String, sub: SubscriptionItem): String {
        val account = accountNameFor(subId)
        // THE RANKING LIVES IN [SubscriptionNaming] NOW, and not here. It used to be this Fragment's
        // private business, which is precisely why the background worker that posts «Обновляем «…»»
        // could not reach it and formatted the raw remark instead — putting «import sub» in the
        // user's notification shade. One resolver, every surface: this card, the server-list group
        // heading, and the notification.
        return SubscriptionNaming.titleOf(
            requireContext(),
            sub,
            accountDisplayName = account?.displayName,
            accountDefaultLabel = account?.defaultLabel,
        )
    }

    /**
     * [candidate] when it actually names a подписка, else null — the shared filter, applied here so
     * the account payload's own fields go through exactly the same test as the stored remark. See
     * [SubscriptionNaming.PLACEHOLDERS] for what is refused and why.
     */
    private fun realName(candidate: String?): String? = SubscriptionNaming.realName(candidate)

    /** The user's own nickname for a подписка and the backend's generated label, kept apart. */
    private data class AccountSubName(val displayName: String?, val defaultLabel: String?)

    /**
     * What the account calls this local подписка, or null when it is not an account one (or the
     * account has not answered yet). The two fields are returned SEPARATELY because they rank
     * differently: `displayName` is a name a human chose and outranks everything, while
     * `defaultLabel` is «Подписка #N» and must not outrank the провайдер's own title.
     *
     * The import remembers each подписка under an identity key — the constant "root" for the account's
     * primary, the remnawave uuid / id for a secondary — so the mapping back to a `SubInfoDto` is
     * that key, not a name match.
     */
    private fun accountNameFor(subId: String): AccountSubName? {
        val identity = runCatching {
            AuthTokenStore.getManagedGuids().entries.firstOrNull { it.value == subId }?.key
        }.getOrNull() ?: return null
        val info = accountSubs.firstOrNull { candidate ->
            val key = if (candidate.type.equals(SubscriptionSyncManager.TYPE_ROOT, ignoreCase = true)) {
                SubscriptionSyncManager.TYPE_ROOT
            } else {
                candidate.remnawaveUuid.ifBlank { candidate.id }
            }
            key.isNotBlank() && key == identity
        } ?: return null
        return AccountSubName(
            displayName = realName(info.displayName),
            defaultLabel = realName(info.defaultLabel),
        )
    }

    /**
     * The line under the card's heading: when the подписка last updated, and how often it does. The
     * timestamp is formatted from a resource pattern rather than a locale-dependent default so a
     * Russian sentence never ends in an English month.
     *
     * THE INTERVAL IS THE VALUE ALONE — «10.07.2026 10:56 · 1 ч», never «… · Автообновление — 1 ч».
     * The owner's instruction: «надо этот текст убрать и просто оставить отображение значения типа
     * 1ч 3ч и тд, без слова». The word was carrying nothing the figure does not: a period beside a
     * timestamp on a подписка card is the refresh cadence and can be nothing else. The one case that
     * still needs a word is the one with no period to show, and there the value IS the word:
     * «вручную».
     */
    private fun metaSubtitle(sub: SubscriptionItem): String {
        val last = if (sub.lastUpdated > 0L) {
            SimpleDateFormat(getString(R.string.home_date_numeric_time), Locale.getDefault())
                .format(Date(sub.lastUpdated))
        } else {
            getString(R.string.home_sub_updated_never)
        }
        val interval = if (!sub.autoUpdate) {
            getString(R.string.home_sub_auto_update_off)
        } else {
            val minutes = sub.updateInterval
            if (minutes >= 60L && minutes % 60L == 0L) {
                getString(R.string.home_sub_interval_hours, (minutes / 60L).toInt())
            } else {
                getString(R.string.home_sub_interval_minutes, minutes.toInt())
            }
        }
        return getString(R.string.home_sub_meta, last, interval)
    }

    /**
     * Paints one card: the name, the meta line, the traffic pill, the expiry marker, the operator's
     * notice and the support / Telegram actions. The pill is a rounded track with the usage figure
     * centred on it; the expiry shows ∞ when there is none (or an effectively unlimited one).
     */
    private fun bindMetaBar(meta: LayoutSubscriptionMetaBarBinding, subId: String, sub: SubscriptionItem?) {
        if (sub == null) {
            meta.root.isVisible = false
            return
        }
        meta.root.isVisible = true
        meta.tvSubTitle.text = metaTitle(subId, sub)
        meta.tvMetaSubtitle.text = metaSubtitle(sub)
        meta.tvMetaSubtitle.isVisible = true

        // Accessible names for the card's actions, in this screen's own voice.
        meta.btnCollapse.contentDescription = getString(
            if (homeListCollapsed) R.string.home_sub_cd_expand else R.string.home_sub_cd_collapse
        )
        meta.btnPing.contentDescription = getString(R.string.home_sub_cd_ping)
        meta.btnRefresh.contentDescription = getString(R.string.home_sub_cd_refresh)
        meta.btnSupport.contentDescription = getString(R.string.home_sub_cd_support)
        meta.btnTelegram.contentDescription = getString(R.string.home_sub_cd_telegram)

        val primaryColor = MaterialColors.getColor(meta.btnPin, androidx.appcompat.R.attr.colorPrimary)
        val onVariant = MaterialColors.getColor(meta.btnPin, com.google.android.material.R.attr.colorOnSurfaceVariant)
        meta.btnPin.setColorFilter(if (sub.pinned) primaryColor else onVariant)
        meta.btnPin.contentDescription =
            getString(if (sub.pinned) R.string.home_sub_cd_unpin else R.string.home_sub_cd_pin)

        // The operator's notice, verbatim, as plain text — never markup.
        if (sub.announce.isNotBlank()) {
            meta.tvAnnounce.isVisible = true
            meta.tvAnnounce.text = sub.announce
        } else {
            meta.tvAnnounce.isVisible = false
        }
        meta.btnSupport.isVisible = sub.supportUrl.isNotBlank()
        meta.btnTelegram.isVisible = sub.supportUrl.isNotBlank()

        if (!sub.hasUserInfo) {
            meta.layoutTraffic.isVisible = false
            return
        }
        meta.layoutTraffic.isVisible = true

        val onSurfaceColor = MaterialColors.getColor(meta.tvTraffic, com.google.android.material.R.attr.colorOnSurface)
        val variantColor = MaterialColors.getColor(meta.tvExpiry, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val dangerColor = MaterialColors.getColor(meta.tvExpiry, R.attr.colorDestructiveText)

        meta.tvTraffic.text = if (sub.isUnlimited) {
            getString(R.string.home_sub_traffic_unlimited, sub.usedTraffic.toTrafficString())
        } else {
            getString(
                R.string.home_sub_traffic_used,
                sub.usedTraffic.toTrafficString(),
                sub.totalTraffic.toTrafficString(),
            )
        }
        meta.tvTraffic.setTextColor(onSurfaceColor)
        // Unlimited traffic keeps an empty rounded track behind the label instead of a filled bar
        // (§4: «безлимит — полоса не заливается»). A horizontal ProgressBar takes an Int against
        // max=1000, so the fraction is unchanged.
        val fillFraction = if (sub.isUnlimited) 0f else sub.trafficFraction
        setMeterFill(meta.progressTraffic, (fillFraction * 1000).toInt())

        val expiryUnlimited = sub.expire <= 0L || sub.expire >= UNLIMITED_EXPIRE_SECONDS
        when {
            expiryUnlimited -> {
                meta.tvExpiry.text = getString(R.string.home_sub_infinity)
                meta.tvExpiry.setTextColor(variantColor)
            }

            sub.isExpired -> {
                meta.tvExpiry.text = getString(R.string.home_sub_expired)
                meta.tvExpiry.setTextColor(dangerColor)
            }

            // §4: «справа остаток срока («12 дн.»)». The row used to print the absolute
            // date, which makes the reader subtract to answer the question this line is
            // for. Counted in calendar days by the same rule as [classifyExpiry], so the
            // readout and the status strip never disagree about what «завтра» means; the
            // expired case is already the branch above, so a term with hours rather than
            // days left is still a day and floors to one instead of to «0 дн.».
            else -> {
                meta.tvExpiry.text = getString(R.string.home_sub_days_left, daysUntil(sub.expire * 1000L))
                meta.tvExpiry.setTextColor(variantColor)
            }
        }
        meta.tvExpiry.isVisible = true
    }

    /** Whole calendar days from today to [untilMs], never below 1. See [classifyExpiry]. */
    private fun daysUntil(untilMs: Long): Int {
        val today = LocalDate.now(ZoneId.systemDefault())
        val until = Instant.ofEpochMilli(untilMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(today, until).toInt().coerceAtLeast(1)
    }

    /**
     * The traffic meter's fill, and §4's «переход 500 мс» on it.
     *
     * IT TWEENS ONLY WHEN THE FRACTION REALLY MOVED. This runs from [bindMetaBar], which the
     * carousel calls again on every page rebuild, every account refresh and every repaint — so a
     * blind animation would replay the same 500ms slide at a user who did nothing, and would do it
     * three times over on a three-подписка carousel. A meter arriving at its first value, or landing
     * on the value it already shows, is set instantly; only a genuine change is travelled.
     *
     * `setProgress(v, true)` is not used: its duration is the platform's, not §8's, and it is a
     * no-op on a view that has not been laid out yet.
     */
    private fun setMeterFill(meter: android.widget.ProgressBar, target: Int) {
        (meter.getTag(R.id.progress_traffic) as? ValueAnimator)?.cancel()
        val from = meter.progress
        if (from == target) return
        // `isLaidOut` IS the "first value" test, and it is the only one. `from == 0` used to be
        // tested beside it and it is a different question: a meter reading zero is not a meter that
        // has never been painted — it is an unlimited подписка (§4: «безлимит — полоса не
        // заливается»), or a fresh month with nothing spent yet. Both of those DO travel when they
        // stop being zero, and the prototype states the transition on the fill itself
        // («transition:width 500ms cubic-bezier(.25,1,.5,1)») with no exception for leaving zero.
        // A view that has never been laid out — including the off-screen probes
        // measureHomeMetaHeight inflates — still lands instantly.
        if (!meter.isLaidOut || meter.reducedMotion()) {
            meter.progress = target
            return
        }
        val animator = ValueAnimator.ofInt(from, target).apply {
            duration = METER_FILL_MS
            interpolator = easeOutQuart
            addUpdateListener { meter.progress = it.animatedValue as Int }
            start()
        }
        // Parked on the view, not in a field: there is one meter per carousel page and the pages
        // are recycled, so the animator has to belong to the view it drives or two pages would
        // share one handle and cancel each other.
        meter.setTag(R.id.progress_traffic, animator)
    }

    /** The подписка the selected server belongs to, else the first one there is. */
    private fun currentMetaSubId(): String {
        mainViewModel.findSubscriptionIdBySelect()?.takeIf { it.isNotEmpty() }?.let { return it }
        return mainViewModel.getProviderGroups().firstOrNull()?.id.orEmpty()
    }

    // ==================== Observers ====================

    private fun observeTunnel() {
        mainViewModel.updateSpeedAction.observe(viewLifecycleOwner) { (down, up) ->
            downBytesPerSec = down
            upBytesPerSec = up
            if (renderedConn == Conn.CONNECTED) paintFigures()
        }

        // NO AUTO-FALLBACK OBSERVER ANY MORE. «Переключаться на быстрейший сервер» is gone by the
        // owner's instruction — the setting was removed and the stored key is forced false on every
        // start — and this screen's half of it went with it: the post-connect health probe, its
        // confirmation re-probe and the handler that restarted the tunnel on somebody else's
        // server. The app does not move the user off the server he picked.
        //
        // What stays is everything that was never the fallback: the 30s latency probe
        // ([latencyRunnable]) that produces the «мс» figure, and the connect watchdog.

        mainViewModel.isRunning.observe(viewLifecycleOwner) { isRunning ->
            // THE STOP HALF OF A SWITCH IS NOT AN OUTCOME, and this is the whole of the owner's
            // red flash between two servers. @see switching
            //
            // Everything below this line treats the report as the END of an attempt: it stands the
            // watchdog down, decides whether the attempt failed, stops the session clock and
            // publishes a state. All four are wrong about a teardown we asked for and are already
            // following with a start — including standing the watchdog down, which is what left the
            // switch with no deadline of its own once it stopped being an "outcome". So the report
            // is dropped whole, `connectInProgress` and `lastRunningState` keep the values they had
            // before the switch, and the object stays in the busy state it was put in by
            // [applySelectionToRunningTunnel] until the start half answers or the watchdog gives up.
            if (switching && !isRunning) return@observe
            switching = false

            // A definitive running/stopped state arrived (success or failure): the connect attempt
            // is over, so the watchdog is no longer needed.
            cancelConnectWatchdog()

            // Play the signature confirm/reverse ONLY on a genuine live transition — a connect the
            // user just triggered (connectInProgress), or a real running-state flip. Never on the
            // LiveData replay at launch (prev == null, no connect in progress), which jumps to end.
            //
            // …AND NEVER ON THE HANDSHAKE ANSWER EITHER, which is the half that was missing and the
            // whole of «если быть подключенным, закрыть приложение из вкладок и вернуться назад, то
            // опять появляются границы, появляется анимация у кнопки вибрация».
            //
            // Swiping the task away kills the process; the tunnel is a foreground service and does
            // not die with it. On the next launch `MainViewModel.startListenBroadcast` publishes
            // `isRunning = false` — a PLACEHOLDER, and it says so in its own comment («the screen
            // has to paint something while the handshake is in flight») — and only then asks the
            // daemon, which answers `MSG_STATE_RUNNING` a few hundred ms later. Two emissions,
            // false then true, and the second one is a textbook flip: `prevRunning != isRunning`.
            // So every cold start on a live tunnel buzzed the phone and fired the confirmation
            // sonar at a user who had pressed nothing — the exact thing the comment in paintConnect
            // says must not happen («which must not buzz the phone in the user's pocket»).
            //
            // The discriminator is the ENTRANCE. §3's table is running from the moment this screen
            // is created until ~1.3s later, and the daemon's answer lands inside that window every
            // time; more to the point, until the table has finished the user has not been SHOWN the
            // connect object at all, so nothing that arrives while it plays can be a transition he
            // is watching. It is the screen learning what was already true, and that is a state to
            // arrive in, not an event to celebrate. A tunnel raised from the tile or the
            // notification while Главная is genuinely on screen is past the entrance and still
            // animates.
            val prevRunning = lastRunningState
            val liveTransition = connectInProgress ||
                (prevRunning != null && prevRunning != isRunning && !entranceInFlight())

            // A start that ends in "not running" while a connect was in flight is a FAILURE, and it
            // is reported on the screen rather than as a toast that has already gone.
            tunnelError = !isRunning && connectInProgress
            disconnecting = false

            applyRunningState(isLoading = false, isRunning = isRunning, animate = liveTransition)

            if (isRunning) {
                startConnectionTimer()
                startLatencyProbe()
            } else {
                stopConnectionTimer()
                stopLatencyProbe()
            }

            connectInProgress = false
            lastRunningState = isRunning
        }

        mainViewModel.delayResultAction.observe(viewLifecycleOwner) { time ->
            onDelayResult(time)
        }

        // NO `updateTestResultAction` OBSERVER ANY MORE, and no channel behind it. It answered a
        // batch's progress message — one per finished server — with `refreshServerList(-1)`, a full
        // rebuild and `notifyDataSetChanged()` over the whole list, for a string it never read and
        // beside the targeted repaint the same server's result had already triggered. @see
        // MainViewModel's receiver for what replaced it, which is nothing: the row that changed was
        // always named on the other channel.
    }

    /**
     * A delay result: the latency reading, and nothing else.
     *
     * It used to serve a second consumer — the auto-fallback health check, which read the same
     * figure and moved the user to another server when it came back negative twice. That whole
     * behaviour is gone by the owner's instruction, and with it the only reason this method ever
     * did anything besides record the reading.
     */
    private fun onDelayResult(time: Long) {
        if (mainViewModel.isRunning.value == true) {
            if (time >= 0) {
                pingMs = time.toInt()
                pingProbeFailures = 0
            } else {
                pingMs = null
                pingProbeFailures++
            }
            // A FULL render, and it still has to be — but no longer because the reading is drawn
            // under the disc. It is not: the line below the object is the server's IDENTITY and
            // carries no figure at all (see paintStatusLine). What this probe changes on THIS
            // screen is `pingProbeFailures`, which resolveCondition turns into the «сервер не
            // отвечает» condition after three consecutive misses, so the render is what surfaces
            // that. MSG_STATE_DELAY_RESULT arrives once per 30s probe and once per health check,
            // never once per row of a bulk test, so this is not a hot path.
            if (isBindingInitialized) render()
        }
    }

    /**
     * The account and its subscriptions. Both feed the header row and the subscription card, and
     * NEITHER blocks the other: a подписка that resolves while the profile is still in flight paints
     * its card and leaves the header a skeleton.
     */
    private fun observeAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AccountSession.state.collect { applyAccountState(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                accountViewModel.subscriptions.collect { subs ->
                    accountSubs = subs
                    subsResolved = true
                    subsError = false
                    if (!isBindingInitialized) return@collect
                    // The account's nicknames are what the cards are named by, so a fresh list
                    // repaints them.
                    homeMetaAdapter?.repaint()
                    render()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // AN EVENT STREAM, NOT THE `error` STATE — because the ViewModel is shared with
                // Аккаунт now. The old collector CONSUMED the state (`clearError()`) so that a
                // collector restart would not re-raise a stale bar; two screens cannot both consume
                // one conflated value, and whichever got there first would have silently swallowed
                // the other's error. `accountDataErrors` is delivered to every collector once, and
                // carries only the two loads Главная depends on — a payments failure on the Аккаунт
                // tab is not «данные подписки устарели». @see AccountViewModel.accountDataErrors
                accountViewModel.accountDataErrors.collect {
                    accountResolved = true
                    subsResolved = true
                    subsError = true
                    if (!isBindingInitialized) return@collect
                    render()
                    showRetry()
                }
            }
        }
        refreshAccountData()
        // …AND [onResume] IS ABOUT TO ASK FOR THE SAME THING. The shell hosts its tabs with
        // show()/hide(), so a hidden tab stays RESUMED — which also means onResume ALWAYS follows
        // onViewCreated, one main-thread message later, and it asks for the account too
        // ([refreshAccountDataSoon]).
        // Every creation of this screen with a signed-in account therefore fired the account's
        // whole fetch TWICE within a few milliseconds: `/client/profile` ran to completion both
        // times (refreshProfile keeps no job, so neither run cancels the other and both publish),
        // and `/subscription/all` + `/client/subscription` went out twice as well — latest-wins
        // cancels the first coroutine, but by then its requests are on the wire and OkHttp finishes
        // them; only the answers are thrown away. Six requests where three do the job, on every
        // cold start, rotation and theme change.
        //
        // The fetch stays HERE rather than only in onResume, because the not-signed-in branch of
        // [refreshAccountData] is what sets `accountResolved`/`subsResolved`, and those decide
        // whether §3's entrance paints a skeleton or the gate. Deferring them by a frame would put
        // a skeleton on the start screen. So onResume is the one that stands down, once.
        accountDataFetchedOnCreate = true
    }

    /**
     * True from [observeAccount]'s own fetch until the first [onResume] after it, which is the one
     * resume that has nothing left to ask for. @see observeAccount
     */
    private var accountDataFetchedOnCreate = false

    /** Every error ships a recovery affordance. */
    private fun showRetry() {
        val bar = Snackbar.make(binding.root, getString(R.string.home_sub_stale), Snackbar.LENGTH_LONG)
            .setAction(R.string.home_action_retry) { refreshAccountData() }
        (bar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin += snackbarBottomOffset()
            bar.view.layoutParams = lp
        }
        bar.show()
    }

    private fun applyAccountState(state: AccountSession.AccountState) {
        val loggedIn = BackendConfig.isConfigured() && state is AccountSession.AccountState.LoggedIn
        if (state is AccountSession.AccountState.LoggedIn) {
            accountResolved = true
            if (isBindingInitialized) bindAccountRow(state.profile)
        } else {
            accountResolved = true
            accountSubs = emptyList()
            subsResolved = true
        }
        if (isBindingInitialized) render()
        // Login state feeds the shell's nav gates: signing in reveals the Аккаунт item.
        mainHost.refreshNavGates()
        // Fire the one-shot post-login import only on a genuine logged-out -> logged-in transition,
        // not on the state replay that happens every time the activity restarts while signed in.
        if (loggedIn && !accountLoggedIn) onLoggedIn()
        // AND THE OTHER DIRECTION. An explicit sign-out (`AccountSession.wipe`) removes the
        // account's подписки and their серверы from the store, but nothing rebuilds the cache this
        // screen's list is painted from — so the rows outlived the session and stayed selectable
        // with no account behind them. The reload publishes through the shell, which repaints the
        // list, the carousel and the nav gates together.
        if (!loggedIn && accountLoggedIn) mainViewModel.reloadServerList()
        accountLoggedIn = loggedIn
    }

    private fun refreshAccountData() {
        if (!BackendConfig.isConfigured() || !AccountSession.isLoggedIn()) {
            accountSubs = emptyList()
            subsResolved = true
            accountResolved = true
            return
        }
        subsError = false
        accountViewModel.refreshProfile()
        accountViewModel.loadSubscriptions()
    }

    /**
     * ============================================================================
     * THIS IS THE IMPORT THAT ADDS THE ПОДПИСКА, AND NOTHING WAS WAITING FOR IT.
     * ============================================================================
     *
     * Runs once when the user transitions to signed-in: auto-import their подписки and reload the
     * server list on success.
     *
     *     «почему он меня кидает на главную когда подписка ещё полностью не добавилась? должно
     *      продолжаться начальное окно … и только потом как добавилось перекидывать на главную»
     *
     * THE SIGN-IN FLOW WAS WAITING FOR A DIFFERENT ERRAND. `TelegramFlow` used to answer
     * `LoginState.Success` with its host's `refreshSubscriptions()`, which is the shell's
     * `importConfigViaSub()` — it walks the подписки that are ALREADY on the device and re-fetches
     * each one. A minute-old account has none, so that call finds nothing, returns in the half
     * second its own `delay` costs, and `loadsInFlight` is back to zero long before the account's
     * подписка exists. `revealHome` waited on that counter, saw it settle, and let Главная assemble
     * around an empty list. `NoticePolicy` rule 5 says the same thing in prose and has said it since
     * it was written: «signing in fires refreshSubscriptions() before the account's подписка has
     * been written». The refresh is not the work; THIS is the work, and it reported to nobody.
     *
     * So it declares itself, on the one signal the hand-off reads. The bracket is
     * [MainHost.reportSubscriptionImport] and not the shell's `showLoading`, because those two
     * things are not the same: `showLoading` also raises the in-flight bar and spins the connect
     * arc, and this import runs at EVERY cold start with a stored session, where announcing it
     * would be the app reporting an errand nobody started.
     *
     * EVERY EXIT LOWERS IT. The bracket is a `finally`, so a failure, a cancelled scope (the
     * fragment going while the two calls are in flight) and an empty account all reach it;
     * `autoImportSubscriptions` additionally cannot throw — every branch of it returns a `Result`.
     * The shell puts its own deadline under the wait on top of that. @see MainActivity.revealHome
     */
    private fun onLoggedIn() {
        // Read once, here, while there certainly is an activity: `mainHost` goes through
        // requireActivity(), and the `finally` below can run on a fragment that has been detached.
        val shell = mainHost
        shell.reportSubscriptionImport(true)
        lifecycleScope.launch {
            try {
                AccountRepository().autoImportSubscriptions().onSuccess { mainViewModel.reloadServerList() }
                accountViewModel.loadSubscriptions()
            } finally {
                // The reload above is the heaviest main-thread work on this path, and the release
                // this unblocks is §3's whole table. They do not share a frame. @see afterListSettles
                afterListSettles { shell.reportSubscriptionImport(false) }
            }
        }
        showStatus(getString(R.string.toast_subscription_linked))
    }

    /**
     * Offline is a CONDITION, so it is observed and not polled: the strip has to appear the moment
     * the network goes and disappear the moment it returns, without the user touching anything.
     */
    private fun observeNetwork() {
        // The APPLICATION context: this registration outlives nothing it should, but the manager it
        // is made against has to be reachable from [unregisterNetworkCallback], which runs while
        // the fragment may already be detached. @see connectivityForCallback
        val manager = requireContext().applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        offline = !hasInternet(manager)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = postOffline(false)
            override fun onLost(network: Network) = postOffline(!hasInternet(manager))
            override fun onUnavailable() = postOffline(true)
        }
        networkCallback = callback
        connectivityForCallback = manager
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                networkCallback = null
                connectivityForCallback = null
            }
    }

    private fun postOffline(value: Boolean) {
        timerHandler.post {
            if (!isBindingInitialized) return@post
            if (offline == value) return@post
            offline = value
            render()
        }
    }

    private fun hasInternet(manager: ConnectivityManager): Boolean {
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = connectivityForCallback
        networkCallback = null
        connectivityForCallback = null
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    // ==================== Resolve ====================

    /**
     * Reads every input once and returns the screen. This is the only place a decision is made; from
     * here down the code paints what it is told.
     */
    private fun resolveState(): HomeState {
        val serverCount = mainViewModel.serversCache.size
        val selected = MmkvManager.getSelectServer()
        // THE LINE UNDER THE SHIELD NAMES WHAT IS CARRYING THE TRAFFIC, NOT WHAT IS TICKED.
        //
        // Tapping a row SELECTS and never connects (MainActivity.setSelectServer), so while a
        // tunnel is up the two can be different servers — and they routinely are, because picking
        // another row is exactly how the user asks to move. The line used to read the SELECTION in
        // every state, which meant the moment after that tap it named a server the traffic was not
        // going through, and it kept naming it until the user applied the change.
        //
        // `MainViewModel.runningGuid` is the daemon's own answer to "which server is up" — the same
        // fact the shell gates its «Переподключиться» offer on — so the running server names the
        // line while there is one, and the selection names it when there is not. On any state where
        // nothing is running the two are the same value anyway.
        val running = mainViewModel.runningGuid?.takeIf { tunnelRunning || disconnecting }
        // …AND IT FALLS BACK TO THE SELECTION WHEN THE RUNNING GUID DOES NOT DECODE.
        //
        // `running ?: selected` picked the guid and then decoded once, so a running guid that no
        // longer names a stored profile produced NO profile at all instead of dropping through to
        // the selection. That happens for real and it happens on a schedule: a подписка refresh
        // deletes the profiles it does not recognise in the new answer and writes new guids for
        // them, so an hourly auto-update that changed the running server on a live tunnel leaves
        // `runningGuid` pointing at a record that is gone. The connect wave closed
        // the same hole for the SELECTED server — `getSelectServer` returns null for a vanished
        // profile — and this is the other half of it.
        //
        // What the user saw: «он пишет подключено вместо названия сервера». With no name, the line
        // under the ledger fell through to its own state word and printed the pill's sentence a
        // second time, in a second typeface, where the server's identity belongs. Intermittent,
        // because it needs a refresh to land between one guid and the next.
        val profile = running?.let { MmkvManager.decodeServerConfig(it) }
            ?: selected?.let { MmkvManager.decodeServerConfig(it) }
        val resolvedName = profile?.remarks
            ?.takeIf { it.isNotBlank() }
            // The leading country flag is the tile beside the name, never text inside it.
            ?.let { FlagUtil.stripLeadingFlag(it).trim() }
            ?.takeIf { it.isNotEmpty() }
        val resolvedFlag = profile?.let { FlagUtil.resolveFlag(it) }
        // THE LAST NAME THIS SCREEN COULD RESOLVE, kept for the window in which it can resolve
        // none. The two guids are written by different actors — the daemon reports the tunnel it
        // raised, the store is rewritten by whatever refreshed it — so there is an instant on a live
        // tunnel where neither answers. Holding the name the user was already reading is the honest
        // thing to show there: the traffic has not changed servers, only the record naming it has.
        if (resolvedName != null) {
            lastServerName = resolvedName
            lastServerFlag = resolvedFlag
        }
        val holdName = tunnelRunning || disconnecting || connectLoading
        val serverName = resolvedName ?: lastServerName.takeIf { holdName }
        val serverFlag = if (resolvedName != null) resolvedFlag else lastServerFlag.takeIf { holdName }

        // A sync that produced servers is a sync that succeeded; the request is spent.
        if (serverCount > 0) syncRequested = false
        val gate = resolveGate(serverCount)
        val sub = resolveSubscription()

        val conn = when {
            gate != null -> Conn.GATED
            connectLoading -> Conn.CONNECTING
            disconnecting -> Conn.DISCONNECTING
            tunnelRunning -> Conn.CONNECTED
            tunnelError -> Conn.ERROR
            serverName == null -> Conn.NO_SERVER
            else -> Conn.DISCONNECTED
        }

        return HomeState(
            conn = conn,
            gate = gate,
            accountLoading = !accountResolved,
            serverName = serverName,
            serverFlag = serverFlag,
            serverCount = serverCount,
            sub = sub,
            condition = resolveCondition(sub, conn),
            stale = offline,
        )
    }

    /**
     * The gate replaces the card and the list only when they would have nothing true to show. On
     * this product that reduces to one test — there is not a single server to connect to — and then
     * WHICH gate is a question about what the user is missing.
     */
    private fun resolveGate(serverCount: Int): Gate? {
        if (serverCount > 0) return null
        if (!BackendConfig.isConfigured()) return Gate.ADD_SUBSCRIPTION
        if (!AccountSession.isLoggedIn()) return Gate.SIGN_IN
        // Still resolving: show nothing rather than guess a gate and swap it a second later.
        if (!subsResolved) return null
        if (accountSubs.isEmpty()) return Gate.BUY
        // A sync that the user asked for, that has finished, and that produced no server, is a
        // FAILURE and says so — offering «Загрузить серверы» a second time as if nothing happened
        // is the button that does nothing.
        return if (syncRequested && backgroundLoads == 0) Gate.SYNC_FAILED else Gate.SYNC_SERVERS
    }

    /**
     * The subscription, from the account when there is one and from the local metadata otherwise, so
     * a pasted подписка still tells the truth.
     *
     * `isTrial` is the backend's flag and is never inferred from a tariff name or a squad — in this
     * deployment the trial squad IS the paid base squad, so squad-based detection misclassifies real
     * paying customers.
     */
    private fun resolveSubscription(): Sub {
        if (BackendConfig.isConfigured() && AccountSession.isLoggedIn()) {
            if (!subsResolved) return Sub.Unknown
            val active = activeAccountSub() ?: return Sub.None
            val until = parseIsoMillis(active.expireAtIso) ?: return Sub.Active(null)
            return classifyExpiry(until, active.isTrial)
        }
        // No account: the подписка's own `userinfo` expiry is the only truth available. Panels
        // sometimes send a huge timestamp instead of 0 for "never", which reads as an active
        // subscription with no date — which is exactly what Sub.Active(null) draws.
        val expireSeconds = localExpirySeconds() ?: return Sub.None
        if (expireSeconds >= UNLIMITED_EXPIRE_SECONDS) return Sub.Active(null)
        return classifyExpiry(expireSeconds * 1000L, trial = false)
    }

    /**
     * The account's ACTIVE подписка, asked for by TYPE and not by position.
     *
     * `accountSubs` is merged from two endpoints — `/client/subscription`, which is what identifies
     * the active one, and `/client/subscription/all`, the only source of the secondaries — and the
     * merge puts the root first WHEN IT HAS A ROOT TO PUT THERE. On the round where the first of
     * those answers late, or with a payload whose shape cannot be read, the list simply STARTS with
     * a secondary, and `firstOrNull()` then handed a secondary's `expireAtIso` to [classifyExpiry]
     * and let it speak for the whole account. An old secondary is exactly how «подписка истекла»
     * appeared over a подписка that had not expired at all.
     *
     * Falling back to the first entry keeps the previous answer for the case it was written for —
     * an account whose root is its only подписка — while a list with genuinely no root still
     * describes something rather than nothing. Deliberately the same rule, by the same key, as
     * `AccountFragment.activeSub()`: the two surfaces must not disagree about which подписка the
     * account HAS.
     */
    private fun activeAccountSub(): SubInfoDto? =
        accountSubs.firstOrNull { it.type.equals(SubscriptionSyncManager.TYPE_ROOT, ignoreCase = true) }
            ?: accountSubs.firstOrNull()

    /**
     * The expiry of the ПОДПИСКИ, КОТОРАЯ СЕЙЧАС ВЕЗЁТ ТРАФИК, and of no other подписка on the
     * device — the date that подписка's own server sent in its `subscription-userinfo` header.
     *
     * ЭТО НЕ АГРЕГАТ, И БЫТЬ ИМ НЕ ДОЛЖНО. Two earlier shapes of this function each computed the
     * verdict from the whole store: first `minOrNull()` — the oldest date on the device — and then
     * a three-tier fallback that, with no selection to go on, reached for «the live one that lasts
     * longest» and then for «the most recent expiry». Both answered a question nobody asked. The
     * store holds every подписка the device has ever seen: one pasted by hand a year ago, one trial
     * that lapsed, one провайдер the user stopped paying for. Any of them, standing beside a
     * perfectly live подписка, could put «Подписка истекла» on Главная over a working tunnel — the
     * screen was reading somebody else's date. Owner: «истекла брать по дате, которая даётся, как по
     * логике и работает».
     *
     * So there is exactly one source now: the подписка the SELECTED server was imported from. If
     * nothing is selected, or that подписка carries no date, there is NO ACTIVE ПОДПИСКА TO SPEAK
     * FOR and the screen returns [Sub.None] — no verdict at all, rather than a verdict borrowed
     * from a record that is not the one in use.
     *
     * It also reads ONE record instead of the whole store. [resolveSubscription] runs inside
     * `resolveState()`, i.e. on every `render()`, and the old shape decoded and JSON-parsed every
     * подписка on the device each time; this decodes one string and parses one object.
     */
    private fun localExpirySeconds(): Long? {
        val subId = mainViewModel.findSubscriptionIdBySelect()?.takeIf { it.isNotEmpty() } ?: return null
        return MmkvManager.decodeSubscription(subId)?.expire?.takeIf { it > 0L }
    }

    private fun classifyExpiry(untilMs: Long, trial: Boolean): Sub {
        val today = LocalDate.now(ZoneId.systemDefault())
        val until = Instant.ofEpochMilli(untilMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val daysLeft = ChronoUnit.DAYS.between(today, until).toInt()
        return when {
            daysLeft < 0 -> Sub.Expired(untilMs)
            trial -> Sub.Trial(untilMs)
            daysLeft < EXPIRING_DAYS -> Sub.Expiring(untilMs, daysLeft.coerceAtLeast(0))
            else -> Sub.Active(untilMs)
        }
    }

    /**
     * ONE condition, resolved by priority. The renderer never picks between conditions; if two are
     * true at once, the higher one is the only one shown.
     */
    private fun resolveCondition(sub: Sub, conn: Conn): Condition? {
        if (sub is Sub.Expired) {
            return Condition(
                text = getString(R.string.home_condition_expired),
                severity = Severity.ERROR,
                actionLabel = getString(R.string.home_action_renew),
                action = { openSubscription() },
            )
        }
        if (offline) {
            return Condition(
                text = getString(R.string.home_condition_offline),
                severity = Severity.INFO,
                actionLabel = getString(R.string.home_action_retry),
                action = { mainHost.refreshSubscriptions() },
            )
        }
        if (conn == Conn.CONNECTED && pingProbeFailures >= SILENT_SERVER_FAILURES) {
            // The list is on this screen, so the warning can point at it: the recovery is one tap
            // away and no longer a dead end.
            return Condition(
                text = getString(R.string.home_condition_silent),
                severity = Severity.WARN,
            )
        }
        if (sub is Sub.Expiring) {
            return Condition(
                text = getString(R.string.home_condition_expiring, formatDate(sub.untilMs)),
                severity = Severity.WARN,
                actionLabel = getString(R.string.home_action_renew),
                action = { openSubscription() },
            )
        }
        // «Обновляем данные…» IS NOT ONE OF THESE ANY MORE, and it is not gone either.
        //
        // Everything above is a CONDITION — a state of the account or the network that persists
        // until something changes it, and that belongs in the strip under the header where the
        // user meets it on the way down the screen. A refresh in flight is not one of those: it is
        // an ACTION the user started, it ends by itself, and the owner asked for it in a different
        // place — «это уведомление нужно снизу над панелью навигации». It moved to `Notice`, the
        // one bottom surface, driven from `render()`. It is NOT removed: an action in flight must
        // show itself (OWNER-FEEDBACK 2026-07-27 G2), and this is the only thing on the screen
        // that says a refresh is happening while the list still holds the old rows.
        return null
    }

    // ==================== Render ====================

    private fun render(animate: Boolean = false) {
        if (!isBindingInitialized) return
        val state = resolveState()
        paintHeader(state)
        paintLinkCta()
        paintCondition(state.condition)
        paintProgress()
        paintConnect(state, animate)
        paintFigures()
        paintSlot(state)
        renderedConn = state.conn
        // THE HOLD HAS THE LAST WORD, and it has to be the last line here rather than a flag the
        // painters check. Every painter above legitimately writes resting values — that is what
        // painting is — and while a full-screen flow overlay is up, a repaint means the подписка
        // arrived, the gate went and the whole screen came back on. Re-stamping the start values
        // in the SAME traversal is what keeps the screen parked underneath the overlay, so the
        // overlay's 520ms fade uncovers a screen that has not assembled yet and the frame that
        // removes it has nothing to snap. @see entranceHeld
        if (entranceHeld) primeEntrance()
    }

    private fun paintHeader(state: HomeState) {
        val header = binding.layoutHomeAccount
        // The row is a navigation row in every state, so it is never hidden — an account entry point
        // that disappears is an account entry point the user cannot find.
        header.rowAccount.isVisible = true

        if (state.accountLoading) {
            header.accountText.isVisible = false
            if (!accountSkeletonArmed) {
                accountSkeletonArmed = true
                SkeletonBinder.showAfterDelay(header.accountSkeleton)
            }
            header.rowAccount.isEnabled = false
            return
        }
        header.rowAccount.isEnabled = true
        if (header.accountSkeleton.isVisible) {
            SkeletonBinder.swap(skeleton = header.accountSkeleton, content = header.accountText)
        } else if (accountSkeletonArmed) {
            SkeletonBinder.cancel(header.accountSkeleton)
            header.accountText.isVisible = true
        } else {
            header.accountText.isVisible = true
        }
        accountSkeletonArmed = false

        if (!AccountSession.isLoggedIn() || !BackendConfig.isConfigured()) {
            header.accountTile.isVisible = true
            header.imgAvatar.isVisible = false
            header.tvAvatarInitial.isVisible = false
            header.tvAccountName.text = getString(R.string.home_account_title)
            header.tvAccountSub.text = getString(R.string.home_account_subtitle)
        }
    }

    /**
     * THE ACCOUNT ROW IS PAINTED FROM THE ACCOUNT WE ALREADY HAVE, BEFORE THE FIRST FRAME.
     *
     * The owner: «при входе в приложение сверху где управление аккаунтом на главной он сначала
     * тёмный потом возвращает свой цвет». That is this row drawing its LOADING dress and then its
     * real one, and there was never anything to load:
     *
     *  - `accountResolved` starts false whenever the user is signed in, so the first [render] takes
     *    [paintHeader]'s loading branch — the text column is hidden and a skeleton is armed at
     *    300ms. Those skeleton bars are `colorSurfaceContainerHighest` on `bg_skeleton_bar`, i.e.
     *    two dark blocks where the name and «Управление аккаунтом» belong;
     *  - `account_tile` is VISIBLE in the layout by default and only [bindAccountRow] hides it, so
     *    the 40dp slot showed @drawable/bg_tile_neutral — a dark tile — until the profile arrived
     *    and swapped in the avatar;
     *  - and the profile only arrives on the collector in [observeAccount], which is inside a
     *    `repeatOnLifecycle(STARTED)` and therefore cannot run until after `onViewCreated` has
     *    returned. So the loading dress is guaranteed to be the one that reaches the screen first.
     *
     * `AccountSession.state` is a `StateFlow` seeded synchronously from `AuthTokenStore` — the
     * profile is in memory, off MMKV, with no I/O at all. Reading its CURRENT VALUE here, before
     * the first render, is the whole fix: the row is painted with the real identity in the frame it
     * is first drawn, and neither the tile nor the skeleton is ever shown.
     *
     * The skeleton machinery is untouched and still covers the case it was written for — a session
     * whose stored profile names nobody, where the row really is waiting on `getMe`.
     */
    private fun seedAccountRow() {
        val state = AccountSession.state.value as? AccountSession.AccountState.LoggedIn ?: return
        val profile = state.profile
        // A session with no stored profile is genuinely unresolved: it would seed the row with
        // «Аккаунт» and a monogram, which is a second wrong answer rather than none. Let the
        // skeleton have that one.
        val named = !profile.telegramUsername.isNullOrBlank() ||
            profile.email.isNotBlank() ||
            !profile.telegramName.isNullOrBlank()
        if (!named) return
        accountResolved = true
        bindAccountRow(profile)
        // AND THE DRAWABLE JUMPS WITH IT. @drawable/bg_row is a <selector> carrying
        // enterFadeDuration / exitFadeDuration, so a state written before the first draw is not
        // simply applied — DrawableContainer parks the crossfade and spills it into the first
        // onDraw, which is the same «сначала одно, потом на глазах становится другим» the settings
        // wave found under its switches. The rule is one line and it is the same everywhere: the
        // FIRST state is instant, and only a state the user changes is animated.
        binding.layoutHomeAccount.rowAccount.jumpDrawablesToCurrentState()
    }

    /**
     * Fills the signed-in header from the profile: the @handle, the photo or its monogram, and the
     * one neutral subtitle. The avatar tile is the signed-out state and goes with the identity.
     */
    private fun bindAccountRow(profile: UserProfileDto) {
        val header = binding.layoutHomeAccount
        val handle = profile.telegramUsername?.takeIf { it.isNotBlank() }?.let { "@$it" }
        val identity = handle
            ?: profile.email.takeIf { it.isNotBlank() }
            ?: profile.telegramName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.home_account_title)
        header.accountTile.isVisible = false
        header.tvAccountName.text = identity
        header.tvAccountSub.text = getString(R.string.home_account_manage)
        AvatarManager.setMonogram(header.tvAvatarInitial, identity)
        AvatarManager.applyAvatar(
            viewLifecycleOwner.lifecycleScope,
            requireContext(),
            header.imgAvatar,
            header.tvAvatarInitial,
            profile,
        )
    }

    private fun paintCondition(condition: Condition?) {
        val strip = binding.layoutStatusStrip
        if (condition == null) {
            if (strip.statusStrip.isVisible) hideStrip()
            return
        }
        val wasVisible = strip.statusStrip.isVisible && !stripHiding
        // A replaced message CROSSFADES; the bar itself does not move. Only the text is animated, so
        // a strip that stays up while its reason changes never jumps.
        if (wasVisible && strip.statusStripText.text?.toString() != condition.text.toString()) {
            crossfadeText(strip.statusStripText, condition.text)
        } else {
            strip.statusStripText.text = condition.text
        }
        val tint = when (condition.severity) {
            Severity.INFO -> themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            Severity.WARN -> themeColor(R.attr.warning)
            Severity.ERROR -> themeColor(R.attr.colorDestructiveText)
        }
        strip.statusStripIcon.imageTintList = ColorStateList.valueOf(tint)

        val label = condition.actionLabel
        val action = condition.action
        if (label == null || action == null) {
            strip.statusStripAction.isVisible = false
        } else {
            strip.statusStripAction.isVisible = true
            strip.statusStripAction.text = label
            strip.statusStripAction.onSingleClick { action() }
        }
        if (!wasVisible) showStrip()
    }

    /**
     * The in-flight signal, at the BOTTOM, above the navigation — where the owner asked for it.
     *
     * It used to be an inline banner at the TOP of Главная, resolved as if it were a condition
     * alongside «Подписка истекла» and «Нет сети». It is not one: a refresh is an action with an
     * end, not a state of the account, and putting it in the same slot pushed the connect object
     * down the screen every time the app fetched anything. Now it takes the same bottom surface
     * every other message in the product takes, so the screen never reflows for it.
     *
     * It is deliberately NOT policy-gated. `NoticePolicy` decides what may INTERRUPT the user;
     * this is the app showing its own work, and G2 says an action in flight must show itself.
     */
    private fun paintProgress() {
        if (backgroundLoads > 0) {
            Notice.progress(requireContext(), getString(R.string.home_condition_loading))
        } else {
            Notice.clearProgress()
        }
    }

    /** Half out, swap, half back: one `motion_state` in total, and the box never moves. */
    private fun crossfadeText(view: android.widget.TextView, text: CharSequence) {
        if (view.reducedMotion()) {
            view.text = text
            view.alpha = 1f
            return
        }
        val half = durState / 2
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(half).setInterpolator(easeStandard)
            .withEndAction {
                if (!isBindingInitialized) return@withEndAction
                view.text = text
                view.animate().alpha(1f).setDuration(half).setInterpolator(easeStandard).start()
            }.start()
    }

    private fun showStrip() {
        val strip = binding.layoutStatusStrip.statusStrip
        stripHiding = false
        strip.animate().cancel()
        strip.isVisible = true
        if (strip.reducedMotion()) {
            strip.alpha = 1f
            strip.translationY = 0f
            return
        }
        strip.alpha = 0f
        strip.translationY = resources.getDimensionPixelSize(R.dimen.space_8).toFloat()
        strip.animate().alpha(1f).translationY(0f)
            .setDuration(durReveal).setInterpolator(easeOutQuint).start()
    }

    private fun hideStrip() {
        val strip = binding.layoutStatusStrip.statusStrip
        if (strip.reducedMotion()) {
            strip.isVisible = false
            return
        }
        stripHiding = true
        strip.animate().alpha(0f)
            .translationY(resources.getDimensionPixelSize(R.dimen.space_8).toFloat())
            .setDuration(durRevealExit).setInterpolator(easeStandard)
            .withEndAction {
                if (!isBindingInitialized) return@withEndAction
                stripHiding = false
                strip.isVisible = false
                strip.alpha = 1f
                strip.translationY = 0f
            }.start()
    }

    /**
     * The rings, the disc, the shield and the status line. Colour carries the state on a geometry
     * that never changes: every ring is 3dp in every state and only its tint moves, because a ring
     * that changes width makes the control appear to change size.
     */
    private fun paintConnect(state: HomeState, animate: Boolean) {
        val frame = binding.connectFrame
        val enabled = when (state.conn) {
            Conn.GATED, Conn.NO_SERVER, Conn.DISCONNECTING -> false
            // The app does not know better than the OS whether a tunnel can be raised, so offline
            // keeps the disc live.
            else -> true
        }
        frame.isEnabled = enabled
        frame.isFocusable = enabled
        frame.alpha = if (enabled) 1f else DISABLED_ALPHA
        // The accessible name states STATE and ACTION. A disabled object names the state alone,
        // because it has no action to offer and promising one is a lie to a screen reader.
        frame.contentDescription = getString(
            when (state.conn) {
                Conn.CONNECTED -> R.string.home_cd_disconnect
                Conn.CONNECTING -> R.string.home_cd_cancel
                Conn.GATED -> R.string.home_cd_locked
                Conn.NO_SERVER -> R.string.home_status_no_server
                Conn.DISCONNECTING -> R.string.home_status_disconnecting
                else -> R.string.home_cd_connect
            }
        )

        val negotiating = state.conn == Conn.CONNECTING
        // The sweep spins for the two things this screen has always spun it for: the core
        // negotiating a tunnel, and a подписка being fetched.
        val sweeping = negotiating || backgroundLoads > 0
        // BOTH SIDES, and both idempotent: showSweep() re-arms a revolution the background
        // stopped without replaying the entrance, hideSweep() winds the arc down instead of
        // dropping it mid-turn. The `sweepRunning` edge is what keeps the entrance one-shot.
        //
        // Each side ANSWERS WITH THE LENGTH OF THE MOVEMENT IT JUST STARTED, and zero when it found
        // nothing to move. Zero is the whole of what the ring needs from both sides — «there is no
        // movement here to join», so land instantly. The step DOWN additionally rides that length,
        // because the arc is arriving over it and the two are one gesture; the step back UP is an
        // appearance and keeps its own tempo. @see dimRingTrack
        val handover = if (sweeping) showSweep() else hideSweep()
        sweepRunning = sweeping
        // The ring under the arc steps down to the prototype's 30% for as long as the arc is on it,
        // so the movement reads. Set BEFORE the tint below, which is what repaints the stroke.
        dimRingTrack(sweeping, handover)

        // THE ONE BREATH LEFT. Negotiating owns the ring alphas at 850ms and says "working on it";
        // every other state is still, because §4 gives the object exactly one other motion — the
        // 5.5s settle after a connect, which is one-shot and belongs to the TRANSITION rather than
        // to the state, so it is fired from playConfirm below and not from this repaint path.
        // A repaint that finds the tunnel already up must not restart it: the decay runs once.
        if (negotiating) {
            stopAmbient()
            startBreathing()
        } else {
            stopBreathing()
        }

        val targetRing = when (state.conn) {
            // The rings' connected hue rides the same figure token as everything else the object
            // draws — see buildConnectObject. Identical to colorPrimary in blue, a mid neutral in
            // mono light, where near-black rings read as an outline drawing rather than an accent.
            Conn.CONNECTED -> themeColor(R.attr.colorAccentFigure)
            Conn.CONNECTING -> themeColor(R.attr.connectActiveColor)
            Conn.ERROR -> themeColor(androidx.appcompat.R.attr.colorError)
            Conn.GATED, Conn.NO_SERVER -> themeColor(com.google.android.material.R.attr.colorOutline)
            else -> idleRingColor()
        }

        val nowConnected = state.conn == Conn.CONNECTED
        // `transition` is "the user just did this"; `live` is "and the OS wants motion". They are
        // separate because the confirm HAPTIC fires on every genuine transition, reduced motion or
        // not, and on none of the repaints — including the one at launch that finds a tunnel
        // already up, which must not buzz the phone in the user's pocket.
        val transition = animate && renderedConn != null
        val live = transition && !frame.reducedMotion()

        when {
            nowConnected && !visualConnected -> playConfirm(targetRing, live, transition)
            !nowConnected && visualConnected -> playRelease(targetRing, live)
            else -> {
                tintRing(targetRing, animate = live && renderedConn != state.conn)
                binding.shieldFilled.alpha = if (nowConnected) 1f else 0f
                binding.shieldOutline.alpha = if (nowConnected) 0f else 1f
            }
        }
        visualConnected = nowConnected

        paintStatusLine(state)
    }

    /**
     * ONE LINE UNDER THE LEDGER, and it says what the screen is running through.
     *
     * There is no status word above it any more. The owner compared this build with the one he
     * approved and reported the extra line as a defect — «No «Отключено» word above it» — and the
     * word was never carrying anything on its own: idle grey / negotiating amber / accent connected
     * / failed red is already on the object the line sits under, and the object's contentDescription
     * states the same thing for a screen reader.
     *
     * So the single line resolves by priority:
     *
     *  - a SERVER to name  -> the flag and the name, in the accent and bold, AND NOTHING ELSE. This
     *                         is the resting look, and it is the one in his reference: «flag glyph +
     *                         Hybrid (Автовыбор) in blue, bold».
     *
     *                         NO LATENCY HERE. 3c7428b appended «· 103 мс» to this line on a live
     *                         tunnel and the owner rejected it — «а и пинга не должно показываться
     *                         ниже кнопки где название локации». The line is an IDENTITY, not a
     *                         measurement: it answers "what am I running through", and a figure that
     *                         changes every few seconds inside it makes the one stable label on the
     *                         screen unstable. The latency keeps the home he asked for it to keep —
     *                         the server rows, where it sits beside the row it measures.
     *  - no server, but something to say -> the state word or the instruction, in its own colour and
     *                         with NO flag: a flag beside «Нажмите, чтобы повторить» would label the
     *                         instruction with a country.
     *
     * The text is swapped INSTANTLY, never crossfaded: a crossfade is unreadable for its duration
     * and this is the string that has to be legible in four seconds.
     */
    private fun paintStatusLine(state: HomeState) {
        val onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val accent = themeColor(androidx.appcompat.R.attr.colorPrimary)
        val name = state.serverName

        // THE LEDGER AND THE GATE ARE MUTUALLY EXCLUSIVE (13-start-screen.md §4), and on the first
        // run that is not a style rule, it is the difference between seeing «Добавить подписку» and
        // not. A gated screen has no tunnel to meter and never will until the user acts, so the row
        // can only ever read «0 КБ/с 00:00:00 0 КБ/с» — 32dp of zeroes standing between the object
        // and the one action on the screen. «стартовый экран кнопок нет для добавления и тд, еле
        // подписку добавил»: the block below has to reach the fold on a short phone.
        //
        // It is HIDDEN and not GONE-and-forgotten: the moment a подписка exists the gate goes and
        // the ledger comes back in the same frame, at the same y, because everything above it is
        // fixed height. Nothing is lost and nothing moves.
        binding.numericStrip.isVisible = state.conn != Conn.GATED

        val identity: CharSequence? = when (state.conn) {
            Conn.ERROR, Conn.NO_SERVER, Conn.GATED -> null
            else -> name
        }
        // The fallback line, for every state with no server to name. Each one keeps a string the
        // two-line stack already used, so nothing is lost by collapsing them into one row.
        //
        // ERROR takes the RECOVERY and not the verdict: the object above it is already red, so
        // «Не удалось подключиться» would spend the only line restating a colour, while «Нажмите,
        // чтобы повторить» spends it on the way out. State plus action, one channel each.
        //
        // THE FOUR TUNNEL STATES FALL BACK TO NOTHING, AND THAT IS THE POINT. «Подключаемся…» ·
        // «Отключаемся…» · «Не подключено» · «Подключено» are the STATUS PILL's own four words,
        // eleven dp above this line — printing one of them here says the same thing twice, in two
        // typefaces, in the slot the user reads the server's name out of. That is what he caught:
        // «он пишет подключено вместо названия сервера». They were only ever reachable when the
        // name could not be resolved, which resolveState now makes very nearly impossible (it falls
        // back to the selection, and then to the last name it knew); this is the floor under that,
        // and an empty identity is better than a false one. The row keeps its height either way —
        // an empty TextView still lays out one line box — so nothing below it moves.
        val fallback: CharSequence = when (state.conn) {
            Conn.ERROR -> getString(R.string.home_detail_retry)
            Conn.NO_SERVER -> getString(R.string.home_detail_pick_server)
            Conn.GATED -> getString(gateStatusWord(state))
            Conn.CONNECTING, Conn.DISCONNECTING, Conn.DISCONNECTED, Conn.CONNECTED -> ""
        }
        val detail: CharSequence = identity ?: fallback
        val colour = when {
            identity != null -> accent
            state.conn == Conn.ERROR -> themeColor(R.attr.colorDestructiveText)

            else -> onSurfaceVariant
        }

        binding.tvServerFlag.text = state.serverFlag.orEmpty()
        binding.tvServerFlag.isVisible = identity != null && state.serverFlag != null
        binding.tvStatusDetail.text = detail
        binding.tvStatusDetail.setTextColor(colour)
        // Always laid out, so the block below it never moves when the line changes what it says.
        binding.serverIdentity.visibility = View.VISIBLE

        // THE STATUS PILL, in whichever of its three states applies. See the pill's own note in
        // fragment_home.xml: as of 2026-08-04 it is a field again, not a confirmation.
        paintStatusPill(state.conn)
    }

    /**
     * Paints the status pill: «Не подключено» / «Подключаемся…» / «Подключено» (handoff §4).
     *
     * THREE WORDS FOR SEVEN STATES, and the mapping is the honest one rather than the tidy one.
     * The prototype knows three connection states; this build has seven, and two of the extra
     * five are real things the user is looking at while they happen. DISCONNECTING keeps its own
     * word, because a pill reading «Подключено» while the tunnel is coming down is a lie and one
     * reading «Не подключено» while it is still up is a different lie. ERROR, GATED and NO_SERVER
     * all land on «Не подключено», which is exactly what they are — the object above says WHY in
     * its colour, and the identity line under the ledger says it in words.
     *
     * The outline and the label are one colour, tinted together: the pill shape is transparent
     * inside, so backgroundTintList reaches its stroke and nothing else (SRC_IN keeps transparent
     * pixels transparent). The tint TWEENS over motion_state — §8's «Смена цвета 220 мс» — while
     * the word is swapped instantly, because a crossfaded label is unreadable for its duration and
     * this is a string the user is waiting on.
     */
    private fun paintStatusPill(conn: Conn) {
        val pill = binding.tvConnectedPill
        val label = when (conn) {
            Conn.CONNECTED -> R.string.home_status_connected
            Conn.CONNECTING -> R.string.home_pill_connecting
            Conn.DISCONNECTING -> R.string.home_status_disconnecting
            else -> R.string.home_not_connected
        }
        val colour = when (conn) {
            Conn.CONNECTED -> themeColor(R.attr.colorAccentFigure)
            Conn.CONNECTING -> themeColor(R.attr.connectActiveColor)
            else -> themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        pill.setText(label)
        if (colour == pillColor) return
        val from = pillColor
        pillColor = colour
        if (from == null || pill.reducedMotion()) {
            applyPillColor(colour)
            return
        }
        pillTint?.cancel()
        pillTint = ValueAnimator.ofObject(ArgbEvaluator(), from, colour).apply {
            duration = durState
            interpolator = easeOutQuart
            addUpdateListener { applyPillColor(it.animatedValue as Int) }
            start()
        }
    }

    /** One colour for the pill's outline and its label. @see paintStatusPill */
    private fun applyPillColor(colour: Int) {
        val pill = binding.tvConnectedPill
        pill.backgroundTintList = ColorStateList.valueOf(colour)
        pill.setTextColor(colour)
    }

    private fun gateStatusWord(state: HomeState): Int = when {
        state.sub is Sub.Expired -> R.string.home_status_expired
        state.gate == Gate.BUY -> R.string.home_status_no_subscription
        else -> R.string.home_status_no_servers
    }

    /**
     * The strip's two speed columns. A figure LANDS — it does not tick, count or animate — and it
     * reads zero at rest rather than blank: this row is the screen's ledger and it is always there.
     * The session clock is written by [uptimeRunnable], which owns it second by second.
     *
     * THE UNIT RIDES WITH THE VALUE — «1,0 КБ/с», not a bare «1,0» under a «Отдача, Мбит/с»
     * caption. The captions are gone (the owner's reference has none) so there is nothing left to
     * hold the unit, and a bare figure whose scale is invisible is worse than no figure. This is
     * `Long.toSpeedString`, which is what the anchor build printed here (in Latin then, «1,0 KB/s»)
     * and what the whole app prints everywhere else, so the same rate can never read two different
     * ways in one product.
     */
    private fun paintFigures() {
        if (!isBindingInitialized) return
        val zero = getString(R.string.home_speed_zero)
        binding.tvUp.text = upBytesPerSec?.toSpeedString() ?: zero
        binding.tvDown.text = downBytesPerSec?.toSpeedString() ?: zero
    }

    /** The subscription card + list, and the gate block, share one slot and are never both up. */
    private fun paintSlot(state: HomeState) {
        val gate = state.gate
        val gateVisible = gate != null
        // ONE of the gate's four shapes is not this screen. See paintOnboardingShell.
        paintOnboardingShell(gate == Gate.SIGN_IN)
        binding.subscriptionSlot.isVisible = !gateVisible
        binding.layoutGate.gate.isVisible = gateVisible
        if (gate != null) paintGate(gate) else paintSubscriptionSlot(state)
        // The slot's two occupants swap in place over motion_state. Nothing else on the screen
        // moves, because the slot's y is fixed by the rhythm above it.
        if (renderedGateVisible != null && renderedGateVisible != gateVisible) {
            val incoming = if (gateVisible) binding.layoutGate.gate else binding.subscriptionSlot
            incoming.animate().cancel()
            if (incoming.reducedMotion()) {
                incoming.alpha = 1f
            } else {
                incoming.alpha = 0f
                incoming.animate().alpha(1f)
                    .setDuration(durState).setInterpolator(easeStandard).start()
            }
        }
        renderedGateVisible = gateVisible
    }

    /**
     * НАЧАЛЬНЫЙ ЭКРАН (README §2): in the SIGN_IN shape the gate block IS the screen, so Главная
     * takes itself off it.
     *
     * THE GATE HAS FOUR SHAPES AND ONLY THIS ONE IS THE START SCREEN (PORT-DELTA П-01). SIGN_IN is
     * signed out with not one server: there is no account to name, no subscription to add to, no
     * server to connect to and no session to meter, so the account row, the «+», the connect object,
     * the status pill and the ledger line were all six of them stating «нет» at a user who has not
     * been asked for anything yet. The shell reads the same condition and takes the whole bottom
     * navigation away (MainActivity.updateBottomNavVisibility), which is what left the rest of this
     * screen looking like the remains of a page rather than a first run.
     *
     * BUY, ADD_SUBSCRIPTION, SYNC_SERVERS and SYNC_FAILED KEEP EVERY ONE OF THEM. Those three
     * shapes belong to a user who is signed in or already has servers: the account row is his way
     * back to the account, the connect object is live the moment a server lands, and BUY's own
     * «Привязать Telegram» secondary is the only entry point to linking on this screen. They are
     * ordinary Главная with the gate block in the subscription slot's place, and nothing here
     * touches them — every property below is written in BOTH directions on every repaint, so a
     * screen that leaves SIGN_IN gets all of it back in the same frame.
     *
     * Nothing is GONE-and-forgotten and nothing is removed from the layout: this is one visibility
     * flag per band, resolved from the one state the screen already resolved.
     *
     * IT RUNS LAST, from [paintSlot], and that is load-bearing: [paintHeader] writes the account
     * row visible on every repaint and [paintStatusLine] writes the identity line visible on every
     * repaint, both of them correctly for the screen they are painting. This has the final word on
     * which screen that is, exactly as `bindOnboarding` does inside the block itself.
     */
    private fun paintOnboardingShell(onboarding: Boolean) {
        val chrome = !onboarding
        binding.layoutHomeAccount.root.isVisible = chrome
        binding.btnHomeAdd.isVisible = chrome
        binding.connectFrame.isVisible = chrome
        binding.tvConnectedPill.isVisible = chrome
        // The ledger and the identity line together — hiding their column takes both, and leaves
        // paintStatusLine's own per-state writes inside it untouched for when the column returns.
        binding.statusLine.isVisible = chrome
        // The rhythm between the bands goes with the bands. 64dp of gap between things that are not
        // there would push the centred block down by half of it.
        binding.gapBeforeConnect.isVisible = chrome
        binding.gapAfterConnect.isVisible = chrome
        binding.gapBeforeSlot.isVisible = chrome
        // The pair that floats the gate block; see fragment_home.xml for the 3:4.
        binding.onboardLead.isVisible = onboarding
        binding.onboardTrail.isVisible = onboarding
        // «Привязать Telegram» is offered to the pasted-подписка user, who by definition HAS servers
        // and therefore never sees a gate. The two overlap only if his подписка produced none, and
        // on that screen the gate's own primary already says «Войти через Telegram» — a better
        // offer, in the composition's own slot. paintLinkCta owns the flag in every other state.
        if (onboarding) binding.ctaLinkTelegram.isVisible = false
        if (onboardingShell == onboarding) return
        onboardingShell = onboarding
        applyListInsets()
    }

    private fun paintSubscriptionSlot(state: HomeState) {
        setServerListShown(state.serverCount > 0 && !homeListCollapsed)
        // Offline, or a failed refresh: the card and the rows keep their last values and the screen
        // says so, rather than emptying.
        binding.tvStaleHint.isVisible = state.stale || subsError
    }

    /**
     * THE LIST'S OTHER HALF. It arrives by §3's table — «слева, −44dp, 560 мс, задержка 700 + 85 на
     * строку» — and it used to LEAVE by having `isVisible` written false under it, which is the
     * owner's «нет плавного скрытия серверов»: the card's chevron took a whole screenful of rows
     * away between two frames and everything under it jumped up to meet the gap.
     *
     * Both directions are the prototype's own, and it states them on one element:
     *
     *     <div style="overflow:hidden;max-height:{{ srv.h }};opacity:{{ srv.op }};
     *                 transition:max-height 340ms cubic-bezier(.25,1,.5,1),opacity 220ms">
     *
     * i.e. HEIGHT over @integer/motion_expand (340, §8's «Раскрытие блока») on
     * @interpolator/ease_out_quart, and OPACITY over @integer/motion_state (220) alongside it. One
     * description for opening and closing, so the two cannot drift apart — and both shorter than
     * the 560ms arrival, because this is a disclosure and that is an entrance.
     *
     * THE HEIGHT IS MEASURED, NEVER A CONSTANT. The prototype can write `620px` because it knows
     * its own seven rows; a real list is any length, and a fixed number would clip it or hole it.
     * It costs nothing extra to measure: this RecyclerView is `wrap_content` inside the page's
     * scroll view with nested scrolling off, so it already lays out every row on every pass.
     *
     * THE FIRST PAINT IS INSTANT. [renderedListShown] is null until the screen has drawn the list
     * once, so a launch, a tab switch and a подписка arriving all land on the end state; only a
     * genuine open/close is travelled. It is also skipped while the entrance is held, because the
     * list is parked at alpha 0 there and belongs to §3's table, not to this.
     */
    private fun setServerListShown(shown: Boolean) {
        val list = binding.rvHomeServers
        val previous = renderedListShown
        renderedListShown = shown
        if (previous == shown) return
        // WHERE IT IS NOW, read BEFORE the running animator is dropped. A collapse that is
        // interrupted half way — the chevron tapped twice — is cancelled below, and a cancelled
        // ValueAnimator still runs its end action, which is what writes `isVisible = false`. Asking
        // afterwards would answer "hidden, height 0" about a list the user can plainly still see,
        // and the re-open would jump to zero before travelling.
        val from = if (list.isVisible) list.height else 0
        RunningAnimators.cancel(list)
        list.animate().cancel()
        if (previous == null || entranceHeld || list.reducedMotion()) {
            list.isVisible = shown
            list.alpha = 1f
            setListHeight(list, if (shown) ViewGroup.LayoutParams.WRAP_CONTENT else 0)
            return
        }

        if (shown) {
            list.visibility = View.VISIBLE
            setListHeight(list, from)
        }
        val target = if (shown) measureListHeight(list) else 0
        list.animate().alpha(if (shown) 1f else 0f)
            .setDuration(durState)
            .setInterpolator(easeOutQuart)
            .start()
        val height = ValueAnimator.ofInt(from, target).apply {
            duration = durExpand
            interpolator = easeOutQuart
            addUpdateListener { setListHeight(list, it.animatedValue as Int) }
            doOnEnd {
                if (!isBindingInitialized) return@doOnEnd
                if (shown) {
                    // Back to wrap_content, never left at the measured pixel height: a list that
                    // gains a row while it is open has to grow, and a frozen height would clip it.
                    setListHeight(list, ViewGroup.LayoutParams.WRAP_CONTENT)
                } else {
                    list.isVisible = false
                    list.alpha = 1f
                }
            }
        }
        RunningAnimators.set(list, height)
    }

    private fun setListHeight(list: View, height: Int) {
        list.layoutParams = list.layoutParams.apply { this.height = height }
    }

    /** The list's open height, at the width it already has. @see setServerListShown */
    private fun measureListHeight(list: View): Int {
        val width = list.width
        if (width <= 0) return list.height
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return list.measuredHeight
    }

    private fun paintGate(gate: Gate) {
        val block = binding.layoutGate
        // The heading, restored from the onboarding card this block replaced (5e8cd54's
        // home_empty_title). It names the SHAPE the user is in; the caption under it keeps the
        // reason, which is why the two are separate strings rather than one longer sentence.
        block.tvGateTitle.setText(
            when (gate) {
                Gate.SIGN_IN -> R.string.home_gate_signin_title
                Gate.ADD_SUBSCRIPTION -> R.string.home_gate_subscription_title
                Gate.BUY -> R.string.home_gate_buy_title
                Gate.SYNC_SERVERS -> R.string.home_gate_sync_title
                Gate.SYNC_FAILED -> R.string.home_gate_sync_failed_title
            }
        )
        val captionRes = when (gate) {
            Gate.SIGN_IN -> R.string.home_gate_signin_caption
            Gate.ADD_SUBSCRIPTION -> R.string.home_gate_subscription_caption
            Gate.BUY -> R.string.home_gate_buy_caption
            Gate.SYNC_SERVERS -> R.string.home_gate_sync_caption
            Gate.SYNC_FAILED -> R.string.home_gate_sync_failed_caption
        }
        block.tvGateCaption.setText(captionRes)
        // The failure reason IS the caption, so it carries the failure's colour.
        block.tvGateCaption.setTextColor(
            if (gate == Gate.SYNC_FAILED) {
                themeColor(R.attr.colorDestructiveText)
            } else {
                themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
        )

        // THE LOADING STATE of this block. «Загрузить серверы» is the action that STARTS the
        // subscription refresh this screen is then waiting on, so while one is in flight it is
        // disabled: an in-flight action shows it and cannot be fired twice (OWNER-FEEDBACK
        // 2026-07-27 G2). The strip above already says «Загружаем…» and the connect object's sweep
        // is spinning, so the button does not need to restate it — it needs to stop accepting taps
        // that would stack a second refresh on the first. Every other gate's action starts nothing
        // this screen waits on, so it stays live.
        block.btnGatePrimary.isEnabled = !(gate == Gate.SYNC_SERVERS && backgroundLoads > 0)

        when (gate) {
            Gate.SIGN_IN -> {
                block.btnGatePrimary.setText(R.string.home_gate_signin)
                block.btnGatePrimary.onSingleClick { openLoginScreen() }
                block.btnGateSecondary.isVisible = true
                block.btnGateSecondary.setText(R.string.home_gate_add_subscription)
                block.btnGateSecondary.onSingleClick { mainHost.showAddMenu(it) }
            }

            Gate.ADD_SUBSCRIPTION -> {
                block.btnGatePrimary.setText(R.string.home_gate_add_subscription)
                block.btnGatePrimary.onSingleClick { mainHost.showAddMenu(it) }
                block.btnGateSecondary.isVisible = false
            }

            Gate.BUY -> {
                block.btnGatePrimary.setText(R.string.home_gate_buy)
                block.btnGatePrimary.onSingleClick {
                    startActivity(Intent(requireContext(), BuyTariffActivity::class.java))
                }
                // «Привязать Telegram» — the only live entry point to linking Telegram on this
                // screen. It lived on the onboarding card this block replaced, and it is offered on
                // exactly the same condition: signed in, no подписка, Telegram not yet attached.
                val linked = (AccountSession.state.value as? AccountSession.AccountState.LoggedIn)
                    ?.profile?.telegramLinked == true
                block.btnGateSecondary.isVisible = !linked
                block.btnGateSecondary.setText(R.string.home_gate_link_telegram)
                block.btnGateSecondary.onSingleClick { openTelegramLink() }
            }

            Gate.SYNC_SERVERS -> {
                block.btnGatePrimary.setText(R.string.home_gate_sync)
                block.btnGatePrimary.onSingleClick { requestServerSync() }
                block.btnGateSecondary.isVisible = false
            }

            Gate.SYNC_FAILED -> {
                block.btnGatePrimary.setText(R.string.home_gate_retry)
                block.btnGatePrimary.onSingleClick { requestServerSync() }
                block.btnGateSecondary.isVisible = false
            }
        }

        // НАЧАЛЬНЫЙ ЭКРАН (README §2). In the SIGN_IN shape this block IS the onboarding screen —
        // shield, clipboard card, the two contour pills and «Другие способы» — and everything it
        // does lives in GateView next to its own layout. Called LAST on purpose: it has the final
        // word on what is visible, so the `when` above cannot leave a generic button on a screen
        // that has its own. Every other shape gets the plain heading + caption + two buttons back.
        block.gate.bindOnboarding(gate == Gate.SIGN_IN, gateHost)
    }

    /**
     * The four errands the start screen cannot run itself: the shell owns the auth launcher (a
     * login that changes the theme or the server groups is applied on the way back through it), the
     * QR scanner and the server list.
     */
    private val gateHost by lazy {
        object : GateView.Host {
            override fun openAuth(intent: Intent) = mainHost.launchAuthScreen(intent)

            override fun addByQr(anchor: View) = mainHost.showAddMenu(anchor)

            override fun onSubscriptionAdded() = mainViewModel.reloadServerList()

            // Straight through to the shell, which is where the import is tracked — this fragment
            // starts it (onLoggedIn) but the shell is what knows when every one of them has landed.
            override suspend fun awaitSubscriptionImport() = mainHost.awaitSubscriptionImport()
        }
    }

    /**
     * «Загрузить серверы», and the two different loads that hide behind one button.
     *
     * The owner: «если удалить свою подписку при вошедшем аккаунте, то пишет типа загрузить сервера
     * … и не работает кнопка, пишет не удалось загрузить, хотя должно работать».
     *
     * He is in a state this button had no branch for. Deleting the подписка from the card removes
     * the LOCAL copy — `MmkvManager.removeSubscription` drops it from the store and takes its
     * серверы with it — while the ACCOUNT still holds the подписка, which is exactly why the gate
     * correctly reads «Подписка активна, серверы ещё не загружены». But the button called
     * `refreshSubscriptions()`, i.e. the shell's `updateConfigViaSubAll()`, which walks the LOCAL
     * подписки and refetches each one. There were none. It returned `successCount == 0` without a
     * single request going out, the shell reported «Не удалось обновить», and this screen then
     * resolved [Gate.SYNC_FAILED] — a verdict about a network failure that never happened. The
     * instant appearance of the message was the tell.
     *
     * So the button asks the right source: with no local подписка to refresh and a live account,
     * the servers can only come from a RE-IMPORT, which is the same `autoImportSubscriptions()`
     * that runs after a sign-in. `SubscriptionSyncManager.importAll` keys each подписка by its
     * account identity through `AuthTokenStore.getManagedGuids()`, so the deleted one is recreated
     * under the guid it had rather than duplicated.
     *
     * Anything else keeps the refresh it always had: a pasted подписка, or an account подписка that
     * is still on the device and merely out of date, is a refresh and not an import.
     */
    private fun requestServerSync() {
        syncRequested = true
        val nothingLocalToRefresh = MmkvManager.decodeSubsList().isEmpty()
        val accountCanSupply = BackendConfig.isConfigured() && AccountSession.isLoggedIn()
        if (nothingLocalToRefresh && accountCanSupply) importAccountSubscriptions() else mainHost.refreshSubscriptions()
    }

    /**
     * Re-imports the account's подписки, and reports on the surfaces this screen already has.
     *
     * [showConnectArc] / [hideConnectArc] are the SAME in-flight signal the shell raises for its own
     * refresh — the connect object's sweep plus the bottom «Обновляем данные…» — so an import
     * started here is indistinguishable from one started anywhere else, and `backgroundLoads` is
     * what holds the gate's button disabled while it runs (see [paintGate]) and what keeps
     * [resolveGate] on «Подписка активна» rather than jumping to a failure that has not happened
     * yet. Balanced on every exit, including the failure one.
     */
    private fun importAccountSubscriptions() {
        showConnectArc()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { AccountRepository().autoImportSubscriptions() }
                .getOrElse { Result.failure(it) }
            if (!isBindingInitialized) {
                // The counter outlives the view — it is the fragment's, not the binding's — so the
                // one exit with no screen left to paint still balances it. Left unbalanced, the arc
                // would be spinning on the next view this fragment gets.
                if (backgroundLoads > 0) backgroundLoads--
                return@launch
            }
            result
                .onSuccess {
                    // The cache is what the list and the carousel are painted from, and the account
                    // list is what names the cards; a подписка that has just come back needs both.
                    mainViewModel.reloadServerList()
                    accountViewModel.loadSubscriptions()
                }
            // THE REBUILD ABOVE AND THE ARC'S EXIT ARE NOT THE SAME FRAME any more. `hideConnectArc`
            // used to run first and the rebuild second, in one message, so the exit's clock started
            // on the frame that binds every row. @see afterListSettles
            afterListSettles {
                hideConnectArc()
                // A REAL failure this time, so the gate is allowed to say so: `syncRequested`
                // is still set and `backgroundLoads` is back to zero, which is precisely the
                // pair [resolveGate] turns into SYNC_FAILED.
                if (result.isFailure && isBindingInitialized) render()
            }
        }
    }

    // ==================== Motion ====================

    /**
     * The one hero moment in the product: 600ms, once, and nothing else in the app is allowed this
     * budget. Four beats fire together at T=0 — the shield crossfade, the ring tint, the sweep's
     * exit and the single confirm ring.
     *
     * Reduced motion: the shield is filled instantly, the ring tint is set instantly, the confirm
     * ring is NOT emitted at all, and the haptic still fires.
     */
    private fun playConfirm(ringTarget: Int, live: Boolean, haptic: Boolean) {
        // `HapticFeedbackConstants.CONFIRM` is API 30 and `minSdk` is 24, so the raw call this used
        // to be was silence on every older device and on every OEM build that never mapped the
        // constant — the app's single most important buzz, refused and unnoticed. `confirmHaptic()`
        // still asks for CONFIRM first and keeps that distinct feel wherever it exists.
        if (haptic) binding.connectFrame.confirmHaptic()
        stopBreathing()
        tintRing(ringTarget, animate = live)
        // §4: the connected ring is «акцентное, медленное затухание яркости 5.5 с». It starts HERE,
        // on the edge into connected, so it plays once per connection and not once per repaint.
        settleRing(live)

        if (!live) {
            binding.shieldFilled.alpha = 1f
            binding.shieldOutline.alpha = 0f
            clearConfirmRings()
            return
        }

        // §4: «Щит 68dp: контурный когда отключено, залитый когда подключено, ПЕРЕХОД ПО
        // ПРОЗРАЧНОСТИ 300 МС». @integer/motion_reveal is that 300. It is longer than the ring's
        // own colour change (motion_state, 220) on purpose — the ring is one property crossing,
        // the shield is one glyph becoming another, and the slower of the two is the one the eye
        // is actually on.
        binding.shieldFilled.animate().cancel()
        binding.shieldFilled.animate().alpha(1f).setDuration(durReveal).setInterpolator(easeStandard).start()
        binding.shieldOutline.animate().cancel()
        binding.shieldOutline.animate().alpha(0f).setDuration(durReveal).setInterpolator(easeStandard).start()

        // TWO RINGS. The lead ring, then a dimmer echo a beat behind it: the desktop's payoff
        // (ConnectHeroView.axaml, Sonar.pulsing + Sonar.pulsing-echo), which Android emitted only
        // the first half of. Two, and never a third — «максимум ДВА кольца, не радар».
        emitConfirmRings()
    }

    /**
     * THE SONAR, AND IT MOVES ON ITS OWN — the two confirm rings, thrown clear of the object once
     * and returned to INVISIBLE at the end of the travel.
     *
     * THE RINGS USED TO BE MOVED BY THE BOX THEY FLY OUT OF, and that is the third face of the
     * fault the owner keeps reporting as «улетают за невидимый квадрат». A legacy view animation
     * (`View.startAnimation`, @anim/connect_confirm) does not belong to the view it plays on: the
     * PARENT resolves it every frame — `ViewGroup.getChildTransformation()` — and hands the result
     * to the child inside its own draw pass, so the ring's geometry is computed by, and travels
     * through, the 214dp frame. The two causes already known for this class of defect are the
     * same shape: a container that clips its children, and a hardware layer handed to the
     * container instead of to what moves inside it. This is the same mistake spelled a third way,
     * and it is the one Главная had left — the clip is off on connect_frame, home_content and
     * home_tab_root (fragment_home.xml), and no layer was ever put on the frame.
     *
     * So the rings are property animators now: SCALE_X/SCALE_Y/ALPHA on the ring's OWN RenderNode,
     * exactly the construction [com.v2ray.ang.ui.component.FlowOverlay.playFinale] uses for the
     * прогрузка sonar. One [AnimatorSet] with a start delay for the echo, never a chain of
     * `postDelayed`.
     *
     * The hardware layer goes on the two rings — the things that move — and comes back off when
     * the set ends OR is cancelled, which is why the listener answers both. Never on
     * @id/connect_frame: a layer is a buffer the size of the view it is given to, and one on the
     * frame would re-impose the very 214dp square the layout has stopped drawing.
     *
     * EVERY NUMBER IS THE ONE THE DESIGN STATES, and they are the numbers @anim/connect_confirm
     * carried:
     *
     *     lead  scale 1 -> 1.6, alpha 1.0 -> 0, @integer/motion_emphasis (600) on ease_out_quint,
     *           which IS the prototype's own `sonar 600ms cubic-bezier(.22,1,.36,1)` at
     *           `inset:22px`, i.e. on the 170dp active ring thrown out to 272dp;
     *     echo  scale 1 -> 1.5, alpha 0.5 -> 0, same 600 on the same curve, one
     *           @integer/motion_press_in (70) behind — the desktop's Sonar.pulsing-echo.
     *
     * The ring is parked at the END of its travel and hidden there, rather than being snapped back
     * to 1.0 at full opacity for the frame between the last animation frame and the callback: the
     * prototype's `animation-fill-mode: both` says the same thing, and the old `fillAfter="false"`
     * was one frame of an opaque 170dp accent ring sitting on the object's own inner ring.
     */
    private fun emitConfirmRings() {
        val lead = binding.connectRingPulse
        val echo = binding.connectRingPulseEcho
        confirmAnimator?.cancel()
        val rings = listOf(lead, echo)
        rings.forEach { it.visibility = View.VISIBLE }
        lead.alpha = 1f
        echo.alpha = CONFIRM_ECHO_ALPHA
        rings.forEach { it.scaleX = 1f; it.scaleY = 1f }

        confirmAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(lead, View.SCALE_X, CONFIRM_LEAD_TO),
                ObjectAnimator.ofFloat(lead, View.SCALE_Y, CONFIRM_LEAD_TO),
                ObjectAnimator.ofFloat(lead, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(echo, View.SCALE_X, CONFIRM_ECHO_TO).apply { startDelay = durPressIn },
                ObjectAnimator.ofFloat(echo, View.SCALE_Y, CONFIRM_ECHO_TO).apply { startDelay = durPressIn },
                ObjectAnimator.ofFloat(echo, View.ALPHA, 0f).apply { startDelay = durPressIn },
            )
            duration = durEmphasis
            interpolator = easeOutQuint
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isBindingInitialized) return
                    rings.forEach { it.setLayerType(View.LAYER_TYPE_NONE, null) }
                    parkConfirmRings()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (!isBindingInitialized) return
                    rings.forEach { it.setLayerType(View.LAYER_TYPE_NONE, null) }
                }
            })
            rings.forEach { it.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
            start()
        }
    }

    private fun clearConfirmRings() {
        confirmAnimator?.cancel()
        confirmAnimator = null
        parkConfirmRings()
    }

    /** The rings' resting shape: invisible, unscaled, opaque, ready for the next confirmation. */
    private fun parkConfirmRings() {
        listOf(binding.connectRingPulse, binding.connectRingPulseEcho).forEach { ring ->
            ring.visibility = View.INVISIBLE
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.alpha = 1f
        }
    }

    /**
     * The arc appears, and keeps turning.
     *
     * Idempotent on purpose, because it is called from every repaint that finds the core
     * negotiating: the ENTRANCE plays only when the arc was not on screen, while the revolution is
     * re-armed unconditionally — [onStop] stops the infinite animator and [onResume]'s render is
     * what has to put it back, without replaying an entrance the user already saw.
     *
     * @return how long the arc's arrival takes, or 0 when it was already there. [paintConnect] puts
     *   the ring's step down on the same clock. @see dimRingTrack
     */
    private fun showSweep(): Long {
        val sweep = binding.connectSweep
        var handover = 0L
        // «Visible» is not the same as «staying»: a background load that ends and a connect that
        // starts a beat later catch the arc mid-exit, still visible and still fading to nothing.
        // That is a fresh appearance and takes the entrance again — without this test the arc
        // would keep the exit's alpha and then be hidden by its own end action.
        if (!sweep.isVisible || sweepHiding) {
            sweepHiding = false
            sweep.isVisible = true
            handover = playArcWindUp()
        }
        startSweepSpin()
        return handover
    }

    /**
     * THE ARC LEAVES, AND IT COASTS TO A STOP RATHER THAN BEING SWITCHED OFF.
     *
     * The exit used to be a flat `motion_state_exit` fade over an arc still turning at full tempo,
     * cut at whatever angle the cancel happened to catch. 165ms is 54° of travel: short enough that
     * the eye reads an end rather than a departure, which is half of «слишком резко конец
     * анимации». The other half was the ring, and that is [dimRingTrack].
     *
     * So the arc now FINISHES A QUARTER. [windDownSweep] carries it to the next quarter mark of the
     * circle at the unchanged 1100ms tempo — the tempo is never touched, §4 — and the fade runs over
     * exactly that window, so the arc travels a readable distance while it dissolves and comes to
     * rest on a mark instead of wherever the cancel landed.
     *
     * **The exit is bounded and the bound is stated in the design's own numbers**: the landing is at
     * least `motion_state_exit` of travel away (54° — otherwise an arc already sitting on a mark
     * would vanish in three frames) and at most that plus one quarter, so the window is 165–440ms.
     * Waiting for a WHOLE revolution would have been up to 1100ms of held screen, which is the
     * owner's other complaint spelled backwards.
     *
     * @return that window, or 0 when there was no arc to send away. THE RING'S RETURN IS NO LONGER
     *   LAID OVER IT — it only reads the zero, as «nothing is moving, land instantly». A window that
     *   is 165ms on one refresh and 440ms on the next, because that is where the arc happened to be
     *   standing, is not a clock a second object can keep. @see dimRingTrack
     */
    private fun hideSweep(): Long {
        val sweep = binding.connectSweep
        if (!sweep.isVisible || sweepHiding) return 0L
        sweep.animate().cancel()
        if (sweep.reducedMotion()) {
            stopSweepSpin()
            sweep.alpha = 1f
            sweep.isVisible = false
            return 0L
        }
        val exit = windDownSweep()
        sweepHiding = true
        sweep.animate().alpha(0f)
            .setDuration(exit)
            .setInterpolator(easeStandard)
            .withEndAction {
                // The flag is also the CANCEL guard. A ViewPropertyAnimator runs its end action on
                // cancel as well as on completion, so an exit that [showSweep] interrupted would
                // otherwise hide the arc it had just brought back.
                if (!isBindingInitialized || !sweepHiding) return@withEndAction
                sweepHiding = false
                stopSweepSpin()
                sweep.alpha = 1f
                sweep.isVisible = false
            }
            .start()
        return exit
    }

    /**
     * The last stretch of the revolution: from wherever the arc is to the next quarter mark, at the
     * steady tempo, once.
     *
     * It replaces the infinite animator rather than running beside it — two animators on one
     * ROTATION is how a steady spin gets snapped, which is the note [playArcWindUp] already carries
     * — so it is held in its own handle and [startSweepSpin] drops it before re-arming.
     *
     * @return the length of that stretch in ms, which is also the length of the whole exit.
     */
    private fun windDownSweep(): Long {
        val sweep = binding.connectSweep
        val spin = resources.getInteger(R.integer.motion_spin).toLong()
        sweepSpin?.cancel()
        sweepSpin = null
        val angle = turnOf(sweep.rotation)
        // How far past the last mark the arc is, and therefore how far the next one is.
        val past = angle - (angle / SWEEP_STOP_STEP).toInt() * SWEEP_STOP_STEP
        var travel = SWEEP_STOP_STEP - past
        // …and never so close that the exit would be over before it registered. The floor is
        // motion_state_exit expressed as travel, i.e. the distance the old flat exit covered.
        val minTravel = FULL_TURN_DEGREES * durStateExit / spin
        while (travel < minTravel) travel += SWEEP_STOP_STEP
        val window = (spin * travel / FULL_TURN_DEGREES).toLong()
        sweepWindDown = ObjectAnimator.ofFloat(sweep, View.ROTATION, angle, angle + travel).apply {
            duration = window
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        return window
    }

    /** An angle folded back into one turn, so the arithmetic above never works on 3600°. */
    private fun turnOf(rotation: Float): Float =
        ((rotation % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES

    /**
     * THE REVOLUTION: §4's «оборот 1100 мс», linear, one infinite ObjectAnimator on ROTATION.
     *
     * @integer/motion_spin is that 1100, and this is the same construction `FlowOverlay.startArc`
     * uses on the прогрузка ring — the route the previous wave named and could not take, because
     * the arc was a `CircularProgressIndicator` whose revolution is private to Material and whose
     * `disjoint` arc changes length as it goes. Frame-by-frame against the prototype that is a
     * different animation; the design's arc is a fixed 19.5% of the circle at a steady tempo.
     *
     * Never restarted while it is already running: a repaint must not snap the arc back to 0°.
     *
     * AND IT PICKS UP WHERE THE ARC IS, not at 0°. A load that ends and another that arrives a beat
     * later catch the arc inside [windDownSweep] — the infinite animator is gone by then, so a
     * revolution declared from 0f would jump the arc across the circle in the one frame the user is
     * most likely to be watching it. Starting from the current angle keeps the tempo unbroken
     * through an interrupted exit.
     */
    private fun startSweepSpin() {
        if (sweepSpin?.isRunning == true) return
        val sweep = binding.connectSweep
        if (sweep.reducedMotion()) return
        // The wind-down drives the same property; it is dropped rather than left to fight.
        sweepWindDown?.cancel()
        sweepWindDown = null
        val from = turnOf(sweep.rotation)
        sweepSpin = ObjectAnimator.ofFloat(sweep, View.ROTATION, from, from + FULL_TURN_DEGREES).apply {
            duration = resources.getInteger(R.integer.motion_spin).toLong()
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    /** Stops the revolution and returns the arc to 0°, so nothing infinite outlives the moment. */
    private fun stopSweepSpin() {
        sweepSpin?.cancel()
        sweepSpin = null
        // The bounded half of the same revolution. Cancelled here too, or an exit interrupted by
        // onPause would go on turning an invisible arc. @see windDownSweep
        sweepWindDown?.cancel()
        sweepWindDown = null
        if (isBindingInitialized) binding.connectSweep.rotation = 0f
    }

    /**
     * THE WIND-UP, and it is OPACITY now rather than a revolution.
     *
     * It used to be one 360° turn on ease_out_quint, from the desktop's P0-3
     * (`ConnectHeroView.axaml`, `Ellipse.ConnectArc.arc-windup`), which was safe only while the
     * steady spin belonged to a drawable. It does not any more: [startSweepSpin] drives the same
     * ROTATION property, and two animators on one property is how a steady spin gets snapped. So
     * the arc still *runs up* out of rest — it fades in over @integer/motion_windup while the
     * revolution is already turning — and the 1100ms tempo is never touched.
     *
     * Fires on the EDGE into negotiating only, and never under reduced motion, where the arc is
     * the only signal there is and must simply be present.
     *
     * @return the length of that run-up, so the ring's step down joins it. @see dimRingTrack
     */
    private fun playArcWindUp(): Long {
        val sweep = binding.connectSweep
        if (sweep.reducedMotion()) {
            sweep.alpha = 1f
            return 0L
        }
        sweep.animate().cancel()
        sweep.alpha = 0f
        sweep.animate()
            .alpha(1f)
            .setDuration(durWindUp)
            .setInterpolator(easeOutQuint)
            .start()
        return durWindUp
    }

    /** Exit is 75 percent of enter, and it emits nothing. */
    private fun playRelease(ringTarget: Int, live: Boolean) {
        stopBreathing()
        tintRing(ringTarget, animate = live)
        // The settle belongs to the connection that just ended: if it is still decaying, it stops
        // here and the rings go back to their resting brightness with the rest of the object.
        stopAmbient()
        clearConfirmRings()

        if (!live) {
            binding.shieldFilled.alpha = 0f
            binding.shieldOutline.alpha = 1f
            return
        }
        binding.shieldFilled.animate().cancel()
        binding.shieldFilled.animate().alpha(0f).setDuration(durReveal).setInterpolator(easeStandard).start()
        binding.shieldOutline.animate().cancel()
        binding.shieldOutline.animate().alpha(1f).setDuration(durReveal).setInterpolator(easeStandard).start()
    }

    /**
     * The negotiating breath. The old build breathed the halo glow behind the shield; that glow is
     * banned and did not come back, so the same 850ms reverse lives on the TWO OUTER RINGS, which
     * are part of the object rather than a wash behind it. They swell in opacity together while the
     * sweep travels the disc — motion the user reads as "it is working on it", on the object itself.
     *
     * AND IT REACHES THE SHIELD. The desktop breathes the glyph on the same clock and in the same
     * direction (`ConnectHeroView.axaml`, `Path.shieldbreathe`: alpha 1 <-> 0.8, 850ms sine,
     * Alternate) so the two waves read as ONE calm inhale rather than as two separate fidgets.
     * OPACITY ONLY, never a transform — the desktop's own note, and for the same reason: an opacity
     * animation has no centre to drift out of.
     *
     * Reduced motion: nothing breathes and the sweep is the only signal.
     */
    private fun startBreathing() {
        if (breathAnimator?.isRunning == true) return
        if (binding.connectFrame.reducedMotion()) return
        breathAnimator = ValueAnimator.ofInt(BREATH_ALPHA_MIN, OPAQUE).apply {
            duration = BREATH_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Int
                ringOuter?.alpha = value
                ringMid?.alpha = value
                // The glyph rides the same wave, mapped onto its own narrower band. The OUTLINE is
                // the shield on screen while negotiating; the filled one is at alpha 0 and is left
                // alone, so nothing here can fight the connect crossfade.
                val fraction = (value - BREATH_ALPHA_MIN).toFloat() / (OPAQUE - BREATH_ALPHA_MIN)
                binding.shieldOutline.alpha =
                    SHIELD_BREATH_ALPHA_MIN + (1f - SHIELD_BREATH_ALPHA_MIN) * fraction
            }
            start()
        }
    }

    private fun stopBreathing() {
        val wasBreathing = breathAnimator != null
        breathAnimator?.cancel()
        breathAnimator = null
        // NOTHING IS WRITTEN UNLESS THIS BREATH WAS ACTUALLY RUNNING. paintConnect calls this on
        // every repaint of every non-negotiating state, and a blind reset here would stamp the
        // rings back to full brightness in the middle of the connected settle — restarting that
        // 5.5s decay from the top on every traffic tick. It would also put one frame of a fully
        // opaque outline over an already-connected object.
        if (!wasBreathing) return
        ringOuter?.alpha = OPAQUE
        ringMid?.alpha = OPAQUE
        if (isBindingInitialized) binding.shieldOutline.alpha = 1f
    }

    /**
     * THE CONNECTED SETTLE — §4's «акцентное, медленное затухание яркости 5.5 с», and the only
     * thing the object does once it is up.
     *
     * ONE SHOT, NOT A LOOP, and that is the whole point of it. What was here was an infinite
     * REVERSE on the rings' opacity plus a second infinite loop scaling a fourth ring outwards —
     * a permanent pulse, which is §11 grabl 8 («Постоянная пульсация кольца назойлива») and which
     * §4 rules out twice over: «не пульсирует постоянно», «без масштаба». So the object arrives
     * bright at the moment of connection and fades to its resting brightness over
     * @integer/motion_ring_fade (5500ms) on ease_in_out — the longest, quietest motion in the
     * product, and it ends. Idle is «приглушённое, СТАТИЧНОЕ»: nothing runs there at all.
     *
     * The decay rides the rings' drawable alpha and never their colour, so it composes with
     * [tintRing] instead of fighting it — the hue is the state, this is only the brightness.
     *
     * It also means no infinite animator survives this screen: the negotiating breath is the one
     * that is left, and it stops the instant negotiation does.
     */
    private fun settleRing(live: Boolean) {
        if (!isBindingInitialized) return
        stopAmbient()
        if (!live) return
        if (binding.connectFrame.reducedMotion()) return
        ringSettleAnimator = ValueAnimator.ofInt(OPAQUE, RING_SETTLED_ALPHA).apply {
            duration = resources.getInteger(R.integer.motion_ring_fade).toLong()
            interpolator = easeInOut
            addUpdateListener {
                val value = it.animatedValue as Int
                ringOuter?.alpha = value
                ringMid?.alpha = value
            }
            start()
        }
    }

    /** Cancels the settle and returns the rings to their resting brightness. */
    private fun stopAmbient() {
        ringSettleAnimator?.cancel()
        ringSettleAnimator = null
        ringOuter?.alpha = OPAQUE
        ringMid?.alpha = OPAQUE
    }

    /** The rings' only state channel. Width never changes; the colour crosses over motion_state. */
    private fun tintRing(target: Int, animate: Boolean) {
        if (ringInner == null) return
        ringAnimator?.cancel()
        ringAnimator = null
        if (!animate || ringColor == target) {
            ringColor = target
            applyRingColor(target)
            return
        }
        val from = ringColor
        ringAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = durState
            interpolator = easeStandard
            addUpdateListener {
                val value = it.animatedValue as Int
                ringColor = value
                applyRingColor(value)
            }
            start()
        }
    }

    private fun idleRingColor(): Int = themeColor(R.attr.colorOnSurfaceDim)

    private fun themeColor(attr: Int): Int = MaterialColors.getColor(binding.connectFrame, attr)

    // ==================== The session clock ====================

    /**
     * Starts the per-second uptime from the instant THE TUNNEL came up.
     *
     * That instant belongs to [CoreServiceManager.sessionStartedAt] — it is stamped beside the core
     * loop, in the daemon process, and this screen only reads it. It used to be stamped and cleared
     * here, and that is the defect the owner reported: «когда закрываешь приложение и заходишь
     * назад, время сессии сбивается». `MainViewModel.startListenBroadcast()` publishes
     * `isRunning = false` on every activity start, before the service has answered the registration
     * handshake; the observer below took that at face value, [stopConnectionTimer] wiped the stored
     * instant, and the real «running» that arrived a moment later found nothing and stamped `now`.
     * The counter was measuring how long the screen had been open, which on a tunnel that had been
     * up for two hours is simply a wrong number.
     *
     * The fallback to `now` stays for the one case it is honest about: a tunnel that is running with
     * no stamp behind it — started by a build older than the stamp, or by something that bypassed
     * [CoreServiceManager.startCoreLoop]. Counting from now is wrong by however long that session
     * has run, and it is the best this screen can know; it is NOT written back, so the moment the
     * daemon stamps a real one the clock corrects itself instead of inheriting the guess.
     */
    private fun startConnectionTimer() {
        val started = CoreServiceManager.sessionStartedAt()
        connectionStartTime = when {
            // The daemon's own stamp always wins, and a restart while the app was away puts a new
            // one there — so a resume follows the session that is actually up.
            started > 0L -> started
            // NO STAMP, AND WE ALREADY GUESSED ONCE. [onResume] calls this on every return now, and
            // re-guessing `now` each time would restart the counter from zero every time the user
            // came back to a stamp-less session. The first guess is the oldest thing this screen
            // knows about that tunnel, so it is the least wrong one to keep.
            connectionStartTime > 0L -> connectionStartTime
            else -> System.currentTimeMillis()
        }
        timerHandler.removeCallbacks(uptimeRunnable)
        timerHandler.post(uptimeRunnable)
    }

    /**
     * Stops the ticking and blanks the readings. **It does not end the session** — that is the
     * daemon's to end, in [CoreServiceManager.stopCoreLoop]. This runs on every optimistic
     * `isRunning = false` too, and erasing the start instant from here is exactly what reset the
     * clock on every app launch.
     */
    private fun stopConnectionTimer() {
        timerHandler.removeCallbacks(uptimeRunnable)
        connectionStartTime = 0L
        // The session is over, so its readings go with it — and the strip returns to zeroes rather
        // than freezing on the last speed the tunnel ever saw.
        downBytesPerSec = null
        upBytesPerSec = null
        pingMs = null
        pingProbeFailures = 0
        if (isBindingInitialized) {
            binding.tvUptime.text = getString(R.string.home_uptime_zero)
            paintFigures()
        }
    }

    // ==================== The connect state machine ====================

    /**
     * The object's action. Three cases, and the middle one is the one the shipped build got wrong: a
     * tap DURING negotiation cancels the attempt, because a control that ignores a tap for twenty
     * seconds is a control the user stops trusting.
     */
    private fun handleConnectAction() {
        when {
            connectInProgress -> {
                // Cancel: stop whatever half-started and return to idle.
                connectInProgress = false
                // A tap here during a switch is the user taking the operation back, so there is no
                // start queued behind the stop any more and «not running» becomes an outcome again.
                // Without this the flag would outlive the watchdog this branch stands down, and the
                // next genuine stop would be swallowed. @see switching
                switching = false
                cancelConnectWatchdog()
                tunnelError = false
                CoreServiceManager.stopVService(requireContext())
                applyRunningState(isLoading = false, isRunning = false)
            }

            mainViewModel.isRunning.value == true -> {
                connectInProgress = false
                switching = false
                cancelConnectWatchdog()
                disconnecting = true
                tunnelError = false
                // The exit plays on the TAP, not one repaint later when the daemon finally answers.
                render(animate = true)
                CoreServiceManager.stopVService(requireContext())
            }

            else -> {
                connectInProgress = true
                tunnelError = false
                applyRunningState(isLoading = true, isRunning = false)
                scheduleConnectWatchdog()
                startVpnWithPermission()
            }
        }
    }

    /**
     * Starts the VPN, requesting the system VPN permission first when needed.
     *
     * The watchdog is STOOD DOWN while that system prompt is up, and re-armed by the result
     * callback when the permission comes back granted. It bounds a start that stalls; a decision the
     * user has not made yet is not a stalled start, and counting the prompt against the 20s deadline
     * reported a failure to anyone who read it before answering.
     */
    private fun startVpnWithPermission() {
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(requireContext())
            if (intent == null) {
                startV2Ray()
            } else {
                cancelConnectWatchdog()
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            // The object is disabled in this state, so this is a backstop rather than the user's
            // first contact with the problem — the status line already says «Сервер не выбран».
            connectInProgress = false
            // Reached from [restartV2Ray] too, and there it is the third way a switch can end with
            // no start behind it. @see switching
            switching = false
            applyRunningState(isLoading = false, isRunning = false)
            return
        }
        CoreServiceManager.startVService(requireContext())
    }

    /**
     * Stops the running tunnel and starts it again on the currently selected server.
     *
     * The core runs in its own process (`:RunSoLibV2RayDaemon`), so stopping is asynchronous and the
     * only truthful signal in this process is `MainViewModel.isRunning`, driven by the daemon's
     * broadcasts. Waiting a fixed delay here used to lose that race: the new start would arrive
     * while the old core was still up, `startContextService()` would see `coreController.isRunning`
     * and return silently, and the tunnel would keep running the PREVIOUS server while the UI showed
     * the new one. So wait for a real stopped state, and report failure rather than pretending to
     * have switched.
     */
    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value != true) {
            startV2Ray()
            return
        }
        // THE INTENT, DECLARED BEFORE THE DAEMON IS ASKED TO STOP. From here until one of the three
        // exits below, «not running» is a step of this operation and not its result. @see switching
        switching = true
        CoreServiceManager.stopVService(requireContext())
        lifecycleScope.launch {
            val deadline = SystemClock.elapsedRealtime() + RESTART_STOP_TIMEOUT_MS
            while (mainViewModel.isRunning.value == true && SystemClock.elapsedRealtime() < deadline) {
                delay(RESTART_STOP_POLL_MS)
            }
            if (mainViewModel.isRunning.value == true) {
                // The old tunnel would not come down, so there is nothing queued behind it any more
                // and this IS the outcome.
                switching = false
                connectInProgress = false
                tunnelError = true
                if (isBindingInitialized) applyRunningState(isLoading = false, isRunning = true)
                return@launch
            }
            startV2Ray()
        }
    }

    /**
     * The one entry point the connect machine uses to publish a state. [animate] is true ONLY on a
     * genuine live transition, so the signature confirmation and its reverse play then — not on the
     * LiveData replay after a rotation/theme recreate, which jumps straight to the end state.
     */
    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean, animate: Boolean = false) {
        connectLoading = isLoading
        tunnelRunning = isRunning && !isLoading
        if (!isLoading) disconnecting = false
        if (isRunning || isLoading) tunnelError = false
        if (isBindingInitialized) render(animate)
    }

    /** Arms the connect watchdog so a stalled/crashed start can't hang the UI on «Подключение…». */
    private fun scheduleConnectWatchdog() {
        timerHandler.removeCallbacks(connectWatchdogRunnable)
        timerHandler.postDelayed(connectWatchdogRunnable, CONNECT_TIMEOUT_MS)
    }

    /** Cancels the connect watchdog once the attempt resolved (success/failure/stop). */
    private fun cancelConnectWatchdog() {
        timerHandler.removeCallbacks(connectWatchdogRunnable)
    }

    private fun startLatencyProbe() {
        timerHandler.removeCallbacks(latencyRunnable)
        timerHandler.post(latencyRunnable)
    }

    private fun stopLatencyProbe() {
        timerHandler.removeCallbacks(latencyRunnable)
    }

    // ==================== Navigation ====================

    private fun openAccount() {
        if (BackendConfig.isConfigured() && AccountSession.isLoggedIn()) {
            mainHost.selectTab(MainTab.ACCOUNT)
        } else {
            openLoginScreen()
        }
    }

    /** The subscription is managed on Аккаунт; the card here acts on the local copy. */
    private fun openSubscription() {
        if (BackendConfig.isConfigured() && AccountSession.isLoggedIn()) {
            mainHost.selectTab(MainTab.ACCOUNT)
        } else {
            openLoginScreen()
        }
    }

    private fun openLoginScreen() {
        if (!BackendConfig.isConfigured()) return
        mainHost.launchAuthScreen(Intent(requireContext(), LoginActivity::class.java))
    }

    /**
     * Opens the Telegram screen in LINK mode: the already-signed-in account gets its Telegram
     * attached, so the bot tracks the подписка. The token request carries the current JWT, so the
     * backend links Telegram to this account instead of starting a separate login.
     */
    private fun openTelegramLink() {
        if (!BackendConfig.isConfigured()) return
        val intent = Intent(requireContext(), LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_MODE, LoginActivity.MODE_TELEGRAM)
            .putExtra(LoginActivity.EXTRA_LINK, true)
        mainHost.launchAuthScreen(intent)
    }

    // ==================== Formatting ====================

    // The speed formatter that used to live here — one decimal of Мбит/с, with the unit stranded in
    // a caption — went with the captions. `Long.toSpeedString` scales its own unit (КБ/с -> МБ/с)
    // and is what every other surface in the app already prints, so the same rate can no longer
    // read two ways in one product. It is also what the reference build showed, «1,0 KB/s», with
    // the units in Russian now.

    /**
     * «14 августа» inside the current year, «14 августа 2027» otherwise. Never a numeric date on
     * this screen, and never `SimpleDateFormat("d MMMM")`, which follows the DEVICE locale and
     * prints «до 14 August» on an English phone.
     */
    private fun formatDate(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val months = resources.getStringArray(R.array.home_months_genitive)
        val month = months.getOrElse(date.monthValue - 1) { "" }
        return if (date.year == LocalDate.now(ZoneId.systemDefault()).year) {
            getString(R.string.home_date_short, date.dayOfMonth, month)
        } else {
            getString(R.string.home_date_full, date.dayOfMonth, month, date.year)
        }
    }

    /** ISO-8601 with an offset, or a bare date; anything else is no date at all. */
    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        runCatching { return OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(iso).toEpochMilli() }
        val parts = iso.substringBefore('T').split('-')
        if (parts.size != 3) return null
        return runCatching {
            LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
}
