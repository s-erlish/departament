package com.v2ray.ang.auth

import com.v2ray.ang.auth.dto.DevicesResult
import com.v2ray.ang.auth.dto.PaymentInitDto
import com.v2ray.ang.auth.dto.PaymentRequestDto
import com.v2ray.ang.auth.dto.PaymentResultDto
import com.v2ray.ang.auth.dto.PaymentsDto
import com.v2ray.ang.auth.dto.PrimarySubscriptionDto
import com.v2ray.ang.auth.dto.PromoDto
import com.v2ray.ang.auth.dto.PublicConfigDto
import com.v2ray.ang.auth.dto.ReferralStatsDto
import com.v2ray.ang.auth.dto.ServerStatusDto
import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.auth.dto.SubscriptionAllDto
import com.v2ray.ang.auth.dto.TariffCatalogDto
import com.v2ray.ang.auth.dto.UpgradeQuoteDto
import com.v2ray.ang.auth.dto.UserProfileDto
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException

/**
 * Coroutine wrapper over [DepartamentApiClient] that returns [Result] instead of throwing, maps
 * every failure to an [ApiError], and performs the higher-level account operations the UI needs.
 *
 * The local session is ended via [AccountSession.endSession] ONLY when the authoritative identity
 * endpoint ([DepartamentApiClient.getMe], via [refreshProfile]) returns 401 — that is the single
 * reliable "the 7-day JWT is dead" signal, so a genuinely expired token self-heals into a
 * logged-out state. A 401/403 on any OTHER endpoint (a per-action permission or scope failure)
 * surfaces as a plain error and NEVER touches the session. Ending a session is not the same as
 * [AccountSession.wipe], which also deletes the imported subscriptions and belongs to an explicit
 * user logout alone.
 */
class AccountRepository(
    private val api: DepartamentApiClient = DepartamentApiClientImpl(),
    private val subs: SubscriptionSyncManager = SubscriptionSyncManager(),
) {

    /**
     * Runs an authenticated API call and normalises failures to [Result].
     *
     * Deliberately does NOT wipe the session on [ApiError.Unauthorized]: a 401 (or, before the
     * error-mapping fix, a 403) on an arbitrary endpoint must not destroy a valid login. Only
     * [refreshProfile] (the identity endpoint) is allowed to wipe — see its dedicated handling.
     */
    private suspend fun <T> guard(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: ApiError) {
            Result.failure(e)
        } catch (e: CancellationException) {
            // Coroutine cancellation (e.g. the screen closed mid-request) is not a real failure —
            // never convert it to ApiError.Network; rethrow so structured concurrency unwinds.
            throw e
        } catch (e: Exception) {
            Result.failure(ApiError.Network(e))
        }
    }

    // Public catalog / status
    suspend fun loadPublicConfig(): Result<PublicConfigDto> = guard { api.getPublicConfig() }
    suspend fun loadCatalog(): Result<TariffCatalogDto> = guard { api.getPublicTariffs() }
    suspend fun loadServerStatus(): Result<List<ServerStatusDto>> = guard { api.getServerStatus() }

    // Profile
    //
    // getMe() is the authoritative identity check: it is the ONLY endpoint whose 401 reliably
    // means the JWT is dead (expired/revoked). A 401 here — and only here — ends the local
    // session so an expired 7-day token self-heals into a logged-out state. Every other failure
    // (network, 403, 5xx, or a 401 on some other endpoint) leaves the session intact.
    suspend fun refreshProfile(): Result<UserProfileDto> {
        // A 401 IS ONLY AN ANSWER ABOUT THE TOKEN THAT WAS ACTUALLY SENT.
        //
        // The Bearer header is attached by an OkHttp interceptor that reads [AuthTokenStore], and
        // that store is allowed to answer "not right now": a Keystore that is briefly busy makes
        // [AuthTokenStore.store] null, and null is documented to read exactly like a signed-out
        // device. The request then goes out with NO Authorization header at all, the backend
        // answers 401 because it must, and the old code read that as «этот токен мёртв» and ended
        // a session whose token was sitting on disk, intact, the whole time. From the outside that
        // is «вылетает аккаунт» — at random, with the подписки still there, healing itself on the
        // next launch when the store opens again.
        //
        // The same hole is open to any caller that reaches this without a session: the payment
        // polls in AccountFragment / BuyTariffActivity re-ask for the profile every 8 seconds for
        // ~40, and neither re-checks that the user is still signed in between rounds.
        //
        // So the token is read BEFORE the call and the verdict is conditioned on it. No token
        // sent, no verdict about a token: the failure surfaces as a plain error and the session is
        // left alone for the next attempt, which is the one that can actually prove anything.
        val sentToken = !AuthTokenStore.getToken().isNullOrBlank()
        return try {
            val profile = api.getMe()
            AccountSession.updateProfile(profile)
            //  САМОЛЕЧЕНИЕ ОТМЕТКИ О ПЕРВОМ ВХОДЕ. Она ставится доводкой после регистрации, а та
            //  идёт вне ожидания — значит процесс может умереть между «сессия поднята» и «отметка
            //  ушла», и досылать её станет некому. Симптом тихий и живучий: «Способы входа» зовут
            //  задать пароль тому, у кого он есть.
            //
            //  Здесь для этого лучшее место: профиль и так перечитывается, а пара «пароль есть,
            //  первый вход не закрыт» — единственный признак, что отметка не дошла. Запрос уходит
            //  только у таких аккаунтов и только пока не удастся: со следующим профилем условие
            //  перестаёт выполняться само.
            if (profile.hasPassword && !profile.onboardingCompleted) {
                AuthManager.settleOnboarding(api)
            }
            Result.success(profile)
        } catch (e: ApiError.Unauthorized) {
            if (sentToken) {
                logTokenDeath()
                AccountSession.endSession()
            }
            Result.failure(e)
        } catch (e: ApiError) {
            Result.failure(e)
        } catch (e: CancellationException) {
            // Cancellation is not an auth failure — do NOT end the session; rethrow to unwind.
            throw e
        } catch (e: Exception) {
            Result.failure(ApiError.Network(e))
        }
    }

    /**
     * One log line at the moment a session dies, saying whether the token had actually reached the
     * expiry it carries.
     *
     * The owner's position is that «токен всегда должен быть, пока нет выхода из аккаунта», so a
     * weekly sign-out is a defect — but the two shapes of that defect need opposite repairs, and
     * nothing outside the app can tell them apart. A 401 that arrives AT the token's own `exp` is
     * the seven-day term simply running out, and only the panel can fix that (a longer term, or a
     * refresh endpoint; this backend exposes neither today). A 401 that arrives BEFORE `exp` is the
     * panel revoking the session early — another sign-in, a password change, a restart that dropped
     * its keys — which is a different conversation entirely.
     *
     * Neither the token nor any claim but the instant reaches the log. @see
     * AuthTokenStore.tokenExpiresAtSeconds
     */
    private fun logTokenDeath() {
        val expiresAt = AuthTokenStore.tokenExpiresAtSeconds()
        if (expiresAt == null) {
            LogUtil.i(AppConfig.TAG, "Session ended: the identity endpoint refused the token (no exp claim to compare)")
            return
        }
        val remaining = expiresAt - System.currentTimeMillis() / 1000
        val verdict = if (remaining > 0) {
            "revoked EARLY, ${remaining}s before its own exp"
        } else {
            "expired on time, ${-remaining}s past its own exp"
        }
        LogUtil.i(AppConfig.TAG, "Session ended: the identity endpoint refused the token — $verdict")
    }

    // Subscriptions
    /** The authoritative ACTIVE (root) subscription — /all often returns no root item. */
    suspend fun loadPrimarySubscription(): Result<PrimarySubscriptionDto> = guard { api.getPrimarySubscription() }
    suspend fun loadSubscriptions(): Result<SubscriptionAllDto> = guard { api.getSubscriptionAll() }

    /**
     * Fetches the account's subscriptions and imports them into the local plumbing. Returns the
     * local guids so the caller (UI) can reload its server list.
     *
     * **Both endpoints are asked, and neither alone is enough.** The connect payload — the
     * Remnawave record carrying `subscriptionUrl`, which is the only thing an import can act on —
     * lives on `GET /client/subscription`, not on `/client/subscription/all` (see
     * [com.v2ray.ang.auth.dto.SubInfoDto]: the field is documented as absent there). Importing from
     * `/all` alone therefore found nothing to import for the commonest account of all — one active
     * подписка, no secondaries — and the user got the onboarding «Купить» screen while the Аккаунт
     * tab showed their real, paid подписка. Conversely `/all` is the only source of the secondary
     * subscriptions and of the ids the root entry needs, so the two are merged.
     *
     * A failure of one is survivable; the import runs on whatever answered. Only when both fail is
     * this a failure, and then nothing is imported and nothing is pruned.
     *
     * **A run that lost `/all` may not delete anything**, and that is what `prune` carries. `/all`
     * is the only endpoint that lists the SECONDARY подписки; the summary describes the active one
     * and nothing else. So when `/all` was the endpoint that failed, the candidate list is exactly
     * one item — the root — and the reconcile step used to read that as «остальные подписки
     * удалены»: every secondary was cancelled and removed, its серверы with it, because one of two
     * endpoints had a bad minute. Surviving a partial outage is why both are asked at all.
     */
    suspend fun autoImportSubscriptions(): Result<List<String>> {
        val allResult = guard { api.getSubscriptionAll() }
        val primaryResult = guard { api.getPrimarySubscription() }

        val allError = allResult.exceptionOrNull()
        val primaryError = primaryResult.exceptionOrNull()
        if (allError != null && primaryError != null) {
            return Result.failure(allError)
        }

        val candidates = importCandidates(
            primary = primaryResult.getOrNull(),
            all = allResult.getOrNull()?.items.orEmpty(),
        )
        return guard { subs.importAll(candidates, prune = allResult.isSuccess) }
    }

    /**
     * Merges the two subscription payloads into the list the importer consumes: the active/root
     * subscription first, enriched with the connect payload that only the primary endpoint carries,
     * then the secondaries from `/all` exactly as they arrived.
     *
     * The root keeps `/all`'s identity fields when it has them, so renew/upgrade still address it by
     * the id the backend expects. It is NOT what the import remembers it by: the two endpoints do
     * not agree on an identifier for the root, so [SubscriptionSyncManager] keys it by its type
     * instead and a run where only one endpoint answered still updates the same подписка in place.
     */
    private fun importCandidates(
        primary: PrimarySubscriptionDto?,
        all: List<SubInfoDto>,
    ): List<SubInfoDto> {
        val rootType = SubscriptionSyncManager.TYPE_ROOT
        val rootFromAll = all.firstOrNull { it.type.equals(rootType, ignoreCase = true) }
        val secondaries = all.filter { !it.type.equals(rootType, ignoreCase = true) }

        val rootCandidate = when {
            primary?.hasActiveSubscription() == true -> (rootFromAll ?: SubInfoDto(type = rootType)).copy(
                subscription = primary.subscription ?: rootFromAll?.subscription,
                tariffDisplayName = primary.tariffDisplayName?.takeIf { it.isNotBlank() }
                    ?: rootFromAll?.tariffDisplayName,
            )

            else -> rootFromAll
        }
        return listOfNotNull(rootCandidate) + secondaries
    }

    suspend fun renameSubscription(scope: String, id: String, name: String): Result<Unit> =
        guard { api.renameSubscription(scope, id, name) }

    suspend fun getQr(remnawaveUuid: String): Result<ByteArray> = guard { api.getSubscriptionQr(remnawaveUuid) }

    // Purchase / renew / upgrade / devices
    suspend fun buy(req: PaymentRequestDto): Result<PaymentInitDto> = guard { api.payPlatega(req) }
    suspend fun payWithBalance(req: PaymentRequestDto): Result<PaymentResultDto> = guard { api.payBalance(req) }

    /** Renew is a Platega purchase of the given tariff/price-option for an existing subscription. */
    suspend fun renew(req: PaymentRequestDto): Result<PaymentInitDto> = guard { api.payPlatega(req) }

    suspend fun upgradeQuote(targetTariffId: String): Result<UpgradeQuoteDto> =
        guard { api.getUpgradeQuote(targetTariffId) }

    suspend fun upgrade(
        targetTariffId: String,
        method: String,
        paymentMethod: String? = null,
        subscriptionUuid: String,
    ): Result<PaymentInitDto> = guard { api.upgrade(targetTariffId, method, paymentMethod, subscriptionUuid) }

    suspend fun addDevices(
        scope: String,
        id: String,
        extraDevices: Int,
        method: String,
        paymentMethod: String? = null,
    ): Result<PaymentInitDto> = guard { api.addDevices(scope, id, extraDevices, method, paymentMethod) }

    suspend fun getDevices(remnawaveUuid: String): Result<DevicesResult> = guard { api.getDevices(remnawaveUuid) }
    suspend fun deleteDevice(hwid: String, remnawaveUuid: String): Result<Unit> =
        guard { api.deleteDevice(hwid, remnawaveUuid) }

    // Payments history
    suspend fun getPayments(): Result<PaymentsDto> = guard { api.getPayments() }

    // Promo / trial / auto-renew / referral
    suspend fun checkPromo(code: String): Result<PromoDto> = guard { api.checkPromo(code) }
    suspend fun activatePromo(code: String): Result<Unit> = guard { api.activatePromo(code) }
    suspend fun activateTrial(): Result<Unit> = guard { api.activateTrial() }
    suspend fun toggleAutoRenew(id: String, autoRenew: Boolean): Result<Unit> =
        guard { api.setSecondaryAutoRenew(id, autoRenew) }

    /** Auto-renew of the active (root/primary) subscription — targets the id-less primary endpoint. */
    suspend fun togglePrimaryAutoRenew(autoRenew: Boolean): Result<Unit> =
        guard { api.setPrimaryAutoRenew(autoRenew) }
    suspend fun getReferralStats(): Result<ReferralStatsDto> = guard { api.getReferralStats() }
}
