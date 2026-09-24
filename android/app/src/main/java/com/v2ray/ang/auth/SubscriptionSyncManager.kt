package com.v2ray.ang.auth

import com.v2ray.ang.auth.dto.SubInfoDto
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bridges the backend subscription payloads into the app's EXISTING subscription plumbing.
 *
 * This is the only place that touches the existing config code, and it reuses rather than
 * duplicates it:
 *  - [MmkvManager.encodeSubscription] / [MmkvManager.decodeSubscription] persist each item
 *  - [AngConfigManager.updateConfigViaSub] fetches + parses the servers (no new parsing)
 *  - [SubscriptionUpdater.syncOne] / [SubscriptionUpdater.cancelOne] handle periodic refresh
 *
 * The uuid->guid mapping is owned by [AuthTokenStore]; MmkvManager does not return the guid it
 * generates, so we generate a stable one ourselves on first import and remember it.
 */
class SubscriptionSyncManager {

    companion object {
        /** The `{scope}` value of the account's single active subscription. */
        const val TYPE_ROOT = "root"

        /**
         * ONE import at a time, process-wide.
         *
         * Every caller builds its own [SubscriptionSyncManager] (through its own
         * [AccountRepository]), so an instance lock would lock nothing: the start-up import in
         * `HomeFragment.onLoggedIn`, the one the Аккаунт tab runs after a purchase and the one
         * behind «Загрузить серверы» are three different objects and they overlap by design — a
         * sign-in that is followed straight away by a tap runs two of them within the same second.
         *
         * Two concurrent runs read the SAME uuid->guid map, both miss the entry for a подписка
         * neither has written yet, and both mint a fresh guid for it: two rows for one подписка,
         * two sets of серверы, and whichever run finishes second overwrites the map — orphaning
         * the first run's row, which no later prune can find because its uuid is no longer keyed
         * to it. Serialising is what makes the second run an UPDATE of what the first one wrote.
         */
        private val importMutex = Mutex()
    }

    /**
     * Imports/updates every subscription in [items], removes locally any managed subscription no
     * longer present remotely, and returns the local guids of the current managed set (so the UI
     * can reload its server list).
     *
     * [items] must be the merged candidate list from [AccountRepository.autoImportSubscriptions],
     * not the raw `/client/subscription/all` payload: `/all` items carry no connect payload, so
     * every one of them would be skipped here.
     *
     * @param prune whether [items] is an AUTHORITATIVE picture of the account, i.e. whether
     * `/client/subscription/all` — the only endpoint that lists the secondary подписки — actually
     * answered. False when it did not: the run then updates what it saw and removes nothing, so a
     * partial outage cannot be mistaken for «этих подписок больше нет». @see reconcile
     */
    suspend fun importAll(items: List<SubInfoDto>, prune: Boolean = true): List<String> =
        importMutex.withLock { runImport(items, prune) }

    private suspend fun runImport(items: List<SubInfoDto>, prune: Boolean): List<String> = withContext(Dispatchers.IO) {
        val managed = AuthTokenStore.getManagedGuids()
        val newMap = HashMap<String, String>()
        val resultGuids = ArrayList<String>()

        for (info in items) {
            // .raw(), not .response: the backend nests the Remnawave record under `response` or
            // under `data.response` depending on the endpoint, and the wrapper is what knows both.
            // Reading one field directly silently skipped every item served in the other shape.
            val raw = info.subscription?.raw() ?: continue
            val url = raw.subscriptionUrl
            if (url.isBlank()) continue

            val uuid = identityOf(info, url)
            val guid = managed[uuid]?.ifBlank { null } ?: Utils.getUuid()

            // НОВАЯ ПОДПИСКА ВСТАЁТ В ВЫБРАННОЕ РАСПИСАНИЕ, А ПРЕЖНЯЯ СВОЁ СОХРАНЯЕТ. Здесь стояло
            // `autoUpdate = true` на каждом импорте, а импорт идёт при каждом входе, после покупки и
            // по «Загрузить серверы». Кто выбрал в настройках «Выключено», получал автообновление
            // обратно при первом же из них - молча, и строка настроек начинала показывать «1 час».
            val item = (MmkvManager.decodeSubscription(guid)
                ?: SubscriptionItem().also { SubscriptionUpdater.applyCurrentSchedule(it) }).apply {
                // THE ПОДПИСКА'S OWN NICKNAME, AND NOTHING THAT ONLY LOOKS LIKE ONE.
                //
                // `displayName` is the label the user set in the cabinet and `defaultLabel` is the
                // backend's per-sub placeholder («Подписка #2») — both name THIS подписка.
                // `tariffDisplayName` does not: for this deployment it is the generic service name
                // «departament vpn», the same string on every подписка, and [SubInfoDto] already
                // filters it out of the tariff badge for exactly that reason. Stamping it here is
                // what put «departament vpn» on the owner's card instead of his подписка's real
                // name, because the remark then outranked the провайдер's own `profile-title`
                // («🍀 erlish») for the rest of that подписка's life.
                //
                // With no nickname the remark is left BLANK on purpose. `updateConfigViaSub` runs
                // three lines below and adopts the `profile-title` header as the remark while the
                // remark is still blank — so the провайдер's real name lands automatically, which
                // is the only route left now that editing a подписка is not a feature
                // (OWNER-DECISION-2026-08-02 §5: «the naming has to be right at import time»).
                // The old literal fallback also spelled the brand with capitals, against B2.
                remarks = info.displayName?.takeIf { it.isNotBlank() }
                    ?: info.defaultLabel?.takeIf { it.isNotBlank() }
                    ?: ""
                this.url = url
                enabled = true
                // No per-subscription User-Agent is stamped here on purpose. The fetch resolves
                // per-sub -> global (provider screen) -> operator default itself, and the per-sub
                // tier wins absolutely — so stamping the operator default made the provider
                // screen's User-Agent row dead UI for exactly the subscriptions this deployment
                // creates, and overwrote a value typed in SubEditActivity on every sign-in and
                // account refresh. Leaving it unset resolves to the same operator default, but
                // now an override can actually reach the request.
                //
                // Earlier builds did stamp it, and that stamp is still there on every upgraded
                // install — sitting in the tier that wins absolutely, which is what defeated the
                // fix above for exactly the users who already had the app. It was written by an
                // OLDER build, so it cannot be recognised by comparing against today's resolved
                // default; [BackendConfig.isAppStampedUserAgent] knows every string this app has
                // ever stamped. Anything else was typed by the user and is kept.
                userAgent = userAgent?.takeIf { !BackendConfig.isAppStampedUserAgent(it) }
            }

            MmkvManager.encodeSubscription(guid, item)
            // Fetch first, schedule second — the order is load-bearing. A successful fetch stamps
            // `lastUpdated`, and syncOne derives the worker's initial delay from it, so the timer
            // starts a full interval out. Reversed, that delay would be 0 and the periodic worker
            // would fire at once, pulling the same subscription twice on every sign-in. A failed
            // fetch leaves `lastUpdated` untouched and the worker does run promptly — that retry is
            // the point, not the duplicate above.
            AngConfigManager.updateConfigViaSub(SubscriptionCache(guid, item))
            SubscriptionUpdater.syncOne(subId = guid)

            newMap[uuid] = guid
            resultGuids.add(guid)
        }

        reconcile(managed = managed, seen = newMap, prune = prune)
        resultGuids
    }

    /**
     * Writes back the uuid->guid map and removes what the account no longer has.
     *
     * TWO CONDITIONS GATE THE REMOVAL, and both were paid for in lost серверы.
     *
     * [seen] must be non-empty. An empty result is not evidence that the account has none: a
     * payload shape we could not read, a partial outage, or a signed-in account whose connect
     * payload simply did not arrive all produce the same empty map, and pruning on that deletes
     * every подписка and every сервер the user has, unrecoverably.
     *
     * [prune] must be true, i.e. `/client/subscription/all` answered. It is the ONLY source of the
     * secondary подписки — `/client/subscription` describes the active one and nothing else — so a
     * run that lost `/all` and kept the summary sees exactly one candidate, the root. That run
     * used to look indistinguishable from «остальные подписки удалены»: every secondary was
     * cancelled and deleted, серверы and all, because one of two endpoints had a bad minute.
     * Surviving a partial outage is the whole reason both are asked; it cannot be paid for with
     * the user's подписки.
     *
     * The map is still written in the non-authoritative case, merged over what was already there:
     * a подписка imported under a fresh guid has to be remembered by uuid immediately or the next
     * run mints a second guid for it and the account grows a duplicate row.
     */
    private fun reconcile(managed: Map<String, String>, seen: Map<String, String>, prune: Boolean) {
        if (seen.isEmpty()) return
        if (!prune) {
            AuthTokenStore.setManagedGuids(managed + seen)
            return
        }
        for ((uuid, guid) in managed) {
            if (!seen.containsKey(uuid)) {
                SubscriptionUpdater.cancelOne(subId = guid)
                MmkvManager.removeSubscription(guid)
            }
        }
        AuthTokenStore.setManagedGuids(seen)
    }

    /**
     * The key this subscription is remembered under in the uuid->guid map, i.e. what makes a
     * re-import an UPDATE of the same провайдер instead of a second copy of it.
     *
     * The root subscription is keyed by a constant, because an account has exactly one and the two
     * endpoints that describe it do not agree on an identifier: `/subscription/all` carries an id,
     * the `/subscription` summary carries none, and the merge synthesises the root from whichever
     * answered. Keying it by "whatever identifier arrived" therefore made the SAME подписка look
     * like a different one the moment `/all` was the endpoint that failed — the import re-added it
     * under a fresh guid, and the prune below then deleted the original, taking that провайдер's
     * серверы and the selected one with it. Surviving a partial outage is the whole point of asking
     * both endpoints; it cannot be paid for with the user's server list.
     *
     * Secondaries keep their own identity: `/all` is their only source and it always carries the id.
     */
    private fun identityOf(info: SubInfoDto, url: String): String {
        if (info.type.equals(TYPE_ROOT, ignoreCase = true)) return TYPE_ROOT
        return info.remnawaveUuid.ifBlank { info.id }.ifBlank { url }
    }

    /**
     * Removes every managed subscription and cancels their auto-update tasks. Invoked only from
     * [AccountSession.wipe], i.e. an explicit user logout.
     *
     * A dead JWT deliberately does NOT come through here any more: an expired 7-day token is not
     * the user asking to give up their подписки, and treating it as one deleted every сервер on the
     * device the first time the Аккаунт tab noticed. That path is [AccountSession.endSession].
     *
     * Takes the same lock an import does, so a sign-out cannot interleave with one. Untaken, the
     * import's own writes land AFTER the removal — it read the map before the wipe and re-encodes
     * every подписка it was already fetching — and the device is left signed out with the previous
     * account's подписки back on it, keyed to a guid map that was just emptied.
     */
    suspend fun removeAllManaged() = importMutex.withLock {
        val managed = AuthTokenStore.getManagedGuids()
        for ((_, guid) in managed) {
            if (guid.isBlank()) continue
            SubscriptionUpdater.cancelOne(subId = guid)
            MmkvManager.removeSubscription(guid)
        }
        AuthTokenStore.setManagedGuids(emptyMap())
    }
}
