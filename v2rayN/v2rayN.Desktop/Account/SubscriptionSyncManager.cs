using ServiceLib.Helper;
using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.Account;

/// <summary>
/// Bridges the backend subscription payloads into the app's EXISTING subscription plumbing. Port of
/// V2rayNG auth/SubscriptionSyncManager.kt — it REUSES the engine rather than duplicating it:
///  - <see cref="ConfigHandler.AddSubItem(Config, SubItem)"/> persists each subscription row;
///  - <see cref="SubscriptionHandler.UpdateProcess"/> fetches + parses + imports the servers, FORCING
///    a v2rayNG-family User-Agent (the panel keys the "real server list vs app-not-supported" response
///    off it — the branding UA gets the wrong content);
///  - <see cref="ConfigHandler.DeleteSubItem(Config, string)"/> drops a managed sub gone remotely.
///
/// KEY FACT (why the previous import fetched nothing): the connect URL (<c>subscriptionUrl</c>) lives
/// ONLY on GET /client/subscription — the authoritative ACTIVE/primary subscription. The
/// /client/subscription/all items arrive WITHOUT a <c>subscription</c>/<c>remnawaveUuid</c> block, so
/// importing from /all alone yields no URL and therefore no servers. This manager fetches the PRIMARY
/// summary for the real URL (and merges any /all item that happens to expose its own URL), sets it as
/// the created <see cref="SubItem.Url"/>, and lets the normal subscription update download the servers.
///
/// The uuid-&gt;guid mapping is owned by <see cref="AuthTokenStore"/>.
/// </summary>
public sealed class SubscriptionSyncManager
{
    /// <summary>
    /// v2rayNG-family User-Agent stamped on every account-imported subscription. The departament
    /// panel keys its managed server list off a recognised v2rayNG client, so this must stay a
    /// v2rayNG UA — it is passed through verbatim by <see cref="SubscriptionHandler"/>. Single source
    /// of truth: <see cref="Global.SubscriptionUserAgent"/>, the SAME literal the manual-add path
    /// forces, so the account and manual fetches send byte-identical User-Agents.
    /// </summary>
    private const string AccountSubscriptionUserAgent = Global.SubscriptionUserAgent;

    /// <summary>
    /// One import at a time, process-wide. Two syncs can legitimately be asked for at once — the
    /// startup sync (AccountViewModel.StartupLoad) and a purchase settling (BuyViewModel) are separate
    /// call chains — and they raced on shared state: both read the uuid-&gt;guid map and the SubItem list
    /// BEFORE either had written, so neither found the other's row, both handed
    /// <c>ConfigHandler.AddSubItem</c> a SubItem with a blank Id, and AddSubItem assigns a fresh guid to
    /// any blank Id (it does not de-duplicate by URL — that check exists only on the paste/clipboard
    /// overload). The result was TWO rows for one subscription, the same servers imported twice under
    /// two subids, and whichever sync wrote the map last orphaned the other row: no longer managed, so
    /// never reconciled and never removable by logout. Serialising costs one short wait and makes the
    /// second run see the first one's rows, which is exactly what the reuse-by-guid path needs.
    /// </summary>
    private static readonly SemaphoreSlim _importGate = new(1, 1);

    private readonly IDepartamentApiClient _api;

    public SubscriptionSyncManager(IDepartamentApiClient? api = null)
    {
        _api = api ?? new DepartamentApiClient();
    }

    /// <summary>
    /// GETs the account's subscriptions (primary summary + /all), imports/updates each into the local
    /// plumbing, removes any managed subscription no longer present remotely, and returns the local
    /// guids of the current managed set (so the caller can reload its server list).
    /// </summary>
    public async Task<List<string>> ImportAll()
    {
        await _importGate.WaitAsync();
        try
        {
            return await ImportAllCore();
        }
        finally
        {
            _importGate.Release();
        }
    }

    private async Task<List<string>> ImportAllCore()
    {
        // The PRIMARY summary is the authoritative source of the real connect URL. A "no active
        // subscription" account returns a 200 with an empty subscription (not an error), so the
        // best-effort try/catch only swallows genuine transient failures — the subsequent
        // AccountViewModel.FetchAndApplySubscriptions surfaces those to the UI.
        //
        // `authoritative` records whether we actually LEARNED the remote state. Every transport
        // failure (offline, DNS not up yet, timeout, 5xx, unparseable body) is normalised to an
        // ApiError by DepartamentApiClient, so a swallowed ApiError means "we know nothing", NOT
        // "the account has no subscriptions". Import must never infer a deletion from that — see
        // the reconciliation guard in Import.
        var authoritative = true;

        PrimarySubscriptionDto? primary = null;
        try
        {
            primary = await _api.GetPrimarySubscription();
        }
        catch (ApiError)
        {
            // fall through — still import anything /all exposes
            authoritative = false;
        }

        List<SubInfoDto> all;
        try
        {
            all = (await _api.GetSubscriptionAll()).Items;
        }
        catch (ApiError)
        {
            all = new List<SubInfoDto>();
            authoritative = false;
        }

        // A 200 is not by itself an answer we can reconcile against. The prune below reads "no
        // candidate for this guid" as "gone from the account", and the ONLY payload entitled to say
        // that is one that reports no active subscription at all. When the primary summary says the
        // account HAS an active subscription but we cannot read a connect URL out of it — an envelope
        // shape this build does not know, a record still being provisioned, a truncated proxy reply —
        // the candidate list comes out empty for want of a URL, not because the subscription ended.
        // Reconciling on that deleted the subscription and, through DeleteSubItem ->
        // RemoveServersViaSubid, every server behind it. We learned nothing here, so we change nothing.
        if (primary?.HasActiveSubscription() == true && primary.Raw()?.SubscriptionUrl.IsNullOrEmpty() != false)
        {
            authoritative = false;
        }

        var profile = AuthTokenStore.GetUser();
        return await Import(primary, all, profile, authoritative);
    }

    private async Task<List<string>> Import(PrimarySubscriptionDto? primary, List<SubInfoDto> all, UserProfileDto? profile, bool authoritative)
    {
        var config = AppManager.Instance.Config;
        var managed = AuthTokenStore.GetManagedGuids();
        var existing = await AppManager.Instance.SubItems() ?? new List<SubItem>();
        var newMap = new Dictionary<string, string>();
        var resultGuids = new List<string>();

        foreach (var candidate in BuildCandidates(primary, all, profile))
        {
            // Reuse the guid we already manage for this uuid, else an existing SubItem with the same
            // URL, else create a new row (AddSubItem assigns the guid).
            SubItem? item = null;
            if (managed.TryGetValue(candidate.Uuid, out var mappedGuid) && mappedGuid.IsNotEmpty())
            {
                item = await AppManager.Instance.GetSubItem(mappedGuid);
            }
            item ??= existing.FirstOrDefault(s => s.Url == candidate.Url);
            item ??= new SubItem { Id = string.Empty, Url = candidate.Url };

            // THE SUBSCRIPTION'S OWN NAME — AND NOTHING THAT MERELY LOOKS LIKE ONE.
            //
            // Only a name that identifies THIS subscription is stamped (the cabinet nickname; see
            // BuildCandidates). When there is none the remark is left BLANK on purpose: the fetch two
            // dozen lines below brings the provider's own `profile-title` («🍀 erlish»), and
            // AdoptProviderTitle then adopts it. Overwriting a stored real name with a blank would
            // throw that away on every sync, so a blank candidate never clears an existing name.
            if (candidate.Remarks.IsNotEmpty() || !IsRealName(item.Remarks, candidate.PlaceholderNames))
            {
                item.Remarks = candidate.Remarks;
            }
            item.Url = candidate.Url;                              // the ACCOUNT subscription URL
            item.Enabled = true;
            // Stamp an explicit v2rayNG-family UA: the departament/Remnawave panel serves its managed
            // server list only for a recognised v2rayNG client. SubscriptionHandler.ResolveSubUserAgent
            // honours this verbatim. (Manually-added subs carry no UA and correctly fall back to the
            // standard "v2rayN/<version>" desktop UA instead — see that method.)
            item.UserAgent = AccountSubscriptionUserAgent;

            await ConfigHandler.AddSubItem(config, item);
            var guid = item.Id;
            if (guid.IsNullOrEmpty())
            {
                continue;
            }

            // Download + import the servers behind the subscription URL via the shared updater, which
            // FORCES the correct v2rayNG-family User-Agent. This is the whole reason a plain
            // DownloadService call with the branding UA fetched an "app not supported" placeholder.
            await SubscriptionHandler.UpdateProcess(config, guid, false, static (_, _) => Task.CompletedTask);

            // The fetch above stamped the provider's `profile-title` on the row. Adopt it as the name
            // when nothing else names the subscription — this is also the healing path for rows an
            // older build left stamped with a tariff name («Base»).
            await AdoptProviderTitle(guid, candidate);

            if (!newMap.ContainsKey(candidate.Uuid))
            {
                newMap[candidate.Uuid] = guid;
                resultGuids.Add(guid);
            }
        }

        // Drop any previously managed subscription whose guid is not in the freshly imported set.
        //
        // ONLY when the remote answer was authoritative. "This guid is gone remotely" is an inference
        // from the fetched set, and a fetch that FAILED carries no such information: with
        // authoritative == false the candidate list is empty for want of an answer, not because the
        // account lost its subscriptions. Reconciling against it deleted every managed SubItem and —
        // via DeleteSubItem -> RemoveServersViaSubid -> "delete from ProfileItem where subid = ..." —
        // every server behind them, permanently. That fired on the ordinary cold-start path
        // (AccountViewModel ctor -> StartupLoad -> RunSyncPhases -> AutoImportSubscriptions) whenever
        // the network was not up yet at launch, which is exactly the reported "сервера просто
        // исчезают при запуске". Worse, ImportAll then returned normally, so AccountRepository.Guard
        // reported SUCCESS and the user was handed a silently empty Home with no error at all.
        //
        // AN EMPTY ANSWER IS NOT A DELETION ORDER EITHER. `newMap.Count == 0` after an authoritative
        // fetch means the account named no subscription we could import — and the ordinary way to
        // reach that is a subscription that LAPSED: the owner's panel keeps answering, but the account
        // summary no longer advertises an active one. Pruning on it ran DeleteSubItem ->
        // RemoveServersViaSubid and took every server with it, which is exactly what must not happen
        // on expiry: the subscription has to survive so the user can see «истекла» and renew it, and
        // renewing brings the SAME url back to life. A deletion needs a positive statement — the
        // account naming subscriptions, none of which is this one — so it is kept for the case that
        // actually needs it: signing in as somebody else, whose own subscriptions come back in newMap
        // and reconcile the previous account's rows away.
        if (!authoritative || newMap.Count == 0)
        {
            var merged = new Dictionary<string, string>(managed);
            foreach (var kv in newMap)
            {
                merged[kv.Key] = kv.Value;
            }
            AuthTokenStore.SetManagedGuids(merged);

            foreach (var guid in merged.Values)
            {
                if (guid.IsNotEmpty() && !resultGuids.Contains(guid))
                {
                    resultGuids.Add(guid);
                }
            }
            return resultGuids;
        }

        foreach (var kv in managed)
        {
            if (kv.Value.IsNotEmpty() && !newMap.Values.Contains(kv.Value))
            {
                await ConfigHandler.DeleteSubItem(config, kv.Value);
            }
        }

        AuthTokenStore.SetManagedGuids(newMap);
        return resultGuids;
    }

    /// <summary>
    /// Builds the ordered import set: the ACTIVE/primary subscription first (the only reliable source
    /// of a real connect URL), then any /all item that happens to expose its OWN url (future-proof —
    /// today /all never does). De-duplicated by url.
    /// </summary>
    private static IEnumerable<Candidate> BuildCandidates(PrimarySubscriptionDto? primary, List<SubInfoDto> all, UserProfileDto? profile)
    {
        var result = new List<Candidate>();
        var seenUrls = new HashSet<string>();
        var rootFromAll = all.FirstOrDefault(it => string.Equals(it.Type, "root", StringComparison.OrdinalIgnoreCase));

        var primaryUrl = primary?.Raw()?.SubscriptionUrl;
        if (primary?.HasActiveSubscription() == true && primaryUrl.IsNotEmpty() && seenUrls.Add(primaryUrl!))
        {
            var uuid = FirstNonBlank(profile?.RemnawaveUuid, rootFromAll?.RemnawaveUuid, rootFromAll?.Id, primaryUrl);
            var placeholders = PlaceholdersFor(primary.TariffDisplayName, rootFromAll?.TariffDisplayName);
            var remarks = FirstRealName(placeholders, rootFromAll?.DisplayName);
            result.Add(new Candidate(uuid, primaryUrl!, remarks, rootFromAll?.DefaultLabel ?? string.Empty, placeholders));
        }

        foreach (var info in all)
        {
            var url = info.Subscription?.Raw()?.SubscriptionUrl;
            if (url.IsNullOrEmpty() || !seenUrls.Add(url!))
            {
                continue;
            }
            var uuid = FirstNonBlank(info.RemnawaveUuid, info.Id, url);
            var placeholders = PlaceholdersFor(info.TariffDisplayName);
            var remarks = FirstRealName(placeholders, info.DisplayName);
            result.Add(new Candidate(uuid, url!, remarks, info.DefaultLabel ?? string.Empty, placeholders));
        }

        return result;
    }

    #region naming

    /// <summary>
    /// Lower-cased strings that name NO subscription, whatever a payload or an older build put in the
    /// remark. Same list as Android's <c>SubscriptionNaming.PLACEHOLDERS</c>, plus this app's own two
    /// historic stamps: <c>import_sub</c> (ConfigHandler's manual-add default) and the brand literal
    /// this manager itself used to write.
    ///
    /// It is not a cosmetic filter. There is no rename anywhere in the app (parity with
    /// OWNER-DECISION-2026-08-02 §5), so the automatic name is the ONLY name — a placeholder allowed to
    /// stick would be permanent. Because the adoption below asks this same question, a machine that
    /// already stored one heals itself on the next sync.
    /// </summary>
    private static readonly HashSet<string> _placeholderNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "default", "import sub", "import_sub", "departament", "departament vpn",
    };

    /// <summary>
    /// The placeholder set for ONE subscription: the shared list plus the tariff names this payload
    /// carries. A tariff name («Base», «Plus») names the PLAN, not the subscription — it is the same
    /// string for every customer on that plan — and it is exactly what an older build stamped on the
    /// owner's row, which is why the card read «Base» instead of his subscription's real name. Listing
    /// it here both stops it being written again and heals the rows that already carry it.
    /// </summary>
    private static HashSet<string> PlaceholdersFor(params string?[] tariffNames)
    {
        var set = new HashSet<string>(_placeholderNames, StringComparer.OrdinalIgnoreCase);
        foreach (var name in tariffNames)
        {
            var trimmed = name?.Trim();
            if (trimmed.IsNotEmpty())
            {
                set.Add(trimmed!);
            }
        }
        return set;
    }

    /// <summary>
    /// Имя подписки, годное ДЛЯ ПОКАЗА, или null. Последний рубеж перед экраном.
    ///
    /// Заглушка может лежать в базе по причинам, которые синхронизация уже не исправит: подписку
    /// добавили вручную из буфера (импорт штампует своё «import sub»), запись пришла от старой
    /// сборки, панель ещё не ответила после переустановки. Пока имя брали из базы как есть, на
    /// месте названия подписки — и в шапке группы, и в самой карточке — появлялось служебное
    /// «import sub», и уходило только после ручного обновления.
    ///
    /// Поэтому фильтр стоит именно на выводе: в базе пусть лежит что угодно, показать заглушку
    /// нельзя. Тариф в список не подставляем — он называет план, а не подписку, и на выводе его
    /// имени взяться неоткуда.
    /// </summary>
    public static string? DisplayName(string? candidate) =>
        IsRealName(candidate, _placeholderNames) ? candidate!.Trim() : null;

    /// <summary><paramref name="candidate"/> when it actually names a subscription (not blank, not a placeholder).</summary>
    private static bool IsRealName(string? candidate, HashSet<string> placeholders)
    {
        var trimmed = candidate?.Trim();
        return trimmed.IsNotEmpty() && !placeholders.Contains(trimmed!);
    }

    /// <summary>The first candidate that really names a subscription, else blank.</summary>
    private static string FirstRealName(HashSet<string> placeholders, params string?[] values)
    {
        foreach (var v in values)
        {
            if (IsRealName(v, placeholders))
            {
                return v!.Trim();
            }
        }
        return string.Empty;
    }

    /// <summary>
    /// Names the freshly fetched subscription from the provider's own <c>profile-title</c> header
    /// («🍀 erlish»), which <see cref="SubscriptionHandler"/> has just persisted onto the row — but
    /// ONLY while nothing else names it. Then, still unnamed, falls back to the backend's per-sub label
    /// («Подписка #2»): generated rather than chosen, so it ranks below the provider's title. Identical
    /// ranking to Android's <c>SubscriptionNaming.nameOf</c>: cabinet nickname → profile-title →
    /// stored remark → default label.
    ///
    /// Reads the row back instead of reusing the in-memory one: the fetch wrote to the database, and the
    /// title is the whole reason for this pass.
    /// </summary>
    private static async Task AdoptProviderTitle(string guid, Candidate candidate)
    {
        try
        {
            var item = await AppManager.Instance.GetSubItem(guid);
            if (item is null || IsRealName(item.Remarks, candidate.PlaceholderNames))
            {
                return;
            }

            var name = FirstRealName(candidate.PlaceholderNames, item.ProfileTitle, candidate.DefaultLabel);
            if (name.IsNullOrEmpty() || string.Equals(name, item.Remarks, StringComparison.Ordinal))
            {
                return;
            }

            item.Remarks = name;
            await SQLiteHelper.Instance.UpdateAsync(item);
        }
        catch (Exception ex)
        {
            // A name is not worth failing an import over — the subscription and its servers are already in.
            Logging.SaveLog("SubscriptionSyncManager.AdoptProviderTitle", ex);
        }
    }

    #endregion naming

    /// <summary>
    /// Removes every managed subscription and its servers. Invoked only from
    /// <see cref="AccountSession.Wipe"/> — EXPLICIT USER LOGOUT. A dead JWT deliberately does not come
    /// through here: an expired 7-day token is not the user asking to give up their subscriptions, and
    /// treating it as one deleted every server on the machine the first time the Account tab noticed.
    /// That path is <see cref="AccountSession.EndSession"/>.
    /// </summary>
    public async Task RemoveAllManaged()
    {
        var config = AppManager.Instance.Config;
        var managed = AuthTokenStore.GetManagedGuids();
        foreach (var kv in managed)
        {
            if (kv.Value.IsNotEmpty())
            {
                await ConfigHandler.DeleteSubItem(config, kv.Value);
            }
        }
        AuthTokenStore.SetManagedGuids(new Dictionary<string, string>());
    }

    private static string FirstNonBlank(params string?[] values)
    {
        foreach (var v in values)
        {
            if (v.IsNotEmpty())
            {
                return v!;
            }
        }
        return string.Empty;
    }

    /// <param name="Remarks">The subscription's own nickname from the cabinet — blank when it has none.</param>
    /// <param name="DefaultLabel">The backend's generated per-sub label («Подписка #2»), used last.</param>
    /// <param name="PlaceholderNames">Strings that name nothing for THIS subscription (see <see cref="PlaceholdersFor"/>).</param>
    private readonly record struct Candidate(string Uuid, string Url, string Remarks, string DefaultLabel, HashSet<string> PlaceholderNames);
}
