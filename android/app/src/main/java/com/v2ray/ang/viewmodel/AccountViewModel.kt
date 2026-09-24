package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.auth.AccountCache
import com.v2ray.ang.auth.AccountRepository
import com.v2ray.ang.auth.AccountSession
import com.v2ray.ang.auth.ApiError
import com.v2ray.ang.auth.dto.PaymentDto
import com.v2ray.ang.auth.dto.PaymentInitDto
import com.v2ray.ang.auth.dto.PaymentOutcome
import com.v2ray.ang.auth.dto.PaymentRequestDto
import com.v2ray.ang.auth.dto.paymentOutcomeOf
import com.v2ray.ang.auth.dto.PrimarySubscriptionDto
import com.v2ray.ang.auth.dto.PromoDto
import com.v2ray.ang.auth.dto.PublicConfigDto
import com.v2ray.ang.auth.dto.ServerStatusDto
import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.auth.dto.TariffGroupDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.AvatarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Backs the account/subscriptions/store/payments screens. Holds the observable data as
 * [StateFlow]s and delegates every action to [AccountRepository]; errors surface via [error].
 */
class AccountViewModel : ViewModel() {

    private val repo = AccountRepository()

    /** Logged-in/out state (seeded from the persisted session). */
    val account: StateFlow<AccountSession.AccountState> = AccountSession.state

    private val _profile = MutableStateFlow<UserProfileDto?>(null)
    val profile: StateFlow<UserProfileDto?> = _profile.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<SubInfoDto>>(emptyList())
    val subscriptions: StateFlow<List<SubInfoDto>> = _subscriptions.asStateFlow()

    /**
     * False until the subscription list has RESOLVED at least once — succeeded or failed — as
     * opposed to merely being empty.
     *
     * [subscriptions] cannot answer that question: its seed is `emptyList()` and it replays that
     * seed to every new collector, so "not fetched yet" and "fetched, nothing there" are the same
     * value. The account tab needs them apart. The profile is ONE request and the list is TWO, so
     * the profile lands first almost every time, and a screen that treated the profile's arrival as
     * "the load is over" concluded «нет активной подписки» for the second or so before the
     * subscriptions caught up — the bordered card the owner sees flash in the ring's place.
     */
    private val _subsResolved = MutableStateFlow(false)
    val subsResolved: StateFlow<Boolean> = _subsResolved.asStateFlow()

    // Cache of the last subscription fetch so we can re-merge the synthesized root when the profile
    // (which supplies the root's auto-renew flag + remnawave uuid) arrives after the sub list.
    private var lastPrimary: PrimarySubscriptionDto? = null
    private var lastAll: List<SubInfoDto> = emptyList()
    private var hasSubData = false

    // Single-flight ownership for subscription loads: [loadSubscriptions] is polled every ~8s and
    // must not race an older in-flight load and publish stale state. Cancel the previous before
    // launching the next (latest-wins).
    private var subsJob: Job? = null
    private var profileJob: Job? = null

    private val _tariffs = MutableStateFlow<List<TariffGroupDto>>(emptyList())
    val tariffs: StateFlow<List<TariffGroupDto>> = _tariffs.asStateFlow()

    private val _payments = MutableStateFlow<List<PaymentDto>>(emptyList())
    val payments: StateFlow<List<PaymentDto>> = _payments.asStateFlow()

    // Live connected-device count for the ACTIVE subscription, from GET /client/devices (its
    // total). /subscription/all reports 0 here, so the account card reads this instead. Null until
    // first resolved. Latest-wins via [devicesJob].
    private val _deviceCount = MutableStateFlow<Int?>(null)
    val deviceCount: StateFlow<Int?> = _deviceCount.asStateFlow()

    private var devicesJob: Job? = null

    private val _publicConfig = MutableStateFlow<PublicConfigDto?>(null)
    val publicConfig: StateFlow<PublicConfigDto?> = _publicConfig.asStateFlow()

    private val _serverStatus = MutableStateFlow<List<ServerStatusDto>>(emptyList())
    val serverStatus: StateFlow<List<ServerStatusDto>> = _serverStatus.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error.asStateFlow()

    /**
     * True from the moment a charge is issued until the provider answers — the guard behind D10.
     *
     * It lives HERE, not on the screen, for two reasons. A ViewModel survives a rotation, so a
     * purchase fired before one is still visibly in flight after it, instead of handing the user a
     * fresh, enabled «Оплатить» over a request that is still running. And [buy] / [payWithBalance]
     * consult it themselves, so even a caller that forgets to disable its own button cannot issue
     * a second charge: the second call returns without touching the network.
     */
    private val _paymentInFlight = MutableStateFlow(false)
    val paymentInFlight: StateFlow<Boolean> = _paymentInFlight.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /**
     * **Отказ ДАННЫХ АККАУНТА — как событие, а не как состояние.**
     *
     * [error] is a conflated [StateFlow] and it has to stay one: `AccountFragment.renderHeroState`
     * reads `error.value` to decide whether the hero shows its error card. But this ViewModel is
     * shared by TWO screens now (Главная and Аккаунт), and a conflated state cannot be consumed
     * twice: whichever collector ran first called `clearError()` and the other one, waking to read
     * the LATEST value, found `null` and never learned anything had failed. Most [ApiError]s are
     * singleton `object`s besides, so a second identical failure would not even re-emit.
     *
     * This stream carries the failures of the two loads Главная actually depends on — the profile
     * and the подписки — to every collector, once each, with no consumption and no conflation. It
     * is deliberately NOT everything [error] carries: тарифы, платежи, устройства and публичная
     * конфигурация are loaded by the Аккаунт tab alone, and Главная must not raise «данные подписки
     * устарели» because the payment history did not load.
     */
    private val _accountDataErrors = MutableSharedFlow<ApiError>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val accountDataErrors: SharedFlow<ApiError> = _accountDataErrors.asSharedFlow()

    private fun report(t: Throwable?) {
        _error.value = t as? ApiError ?: t?.let { ApiError.Network(it) }
    }

    /** [report], plus an announcement on [accountDataErrors]. For the profile and подписки only. */
    private fun reportAccountData(t: Throwable?) {
        report(t)
        _error.value?.let { _accountDataErrors.tryEmit(it) }
    }

    // region loads

    /**
     * LATEST-WINS, exactly as [loadSubscriptions] already was, and for the same reason it needed to
     * be: this had NO job behind it, so two calls in the same breath both ran to the end and both
     * published — the older answer landing last would overwrite the newer profile, and both paid
     * for a `/client/profile` round trip. The screens ask more than once by design (a resume, a
     * checkout returning, the payment poll's six rounds), so "more than once in flight" is the
     * normal case here, not an edge one.
     */
    fun refreshProfile() {
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            repo.refreshProfile()
                .onSuccess {
                    _profile.value = it
                    // Re-merge so the active/root sub reflects the profile's auto-renew flag + uuid,
                    // even if the profile finished loading after the subscription list.
                    if (hasSubData) _subscriptions.value = mergeSubscriptions(lastPrimary, lastAll, it)
                }
                .onFailure { reportAccountData(it) }
        }
    }

    /**
     * Loads the account's subscriptions for the Account screen. The `/subscription/all` list does
     * NOT carry the user's primary/active subscription in every case (an account with only a
     * primary sub and no secondary subs gets `items: []`), which is why the tab used to show
     * "нет активной подписки" for a genuinely-active account. We therefore ALSO fetch the
     * authoritative primary subscription (`/client/subscription`) and merge it in, so the active
     * sub always renders with its real name/expiry/devices/auto-renew state and connect payload.
     */
    fun loadSubscriptions() {
        // Latest-wins: cancel any in-flight load before starting a new one so a slow older request
        // can't finish late and publish stale state over a newer one.
        subsJob?.cancel()
        subsJob = viewModelScope.launch { fetchAndApplySubscriptions() }
    }

    /**
     * Fetches `/subscription/all` plus the authoritative primary subscription and publishes the
     * merged list via [applyMerged]. The single code path shared by the polled [loadSubscriptions]
     * and [autoImportSubscriptions], so both always render the active/primary sub (never the raw
     * un-merged `/all` list, and never a "нет активной подписки" flash).
     */
    private suspend fun fetchAndApplySubscriptions() {
        val allResult = repo.loadSubscriptions()
        val primaryResult = repo.loadPrimarySubscription()

        if (allResult.isFailure && primaryResult.isFailure) {
            // Nothing was learned. Surface the error and leave the published list exactly as it
            // was — replacing it with the empty one this run produced would state «нет активной
            // подписки» on the strength of a dropped connection.
            (allResult.exceptionOrNull() ?: primaryResult.exceptionOrNull())?.let { reportAccountData(it) }
            _subsResolved.value = true
            return
        }

        // A FAILED ENDPOINT KEEPS ITS LAST ANSWER; ONLY A REPLY REPLACES ONE.
        //
        // The two endpoints describe different halves of the account and either can fail on its
        // own. Reading a failure as an empty half is what made the Главная card lie: it decides the
        // whole подписка state from `accountSubs.first()`, and that first entry is the ACTIVE
        // подписка only because the merge puts it there. Lose `/client/subscription` for one round
        // — a timeout, a 502 — and the merge had no root to place, so the list started with a
        // SECONDARY подписка and the screen reported that one's expiry as the account's:
        // «Подписка истекла» over a live, paid подписка whose date the app had had a second ago.
        // Same story on this tab, where the hero time block reads the same first entry.
        //
        // An empty REPLY still empties its half — that is the backend saying the подписка is gone,
        // and it must show. Only a failure is held.
        val all = allResult.getOrNull()?.items ?: lastAll
        val primary = primaryResult.getOrNull() ?: lastPrimary
        applyMerged(primary, all, _profile.value)
        // Resolved either way — a failure is an answer too, and a screen waiting for this one must
        // not wait forever on it. Set last, after the list (or the error) is already published, so
        // a collector woken by this flag reads the state it describes.
        _subsResolved.value = true
    }

    /**
     * The single place that publishes the displayed subscription list: caches the inputs
     * ([lastPrimary]/[lastAll]/[hasSubData]) so [refreshProfile] can re-merge later, and pushes the
     * merged list to [subscriptions].
     */
    private fun applyMerged(
        primary: PrimarySubscriptionDto?,
        all: List<SubInfoDto>,
        profile: UserProfileDto?,
    ) {
        lastPrimary = primary
        lastAll = all
        hasSubData = true
        _subscriptions.value = mergeSubscriptions(primary, all, profile)
    }

    /**
     * Builds the list the Account screen consumes: the active/root subscription first (enriched
     * from the primary payload when present), then the secondary subscriptions from `/all`.
     */
    private fun mergeSubscriptions(
        primary: PrimarySubscriptionDto?,
        all: List<SubInfoDto>,
        profile: UserProfileDto?,
    ): List<SubInfoDto> {
        val rootFromAll = all.firstOrNull { it.type.equals("root", ignoreCase = true) }
        val secondaries = all.filter { !it.type.equals("root", ignoreCase = true) }

        val activeRoot: SubInfoDto? = when {
            primary?.hasActiveSubscription() == true -> buildRootSub(primary, rootFromAll, profile)
            rootFromAll != null -> rootFromAll
            else -> null
        }
        // Dedup by non-blank id (a synthesized root can have a blank id and must be kept).
        val ordered = listOfNotNull(activeRoot) + secondaries
        val seen = HashSet<String>()
        return ordered.filter { it.id.isBlank() || seen.add(it.id) }
    }

    /**
     * Synthesizes/enriches the root [SubInfoDto] from the primary payload. Display + connect data
     * (tariff name, expiry, device limit, subscription URL) come from the primary's raw remnawave
     * record; the action ids (subscription id for auto-renew, tariff/price-option for renew) live
     * only on the `/all` root entry; the root's auto-renew flag and remnawave uuid live on the
     * profile — mirroring how the web cabinet composes the same card.
     */
    private fun buildRootSub(
        primary: PrimarySubscriptionDto,
        rootFromAll: SubInfoDto?,
        profile: UserProfileDto?,
    ): SubInfoDto {
        val raw = primary.raw()
        return SubInfoDto(
            type = "root",
            // Auto-renew / renew target the id from /all; blank when /all has no root entry.
            id = rootFromAll?.id.orEmpty(),
            remnawaveUuid = profile?.remnawaveUuid?.takeIf { it.isNotBlank() }
                ?: rootFromAll?.remnawaveUuid.orEmpty(),
            // Carry the raw record so import (subscriptionUrl) and the unlimited-devices check work.
            subscription = primary.subscription,
            tariffDisplayName = primary.tariffDisplayName?.takeIf { it.isNotBlank() }
                ?: rootFromAll?.tariffDisplayName,
            displayName = rootFromAll?.displayName,
            defaultLabel = rootFromAll?.defaultLabel,
            subscriptionIndex = rootFromAll?.subscriptionIndex,
            // Prefer the /all root's tariff id (it also drives renew/upgrade), but fall back to the
            // PRIMARY payload's own tariff id when /all has no root (a primary-only account). Without
            // this the badge lost the tariff identity and mis-resolved to a stale product label.
            tariffId = rootFromAll?.tariffId?.takeIf { it.isNotBlank() }
                ?: primary.activeTariffId(),
            tariffPriceOptionId = rootFromAll?.tariffPriceOptionId,
            deviceCount = rootFromAll?.deviceCount ?: 0,
            totalDevices = rootFromAll?.totalDevices
                ?: raw?.hwidDeviceLimit?.takeIf { it > 0 } ?: 0,
            connectedDevices = rootFromAll?.connectedDevices ?: 0,
            // THE ROOT SUB'S AUTO-RENEW IS EXPOSED ON THE PROFILE (as in the web cabinet), and that
            // is why [reloadAfterAutoRenew] re-reads the profile and not only the list: this field
            // is the only one on the card whose source is not one of the two subscription endpoints.
            //
            // Written as an explicit branch because the old elvis chain could not do what it looked
            // like it did: `UserProfileDto.autoRenewEnabled` is a non-null Boolean, so
            // `profile?.autoRenewEnabled ?: rootFromAll?...` fell through to `/all` only when the
            // whole PROFILE was null — never when the profile simply omitted the flag. Same
            // behaviour, minus the dead-looking fallback.
            autoRenewEnabled = if (profile != null) {
                profile.autoRenewEnabled
            } else {
                rootFromAll?.autoRenewEnabled ?: false
            },
            expireAtIso = raw?.expireAt?.takeIf { it.isNotBlank() } ?: rootFromAll?.expireAtIso,
            isTrial = rootFromAll?.isTrial ?: false,
            tariffPrice = rootFromAll?.tariffPrice,
            tariffCurrency = primary.autoRenewCurrency?.takeIf { it.isNotBlank() }
                ?: rootFromAll?.tariffCurrency,
            renewalPrice = primary.autoRenewNextChargeAmount ?: rootFromAll?.renewalPrice,
        )
    }

    fun loadTariffs() = viewModelScope.launch {
        repo.loadCatalog()
            .onSuccess { _tariffs.value = it.items }
            .onFailure { report(it) }
    }

    /**
     * Resolves a tariff's display name ("Base" / "Plus") from its [tariffId] against the loaded
     * catalog ([tariffs]). Returns null when the catalog isn't loaded yet or the id doesn't match,
     * so callers can fall back to the sub's own product name.
     */
    fun tariffNameFor(tariffId: String?): String? {
        if (tariffId.isNullOrBlank()) return null
        return _tariffs.value.asSequence()
            .flatMap { it.tariffs.asSequence() }
            .firstOrNull { it.id == tariffId }
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolves a tariff's display name ("Base" / "Plus") from the PRICE-OPTION id the subscription
     * renews on ([SubInfoDto.tariffPriceOptionId]) against the loaded catalog. Used as the second
     * badge source when [tariffNameFor] can't resolve because the sub carries no [tariffId]: the
     * price-option id reflects the option the sub CURRENTLY renews on, so it points at the real
     * current tariff even after a Base→Plus upgrade (unlike the stale provisioning product name).
     * Returns null when the id is blank, the catalog isn't loaded yet, or nothing matches.
     */
    fun tariffNameForPriceOptionId(priceOptionId: String?): String? {
        if (priceOptionId.isNullOrBlank()) return null
        return _tariffs.value.asSequence()
            .flatMap { it.tariffs.asSequence() }
            .firstOrNull { tariff -> tariff.priceOptions.any { it.id == priceOptionId } }
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    fun loadPayments() = viewModelScope.launch {
        repo.getPayments()
            .onSuccess {
                // payments(), not items: the flat array and the nested `response` shape are both
                // things this backend returns, and reading one of them directly is how the Devices
                // screen once shipped blank (DevicesDto's note).
                val operations = it.payments()
                _payments.value = operations
                // Warm the process-wide cache so PaymentHistoryActivity (a separate ViewModel
                // instance) renders instantly instead of spinning through a fresh network load.
                AccountCache.putPayments(operations)
            }
            .onFailure { report(it) }
    }

    /**
     * Resolves the ACTIVE subscription's connected-device count from GET /client/devices (its
     * total = list size) and publishes it via [deviceCount]. Cache-first: a fresh (< 1h)
     * [AccountCache] entry is used immediately with no network call; a miss fetches and repopulates
     * the cache, so the Devices sub-screen also opens instantly (pre-warm). A device-fetch failure
     * is swallowed on purpose — the count is secondary and must not pop an error toast on the
     * Account tab; the sub-screen surfaces its own diagnostics.
     */
    fun loadDevices(uuid: String) {
        if (uuid.isBlank()) return
        val cached = AccountCache.getDevices(uuid)
        if (cached != null) {
            _deviceCount.value = cached.size
            return
        }
        devicesJob?.cancel()
        devicesJob = viewModelScope.launch {
            repo.getDevices(uuid)
                .onSuccess {
                    AccountCache.putDevices(uuid, it.devices)
                    _deviceCount.value = it.devices.size
                }
                .onFailure { /* secondary data: keep last known count, no error toast */ }
        }
    }

    fun loadPublicConfig() = viewModelScope.launch {
        repo.loadPublicConfig()
            .onSuccess { _publicConfig.value = it }
            .onFailure { report(it) }
    }

    fun loadServerStatus() = viewModelScope.launch {
        repo.loadServerStatus()
            .onSuccess { _serverStatus.value = it }
            .onFailure { report(it) }
    }

    /** Fetch + import all subscriptions; publishes the local guids and refreshes the sub list. */
    fun autoImportSubscriptions(onImported: (List<String>) -> Unit = {}) = viewModelScope.launch {
        _loading.value = true
        try {
            repo.autoImportSubscriptions()
                .onSuccess { onImported(it) }
                .onFailure { report(it) }
            // Publish through the same merge path as loadSubscriptions so the active/primary sub
            // renders (never the raw un-merged /all list) and lastPrimary/lastAll/hasSubData stay set.
            fetchAndApplySubscriptions()
        } finally {
            // In a `finally` because the screen can go while the two fetches are in flight, and a
            // cancelled coroutine that skipped this left the tab's skeleton pulsing over data that
            // had already arrived — for as long as the ViewModel lived.
            _loading.value = false
        }
    }

    // endregion

    // region actions

    /**
     * Issues a provider checkout for [req].
     *
     * The in-flight guard is taken with [MutableStateFlow.compareAndSet] BEFORE the first
     * suspension point, and `viewModelScope` runs on `Dispatchers.Main.immediate`, so a second
     * call made from the same frame as the first — a double tap — finds the flag already set and
     * returns without touching the network (D10). It is released in a `finally`, so a throw cannot
     * strand a screen with a permanently busy CTA.
     *
     * Failures surface through [error] alone. There is deliberately no per-attempt failure
     * callback: nothing needs one, because the guard that a caller would clear from it lives here
     * rather than on the screen.
     */
    fun buy(
        req: PaymentRequestDto,
        onInit: (PaymentInitDto) -> Unit,
    ) = viewModelScope.launch {
        if (!_paymentInFlight.compareAndSet(expect = false, update = true)) return@launch
        try {
            repo.buy(req).onSuccess { onInit(it) }.onFailure { report(it) }
        } finally {
            _paymentInFlight.value = false
        }
    }

    /**
     * Settles [req] against the wallet balance. Same in-flight guard as [buy].
     *
     * **A 2xx IS NOT A PAYMENT.** `POST /client/payments/balance` answers with a
     * `PaymentResultDto` whose `status` is the actual outcome, and this used to call [onDone] on
     * any successful HTTP reply without reading it. Every screen behind it then said so in its own
     * words — «Баланс пополнен», «Подписка оплачена», and the buy screen closed itself — for a
     * charge the backend had just reported as declined, or as still being settled by the bank.
     * That is the worst failure a money path has: it is silent, and the user leaves believing they
     * have paid.
     *
     * The status is read through [paymentOutcomeOf], the app's single reading of that field —
     * the same one the payment history renders — so one operation cannot be «Оплачено» here and
     * «В обработке» in the ledger.
     *
     * **UNKNOWN counts as settled, deliberately.** A spelling this build does not recognise (and
     * an absent field, which deserialises to "") is not evidence of failure, and turning it into
     * one would break a flow that works today for the sake of a word we have not seen yet. The
     * safe direction here is the opposite of the ledger's: refuse only what the backend explicitly
     * NAMES as not-happening. Both callers re-read the profile afterwards, so the balance figure on
     * screen comes from the server either way.
     *
     * A FAILED / CANCELED status is reported through [error] like any other payment failure, so it
     * lands in the diagnostic dialog with the raw status in it, ready to be screenshotted.
     */
    fun payWithBalance(
        req: PaymentRequestDto,
        onDone: (PaymentOutcome) -> Unit = {},
    ) = viewModelScope.launch {
        if (!_paymentInFlight.compareAndSet(expect = false, update = true)) return@launch
        try {
            repo.payWithBalance(req)
                .onSuccess { result ->
                    when (val outcome = paymentOutcomeOf(result.status)) {
                        PaymentOutcome.SETTLED, PaymentOutcome.UNKNOWN -> onDone(PaymentOutcome.SETTLED)
                        PaymentOutcome.PENDING -> onDone(outcome)
                        PaymentOutcome.FAILED, PaymentOutcome.CANCELED ->
                            // Server(200) is not a lie: the request WAS answered 200, and the
                            // refusal is in the body. The code and the raw status both reach the
                            // diagnostic dialog, which is what the owner screenshots.
                            report(ApiError.Server(200, result.status.takeIf { it.isNotBlank() }))
                    }
                }
                .onFailure { report(it) }
        } finally {
            _paymentInFlight.value = false
        }
    }

    fun upgrade(
        targetTariffId: String,
        method: String,
        subscriptionUuid: String,
        paymentMethod: String? = null,
        onInit: (PaymentInitDto) -> Unit,
    ) = viewModelScope.launch {
        repo.upgrade(targetTariffId, method, paymentMethod, subscriptionUuid)
            .onSuccess { onInit(it) }
            .onFailure { report(it) }
    }

    fun addDevices(
        scope: String,
        id: String,
        extraDevices: Int,
        method: String,
        paymentMethod: String? = null,
        onInit: (PaymentInitDto) -> Unit,
    ) = viewModelScope.launch {
        repo.addDevices(scope, id, extraDevices, method, paymentMethod)
            .onSuccess { onInit(it) }
            .onFailure { report(it) }
    }

    fun checkPromo(code: String, onResult: (PromoDto) -> Unit) = viewModelScope.launch {
        repo.checkPromo(code).onSuccess { onResult(it) }.onFailure { report(it) }
    }

    fun toggleAutoRenew(
        id: String,
        autoRenew: Boolean,
        onError: (ApiError) -> Unit = { report(it) },
        onDone: () -> Unit = {},
    ) = viewModelScope.launch {
        repo.toggleAutoRenew(id, autoRenew)
            .onSuccess {
                reloadAfterAutoRenew()
                onDone()
            }
            .onFailure { t -> onError(t as? ApiError ?: ApiError.Network(t)) }
    }

    /** Auto-renew for the active (root/primary) subscription — hits the id-less primary endpoint. */
    fun togglePrimaryAutoRenew(
        autoRenew: Boolean,
        onError: (ApiError) -> Unit = { report(it) },
        onDone: () -> Unit = {},
    ) = viewModelScope.launch {
        repo.togglePrimaryAutoRenew(autoRenew)
            .onSuccess {
                reloadAfterAutoRenew()
                onDone()
            }
            .onFailure { t -> onError(t as? ApiError ?: ApiError.Network(t)) }
    }

    /**
     * WHY TURNING AUTO-RENEW OFF DID NOT WORK, AND WHY IT DOES NOW.
     *
     * The PATCH was always fine. What put the switch back was the reload after it: the root
     * подписка's auto-renew flag does not come from `/subscription/all` or from
     * `/client/subscription` — [buildRootSub] reads it off the PROFILE, mirroring the web cabinet —
     * and the only thing the toggle refreshed was the subscription list. So the merge re-ran against
     * `_profile.value`, the copy of the profile fetched when the tab last opened, which still said
     * auto-renew was on. The list republished, the pager re-bound, `setChecked(true)` ran, and the
     * switch flipped back under the user's finger.
     *
     * The asymmetry the owner reported falls straight out of that: an account with auto-renew ON has
     * a cached profile saying ON, so turning it ON is a no-op that looks like it worked, and turning
     * it OFF is the only direction with a stale value to fight — «отключение авто списания не
     * работает».
     *
     * So the flag's own source is re-read, not just the list. Both refreshes converge whichever
     * finishes first: [refreshProfile] re-merges against the cached list, and
     * [fetchAndApplySubscriptions] re-merges against `_profile.value`. The switch therefore ends up
     * showing what the SERVER says, in both directions, which is the only thing it should ever show.
     */
    private fun reloadAfterAutoRenew() {
        refreshProfile()
        loadSubscriptions()
    }

    fun activateTrial(onDone: () -> Unit = {}) = viewModelScope.launch {
        repo.activateTrial().onSuccess { onDone() }.onFailure { report(it) }
    }

    fun renameSubscription(scope: String, id: String, name: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        repo.renameSubscription(scope, id, name).onSuccess { onDone() }.onFailure { report(it) }
    }

    /**
     * True while the core is up, so the sign-out dialog can tell the truth about the tunnel.
     *
     * **`CoreServiceManager.isRunning()` alone cannot answer this from here.** It reads
     * `coreController`, a field of an `object` — one instance PER PROCESS — and the core lives in
     * `:RunSoLibV2RayDaemon`. In the UI process that controller is a fresh Go object nobody ever
     * handed a config to, so it answers `false` for the life of the app however long the tunnel has
     * been up. Both readers here were wrong in the same direction and in ways the user sees: the
     * sign-out dialog never warned that «Выйти» would drop a live tunnel, and [logout]'s step 2
     * never actually stopped one — the core kept routing the whole device through подписки that had
     * just been deleted from under it, which is exactly the undefined state that step exists to
     * prevent.
     *
     * The answer is already on disk in MULTI_PROCESS_MODE, stamped either side of the core loop:
     * a session instant exists exactly while a tunnel does. `isRunning` is still asked first
     * because inside the daemon it is the stricter truth (during a teardown the stamp is cleared
     * before `stopLoop()` returns).
     *
     * This is the same rule as `CoreServiceManager.isTunnelUp()` on the core branch and collapses
     * to a call to it once that lands; it is written out here only because this branch does not
     * have that method yet. It must NOT be reused as a service-START guard — a stamp left behind by
     * a killed daemon would refuse the very reconnect that repairs it — and neither caller here is
     * one: one draws a sentence, the other sends a stop.
     */
    fun isTunnelRunning(): Boolean = runCatching {
        CoreServiceManager.isRunning() || CoreServiceManager.sessionStartedAt() > 0L
    }.getOrDefault(false)

    /**
     * Signs the user out.
     *
     * **There is no network call here, and there cannot be one.** This backend issues a 7-day,
     * non-refreshable JWT and exposes no logout endpoint (see [com.v2ray.ang.auth.AuthManager]'s
     * class comment: "There is NO refresh/logout here"). Sign-out is entirely local: nothing here
     * waits on a server, so there is no request to time out and no late reply to arrive after the
     * screen is gone. Local is not the same as instant, though - see the watchdog note below.
     *
     * The failure that matters is a HALF-cleared session, so the order is load-bearing:
     *
     * 1. **[AccountSession.wipe] runs first and alone.** It removes every subscription this
     *    session imported, cancels their auto-update workers, drops the token / cached profile /
     *    managed-guid map, and only then flips the state to LoggedOut. If it throws part-way, the
     *    token is still there, the user is still signed in and still connected, and the whole
     *    action is safely retryable (every step in it is idempotent).
     * 2. **Then the tunnel is stopped**, because the servers it was built from no longer exist.
     *    Left running, the core would keep routing the whole device through a subscription the
     *    user just detached from their account, under a notification naming a profile that is
     *    gone from storage, and the next launch would start with a live service and no selected
     *    server. That is the undefined state; stopping is not optional.
     *    (Removing the servers already cleared the selected-server key, see
     *    `MmkvManager.removeServerViaSubid`.)
     * 3. **Then the account-scoped local leftovers**: the in-memory [AccountCache], and the
     *    locally-picked avatar, which would otherwise show the previous person's photo to
     *    whoever signs in next on this device. The cost is that a returning user re-picks their
     *    photo; the alternative is wearing a stranger's face, which is worse.
     *
     * What deliberately survives: `AuthTokenStore.deviceId()` (so signing back in reuses the same
     * HWID slot instead of burning a device on the panel), and every server or subscription the
     * user added by hand. Those are the user's, not the account's.
     *
     * [onFailure] is invoked when step 1 threw, i.e. the user is still signed in and the caller
     * should offer a retry. It is also invoked when the wipe stops making progress: none of these
     * calls is a network call, but two of them cross a process boundary (`RemoteWorkManager` binds
     * a service to cancel the auto-update work, `stopVService` messages the core service), and a
     * binder that never comes back would otherwise leave the caller's spinner turning for ever
     * with no way out. [LOGOUT_WATCHDOG_MS] bounds the wait; the wipe itself is deliberately NOT
     * cancelled when the watchdog fires, because a half-run wipe is the one outcome this method
     * exists to prevent. It keeps running, and if it does finish it flips the session to
     * LoggedOut, which the Account tab already observes and treats exactly like a normal
     * sign-out. So the worst case is a retry offered for work that then succeeds on its own —
     * never a stuck screen, and never a torn session.
     *
     * [onFailure] is deliberately NOT invoked on success: the state flip tears the tab down and
     * the UI change is the confirmation, so there is nothing to say.
     */
    fun logout(onFailure: () -> Unit = {}) {
        viewModelScope.launch {
            // Passing NonCancellable as the context's Job detaches this coroutine from
            // viewModelScope, which is the point: the wipe must survive both the ViewModel being
            // cleared mid-flight and the watchdog below giving up on it. Nothing inside can throw
            // (every step is wrapped), so the detached job cannot fail unobserved either.
            val work = viewModelScope.async(Dispatchers.IO + NonCancellable) {
                val ok = runCatching { AccountSession.wipe() }.isSuccess
                if (ok) {
                    val app = AngApplication.application
                    runCatching { if (isTunnelRunning()) CoreServiceManager.stopVService(app) }
                    runCatching { AccountCache.invalidateAll() }
                    runCatching { AvatarManager.clearCustomAvatar(app) }
                }
                ok
            }
            // true = signed out, false = the wipe threw, null = it is still running and the
            // watchdog gave up waiting. The last two are the same thing to the user: still signed
            // in, safe to try again.
            val wiped = withTimeoutOrNull(LOGOUT_WATCHDOG_MS) { work.await() }
            if (wiped == true) clearAccountData() else onFailure()
        }
    }

    /**
     * Drops every piece of account data this ViewModel publishes, and stops the loads that would
     * refill it, so nothing can paint a signed-out screen with the previous session's values.
     *
     * Public, and separate from [logout], because the session can also drop without the user
     * asking: [AccountRepository.refreshProfile] wipes it when the identity endpoint returns 401.
     * The Account tab calls this on that transition too, so an expired token leaves no more
     * behind than an explicit sign-out does.
     */
    fun clearAccountData() {
        subsJob?.cancel()
        subsJob = null
        profileJob?.cancel()
        profileJob = null
        devicesJob?.cancel()
        devicesJob = null
        _profile.value = null
        _subscriptions.value = emptyList()
        lastPrimary = null
        lastAll = emptyList()
        hasSubData = false
        // The next account's list has not resolved either, so the tab cold-loads for it instead of
        // inheriting the previous session's "already answered".
        _subsResolved.value = false
        _payments.value = emptyList()
        _deviceCount.value = null
        _tariffs.value = emptyList()
        _error.value = null
        _loading.value = false
    }

    private companion object {
        /**
         * How long [logout] waits for a wipe that is entirely local before it stops promising the
         * user that something is happening. Generous on purpose: everything here normally lands in
         * single-digit milliseconds, so this only ever fires when a binder call is genuinely wedged,
         * and firing it early would report a failure for work that was about to succeed.
         */
        const val LOGOUT_WATCHDOG_MS = 8_000L
    }

    // endregion
}
