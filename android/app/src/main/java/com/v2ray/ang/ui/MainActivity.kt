package com.v2ray.ang.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.auth.AccountSession
import com.v2ray.ang.auth.BackendConfig
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.template.TemplateManager
import com.v2ray.ang.ui.component.SelectPopup
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.util.FlagUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.SubscriptionOrigin
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.animationsEnabled
import com.v2ray.ang.util.tickHaptic
import com.v2ray.ang.viewmodel.MainViewModel
import com.v2ray.ang.ui.component.Waiters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The three bottom-navigation destinations, in bar order: Главная · Аккаунт · Настройки.
 *
 * There is deliberately no Серверы destination. The owner decided on 2026-07-26 that both clients
 * carry these three and no more (`docs/design2026/11-app-structure.md` 2.0), which overrules the
 * four-destination set that document's 2.1 still describes, and the tab's own capabilities were NOT
 * relocated anywhere — «функции из вкладки сервера не надо никуда пихать».
 *
 * This is the shared vocabulary between the shell and its tab fragments. [navId] is the bar item's
 * view id — the same value [MainActivity] persists across a theme/language recreate — and [tag] is
 * the `FragmentManager` tag the tab's fragment is added under, which is how a restored instance is
 * found again on recreate instead of being rebuilt from scratch.
 *
 * Every tab is a fragment in the one `tab_host` container; `activity_main.xml` holds no tab content
 * of its own.
 */
enum class MainTab(@get:IdRes val navId: Int) {
    HOME(R.id.nav_home),
    ACCOUNT(R.id.nav_account),
    SETTINGS(R.id.nav_settings),
    ;

    /** FragmentManager tag for this tab's fragment. Stable across process death. */
    val tag: String get() = "tab:$name"

    companion object {
        /** The tab a bottom-nav item id belongs to, or null for an id that is not a tab. */
        fun fromNavId(@IdRes navId: Int): MainTab? =
            values().firstOrNull { it.navId == navId }
    }
}

/**
 * What a tab fragment is allowed to ask of the shell.
 *
 * [MainActivity] keeps the window, the insets, the bottom bar, the tab switch, the selected server
 * and the import actions; a tab owns its own content and reaches the shell only through
 * this interface — never by casting to `MainActivity` and calling into its internals. Shared *state*
 * does not come through here: every tab reads the one `MainViewModel` scoped to the activity
 * (`BaseFragment.mainViewModel`).
 *
 * The three connection calls below are the exception that proves the rule: the connect state
 * machine lives in [HomeFragment], and the shell forwards to it. That is why Главная is attached
 * from launch rather than on first visit (see [MainActivity.syncTabFragments]) — a forward that
 * could land on a fragment that does not exist yet would silently drop a tunnel restart.
 *
 * When a later stage needs something the shell owns and this interface does not expose, add it
 * here rather than widening the cast.
 */
interface MainHost {

    /** The tab currently on screen. */
    val selectedTab: MainTab

    /** Switches tabs, exactly as tapping the bar item does (repaint, haptic). */
    fun selectTab(tab: MainTab)

    /**
     * Connect or disconnect, whichever the current tunnel state calls for — the hero disc's own
     * action, including the VPN-permission prompt, the connect watchdog and the status pill.
     */
    fun toggleConnection()

    /**
     * Stops the running tunnel and starts it again on the currently selected server. Waits for a
     * real stopped state rather than a fixed delay; see `HomeFragment.restartV2Ray`.
     */
    fun restartConnection()

    /** The transient status pill («Подключение…», «Отключено», …). */
    fun showStatus(text: CharSequence)

    /**
     * Recomputes the bottom bar's own gates: whether the Аккаунт item exists (signed in, OR holding
     * a departament подписка — see `MainActivity.accountTabAvailable`) and whether the whole bar
     * exists (hidden in the pure onboarding state — signed out AND no servers). Both read shell
     * state only, so the bar stays the shell's alone; a tab calls this after doing something that
     * can change either input.
     */
    fun refreshNavGates()

    /**
     * Bottom padding a tab's scrolling list needs so its last row clears the overlaid bottom nav:
     * the system inset plus the bar itself plus breathing room. The shell computes it once, from
     * the window insets, and every list that needs it asks for it here rather than re-deriving the
     * same figure from its own inset listener.
     */
    val listBottomInset: Int

    /**
     * The per-server row actions — select, edit, share, remove. ONE instance for every server list
     * in the app, so a row behaves identically wherever it is drawn.
     *
     * Главная's inline list is that list today: `HomeFragment.setupServerList` builds its
     * `MainRecyclerAdapter` with exactly this listener. It stays an interface rather than a direct
     * call into the activity so a second list can be plugged in without either screen learning the
     * other's internals.
     */
    val serverActions: MainAdapterListener

    /**
     * Opens the server-actions sheet for one server — edit, share, QR, duplicate, make default,
     * delete.
     *
     * Reached by a long press on a row of Главная's inline list
     * (`HomeFragment.setupServerList` -> `onItemLongClick`). It is the ONLY route to edit, share,
     * QR and delete for a single server, so it is not something a later change may quietly drop:
     * removing the long press removes the whole per-server action set with it.
     */
    fun showServerActions(guid: String)

    /**
     * «Добавить подписку»: the add popup, anchored to [anchor]. **Two items — scan a QR code, or
     * take the link from the clipboard — and nothing else.**
     *
     * The owner cut it to those two on 2026-07-27. A departament customer adds a подписка the way
     * the bot hands it over, and the four other ways of getting a config into the app were burying
     * the two that matter. See `menu_main.xml` for where each of the four went.
     */
    fun showAddMenu(anchor: View)

    /**
     * «Добавить по QR-коду» itself — the scanner, opened directly, with no menu in front of it.
     *
     * [showAddMenu] is the entry point for a user who has NOT yet chosen how to add; this one is
     * for a user who has. The start screen's «Другие способы» list names the method in the row
     * label, so sending that row through the add popup asked the same question twice and put a
     * two-item menu between the tap and the camera (owner report, 2026-08-05).
     *
     * The scanner itself is unchanged: this forwards to the same private `importQRcode()` the add
     * popup's QR item fires, so both routes share one result handler and one importer.
     */
    fun importByQr()

    /**
     * The three add methods «Добавить подписку» no longer carries: a typed link, a hand-built
     * server, a config file. Unchanged in behaviour — only their entry point left the add menu.
     *
     * **Nothing calls this yet, and that is the one unfinished piece of the owner's cut**: he
     * removed the three from the add menu and said where they belong next is his to say. They are
     * one row away from a home — a «Другие способы добавления» row in the Настройки tab's ПОДПИСКА
     * section calling this — and until that row exists the capability is preserved rather than
     * deleted. Do not inline these back into the add menu, and do not delete them for being
     * unreachable: the entry point is what moved, not the feature.
     */
    fun showAdvancedAddMethods()

    /** «Обновить подписки»: re-fetches every subscription and reloads the list. */
    fun refreshSubscriptions()

    /**
     * Главная's own post-sign-in import — the errand that actually WRITES the account's подписка and
     * fetches its servers (`HomeFragment.onLoggedIn` -> `AccountRepository.autoImportSubscriptions`).
     *
     * It is reported to the shell because the shell is what holds Главная back for a full-screen
     * flow, and this is the one piece of work the flow is genuinely waiting on. It is deliberately
     * NOT routed through `showLoading` / `hideLoading`: those also raise the in-flight bar and spin
     * the connect arc, and this import runs at every cold start with a stored session, where
     * announcing it would report an errand nobody started. Here the shell learns the fact and
     * nothing else changes on screen.
     *
     * Called with `true` before the import begins and with `false` from a `finally`, so a failure, a
     * cancelled scope and an account with no подписки all lower it. @see MainActivity.revealHome
     */
    fun reportSubscriptionImport(running: Boolean)

    /**
     * Suspends until the подписка import reported through [reportSubscriptionImport] has finished,
     * so a full-screen flow can keep its own progress on screen for the whole of it instead of
     * handing over to a Главная that has nothing on it yet. Bounded; see the implementation.
     */
    suspend fun awaitSubscriptionImport()

    /**
     * Whether Главная's entrance (handoff §3, «Сборка главной») is being held for a full-screen
     * flow overlay, so the tab parks in its pre-entrance state at [HomeFragment.onViewCreated]
     * instead of assembling itself behind the overlay.
     *
     * Set by [MainActivity.holdHomeEntrance] and cleared by [MainActivity.revealHome]. It lives on
     * the shell rather than in Главная because it has to be answerable BEFORE Главная has a view.
     */
    val homeEntranceHeld: Boolean

    /**
     * Opens a settings sub-screen and applies whatever it changed on the way back: a theme or
     * language change recreates the activity, a core-config change restarts a running tunnel, a
     * group change reloads the server list.
     *
     * The tab could register a launcher of its own, but the three consume-flags
     * (`SettingsChangeManager`) are the shell's contract — recreating the activity is not a
     * fragment's to do, and two copies of that body would drift. So the one launcher stays here
     * and the tab hands it an intent.
     */
    fun launchSettingsScreen(intent: Intent)

    /**
     * Opens the sign-in / link-Telegram screen through that SAME launcher, so a login that changes
     * the theme, the core config or the server groups is applied on the way back exactly as a
     * settings sub-screen is.
     *
     * Named apart from [launchSettingsScreen] because it is not a settings sub-screen — the two
     * share one launcher deliberately, and a later stage that can touch both callers may collapse
     * them into one honestly-named call.
     */
    fun launchAuthScreen(intent: Intent)
}

class MainActivity : HelperBaseActivity(), MainHost {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()

    /**
     * The Главная tab's fragment. Attached from launch (see [syncTabFragments]), so this is null
     * only before `onCreate` has wired the bar and after the activity is gone. Looked up by tag on
     * every access rather than cached in a field, so the instance the FragmentManager restores
     * after a theme/language recreate is found too.
     */
    private val homeFragment: HomeFragment?
        get() = supportFragmentManager.findFragmentByTag(MainTab.HOME.tag) as? HomeFragment

    /**
     * The Настройки tab's fragment, or null until the tab has been opened once (it is attached
     * lazily — see [syncTabFragments]). Looked up by tag for the same reason [homeFragment] is.
     *
     * The shell needs it for ONE thing: pushing a changed bottom inset into the tab's scrolling
     * list, exactly as it pushes one into Главная's ([setupEdgeToEdge]). A tab attached after the
     * window's insets were dispatched reads the published figure itself.
     */
    private val settingsFragment: SettingsTabFragment?
        get() = supportFragmentManager.findFragmentByTag(MainTab.SETTINGS.tag) as? SettingsTabFragment

    /**
     * Same one job as [settingsFragment], for the same reason. The account tab's list ends in «Выйти
     * из аккаунта», and it used to reserve a hard-coded 96dp for the bar — 56 + 16 + a system inset
     * guessed at 24. That guess is right only on a phone with a gesture bar; with three-button
     * navigation the inset is nearer 48 and the last card sits under the bar, which is exactly how
     * «Схемы URL-адресов» disappeared on the settings tab.
     */
    private val accountFragment: AccountFragment?
        get() = supportFragmentManager.findFragmentByTag(MainTab.ACCOUNT.tag) as? AccountFragment

    /**
     * The one row-action listener shared by every server list (see [MainHost.serverActions]).
     */
    private val adapterListener: ActivityAdapterListener by lazy { ActivityAdapterListener() }

    /** Last computed bottom-nav padding for a tab's scrolling list; see [MainHost.listBottomInset]. */
    private var navListPadding = 0

    /**
     * The bottom-nav scrim's height BEFORE the window's bottom inset is added to it — the figure
     * `activity_main.xml` states, read once from the inflated view so the layout stays the single
     * place that number is written. [setupEdgeToEdge] adds the inset to it on every dispatch.
     */
    private var navScrimBaseHeight = 0


    // Cached easing curves (loaded once) so the imperative nav motion rides the same ease-out tempo
    // as the declarative res/interpolator + res/anim resources. No bounce.
    /**
     * §8 «Полоска навигации» and «Смена цвета» both ride ease-out-quart — the curve every 220–340ms
     * state change in the product uses. This replaced ease_standard here: the bar had a curve of
     * its own for no reason, and the shell has no other imperative animation to keep it alive.
     */
    private val easeOutQuart by lazy { AnimationUtils.loadInterpolator(this, R.interpolator.ease_out_quart) }

    /**
     * Where the travelling nav bar is headed, in px. Held because a tab switch lays the shell out
     * again mid-flight (the fragment container shows one child and hides another), and the layout
     * listener that keeps the bar glued would otherwise cancel the travel and snap it home on the
     * very frame it started. Same destination == nothing to do. NaN until the first placement.
     */
    private var navIndicatorTarget = Float.NaN

    private companion object {
        // Remembers which bottom-nav tab was selected so it survives an activity
        // recreate (theme/language change) instead of snapping back to Home.
        const val KEY_SELECTED_NAV = "selected_bottom_nav"

        // THE LONGEST Главная MAY BE HELD once the flow overlay has left the screen.
        //
        // Four seconds, and the figure is chosen against what the user is looking at rather than
        // against what the network might do: the overlay's own finale already covers 2.7s of the
        // import (900ms dwell + 1300ms hold + 520ms fade), so on any ordinary connection the wait
        // below is zero and this never fires. When it does fire the network is in trouble, and four
        // more seconds of a screen that is not painting itself is the boundary between «it is
        // thinking» and «it is broken». @see revealDeadline
        const val REVEAL_WAIT_MAX_MS = 4_000L

        /**
         * How long [MainActivity.awaitSubscriptionImport] gives the import to APPEAR before deciding
         * there isn't one. Short on purpose: it is only covering the gap between the sign-in landing
         * and `HomeFragment.onLoggedIn` raising the flag on the next main-thread message, and every
         * path with no import at all pays it in full before falling through.
         */
        const val IMPORT_START_WINDOW_MS = 700L
    }

    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // FIRST, AND BEFORE ANY EARLY RETURN. A sign-in through «Войти по почте» parks Главная on
        // the way out (see launchAuthScreen); this is the only place it can be let go, and the hold
        // also silences notices through NoticePolicy. Skipping it on one branch would leave the tab
        // parked and the app mute for the rest of the process — which is why it is not written after
        // the two consume* branches below, one of which returns.
        releaseAuthHandoff()
        // NO `consumeRecreateUi()` BRANCH: nothing has raised that flag in this fork, so the
        // `recreate()` under it never ran. The theme is applied where it is picked. @see
        // SettingsChangeManager
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartConnection()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            mainViewModel.reloadServerList()
        }
    }

    /**
     * Launcher for the manual server editors (ServerActivity / ServerGroupActivity /
     * ServerProxyChainActivity).
     *
     * Separate from [requestActivityLauncher] because saving a server sets no
     * [SettingsChangeManager] flag other than restart-service, so a shared launcher would return
     * without reloading and the just-created server would stay invisible until some other reload
     * happened to fire. Here the list is reloaded unconditionally.
     */
    private val createServerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartConnection()
        }
        mainViewModel.reloadServerList()
        homeFragment?.refreshSelectedServer()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        applyThemeDecorations()
        setupEdgeToEdge()

        // Главная always shows the inline up/down speed row, so the traffic-stats pipeline must be
        // on: without this the core config omits the stats outbound and the speed-notification loop
        // never runs, so the row is stuck on «0 KB/s». Enabled here, in the shell, because it has to
        // be true before ANY connect — including one started from the quick-settings tile, which
        // never opens a tab.
        //
        // Read before write: this is `onCreate`, so it also runs on every rotation and on every
        // theme or language recreate, and the flag has been `true` since the first launch. An
        // unconditional `encode` on a MULTI_PROCESS_MODE store is an mmap append plus the store's
        // cross-process lock, for a value that never changes again.
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED, false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, true)
        }

        // All servers are shown in one flat, provider-grouped list (no subscription tabs).
        mainViewModel.subscriptionId = ""

        // Keep the user on the tab they were on when the activity is recreated (e.g. after a theme
        // or language change) instead of jumping back to Home. Handed to setupBottomNav so the
        // restored tab is the FIRST one painted: selecting Home and then correcting it would run
        // two fragment transactions and a tab swap the user never asked for.
        setupBottomNav(restoreSelectedNav(savedInstanceState))
        // The one BACK handler in the shell: any other tab goes to Главная first, and Главная
        // minimises. See onKeyDown for why nothing else may handle the key.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectedNavId != R.id.nav_home ->
                        selectNav(R.id.nav_home)

                    // Keep the upstream semantic on Главная: minimise the task, leaving the
                    // tunnel running and the app in Recents, rather than finishing the activity.
                    else -> moveTaskToBack(false)
                }
            }
        })

        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    /** Persist the selected tab so a theme/language recreate does not reset it to Home. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_NAV, selectedNavId)
    }

    /**
     * The tab to paint first: the one that was on screen before the recreate, or Главная.
     *
     * The stored value is validated against the destination set rather than trusted, because a
     * saved selection can name a tab that no longer exists — the removed Серверы destination is
     * exactly that case, and so is any id an older build wrote. An id with no tab behind it would
     * otherwise reach [showTab], find no fragment to add and no group to show, and leave the shell
     * on an empty container with nothing selected in the bar. Anything unrecognised falls back to
     * Главная, which is also the only tab guaranteed to exist.
     */
    private fun restoreSelectedNav(savedInstanceState: Bundle?): Int {
        val stored = savedInstanceState?.getInt(KEY_SELECTED_NAV, R.id.nav_home) ?: R.id.nav_home
        return MainTab.fromNavId(stored)?.navId ?: R.id.nav_home
    }

    /**
     * Wires the bottom navigation and paints [initialNav] as the first tab on screen. Every tab is
     * a fragment in the shared container; Главная is attached here whichever tab is showing.
     */
    private fun setupBottomNav(initialNav: Int) {
        // The custom bar is a plain LinearLayout with no fitsSystemWindows behaviour, so it never
        // auto-pads itself; setupEdgeToEdge's parent listener is the single source of its bottom
        // inset padding. (A no-op listener returning the insets unchanged used to sit here.)

        // onSingleClick, not setOnClickListener: the 500ms guard is what stops a hammered bar
        // queueing three tab swaps in one frame (D03). Haptic.NONE here because showTab already
        // ticks on a real change — a tab switch buzzes once, whether the bar or a fragment asked
        // for it.
        binding.navHome.onSingleClick { selectNav(R.id.nav_home) }
        binding.navSettings.onSingleClick { selectNav(R.id.nav_settings) }
        // The Account item is a real in-place content tab (AccountFragment), selected like the
        // others — including while signed out, where the tab renders its own "not signed in" state
        // and is the place a clipboard-подписка user signs in from. Its fragment is attached lazily
        // the first time it is opened (see syncTabFragments).
        binding.navAccount.onSingleClick { selectNav(R.id.nav_account) }
        // Аккаунт can vanish with the подписка it came in on, so a restored selection of it is
        // honoured only if the destination still exists — otherwise the tab would be attached (and
        // would start loading) for a user refreshNavGates is about to move off it anyway.
        val start = if (initialNav == R.id.nav_account && !accountTabAvailable()) {
            R.id.nav_home
        } else {
            initialNav
        }
        // Not selectNav: the first paint is not a tab CHANGE, so it takes showTab's previous == tab
        // path (no haptic) and lands on the restored tab in one transaction.
        selectedNavId = start
        updateNavSelection(start)
        // The single travelling indicator has to be placed once the columns have been measured,
        // and re-placed whenever they are measured again — Аккаунт appearing halves them.
        trackNavIndicator()
        showTab(start, start)
    }

    /** Currently selected bottom-nav tab (replaces BottomNavigationView.selectedItemId). */
    private var selectedNavId = R.id.nav_home

    /**
     * Selects a bottom-nav tab: swaps the visible tab content, then repaints the custom bar.
     *
     * **That order is the fix for D03, and it is the whole rule of this method: the content moves
     * first and the highlight follows it, never the other way round.** The bar used to be
     * repainted up front and the transaction attempted afterwards — and [syncTabFragments]
     * legitimately refuses to commit once `onSaveInstanceState` has run, so a tap that arrived in
     * that window moved the blue pill under one tab and left another tab's content on screen, with
     * nothing to correct it. On Аккаунт that is terminal: the item can still leave the bar (the
     * подписка it came in on is removed), and the app would sit on a tab with no way back to it.
     *
     * A re-tap of the current tab is a no-op rather than a rebuild — there is nothing to swap, and
     * a haptic with no consequence teaches the user the bar is unreliable.
     */
    private fun selectNav(navId: Int) {
        val previous = selectedNavId
        if (navId == previous) return
        selectedNavId = navId
        if (!showTab(navId, previous)) {
            // The content could not move, so the bar must not either. The activity is on its way
            // to a recreate; it repaints the persisted tab from onCreate.
            selectedNavId = previous
            return
        }
        updateNavSelection(previous)
    }

    // ==================== MainHost ====================

    override val selectedTab: MainTab
        get() = MainTab.fromNavId(selectedNavId) ?: MainTab.HOME

    override fun selectTab(tab: MainTab) = selectNav(tab.navId)

    // The connect state machine lives in Главная (HomeFragment), which is attached from launch, so
    // these three forwards always land — see MainHost's own note.
    override fun toggleConnection() {
        homeFragment?.toggleConnection()
    }

    override fun restartConnection() {
        homeFragment?.restartConnection()
    }

    override fun showStatus(text: CharSequence) {
        homeFragment?.showStatus(text)
    }

    /**
     * The Аккаунт item exists only while signed in, and the whole bar only once there is something
     * to navigate to. Both inputs are shell state, so both are computed here; see [updateAccountNav]
     * and [updateBottomNavVisibility].
     */
    override fun refreshNavGates() {
        updateAccountNav()
        updateBottomNavVisibility()
    }

    override val listBottomInset: Int
        get() = navListPadding

    override val serverActions: MainAdapterListener
        get() = adapterListener

    override val homeEntranceHeld: Boolean
        get() = entranceHeld

    // ==================== The overlay hand-off (handoff §3, §11 grabl 6) ====================

    /**
     * Whether a full-screen flow overlay has asked Главная to wait before assembling itself.
     * @see holdHomeEntrance
     */
    private var entranceHeld = false

    /**
     * A release that is waiting for the server list, or null.
     *
     * @see revealHome
     */
    private var pendingReveal: (() -> Unit)? = null

    /**
     * How many loads the shell has open — subscription refreshes, imports, exports.
     *
     * It is the balance of [showLoading] / [hideLoading], which every one of those errands already
     * brackets, so it is the app's own answer to «данные ещё едут» rather than a second bookkeeping
     * of the same fact. [revealHome] waits on it; nothing else reads it.
     *
     * **It is not the whole answer, and believing it was is the defect [revealHome] documents.**
     * The account's own import does not come through here — see [subscriptionImportRunning].
     */
    private var loadsInFlight = 0

    /**
     * Whether Главная's post-sign-in подписка import is still running.
     *
     * SEPARATE FROM [loadsInFlight] BECAUSE IT IS A DIFFERENT ERRAND, and telling them apart is the
     * whole of the second defect. `loadsInFlight` counts refreshes of подписки that are already on
     * the device; after a Telegram sign-in there are none, so it settles in half a second while the
     * thing the user is waiting for — the account's подписка being written and its servers fetched —
     * has not started reporting anything. @see MainHost.reportSubscriptionImport
     */
    private var subscriptionImportRunning = false

    /**
     * THE BOUND ON THE WAIT, and the reason [revealHome] cannot hang.
     *
     * The import behind [subscriptionImportRunning] is two API calls at 15s connect / 20s read
     * apiece plus one fetch per подписка, so its OWN bound is most of a minute — far past the point
     * where a screen that is not painting itself reads as a broken app rather than as a pause. Past
     * this the entrance plays regardless and Главная comes up in the state it is honestly in: on
     * this path that is the gate block's «Подписка активна, серверы ещё не загружены», a screen with
     * a sentence and a button on it, which is a great deal better than nothing at all.
     *
     * A `postDelayed` and not an animator, because it is not a movement: it is a deadline, and
     * `AnimatorSet` cannot express one. It is a single message and it is cancelled the moment the
     * wait ends normally.
     */
    private val revealDeadline = Runnable { releaseHomeNow() }

    /**
     * ARM the hand-off: call this BEFORE raising a full-screen loading overlay over the shell.
     *
     * Главная is stamped into its pre-entrance state — the account row, the «+», the connect
     * object, the speeds, the server line, the подписка card and the visible server rows all at
     * alpha 0 — and stays there. Without this the tab assembles itself the moment it is created,
     * finishes in about 1.3 seconds and the overlay comes down over a screen that is already whole,
     * which is README §11 grabl 6.
     *
     * Safe before Главная exists: the flag is the shell's, and the tab reads it in onViewCreated.
     */
    fun holdHomeEntrance() {
        entranceHeld = true
        // Rule 5: nothing interrupts the user while a full-screen flow owns the window. The same
        // bracket the tab's hold uses, because it is the same fact. @see NoticePolicy
        NoticePolicy.enterFlow()
        homeFragment?.holdEntrance()
    }

    /**
     * RELEASE the hand-off — the single call the overlay's owner makes, and the whole reason it
     * takes the teardown as a lambda.
     *
     * [dismissOverlay] runs and Главная starts assembling INSIDE THE SAME synchronous block, so
     * both land in one traversal and the compositor never gets a frame with the overlay gone and
     * the screen already built. Anything that puts a post, a delay or a second animation callback
     * between the two re-opens grabl 6.
     *
     *     mainActivity.revealHome { (overlay.parent as? ViewGroup)?.removeView(overlay) }
     *
     * §3 puts this at 6450ms — the overlay fades over its last 520ms and is REMOVED here, not
     * hidden. Calling it without a prior [holdHomeEntrance] is harmless: Главная will already have
     * assembled, and an entrance plays once per view.
     *
     * ## What it waits for, and why it did not used to wait for anything
     *
     *     «главная должна появляться когда сервера уже подгрузились, а не потом»
     *
     * The overlay's schedule is an ANIMATION's schedule: the flow reports its last step, holds the
     * finale 1300ms and fades over 520. Nothing in that adds up to «the list is ready» — the sign-in
     * fires `refreshSubscriptions()` and moves on, so the screen was released when the overlay had
     * finished counting, not when there was something to release it onto. What the user got was
     * Главная assembling around an empty list, the rows arriving after it, and — since the fetch
     * that had not finished was also the one that reports «нет подписок для обновления» — a bar
     * about the fetch on top of the assemble.
     *
     * So the release waits for [loadsInFlight] to come back to zero, and the assemble then plays
     * over a list that is already bound. Waiting costs nothing to look at: the screen is parked in
     * §3's start values (every element at alpha 0, the gate block with them), so «показывать нечего,
     * пока показывать нечего» is literally what is on the glass.
     *
     * ## …AND THAT WAS A WAIT ON THE WRONG ERRAND
     *
     *     «почему он меня кидает на главную когда подписка ещё полностью не добавилась?»
     *
     * The same report came back, because [loadsInFlight] answers for the wrong half of a sign-in.
     * `TelegramFlow` used to answer `LoginState.Success` with `refreshSubscriptions()`, i.e.
     * `importConfigViaSub` — a walk over the подписки ALREADY on the device, re-fetching each. A
     * brand-new account has none. That call therefore did no work at all, its `showLoading` was
     * balanced within the half second its own `delay` costs, and the counter was back to zero while
     * the errand the user is actually waiting on was only just starting: `HomeFragment.onLoggedIn`
     * -> `AccountRepository.autoImportSubscriptions`, which is what WRITES the account's подписка
     * and fetches its servers, and which reported to nobody. The gate opened on a proxy that had
     * finished measuring nothing. **That walk is gone entirely now** — see `TelegramFlow.Host` —
     * and the wait below is what was always doing the work.
     *
     * So the wait is on BOTH facts now — [loadsInFlight] and [subscriptionImportRunning] — and the
     * second one is bracketed at the import itself, in a `finally`, so the failed, the empty and the
     * cancelled runs all lower it. On the clipboard path the flow awaits its own import before it
     * ever hands off, so neither fact is ever raised there and the release is immediate, which is
     * correct: that list is bound a full second before the overlay starts leaving.
     *
     * **It is bounded twice.** Once by the work — every load balances its [showLoading] on every
     * exit and every import lowers its flag in a `finally` — and once by [revealDeadline], which
     * releases the screen regardless after [REVEAL_WAIT_MAX_MS]. A gate that can hang is worse than
     * the bug it fixes.
     *
     * [dismissOverlay] still runs IMMEDIATELY and is never deferred: the overlay's own teardown is
     * its own business, and holding a removed overlay's frame back would be the flash from the
     * other side. What waits is the assemble.
     */
    fun revealHome(dismissOverlay: () -> Unit) {
        dismissOverlay()
        val release: () -> Unit = {
            entranceHeld = false
            NoticePolicy.leaveFlow()
            homeFragment?.playEntrance()
        }
        if (!homeDataPending()) {
            release()
            return
        }
        pendingReveal = release
        binding.root.removeCallbacks(revealDeadline)
        binding.root.postDelayed(revealDeadline, REVEAL_WAIT_MAX_MS)
    }

    /**
     * Whether anything Главная would assemble AROUND is still on its way: a shell load, or the
     * account's own подписка import. The two are separate counters because they are separate
     * errands; see each of them.
     */
    private fun homeDataPending(): Boolean = loadsInFlight > 0 || subscriptionImportRunning

    /** Runs a release that was waiting for the list, if the list has just become ready. */
    private fun releaseHomeIfReady() {
        homeDataWaiters.notifyChanged()
        if (homeDataPending()) return
        releaseHomeNow()
    }

    /**
     * Everyone suspended inside [awaitSubscriptionImport]. Woken on every change to either counter,
     * because what each caller is waiting FOR differs — see the two phases there.
     */
    private val homeDataWaiters = Waiters()

    /**
     * KEEPS THE FLOW OVERLAY UP UNTIL THE ПОДПИСКА IS ACTUALLY IN.
     *
     * The owner asked for this twice, the second time after a partial fix: «почему он меня кидает на
     * главную когда подписка ещё полностью не добавилась? должно продолжаться начальное окно, где
     * добавление подписки вот это идёт с полосочкой и только потом как добавилось перекидывать на
     * главную». [revealHome] holds the ENTRANCE on the same two facts, but by the time it runs the
     * overlay has already been taken down by `FlowOverlay.finish`, so its wait shows a parked, dark
     * Главная rather than the progress the user was watching. This is the wait one step earlier, on
     * the flow's own side, where the overlay is still on screen.
     *
     * **IT IS TWO PHASES, AND THE FIRST ONE IS THE WHOLE POINT.** The naive version — "suspend while
     * [homeDataPending]" — returns instantly and fixes nothing, and it does so for exactly the reason
     * the previous attempt failed: the errand has not started yet. `LoginState.Success` is delivered
     * to the flow's own collector, while the import is started by `HomeFragment.onLoggedIn` off the
     * account-session flow, which is a later main-thread message. Ask at Success and the flag is
     * still false. So phase 1 waits for the import to APPEAR, phase 2 waits for it to finish.
     *
     * **Neither phase can hang, and neither can stall a path that has no import at all.** Phase 1
     * gives up after [IMPORT_START_WINDOW_MS] — the clipboard flow does its own import inline and
     * never raises the flag, and a sign-in on an account with no подписка raises and lowers it in one
     * pass, so both simply fall through. Phase 2 is bounded by [REVEAL_WAIT_MAX_MS], the same
     * deadline [revealHome] uses, after which the overlay leaves and Главная comes up in whatever
     * state it is honestly in.
     */
    override suspend fun awaitSubscriptionImport() {
        // Phase 1 — let the import declare itself. Falling through here is a normal outcome.
        withTimeoutOrNull(IMPORT_START_WINDOW_MS) { homeDataWaiters.await { homeDataPending() } }
        // Phase 2 — and now let it finish.
        withTimeoutOrNull(REVEAL_WAIT_MAX_MS) { homeDataWaiters.await { !homeDataPending() } }
    }

    /**
     * Lets Главная go, whether or not what it was waiting for has arrived. Called by
     * [releaseHomeIfReady] when it has, and by [revealDeadline] when it has taken too long.
     */
    private fun releaseHomeNow() {
        binding.root.removeCallbacks(revealDeadline)
        val release = pendingReveal ?: return
        pendingReveal = null
        release()
    }

    override fun reportSubscriptionImport(running: Boolean) {
        runOnUiThread {
            subscriptionImportRunning = running
            // The import is the LAST of the two facts to settle on the sign-in path, so this is
            // usually the call that opens the gate.
            if (!running) releaseHomeIfReady()
        }
    }

    override fun showAddMenu(anchor: View) = showImportMenu(anchor)

    /** The add popup's QR item without the popup; see [MainHost.importByQr]. */
    override fun importByQr() {
        importQRcode()
    }

    override fun showAdvancedAddMethods() {
        val labels = arrayOf(
            getString(R.string.menu_actions_add_link),
            getString(R.string.menu_actions_add_create),
            getString(R.string.menu_actions_add_file),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_other_methods_title)
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showManualEntryDialog()
                    1 -> pickManualServerType()
                    else -> importConfigLocal()
                }
            }
            .setNegativeButton(R.string.menu_actions_cancel, null)
            .show()
    }

    override fun refreshSubscriptions() {
        importConfigViaSub()
    }

    override fun launchSettingsScreen(intent: Intent) {
        requestActivityLauncher.launch(intent)
    }

    /**
     * THE SIGN-IN THAT IS NOT A FLOW STILL GETS THE FLOW'S HAND-OFF.
     *
     * `GateView`'s Telegram and clipboard paths park Главная and let it go once its данные are in.
     * `LoginActivity` — «Войти по почте», and now the account tab's own two buttons — had neither
     * half: it launched, came back, and Главная was simply there, so its rows arrived after the
     * screen exactly as they used to on the other two paths. Same shape, same defect, one path late.
     *
     * The hold is taken here and released in [requestActivityLauncher] on EVERY outcome, including a
     * cancelled sign-in — where [revealHome] finds nothing pending and lets go in the same frame.
     */
    override fun launchAuthScreen(intent: Intent) {
        authHandoffPending = true
        holdHomeEntrance()
        requestActivityLauncher.launch(intent)
    }

    /** Whether [launchAuthScreen] is holding Главная for a sign-in that has not come back yet. */
    private var authHandoffPending = false

    /** Lets Главная go after a sign-in, if one was holding it. Idempotent. */
    private fun releaseAuthHandoff() {
        if (!authHandoffPending) return
        authHandoffPending = false
        revealHome { }
    }

    /**
     * Routes subscription add/refresh progress onto Главная's connect circle (the shared rotating
     * arc) instead of a top progress bar — the shell's own imports and bulk actions report through
     * the same indicator the tab uses. Overrides the BaseActivity top-bar spinner.
     */
    override fun showLoading() {
        runOnUiThread {
            loadsInFlight++
            homeFragment?.showConnectArc()
        }
    }

    override fun hideLoading() {
        runOnUiThread {
            if (loadsInFlight > 0) loadsInFlight--
            homeFragment?.hideConnectArc()
            // The list is ready; a hand-off that was waiting for it can let go now.
            releaseHomeIfReady()
        }
    }

    /**
     * Tints the custom bar items: blue (colorPrimary) for the selected one, grey otherwise. On a
     * real tab change (animations enabled) the two involved items TWEEN their icon+label colour
     * grey↔blue over motion_state; everything else (initial paint, reduced motion, unchanged items)
     * sets the colour instantly.
     *
     * The third axis — the 28x3 accent bar — is no longer per item. It is ONE view that slides to
     * the selected column; see [positionNavIndicator].
     */
    private fun updateNavSelection(previousNavId: Int = selectedNavId) {
        val active = themeColor(androidx.appcompat.R.attr.colorPrimary)
        val inactive = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val items = listOf(
            Triple(R.id.nav_home, binding.navHomeIcon, binding.navHomeLabel),
            // The Account tab tints blue when selected, exactly like the other tabs.
            Triple(R.id.nav_account, binding.navAccountIcon, binding.navAccountLabel),
            Triple(R.id.nav_settings, binding.navSettingsIcon, binding.navSettingsLabel),
        )
        val animate = animationsEnabled() && previousNavId != selectedNavId
        items.forEach { (id, icon, label) ->
            val selected = id == selectedNavId
            val target = if (selected) active else inactive
            val involved = selected || id == previousNavId
            if (animate && involved) {
                val from = if (selected) inactive else active
                tweenNavItemColor(icon, label, from, target)
            } else {
                icon.setColorFilter(target)
                label.setTextColor(target)
            }
            // Second active-state axis (beyond the colour tween): a heavier label, so the
            // active tab reads on weight + accent, not tint alone.
            applyNavLabelWeight(label, selected)
        }
        positionNavIndicator(animate)
    }

    /** The nav column a tab id belongs to (null for an unknown id). */
    private fun navItem(navId: Int): View? = when (navId) {
        R.id.nav_home -> binding.navHome
        R.id.nav_account -> binding.navAccount
        R.id.nav_settings -> binding.navSettings
        else -> null
    }

    /**
     * Slides the ONE active-tab bar to the selected column (handoff README §4).
     *
     * The prototype states the three resting positions as 46 / 166 / 286dp, which are that
     * mock-up's own 360dp-wide, always-three-tabs arithmetic. This bar is neither: Аккаунт is a
     * weighted item that collapses to nothing while signed out, so the columns are 1/2 the bar
     * wide as often as they are 1/3. The centre is therefore READ off the column that is actually
     * on screen — the same number the prototype's constants encode, computed instead of copied,
     * so a two-tab bar and a 412dp phone are both right.
     *
     * Travel is @integer/motion_nav_indicator (280ms) on ease_out_quart, and only on a real tab
     * change: the first paint, a recreate and reduced motion all place it without animating,
     * because a bar that flies in from x=0 on launch is a transition to nothing.
     */
    private fun positionNavIndicator(animate: Boolean) {
        val indicator = binding.navIndicator
        val target = navItem(selectedNavId)
        if (target == null || !target.isVisible) {
            indicator.visibility = View.INVISIBLE
            return
        }
        // Pre-layout (first paint, or the frame in which Аккаунт appears): nothing has a width
        // yet, so there is no centre to compute. Come back once the row has been measured.
        if (target.width == 0 || indicator.width == 0) {
            binding.navItems.post { positionNavIndicator(false) }
            return
        }
        val x = target.left + (target.width - indicator.width) / 2f
        indicator.visibility = View.VISIBLE
        // Already going there. See [navIndicatorTarget]: this is the guard that lets the layout
        // listener run on every pass without ever interrupting a travel in progress.
        if (x == navIndicatorTarget) return
        navIndicatorTarget = x
        indicator.animate().cancel()
        if (!animate || !animationsEnabled()) {
            indicator.translationX = x
            return
        }
        indicator.animate()
            .translationX(x)
            .setDuration(resources.getInteger(R.integer.motion_nav_indicator).toLong())
            .setInterpolator(easeOutQuart)
            .start()
    }

    /**
     * Keeps the bar glued to its column across every layout the bar can go through without a tab
     * change: the Аккаунт item arriving or leaving (two columns become three and every centre
     * moves), a rotation, a font-scale change, the window being resized. None of those is a
     * transition, so none of them animates — and a pass that changes nothing is a no-op, because
     * [positionNavIndicator] compares against [navIndicatorTarget] before it touches the view.
     */
    private fun trackNavIndicator() {
        binding.navItems.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionNavIndicator(animate = false)
        }
        binding.navItems.post { positionNavIndicator(animate = false) }
    }

    /** Steps a nav label to 700 when selected and 500 otherwise (real numeric weight on
     *  API 28+, bold/normal fallback below that). */
    private fun applyNavLabelWeight(label: android.widget.TextView, selected: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val family = android.graphics.Typeface.create(label.typeface, android.graphics.Typeface.NORMAL)
            label.typeface = android.graphics.Typeface.create(family, if (selected) 700 else 500, false)
        } else {
            label.setTypeface(
                label.typeface,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }
    }

    /**
     * Tweens one nav item's icon tint + label colour from -> to.
     *
     * @integer/motion_state and ease_out_quart, which is §8's «Смена цвета 220 мс» — the same
     * clock and curve the indicator's travel and every switch in the product run on. It was a
     * hard-coded 200 on ease_standard, i.e. the one place in the bar that had its own tempo.
     */
    private fun tweenNavItemColor(
        icon: android.widget.ImageView,
        label: android.widget.TextView,
        from: Int,
        to: Int,
    ) {
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = resources.getInteger(R.integer.motion_state).toLong()
            interpolator = easeOutQuart
            addUpdateListener {
                val c = it.animatedValue as Int
                icon.setColorFilter(c)
                label.setTextColor(c)
            }
            start()
        }
    }

    /**
     * The fragment that owns [tab].
     *
     * Called ONLY when the FragmentManager has no instance under [MainTab.tag] — a tab is built
     * once per process and then kept, so this is not a place to pass per-open arguments.
     *
     * Every tab is a fragment now. Anything a tab needs from the shell — the connection actions,
     * the status pill, the nav gates, the list inset, the row actions, the result launchers — is on
     * [MainHost], never reached for by casting.
     */
    private fun createTabFragment(tab: MainTab): Fragment = when (tab) {
        MainTab.HOME -> HomeFragment()
        MainTab.ACCOUNT -> AccountFragment()
        MainTab.SETTINGS -> SettingsTabFragment()
    }

    /**
     * Brings [navId]'s fragment on screen and takes the others off it, **keeping every instance
     * added**: a tab is `add`ed once, then only ever hidden and shown again.
     *
     * That is the whole reason this is not `replace()`. A replaced fragment is destroyed and
     * rebuilt on the way back, which throws away scroll position, a half-typed field and any
     * request still in flight; hide/show leaves the view hierarchy and the fragment's lifecycle
     * state untouched, so returning to a tab returns to it exactly as it was left. Hidden
     * fragments stay RESUMED, so nothing that was running keeps running any differently.
     *
     * **Главная is the exception to lazy attachment**: it is added on the very first call whatever
     * tab is selected, hidden if that tab is not it. It carries the connect state machine — the
     * tunnel observer, the status pill, the health check and the restart the Настройки tab asks for
     * after a core-config change — and none of that may wait for the user to visit the tab. A
     * theme/language recreate that restores a non-Главная tab would otherwise leave the app with no
     * state machine at all.
     *
     * On a theme/language recreate the FragmentManager restores each tab's fragment (and its
     * hidden flag) under the same tag before this runs, so the lookup finds the restored instance
     * and [createTabFragment] is never called for it.
     *
     * @return whether the container now shows [navId]. False means the transaction was refused and
     *   the caller must not paint the bar as if it had happened — see [selectNav].
     */
    private fun syncTabFragments(navId: Int): Boolean {
        val fm = supportFragmentManager
        // After onSaveInstanceState a commit is illegal; the restored activity will re-run this
        // from its own onCreate, so there is nothing to lose by skipping it.
        if (fm.isStateSaved) return false
        val tx = fm.beginTransaction()
        var changed = false
        for (candidate in MainTab.values()) {
            val existing = fm.findFragmentByTag(candidate.tag)
            if (candidate.navId == navId) {
                if (existing == null) {
                    tx.add(R.id.tab_host, createTabFragment(candidate), candidate.tag)
                    changed = true
                } else if (existing.isHidden) {
                    tx.show(existing)
                    changed = true
                }
            } else if (existing == null) {
                // Only Главная is attached without being selected; every other tab waits for its
                // first visit. Added and hidden in this same transaction, so it is never on screen.
                if (candidate != MainTab.HOME) continue
                val fragment = createTabFragment(candidate)
                tx.add(R.id.tab_host, fragment, candidate.tag)
                tx.hide(fragment)
                changed = true
            } else if (!existing.isHidden) {
                tx.hide(existing)
                changed = true
            }
        }
        // commitNow, not commit: the tab swap below reads the container in this same frame, and a
        // posted transaction would show it empty for one frame first.
        if (changed) tx.commitNow()
        return true
    }

    /**
     * The tab-content view for a nav id (null for an unknown id). All three tabs are fragments in
     * the one container, so this answers the same view for each — it stays a lookup because
     * [showTab] must still tell a real tab id from an unknown one.
     */
    private fun tabGroup(navId: Int): View? =
        if (MainTab.fromNavId(navId) != null) binding.tabHost else null

    /** Every tab-content view the shell can show, so exactly one is left visible. */
    private fun tabGroups(): List<View> = listOf(binding.tabHost)

    /**
     * The single authority on which tab group is on screen: [visible] is shown and every other
     * group is hidden.
     */
    private fun settleTabs(visible: View?) {
        tabGroups().forEach { it.isVisible = it === visible }
    }

    /**
     * Swaps the tab content: the incoming tab's fragment is added or shown and every other one is
     * hidden, in a single transaction.
     *
     * There is no crossfade left to run. With all three tabs inside `tab_host` the outgoing and
     * incoming views are the same container, so the old group-level fade had nothing to fade
     * between; the pair of fragments inside it can be crossfaded instead, but that is a motion
     * change and belongs to the stage that owns motion (32-master-plan-android.md 9.3 asks for a
     * simultaneous 220 ms crossfade for every tab switch). The tick haptic that marked a switch is
     * kept, so the change is still felt.
     *
     * @return false when the transaction could not be committed, in which case nothing on screen
     *   moved and neither did the haptic — [selectNav] rolls the selection back.
     */
    private fun showTab(tab: Int, previous: Int = tab): Boolean {
        if (!syncTabFragments(tab)) return false
        settleTabs(tabGroup(tab))
        if (previous != tab) binding.bottomNav.tickHaptic()
        return true
    }

    /**
     * True edge-to-edge: the home gradient (home_root) draws behind the status and nav
     * bars; each tab's content receives the top inset (so it clears the clock) and the
     * bottom nav a bottom inset pad (so items clear the gesture bar). The bars themselves
     * stay transparent (handled by the theme, not touched here).
     *
     * **EVERY FIGURE BELOW THAT MEETS THE BOTTOM OF THE WINDOW IS THE INSET PLUS SOMETHING, NEVER A
     * CONSTANT.** The bar, its scrim and the tabs' scrolling lists all have to clear the same
     * hardware, and the phone that has a gesture bar and the phone that has none are the same build
     * — the owner ran both, and every symptom he reported («значки выпали выше из-за слайдера»,
     * «кнопка настройки залазит за навигацию») was a number here that had one of the two baked in.
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The scrim's XML height is its ZERO-INSET baseline (bar + headroom); read before the first
        // dispatch can grow it, so a second dispatch adds the inset to the baseline and not to
        // itself.
        navScrimBaseHeight = binding.bottomNavScrim.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // There is no app bar: every tab draws its own header, so each tab's content clears the
            // status bar itself — the Home scroll header, and the Settings first section, both start
            // just below the clock with no empty band above them. The fragment container is padded
            // once, here, on behalf of every tab it hosts — a tab fragment does not repeat the top
            // inset for itself.
            binding.tabHost.updatePadding(top = bars.top)
            // Pad the custom bar by the FULL bottom inset so its icons/labels sit ABOVE whatever the
            // system draws: the ~48dp of Android 3-button navigation, or the ~24dp gesture pill. The
            // bar has wrap_content height (min 56dp) and hugs the bottom, so this padding grows it
            // UPWARD, lifting the content clear of the buttons. The bottom_nav_scrim and home gradient
            // keep flowing behind it, so there is no black bar in either nav mode.
            val density = resources.displayMetrics.density
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            // AND THE SCRIM GROWS WITH IT. The bar's padding lifts the icons by the whole inset, so
            // a scrim of fixed height stays behind while they climb: at 24dp of gesture bar its ramp
            // reached the icons already a fifth opaque, and at 48dp of 3-button navigation the icons
            // stood above it entirely. Height = baseline + inset keeps the backdrop in exactly the
            // relationship to the buttons it has on a phone with no gesture bar — the one the owner
            // reports as correct — whatever the phone puts under them. Written only when it actually
            // changes: this listener runs on every dispatch and a layout request per dispatch would
            // be a needless pass.
            // (Guarded on a real baseline: the arithmetic is only meaningful while the scrim is
            // declared with an exact height. A layout that ever gave it wrap/match keeps whatever
            // that means instead of being handed a nonsense number.)
            if (navScrimBaseHeight > 0) {
                val scrimHeight = navScrimBaseHeight + bars.bottom
                val scrimLp = binding.bottomNavScrim.layoutParams
                if (scrimLp.height != scrimHeight) {
                    scrimLp.height = scrimHeight
                    binding.bottomNavScrim.layoutParams = scrimLp
                }
            }
            // The nav overlays the content, so pad the scrollable lists so the last row clears the
            // full nav footprint: the system inset + the 56dp bar content + 16dp breathing room.
            val navHeightPx = (56 * density).toInt()
            val breathingPx = (16 * density).toInt()
            val navPad = bars.bottom + navHeightPx + breathingPx
            // Published for the tabs that own their own lists (see MainHost.listBottomInset) and
            // pushed straight into the ones already attached — insets are not re-dispatched just
            // because a fragment was added, and a fragment added later reads the field itself.
            navListPadding = navPad
            homeFragment?.applyListInsets()
            settingsFragment?.applyListInsets()
            accountFragment?.applyListInsets()
            insets
        }
    }

    /**
     * The shell's own ViewModel wiring: the service broadcast listener, the bundled assets, and the
     * list signal that reaches Главная. Everything a single tab cares about — the speed feed, the
     * tunnel state, the latency results — is observed by that tab.
     */
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            val position = index ?: -1
            // Главная reads the one cache; a tab that has no view yet paints itself from the same
            // cache when it gets one.
            homeFragment?.bindList(position)
            // Adding or removing a subscription can change whether there is anything to navigate
            // to, and (with a departament subscription) whether the Аккаунт item belongs there.
            refreshNavGates()
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    /**
     * Opens the Incy server-actions bottom sheet for [guid].
     * Each action delegates to an existing per-server flow; duplicate reuses
     * [MmkvManager.encodeServerConfig] with a blank guid to mint a fresh copy.
     *
     * Opened by a long press on a row of Главная's list — see [MainHost.showServerActions].
     */
    override fun showServerActions(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        ServerActionsSheet(
            context = this,
            profile = profile,
            onShareQr = { showQRCode(guid) },
            onShareClipboard = { share2Clipboard(guid) },
            onEdit = { editServer(guid, profile) },
            onDuplicate = {
                val copy = profile.copy(
                    remarks = profile.remarks + getString(R.string.server_action_duplicate_suffix),
                    addedTime = System.currentTimeMillis()
                )
                MmkvManager.encodeServerConfig("", copy)
                mainViewModel.reloadServerList()
            },
            onSetDefault = { setSelectServer(guid) },
            // -1 = "no row index": the sheet knows the guid, not the adapter position. Главная
            // repaints its whole list from the cache on a removal (HomeFragment.removeServerRow),
            // so an index would buy nothing here.
            onDelete = { removeServer(guid, -1) },
        ).show()
    }

    /**
     * The «Добавить подписку» popup, anchored to the tapped control: scan a QR code, or take the
     * link from the clipboard. Two items — see `menu_main.xml` for the owner's cut and for where
     * the four that used to sit under them went.
     *
     * IT IS THE PRODUCT'S OWN FLYOUT NOW, not the platform's menu.
     *
     *     «где + на главной странице, там менюшка всплывающая не в таком же стиле, как условно в
     *      настройках где нажимаешь на тюн или пинг или днс, надо там +- в таком же стиле сделать
     *      менюшку всплывающую, чтобы сочеталось»
     *
     * `PopupMenu` is a platform surface and dresses itself: Material's own fill, its own corner,
     * its own 8dp vertical padding, its own elevation and its own ripple — none of which any theme
     * attribute in this app reaches, because a PopupMenu builds its window from
     * `?attr/popupMenuStyle` and its rows from `?attr/textAppearanceLargePopupMenu`. Six settings
     * rows away, the same gesture opens [SelectPopup]: a solid fill one step above the card,
     * radius 16, a 1dp outline, and a reveal that is a clip from the top over 260ms with the
     * opacity over 180. Two flyouts, one product.
     *
     * THE MENU RESOURCE IS STILL THE SOURCE, and that is deliberate. The two items, their strings,
     * their glyphs and their ids stay in `menu_main.xml` with the owner's note about the four that
     * were cut, and the dispatch stays in [onOptionsItemSelected] — this reads the resource and
     * hands the chosen item straight back to it. Nothing about the add menu is now stated twice,
     * and a `PopupMenu` is built only to borrow its [Menu] as a parser: it is never shown, never
     * attached to a window and never given the anchor to position itself against.
     */
    private fun showImportMenu(anchor: android.view.View) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor).menu
        menuInflater.inflate(R.menu.menu_main, menu)
        val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
        if (items.isEmpty()) return
        SelectPopup.show(
            anchor = anchor,
            options = items.map { it.title ?: "" },
            // NOT A VALUE PICKER. There is no "current" way of adding a подписка, so there is no
            // check column: -1 takes the mark's 22dp back and gives it to the labels.
            selectedIndex = -1,
            widthRes = R.dimen.select_popup_w_add,
            // …and the glyphs the platform menu only showed because setForceShowIcon asked it to.
            // Two verbs, and the glyph is what tells them apart before the label is read.
            icons = items.map { itemGlyph(it) },
            offsetTopRes = R.dimen.select_popup_offset_top_add,
            offsetRightRes = R.dimen.select_popup_offset_right_add,
        ) { picked ->
            onOptionsItemSelected(items[picked])
        }
    }

    /**
     * The drawable id behind a menu item's glyph.
     *
     * `MenuItem` hands out a resolved [android.graphics.drawable.Drawable] and not the id it was
     * inflated from, so the two are mapped here rather than tinted in place as the PopupMenu
     * needed. That is the better trade: SelectPopup tints its glyph from the layout
     * (`?attr/colorOnSurfaceVariant`), so the tint follows the theme and the mono overlay by
     * itself, and no drawable has to be mutated per open.
     */
    @DrawableRes
    private fun itemGlyph(item: MenuItem): Int = when (item.itemId) {
        R.id.import_qrcode -> R.drawable.ic_scan_24dp
        R.id.import_clipboard -> R.drawable.ic_dl_copy
        else -> 0
    }

    /**
     * Hides the whole bottom nav (bar + scrim) in the pure onboarding state — signed out AND no
     * servers — so the sign-in screen reads as a clean solid background with no tab buttons. It
     * returns as soon as EITHER is true (logged in OR at least one server). The nav is an overlay
     * in a FrameLayout (it never reserved layout space), so hiding it leaves no phantom bottom gap.
     * The selected tab is corrected in BOTH directions: when the bar reappears the selection has to
     * be one that still exists (Аккаунт only while signed in), and when it disappears the tab on
     * screen has to be Главная, since a hidden bar leaves no way back to it.
     *
     * **The count is the STORED one, never `serversCache`** (D15). `serversCache` is a *view*: it
     * is rebuilt by `MainViewModel.updateCache()` through `subscriptionId` and `keywordFilter`, so
     * a filter that matches nothing empties it while the user's servers are all still there. Gated
     * on that, typing in a server search took the whole bottom navigation off screen and threw the
     * app back to onboarding — a filtered-to-zero list and an empty store are not the same fact,
     * and only one of them is a reason to hide the app's navigation.
     * [MmkvManager.decodeAllServerList] is the unfiltered truth; the cache stays a list's own
     * business. There is no search on screen today, which is precisely why this must be written
     * down now: the next one to add a filter must not be able to reintroduce the defect.
     */
    private fun updateBottomNavVisibility() {
        val show = AccountSession.isLoggedIn() || MmkvManager.decodeAllServerList().isNotEmpty()
        val becomingVisible = show && !binding.bottomNav.isVisible
        binding.bottomNav.isVisible = show
        binding.bottomNavScrim.isVisible = show
        if (becomingVisible) {
            val valid = when (MainTab.fromNavId(selectedNavId)) {
                MainTab.HOME, MainTab.SETTINGS -> true
                MainTab.ACCOUNT -> accountTabAvailable()
                null -> false
            }
            if (!valid) selectTabWhenIdle(R.id.nav_home)
        } else if (!show && selectedNavId != R.id.nav_home) {
            // The other half of the same invariant: with no bar there is no way back to Главная
            // except the BACK key, so the onboarding state must never be entered while another
            // tab's content is on screen. (Reaching it needs the last server to go while Настройки
            // is open; the correction is here rather than at each such site so the invariant is
            // total — bar hidden implies Главная — instead of true only on the way back.)
            selectTabWhenIdle(R.id.nav_home)
        }
    }

    /**
     * The account gate: the account tab and every account-only entry point exist ONLY after the user
     * has authorized (logged in). A pasted/imported subscription — even a genuine "departament" one —
     * must NOT unlock the account, since there is no account to load without a login.
     */
    private fun accountAccessAllowed(): Boolean =
        AccountSession.isLoggedIn()

    /**
     * **The Аккаунт destination exists.** One gate, read by [updateAccountNav], [dropAccountTab],
     * [setupBottomNav] and [updateBottomNavVisibility] — ONE expression, because the item hiding
     * while the fragment stays added and collecting is D12 itself, and two copies of the condition
     * is how that comes back.
     *
     * It used to read `BackendConfig.isConfigured() && accountAccessAllowed()`, i.e. **signed in**,
     * and that is the bar the owner photographed: two items stretched to half the width each. He
     * had pasted a departament подписка from the clipboard and never signed in, so the middle
     * destination deleted itself and took the only way to sign in with it. The owner's correction:
     * «убрать эту плашку надо полностью и оставить вкладку аккаунт, где можно зарегистрироваться
     * там и тд … именно для подписки с буфера обмена, тоесть там можно вход сделать».
     *
     * So a departament подписка now opens the destination on its own. What it does NOT do is grant
     * account ACCESS — that is [accountAccessAllowed], it still means a session, and widening it
     * would send the tab after account data with no token, collect a 401 and sign the user out of
     * a session they never had. The tab exists; whether it renders an account or a "not signed in"
     * state is the tab's own business.
     *
     * A FOREIGN pasted subscription still unlocks nothing: [SubscriptionOrigin] answers only for
     * the owner's own links, because payment and account cannot mean anything for someone else's
     * servers. And a build with no backend has no destination at all, which is decision A-3's one
     * sanctioned removal — it happens at start-up, not mid-session.
     */
    private fun accountTabAvailable(): Boolean =
        BackendConfig.isConfigured() &&
            (accountAccessAllowed() || SubscriptionOrigin.hasDepartamentSubscription())

    /**
     * Recomputes the visibility of the Аккаунт nav item from that gate. Called whenever the account
     * state changes (login/logout) and whenever the subscription / server list changes — pasting a
     * departament подписка is now one of the things that can bring the item in, so the existing
     * `updateListAction` call site matters as much as the account one.
     */
    private fun updateAccountNav() {
        val available = accountTabAvailable()
        binding.navAccount.isVisible = available
        // Hiding the item is not enough: the tab BEHIND it has to go too — see dropAccountTab.
        if (!available) dropAccountTab()
    }

    /**
     * Takes the Аккаунт tab off screen and out of the FragmentManager when the DESTINATION goes
     * (D12).
     *
     * Hiding the bar item used to be the whole of it, and a hidden fragment is not a stopped one:
     * tabs are `hide`/`show`n, never replaced, so [AccountFragment] stayed added and RESUMED after
     * a sign-out, with every `repeatOnLifecycle(STARTED)` collector in it still running against a
     * session that no longer exists. Removing it is what makes «выйти» mean it — the next sign-in
     * gets a fresh instance from [syncTabFragments], loading from a clean state rather than from
     * the previous user's rendered screen.
     *
     * **Signing out no longer fires this on its own**, and that is the intended consequence of
     * [accountTabAvailable] widening: with a departament подписка still on the device the
     * destination survives the sign-out, so the fragment survives with it and shows its
     * "not signed in" state. What stops the dead collectors in that case is
     * `AccountFragment.onSessionCleared`, which cancels the poll and clears the ViewModel on the
     * same transition — the half of D12's fix that lives in the tab. This method still runs, and
     * still removes, when the destination itself goes: the подписка is deleted, or the build has no
     * backend.
     *
     * Posted, not inline, for the same reason [selectTabWhenIdle] is: the account state arrives on
     * a fragment's own collector, inside the FragmentManager's dispatch, where a second commit
     * throws. Every precondition is re-read inside the runnable — through the SAME
     * [accountTabAvailable] gate the caller used — so a sign-in that lands in that one frame
     * cancels the removal instead of racing it.
     *
     * Being posted also keeps the removal behind the sign-out repaint: `AccountFragment` reloads
     * the server list from its own collector when the session ends (the store has just lost that
     * account's подписки), and that runs while this runnable is still queued.
     */
    private fun dropAccountTab() {
        val attached = supportFragmentManager.findFragmentByTag(MainTab.ACCOUNT.tag) != null
        if (!attached && selectedNavId != R.id.nav_account) return
        binding.bottomNav.post {
            if (isFinishing || isDestroyed) return@post
            // Signed back in within the frame: the tab is legitimate again, leave it alone.
            if (accountTabAvailable()) return@post
            val fm = supportFragmentManager
            if (fm.isStateSaved) return@post
            // Off the tab first — removing the fragment the user is looking at would empty the
            // container under them.
            if (selectedNavId == R.id.nav_account) selectNav(R.id.nav_home)
            val fragment = fm.findFragmentByTag(MainTab.ACCOUNT.tag) ?: return@post
            fm.beginTransaction().remove(fragment).commitNow()
        }
    }

    /**
     * Selects [navId] on the next loop, not inline.
     *
     * The two gates above run from a tab fragment's own lifecycle callbacks (`onViewCreated`,
     * `onResume`), and those run INSIDE the FragmentManager's dispatch, where committing another
     * transaction throws «FragmentManager is already executing transactions». The correction is a
     * frame later, which no one can see, and it is a no-op if the tab is already right by then.
     */
    private fun selectTabWhenIdle(@IdRes navId: Int) {
        binding.bottomNav.post {
            if (isFinishing || isDestroyed) return@post
            if (selectedNavId != navId) selectNav(navId)
        }
    }


    // ---- Per-server actions (moved from GroupServerFragment) ----

    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(layoutInflater)
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        ivBinding.ivQcode.contentDescription = getString(R.string.title_qr_code)
        AlertDialog.Builder(this).setView(ivBinding.root).show()
    }

    // Copying to the clipboard is one of the two actions in the product that change NOTHING on
    // screen, so it is one of the two that still confirms itself — and it does it in a sentence
    // that names what happened, rather than in upstream's «Успешно» / «Ошибка», which were the
    // same two words every outcome in the app used to get.
    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(this, guid) == 0) toast(R.string.notice_copied)
        else toastError(R.string.notice_copy_failed)
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        // Managed/hidden profiles cannot be opened in an editor (would reveal the config).
        if (TemplateManager.isLocked(profile)) {
            toast(R.string.template_locked_toast)
            return
        }
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            else -> ServerActivity::class.java
        }
        val intent = Intent(this, activityClass)
            .putExtra("guid", guid)
            .putExtra("isRunning", mainViewModel.isRunning.value)
            .putExtra("createConfigType", profile.configType.value)
            .putExtra("subscriptionId", profile.subscriptionId)
        requestActivityLauncher.launch(intent)
    }

    private fun removeServer(guid: String, position: Int) {
        if (guid == MmkvManager.getSelectServer()) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> removeServerSub(guid, position) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
        } else {
            removeServerSub(guid, position)
        }
    }

    private fun removeServerSub(guid: String, position: Int) {
        mainViewModel.removeServer(guid)
        homeFragment?.removeServerRow(guid, position)
    }

    /**
     * Tapping a server row SELECTS it — it never connects and never reconnects.
     *
     * Connecting is the connect button's job alone. When a tunnel is already up and the user picks a
     * different server, the running tunnel is left untouched and an explicit "apply it" action is
     * offered instead, so a tap in the list can never silently tear down a working connection.
     *
     * The shell owns the write, and fans it out, because more than one surface reads the selected
     * server: Главная's under-shield label, its subscription card, and its inline list. The one
     * call below carries both halves — `HomeFragment.onSelectedServerChanged` repaints the labels
     * AND mirrors the change into the adapter via [MainRecyclerAdapter.setSelectServer], which is
     * what guarantees exactly one row is ever painted selected. Never write the store here without
     * that call: a row painted from MMKV instead of the mirrored guid is how two rows end up
     * looking selected at once.
     */
    private fun setSelectServer(guid: String) {
        // A ROW CAN NAME A SERVER THAT NO LONGER EXISTS, and storing that guid is what broke the
        // screen. The list on screen is a cache of guids; a подписка refresh — including the
        // unattended one, which runs in a worker while this Activity is in front — deletes every
        // profile of that провайдер it does not recognise and mints new guids for what is new.
        // Until the cache is rebuilt, such a row addresses a deleted profile, and this write used
        // to accept one: from that moment the selection named nothing, Главная drew «Выберите
        // сервер в списке ниже» over a full list, and the connect object was disabled. The answer
        // is not a message, it is
        // the list: rebuild it from the store so the rows address real servers again, and let the
        // user's next tap land on one. (`MainViewModel.reloadServerList` also repairs the
        // selection, so the screen is never left with servers and nothing selected.)
        if (MmkvManager.decodeServerConfig(guid) == null) {
            mainViewModel.reloadServerList()
            homeFragment?.refreshSelectedServer()
            return
        }

        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            // The Главная half of the same mirror: its under-shield label and the subscription card.
            homeFragment?.onSelectedServerChanged(selected, guid)
        }

        // THE OFFER IS DECIDED AGAINST THE RUNNING SERVER, NEVER AGAINST THE SELECTED ONE, and that
        // is the whole correction here. This used to `return` the moment the tap landed on the row
        // that was already selected — which, after the very first decline, is the row the user has
        // to press. Selecting does not switch the tunnel, so declining leaves the selection sitting
        // one server ahead of the connection; from that point the picked row IS the selection, the
        // guard swallowed every further tap on it, and «Переподключиться» could not be reached
        // again for the rest of the session. The screen made that worse rather than revealing it:
        // Главная's under-shield line is drawn from the SELECTED server (`HomeFragment.resolveState`),
        // so it already named the server the user was pressing, and the press answered with nothing.
        //
        // Now the write is what the change gates, and the offer is gated by the only question it
        // was ever about: is the tunnel already on this server? While it is, there is nothing to
        // apply and silence is honest. While it is not, the way back is offered every time.
        //
        // «НА ЭТОМ СЕРВЕРЕ» ЗНАЧИТ «НА НЁМ И В ЕГО НЫНЕШНИХ НАСТРОЙКАХ». Обновление подписки
        // сохраняет guid узнанного сервера, а конфиг провайдера узнаётся по имени и тогда, когда его
        // шаблон поменялся - туннель при этом едет на прежнем. Сравнение одних guid промолчало бы
        // как раз там, где переподключение что-то даёт; `isTunnelOn` сверяет ещё и отпечаток.
        if (mainViewModel.isRunning.value == true && !mainViewModel.isTunnelOn(guid)) {
            promptApplySelectedServer(guid)
        }
    }

    /**
     * Offers to move an already-running tunnel onto the newly selected server. Declining leaves the
     * connection exactly as it was — the selection is remembered for the next connect.
     */
    private fun promptApplySelectedServer(guid: String) {
        val name = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()
        val message = if (name.isBlank()) {
            getString(R.string.server_selected_reconnect_prompt_generic)
        } else {
            getString(R.string.server_selected_reconnect_prompt, FlagUtil.stripLeadingFlag(name))
        }
        val bar = Snackbar.make(binding.mainContent, message, Snackbar.LENGTH_LONG)
        // Above the navigation, and ONLY while the navigation is actually on screen — the same
        // guard `Notice.show` and [showActionSnackbar] already make. Anchoring to a hidden view
        // parks the bar off the bottom of the window, silently: nothing throws and nothing paints.
        if (binding.bottomNav.isVisible) bar.setAnchorView(binding.bottomNav)
        // The restart runs through the connect state machine, so a stalled one is reported like
        // any other failed start rather than leaving the hero on the old server. It carries the
        // guid the SENTENCE NAMED: the action used to start whatever `getSelectServer()` held when
        // it was pressed, so a selection that moved while the bar was up connected to one server
        // while the words on the bar promised another.
        bar.setAction(R.string.server_selected_reconnect_action) {
            homeFragment?.applySelectionToRunningTunnel(guid)
        }
        bar.show()
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) {}
        override fun onRefreshData() {}
        override fun onRemove(guid: String, position: Int) { removeServer(guid, position) }
        override fun onEdit(guid: String, position: Int, profile: ProfileItem) { editServer(guid, profile) }
        override fun onSelectServer(guid: String) { setSelectServer(guid) }
    }

    /**
     * The blue/light and blue/dark backgrounds are theme-qualified drawables, but the mono
     * overlay is a runtime style overlay (not a resource qualifier), so the decorative home
     * gradient must be swapped to its neutral grey variant here when mono is active. (The hero's
     * glow and ring live in Главная; `HomeFragment.applyThemeDecorations` swaps those.)
     */
    private fun applyThemeDecorations() {
        val mono = MmkvManager.decodeSettingsString(AppConfig.PREF_COLOR_THEME, BaseActivity.THEME_BLUE) == BaseActivity.THEME_MONO
        if (!mono) return
        binding.homeRoot.setBackgroundResource(R.drawable.bg_home_gradient_mono)
    }

    /**
     * Resolves a themed color attribute (respects the active blue/mono overlay).
     */
    private fun themeColor(attr: Int): Int = MaterialColors.getColor(binding.mainContent, attr)

    override fun onResume() {
        super.onResume()
        // THE CACHE IS CHECKED AGAINST THE STORE FIRST, and only re-read when the two disagree.
        // A подписка refresh rewrites a провайдер's servers from a worker that owns no list — new
        // guids for what it does not recognise, new content under the guids it does; it announces
        // itself now (MSG_STATE_SERVERS_CHANGED), but an app that was not running when it landed
        // hears nothing. Every tab paints from `serversCache`, so a stale one can mean a row on
        // screen addresses a profile that has been deleted — and the first tap on one of them left
        // the app with no selection and a disabled connect object. The comparison is guid list
        // against guid list plus one revision number; nothing is parsed and nothing repaints unless
        // something actually moved, and a refresh that changed nothing moves nothing.
        if (mainViewModel.reloadServerListIfStale()) {
            homeFragment?.refreshSelectedServer()
        }
        // Главная re-reads the selected server, and its account chip, in HomeFragment.onResume — a
        // hidden tab is still RESUMED, so every tab refreshes itself without the shell reaching
        // into it. (Other entry points change the selection without owning a list: the URL-scheme
        // and shortcut activities, and the quick tile.)
        //
        // A login or a subscription change from another screen can add or remove the Аккаунт item
        // and, in the onboarding state, the bar itself.
        refreshNavGates()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // No toolbar action menu: the shell has no app bar at all, so the tab's own control owns the
        // menu. Главная opens menu_main as a PopupMenu via showImportMenu; the Settings tab has no
        // control at all. onOptionsItemSelected is the dispatch for that PopupMenu, so it stays.
        return false
    }

    /**
     * Dispatch for the «Добавить подписку» popup, which is the only menu the shell inflates.
     *
     * Two branches, because the menu has two items. The «Ввести ссылку» / «Создать вручную» /
     * «Импортировать из файла» branches that used to sit here moved to
     * [showAdvancedAddMethods] — the actions are unchanged, they are simply no longer reached
     * through a menu id — and «Отправить на телевизор» kept only its live entry point, the
     * Настройки · Устройства row.
     */
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * «Создать вручную»: choose what is being created, then open its editor.
     *
     * The editors pick their layout from `createConfigType` and give the user no way to change it
     * afterwards ([ServerActivity] onCreate maps the type to a layout and finishes silently for a
     * type it has none for), so the type has to be settled here. Only types with a real editor are
     * offered: CUSTOM is deliberately absent because [ServerActivity] would open blank for it, and
     * a raw xray-json body already imports through «Ввести ссылку». A proxy chain is offered only
     * when there are at least two plain servers to chain, which is what
     * [ServerProxyChainActivity.saveServer] requires.
     */
    private fun pickManualServerType() {
        val types = mutableListOf(
            "VLESS" to EConfigType.VLESS,
            "VMess" to EConfigType.VMESS,
            "Trojan" to EConfigType.TROJAN,
            "Shadowsocks" to EConfigType.SHADOWSOCKS,
            "WireGuard" to EConfigType.WIREGUARD,
            "Hysteria2" to EConfigType.HYSTERIA2,
            "SOCKS5" to EConfigType.SOCKS,
            "HTTP" to EConfigType.HTTP,
            getString(R.string.menu_actions_type_group) to EConfigType.POLICYGROUP,
        )
        val chainable = mainViewModel.serversCache.count { !it.profile.configType.isComplexType() }
        if (chainable >= 2) {
            types.add(getString(R.string.menu_actions_type_chain) to EConfigType.PROXYCHAIN)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_actions_type_title)
            .setItems(types.map { it.first }.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                importManually(types[which].second.value)
            }
            .setNegativeButton(R.string.menu_actions_cancel, null)
            .show()
    }

    private fun importManually(createConfigType: Int) {
        // Launched through createServerLauncher (not startActivity) so the new server appears in
        // the list the moment the editor returns.
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            createServerLauncher.launch(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            createServerLauncher.launch(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            createServerLauncher.launch(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * «Ввести ссылку»: a plain text-input dialog for pasting/typing a config or subscription link
     * by hand. The entered string is fed into the same import path as pasted clipboard text.
     *
     * Reached from [showAdvancedAddMethods], no longer from the add menu.
     */
    private fun showManualEntryDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.manual_entry_hint)
        }
        val dialog = AlertDialog.Builder(this)
            // The same wording the picker row carries. It used to read «Ввести вручную» while the
            // row that opened it read «Ввести ссылку» — one concept, two names.
            .setTitle(R.string.menu_actions_add_link)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Validate before dismissing so a bad paste shows an inline reason instead of a generic
        // failure toast after the dialog has already closed.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                when {
                    text.isEmpty() ->
                        input.error = getString(R.string.import_link_required)
                    !looksImportable(text) ->
                        input.error = getString(R.string.import_link_invalid)
                    else -> {
                        dialog.dismiss()
                        importBatchConfig(text)
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Cheap pre-check for the manual-entry dialog: is the text plausibly a subscription link, a
     * proxy share-link (possibly base64-wrapped), or a raw config body? Keeps obviously-broken
     * input from reaching the importer while accepting every format the importer understands.
     */
    private fun looksImportable(text: String): Boolean {
        if (Utils.isValidSubUrl(text)) return true
        if (text.contains("://")) return true
        if (Utils.decode(text).contains("://")) return true
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                // THE RESULT LANDS FIRST AND THE ARC LEAVES AFTERWARDS, with the existing half
                // second between them. `showImportResult` rebuilds the whole list
                // (`reloadServerList` -> `notifyDataSetChanged`), and that rebuild used to share a
                // main-thread message with `hideLoading`, i.e. with the frame the arc's exit treats
                // as t=0 — so the exit's first frames went on binding rows and the interpolator
                // caught up in one jump. The delay was already here; it is now on the useful side of
                // the work. @see importConfigViaSub
                withContext(Dispatchers.Main) { showImportResult(result) }
                delay(500L)
                withContext(Dispatchers.Main) {
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.notice_add_failed)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * The outcome of an add, in the product's own voice and only when the screen cannot say it
     * itself.
     *
     * WHAT CAME OUT OF HERE, and why. This used to answer every branch with a `Toasty` capsule —
     * green «Серверы добавлены: 4», green «Импортировано серверов: 4», red «Ошибка» — and that
     * red one on the QR path is the message the owner named: «по qr коду когда добавляешь там
     * внизу уведомление красное и тд, их убрать надо совсем».
     *
     * A successful add is SILENT now, because it is the one case where the screen answers
     * completely on its own: the gate block is replaced by the подписка card, the card carries
     * the name and the traffic, and the server list fills in under it, all in the same frame as
     * the message would have been. A count of records on top of that is upstream telling the user
     * about its own bookkeeping.
     *
     * The three cases where the tap has NO visible answer keep one sentence each — a подписка
     * that fetched nothing, a link already added, a link that is not ours — and the blunt
     * `toast_failure` («Ошибка») is replaced by a sentence that says what failed.
     */
    private fun showImportResult(result: AngConfigManager.ImportResult) {
        when {
            // A подписка was added. If it produced servers the screen says so by rebuilding
            // itself; if it produced none, nothing on screen changes and that needs a word.
            result.countSub > 0 -> {
                if ((result.subFetch?.configCount ?: 0) <= 0) toastError(R.string.import_sub_empty)
                mainViewModel.reloadServerList()
            }
            // Individual servers: they appear in the list, which is the confirmation.
            result.count > 0 -> mainViewModel.reloadServerList()
            // The subscription link is valid but was already added — nothing changes on screen.
            result.subDuplicate -> toast(R.string.import_sub_duplicate)
            // The subscription link is not from departament.
            result.subRejected -> toast(R.string.import_sub_foreign)
            else -> toastError(R.string.notice_add_failed)
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            // ============================================================================
            // THE REBUILD GETS ITS OWN FRAME, AND THAT IS THE «ПРОЛАГ».
            // ============================================================================
            //
            //     «получается пролаг, когда идёт анимация обновления, то потом с пролагом каким-то
            //      слишком резко конец анимации»
            //
            // `reloadServerList` decodes every profile out of MMKV, rebuilds the карусель and calls
            // `notifyDataSetChanged`; the traversal that answers it binds every visible row. All of
            // that used to sit in the SAME main-thread message as `hideLoading` — which starts the
            // arc's exit and, on a sign-in flow, §3's whole table. An animator's clock is wall time,
            // not frames: frame one went on the rebuild, frame two arrived 60–80ms later, and the
            // interpolator was already a third of the way through. A stall, then a jump, then over.
            //
            // The half-second delay below already existed. Putting the rebuild in front of it costs
            // nothing, shows the new rows half a second sooner, and hands every movement that
            // follows a frame with nothing else in it.
            if (result.configCount > 0) {
                withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
            }
            delay(500L)
            launch(Dispatchers.Main) {
                // THE COUNTER IS GONE, and it is the second message the owner named: «Обновлено
                // серверов: 13 (успешно: 1, ошибки: 0, пропущено: 1)». Four figures about the
                // refresh's own bookkeeping, in upstream's voice, on top of a card that has just
                // repainted with the new timestamp and a list that has just repainted with the new
                // rows. The repaint IS the confirmation, so a refresh that worked says nothing.
                //
                // A refresh that produced NOTHING is the case with no visible answer, and it gets
                // one sentence with a next step in it. `NoticePolicy` blocks the counter shape at
                // the surface too, so the string cannot come back through another call site.
                //
                // «НИЧЕГО НЕ ЗАПРАШИВАЛОСЬ» IS NOT «НЕ УДАЛОСЬ». `successCount == 0` on its own was
                // reported as a network failure, and it covers two states where not one request
                // went out: no подписки stored at all, and every stored подписка skipped
                // (`updateConfigViaSub` returns skipCount for one that is disabled or has no URL).
                // Both told the user his connection was at fault, instantly, about a fetch that
                // never happened — the same defect «Загрузить серверы» had, and the instant arrival
                // of the message is the tell in both. Only a fetch that actually failed says so;
                // when there was nothing to fetch, the answer is that there was nothing to fetch.
                // `SubSettingActivity.updateAll` already branches this way on the same result.
                when {
                    result.failureCount > 0 && result.successCount == 0 ->
                        toastError(R.string.notice_refresh_failed)

                    result.successCount == 0 ->
                        toast(R.string.subs_update_none)
                }
                // The reload used to stand here, immediately before this line. It now runs half a
                // second earlier, on the same `configCount > 0` test — see the note above it — so
                // that this message carries the arc's exit and Главная's entrance and nothing else.
                hideLoading()
            }
        }
        return true
    }

    /**
     * The one feedback surface for the shell's own outcomes: a themed Snackbar, never a Toast
     * (00-rules.md 1.4.8). 3s, which is the no-action duration of 22-components.md 14. Anchored
     * above the bottom navigation only while it is actually visible - in the signed-out empty state
     * it is gone, and anchoring to a hidden view would park the bar in the wrong place.
     */
    private fun showActionSnackbar(text: CharSequence) {
        val bar = Snackbar.make(binding.mainContent, text, Snackbar.LENGTH_LONG)
        bar.duration = 3000
        if (binding.bottomNav.isVisible) bar.setAnchorView(binding.bottomNav)
        bar.show()
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     *
     * A file the system hands back can still be unreadable (revoked permission, a directory, a
     * provider that dies mid-read). That used to fail into the log only, leaving the tap with no
     * visible outcome at all, so the failure is reported.
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                val text = input?.bufferedReader()?.readText()
                if (text.isNullOrBlank()) {
                    showActionSnackbar(getString(R.string.menu_actions_file_failed))
                    return
                }
                importBatchConfig(text)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            showActionSnackbar(getString(R.string.menu_actions_file_failed))
        }
    }

    /**
     * BACK is owned by the [OnBackPressedCallback] registered in [onCreate] (tab -> Главная ->
     * minimise) and must not be handled here as well.
     *
     * This used to consume `KEYCODE_BACK` and call `moveTaskToBack(false)` on key-DOWN, which is
     * the upstream v2rayNG behaviour and pre-empts the dispatcher. With `targetSdk` 37 and no
     * `enableOnBackInvokedCallback` the two paths coexist and the SAME build navigates
     * differently per device: on Android 15+ the platform routes BACK to the dispatcher and Back
     * returns to Главная, while below it the key path won here and Back minimised the app from
     * every tab. One path only, so every supported version behaves the same.
     *
     * Gamepad B still needs routing by hand: it is never delivered through the platform's
     * back-invoked dispatcher, and the leanback build is a declared target.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}