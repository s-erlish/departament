package com.v2ray.ang.auth

import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.HttpUtil

/**
 * Central configuration for the Departament VPN backend + Telegram bot.
 *
 * Base URL is `https://web.departament.site/api`; every path in [Endpoints] is relative to it.
 * JWT auth uses `Authorization: Bearer <token>` (7-day token, NO refresh endpoint).
 *
 * IMPORTANT: login is OPTIONAL. When [isConfigured] is false the app must remain fully
 * usable without any backend — callers only offer/require login when configured.
 */
object BackendConfig {

    /**
     * The `SUB_USER_AGENT` value earlier builds shipped — our branding, not a client string any
     * VPN panel knows. Those builds also STAMPED it onto every managed subscription, so it is
     * still sitting in the highest precedence tier on upgraded installs; [isAppStampedUserAgent]
     * exists to recognise and drop it. The build no longer ships it (see `build.gradle.kts`).
     */
    private const val LEGACY_BRANDING_USER_AGENT = "DepartamentVPN/1.0"

    /**
     * `v2rayNG/<version>` for ANY version — the shape of
     * [HttpUtil.DEFAULT_SUBSCRIPTION_USER_AGENT] in every build this app ever had, so a stamp left
     * by a build with a different `versionName` is recognised too.
     */
    private val APP_DEFAULT_USER_AGENT_SHAPE = Regex("""v2rayNG/\d+(\.\d+)*""", RegexOption.IGNORE_CASE)

    /** Backend base URL, e.g. https://web.departament.site/api (no trailing slash). */
    val baseUrl: String get() = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    /** Telegram bot username without the leading '@', e.g. departament_vpn_bot. */
    val botUsername: String get() = BuildConfig.BOT_USERNAME

    /**
     * User-Agent used for API + subscription requests (negotiates the response format).
     *
     * Panels pick the subscription format — XRAY_JSON template vs base64 link list — from this
     * header and map it themselves, so which string yields the template is a property of the
     * OPERATOR'S panel, not of this app: `SUB_USER_AGENT` is that operator's knob and whatever it
     * holds is sent verbatim. Blank means "not configured" and falls back to
     * [HttpUtil.DEFAULT_SUBSCRIPTION_USER_AGENT], the client string every panel recognises as this
     * client (answered with the base64 link list — see `build.gradle.kts` for why that is the
     * shipped default).
     *
     * The one value that is refused is one that cannot travel in a header, and that is a hard
     * limit rather than a preference: this property is not only a subscription fallback —
     * [DepartamentApiClientImpl] sends it verbatim as the API `User-Agent` — and OkHttp throws
     * while BUILDING the request on a non-ASCII value, so a Cyrillic string here would take down
     * every backend call, not just format negotiation.
     */
    val subscriptionUserAgent: String
        get() = BuildConfig.SUB_USER_AGENT.trim()
            .takeIf { it.isNotBlank() && HttpUtil.isHeaderSafe(it) }
            ?: HttpUtil.DEFAULT_SUBSCRIPTION_USER_AGENT

    /**
     * True when [value] is a User-Agent THIS APP put on a subscription rather than one a person
     * typed — i.e. it carries no user intent and a caller may drop it.
     *
     * Covers every string the app itself has ever stamped or defaulted to: the legacy branding
     * value, the current operator value, and `v2rayNG/<version>` of any build. Matching by shape
     * matters because the stamp on an upgraded install was written by an OLDER build, so comparing
     * against today's resolved value alone silently matches nothing — which is exactly how the
     * stamp survived into the highest precedence tier and defeated the whole User-Agent chain.
     */
    fun isAppStampedUserAgent(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty()
            || v.equals(LEGACY_BRANDING_USER_AGENT, ignoreCase = true)
            || v.equals(subscriptionUserAgent, ignoreCase = true)
            || v.equals(HttpUtil.DEFAULT_SUBSCRIPTION_USER_AGENT, ignoreCase = true)
            || APP_DEFAULT_USER_AGENT_SHAPE.matches(v)
    }

    /** True only when a backend base URL has been provided at build time. */
    fun isConfigured(): Boolean = BuildConfig.BACKEND_BASE_URL.isNotBlank()

    /** Relative paths (appended to [baseUrl]). Parameterized paths are built via helper funcs. */
    object Endpoints {
        // Public
        const val publicConfig = "/public/config"
        const val publicTariffs = "/public/tariffs"
        const val serverStatus = "/public/server-status"

        // Auth
        const val telegramLoginToken = "/client/auth/telegram-login-token"
        const val telegramLoginCheck = "/client/auth/telegram-login-check"
        const val register = "/client/auth/register"
        const val login = "/client/auth/login"
        const val twoFaLogin = "/client/auth/2fa-login"
        const val googleLogin = "/client/auth/google"
        const val me = "/client/auth/me"

        /**
         * **«Восстановить пароль».** `{email}` in, 200 `{message}` out, and the same 200 whether
         * the address has an account behind it or not: the panel refuses to say which, so nobody
         * can use this endpoint to find out who is registered. The app must not undo that by
         * phrasing its answer as a fact, which is why the screen after it says «если аккаунт
         * существует» rather than «письмо отправлено на …».
         *
         * The letter carries a LINK, and the link opens the SITE, where the new password is typed.
         * The panel has a `password-reset/consume` beside this one and the app deliberately does
         * not call it: it never sees the token in the letter, so there is nothing to consume and
         * nothing to poll for either. A password change moves nothing on the profile, so the wait
         * that follows the OTHER letters this app sends would have no question to ask here.
         */
        const val passwordResetRequest = "/client/auth/password-reset/request"

        /**
         * Attach an e-mail to the session already in flight: `{email}` in, a letter carrying a
         * LINK out. Deliberately NOT under `/client/auth` — the panel puts it on the client root,
         * because it is an errand of an account that exists rather than a way of getting one.
         *
         * There is no confirmation endpoint here on purpose. The link in the letter opens the
         * SITE, and the site calls `/client/auth/verify-link-email` with the token in it; the app
         * never sees that token and never posts it. What the app watches instead is [me], which
         * starts answering with a non-blank `email` the moment the link is opened.
         */
        const val linkEmailRequest = "/client/link-email-request"

        /**
         * Give the account a password, so the address attached above becomes a way IN and not only
         * a label. `{newPassword}`, **minimum six characters** — the panel's schema for THIS
         * endpoint; registration's is eight, and the two are not interchangeable.
         *
         * Refused with «Пароль уже установлен. Используйте смену пароля.» exactly when the account
         * has a real password and has finished onboarding, which is what
         * `UserProfileDto.canSetPassword` mirrors so the step is never offered into a refusal.
         */
        const val setPassword = "/client/set-password"

        /**
         * Marks the account's onboarding finished. Body-less; the whole effect is
         * `onboardingCompleted = true`.
         *
         * It is the SECOND half of whatever gave the account its password, not an errand of its
         * own. The panel refuses a set-password only when `passwordHash && onboardingCompleted`, so
         * leaving the flag alone keeps the endpoint open on an account that already has a password:
         * the step could be walked twice, and the app's idea of the account would drift from the
         * site's.
         *
         * Which is why a REGISTRATION sends it too. The panel creates the client with the flag
         * false on both of its registration paths — `/client/auth/register` with e-mail
         * verification off, and `/client/auth/verify-email` — even though the password travelled in
         * that very request. Without the flag the account is born permanently eligible for a
         * password step it does not need.
         */
        const val completeOnboarding = "/client/complete-onboarding"

        /**
         * Replace the address the account already has: `{newEmail, currentPassword?}`. The password
         * is the panel's account-takeover guard and is required only of accounts that HAVE one.
         *
         * Answers with a letter, exactly as [linkEmailRequest] does, and the link in it lands on
         * the same `verify-link-email` on the site. So the app watches the same [me] afterwards —
         * but for the address becoming the NEW one, not merely for it being non-blank.
         */
        const val changeEmailRequest = "/client/profile/change-email/request"

        // Subscription
        /** The authoritative ACTIVE (root) subscription summary — richer than the /all root item. */
        const val subscription = "/client/subscription"
        const val subscriptionAll = "/client/subscription/all"
        const val subscriptionQr = "/client/subscription/qr"
        const val upgradeQuote = "/client/subscriptions/upgrade-quote"
        const val upgrade = "/client/subscriptions/upgrade"
        fun renameSubscription(scope: String, id: String) = "/client/subscription/$scope/$id/name"
        fun addDevices(scope: String, id: String) = "/client/subscription/$scope/$id/add-devices"

        // Devices
        const val devices = "/client/devices"
        const val deleteDevice = "/client/devices/delete"

        // Payments
        const val payPlatega = "/client/payments/platega"
        const val payBalance = "/client/payments/balance"
        const val payments = "/client/payments"

        // Promo / trial / referral
        const val promoCheck = "/client/promo-code/check"
        const val promoActivate = "/client/promo-code/activate"
        const val trial = "/client/trial"
        const val referralStats = "/client/referral-stats"
        fun secondaryAutoRenew(id: String) = "/client/secondary-subscriptions/$id/auto-renew"

        /** Auto-renew of the ACTIVE (root/primary) subscription — no id in the path. */
        const val primaryAutoRenew = "/client/subscription/auto-renew"
    }
}
