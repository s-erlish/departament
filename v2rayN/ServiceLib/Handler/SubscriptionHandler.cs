namespace ServiceLib.Handler;

public static class SubscriptionHandler
{
    public static async Task UpdateProcess(Config config, string subId, bool blProxy, Func<bool, string, Task> updateFunc)
    {
        await updateFunc?.Invoke(false, ResUI.MsgUpdateSubscriptionStart);
        var subItem = await AppManager.Instance.SubItems();

        if (subItem is not { Count: > 0 })
        {
            await updateFunc?.Invoke(false, ResUI.MsgNoValidSubscription);
            return;
        }

        var successCount = 0;
        foreach (var item in subItem)
        {
            try
            {
                if (!IsValidSubscription(item, subId))
                {
                    continue;
                }

                var hashCode = $"{item.Remarks}->";
                if (item.Enabled == false)
                {
                    await updateFunc?.Invoke(false, $"{hashCode}{ResUI.MsgSkipSubscriptionUpdate}");
                    continue;
                }

                // Create download handler
                var downloadHandle = CreateDownloadHandler(hashCode, updateFunc);
                await updateFunc?.Invoke(false, $"{hashCode}{ResUI.MsgStartGettingSubscriptions}");

                // Get all subscription content (main subscription + additional subscriptions),
                // together with the main subscription's response headers (userinfo + directives).
                var result = await DownloadAllSubscriptions(config, item, blProxy, downloadHandle);

                // Process download result (import servers + persist userinfo/directives to the SubItem)
                if (await ProcessDownloadResult(config, item, result, hashCode, updateFunc))
                {
                    successCount++;
                }

                await updateFunc?.Invoke(false, "-------------------------------------------------------");
            }
            catch (Exception ex)
            {
                var hashCode = $"{item.Remarks}->";
                Logging.SaveLog("UpdateSubscription", ex);
                await updateFunc?.Invoke(false, $"{hashCode}{ResUI.MsgFailedImportSubscription}: {ex.Message}");
                await updateFunc?.Invoke(false, "-------------------------------------------------------");
            }
        }

        await updateFunc?.Invoke(successCount > 0, $"{ResUI.MsgUpdateSubscriptionEnd}");
    }

    private static bool IsValidSubscription(SubItem item, string subId)
    {
        var id = item.Id.TrimEx();
        var url = item.Url.TrimEx();

        if (id.IsNullOrEmpty() || url.IsNullOrEmpty())
        {
            return false;
        }

        if (subId.IsNotEmpty() && item.Id != subId)
        {
            return false;
        }

        if (!url.StartsWith(Global.HttpsProtocol) && !url.StartsWith(Global.HttpProtocol))
        {
            return false;
        }

        return true;
    }

    private static DownloadService CreateDownloadHandler(string hashCode, Func<bool, string, Task> updateFunc)
    {
        var downloadHandle = new DownloadService();
        downloadHandle.Error += (sender2, args) =>
        {
            updateFunc?.Invoke(false, $"{hashCode}{args.GetException().Message}");
        };
        return downloadHandle;
    }

    /// <summary>
    /// Resolves the User-Agent to send for a subscription fetch.
    ///
    /// The departament / Remnawave panel serves its real managed server list ONLY for a recognised
    /// v2rayNG-family client; any other UA (a browser/branding UA, or the desktop's own
    /// "v2rayN/&lt;version&gt;") is answered with an "app not supported" placeholder — the panel returns a
    /// single fake VLESS node named «Приложение не поддерживается». This affects BOTH account-imported
    /// subs AND a user's OWN manually-added subscription (which arrives with a blank UA), so we force
    /// the SAME v2rayNG UA the working account path uses for every fetch. An item that already carries
    /// an explicit v2rayNG-family UA is honoured verbatim. 1:1 with the Android client
    /// (HttpUtil.getUrlContentWithUserAgentEx).
    /// </summary>
    private static string ResolveSubUserAgent(string? userAgent)
    {
        var ua = userAgent?.Trim();
        if (ua.IsNotEmpty() && ua!.Contains("v2rayng", StringComparison.OrdinalIgnoreCase))
        {
            return ua;
        }
        return Global.SubscriptionUserAgent;
    }

    private static async Task<string> DownloadSubscriptionContent(DownloadService downloadHandle, string url, bool blProxy, string userAgent)
    {
        var result = await downloadHandle.TryDownloadString(url, blProxy, userAgent);

        // If download with proxy fails, try direct connection
        if (blProxy && result.IsNullOrEmpty())
        {
            result = await downloadHandle.TryDownloadString(url, false, userAgent);
        }

        return result ?? string.Empty;
    }

    /// <summary>
    /// Like <see cref="DownloadSubscriptionContent"/> but also returns the subscription response
    /// headers. Keeps the same proxy -> direct fallback, and adds a final headerless fallback so a
    /// server that rejects the GetAsync/header path still imports servers (just without metadata).
    /// </summary>
    private static async Task<SubContentResult> DownloadSubscriptionContentWithHeaders(DownloadService downloadHandle, string url, bool blProxy, string userAgent)
    {
        var result = await downloadHandle.TryDownloadStringWithHeaders(url, blProxy, userAgent);

        // If download with proxy fails, try direct connection
        if (blProxy && (result?.Body).IsNullOrEmpty())
        {
            result = await downloadHandle.TryDownloadStringWithHeaders(url, false, userAgent);
        }

        // Headerless fallback: never regress below the plain download path.
        if ((result?.Body).IsNullOrEmpty())
        {
            return new SubContentResult { Body = await DownloadSubscriptionContent(downloadHandle, url, blProxy, userAgent) };
        }

        return result!;
    }

    private static async Task<SubContentResult> DownloadAllSubscriptions(Config config, SubItem item, bool blProxy, DownloadService downloadHandle)
    {
        // Download main subscription content (body + response headers)
        var result = await DownloadMainSubscription(config, item, blProxy, downloadHandle);

        // Process additional subscription links (if any). These only extend the body; the
        // userinfo/directive headers stay those of the main subscription.
        if (item.ConvertTarget.IsNullOrEmpty() && item.MoreUrl.TrimEx().IsNotEmpty())
        {
            result.Body = await DownloadAdditionalSubscriptions(item, result.Body ?? string.Empty, blProxy, downloadHandle);
        }

        return result;
    }

    private static async Task<SubContentResult> DownloadMainSubscription(Config config, SubItem item, bool blProxy, DownloadService downloadHandle)
    {
        // Prepare subscription URL and download directly
        var url = Utils.GetPunycode(item.Url.TrimEx());

        // If conversion is needed
        if (item.ConvertTarget.IsNotEmpty())
        {
            var subConvertUrl = config.ConstItem.SubConvertUrl.IsNullOrEmpty()
                ? Global.SubConvertUrls.FirstOrDefault()
                : config.ConstItem.SubConvertUrl;

            url = string.Format(subConvertUrl!, Utils.UrlEncode(url));

            if (!url.Contains("target="))
            {
                url += $"&target={item.ConvertTarget}";
            }

            if (!url.Contains("config="))
            {
                url += $"&config={Global.SubConvertConfig.FirstOrDefault()}";
            }

            // A sub-convert service response carries no subscription-userinfo header; body only.
            return new SubContentResult { Body = await DownloadSubscriptionContent(downloadHandle, url, blProxy, ResolveSubUserAgent(item.UserAgent)) };
        }

        // Direct subscription: fetch body together with the userinfo/directive response headers.
        return await DownloadSubscriptionContentWithHeaders(downloadHandle, url, blProxy, ResolveSubUserAgent(item.UserAgent));
    }

    private static async Task<string> DownloadAdditionalSubscriptions(SubItem item, string mainResult, bool blProxy, DownloadService downloadHandle)
    {
        var result = mainResult;

        // If main subscription result is Base64 encoded, decode it first
        if (result.IsNotEmpty() && Utils.IsBase64String(result))
        {
            result = Utils.Base64Decode(result);
        }

        // Process additional URL list
        var lstUrl = item.MoreUrl.TrimEx().Split(",") ?? [];
        foreach (var it in lstUrl)
        {
            var url2 = Utils.GetPunycode(it);
            if (url2.IsNullOrEmpty())
            {
                continue;
            }

            var additionalResult = await DownloadSubscriptionContent(downloadHandle, url2, blProxy, ResolveSubUserAgent(item.UserAgent));

            if (additionalResult.IsNotEmpty())
            {
                // Process additional subscription results, add to main result
                if (Utils.IsBase64String(additionalResult))
                {
                    result += Environment.NewLine + Utils.Base64Decode(additionalResult);
                }
                else
                {
                    result += Environment.NewLine + additionalResult;
                }
            }
        }

        return result;
    }

    private static async Task<bool> ProcessDownloadResult(Config config, SubItem subItem, SubContentResult result, string hashCode, Func<bool, string, Task> updateFunc)
    {
        var body = result.Body ?? string.Empty;
        if (body.IsNullOrEmpty())
        {
            await updateFunc?.Invoke(false, $"{hashCode}{ResUI.MsgSubscriptionDecodingFailed}");
            return false;
        }

        // A NOTICE IS NOT A SERVER LIST. The panel always answers this URL — an expired subscription
        // does not get an error, it gets a body carrying ONE host whose name is the message («подписка
        // истекла»), exactly the way the HWID-limit case is answered with the «Приложение не
        // поддерживается» host. Handing that body to the importer below did real damage: AddBatchServers
        // is a DESTRUCTIVE replace (delete the group, then import), so the notice host parsed fine,
        // counted as one imported server, and the user's whole location list was replaced by a single
        // fake node they could select and try to connect to. The subscription must survive its own
        // expiry — that is the entire renewal path («увидел, что истекло, и зашёл продлил»).
        //
        // So: recognise it, keep every server already on the machine, and still persist the metadata —
        // SubItem.Expire is what the Home meta-bar reads to say «Просрочено», so the user is TOLD.
        if (IsNoticeResponse(result, body, out var noticeReason))
        {
            await SaveSubscriptionMetadata(subItem.Id, result);
            await updateFunc?.Invoke(false, $"{hashCode}Subscription notice ({noticeReason}) — servers left untouched.");
            return false;
        }

        await updateFunc?.Invoke(false, $"{hashCode}{ResUI.MsgGetSubscriptionSuccessfully}");

        // If result is too short, display content directly
        if (body.Length < 99)
        {
            await updateFunc?.Invoke(false, $"{hashCode}{body}");
        }

        await updateFunc?.Invoke(false, $"{hashCode}{ResUI.MsgStartParsingSubscription}");

        // Add servers to configuration
        var ret = await ConfigHandler.AddBatchServers(config, body, subItem.Id, true);
        if (ret <= 0)
        {
            Logging.SaveLog("FailedImportSubscription");
            Logging.SaveLog(body);
        }
        else
        {
            // Servers imported OK -> persist the subscription-userinfo + directive metadata.
            // Guarded so that absent headers leave the stored values unchanged.
            await SaveSubscriptionMetadata(subItem.Id, result);
        }

        // Update completion message
        await updateFunc?.Invoke(false, ret > 0
                ? $"{hashCode}{ResUI.MsgUpdateSubscriptionEnd}"
                : $"{hashCode}{ResUI.MsgFailedImportSubscription}");

        return ret > 0;
    }

    /// <summary>
    /// True when the fetched body is the panel's NOTICE response instead of a server list.
    ///
    /// Two signals, in order of how much they can be trusted:
    ///
    /// 1. THE HEADER, and it is the authoritative one. `subscription-userinfo` carries `expire` as
    ///    epoch SECONDS, and the panel keeps sending it after the subscription lapses — that is
    ///    already how the Home meta-bar knows to print «Просрочено» (SubscriptionMetaView.ApplyExpiry
    ///    reads the very same value off SubItem.Expire). An expire in the past therefore means the
    ///    body cannot be a live server list, whatever it happens to contain, and it says so without
    ///    depending on any wording. A clock running days fast is the only false positive, and its
    ///    outcome is harmless by construction: we import nothing and CHANGE nothing, so the user keeps
    ///    the servers they already have and the next fetch with a sane clock imports normally.
    ///
    /// 2. THE WORDING, for the case where the header never arrived (the headerless download fallback).
    ///    Only markers actually verified on the wire are listed — a guess here would silently refuse a
    ///    real subscription, which is worse than the bug. The body is base64-decoded and
    ///    percent-decoded first, because a URI list carries the node name in its `#fragment`.
    /// </summary>
    private static bool IsNoticeResponse(SubContentResult result, string body, out string reason)
    {
        var info = ParseUserInfo(result.SubscriptionUserInfo);
        if (info is { } userInfo && userInfo.Expire > 0
            && userInfo.Expire < DateTimeOffset.UtcNow.ToUnixTimeSeconds())
        {
            reason = "expired";
            return true;
        }

        if (ContainsNoticeMarker(body))
        {
            reason = "not a server list";
            return true;
        }

        reason = string.Empty;
        return false;
    }

    /// <summary>
    /// Marker strings the panel puts in the NAME of a notice host. Verified on the wire, not guessed:
    /// the HWID/UA case is documented in <see cref="Global.SubscriptionHwidProvider"/> and reproduced
    /// in this file's <see cref="ResolveSubUserAgent"/> note. Matching is case-insensitive and done on
    /// the decoded body, so it catches the name whether it arrived as a URI fragment or inside JSON.
    /// </summary>
    private static readonly string[] _noticeMarkers =
    [
        "Приложение не поддерживается",
    ];

    private static bool ContainsNoticeMarker(string body)
    {
        if (body.IsNullOrEmpty())
        {
            return false;
        }
        try
        {
            var text = Utils.IsBase64String(body) ? Utils.Base64Decode(body) : body;
            // A node name travels percent-encoded in a URI fragment; decode so one marker list covers
            // both the URI-list and the JSON-array shapes this panel can answer with.
            var decoded = Uri.UnescapeDataString(text);
            return _noticeMarkers.Any(marker => decoded.Contains(marker, StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception ex)
        {
            // A body we cannot even decode is not evidence of a notice — let the importer judge it.
            Logging.SaveLog("SubscriptionNoticeCheck", ex);
            return false;
        }
    }

    /// <summary>
    /// Persists the subscription-userinfo header (traffic/expiry) and the Happ/Incy directives
    /// (announce / support-url / profile-web-page-url / profile-title) onto the SubItem, exactly
    /// as Android's AngConfigManager.updateConfigViaSub does. Re-reads the row first so a concurrent
    /// writer (e.g. TaskManager's UpdateTime) is not clobbered; only called when servers imported.
    /// </summary>
    private static async Task SaveSubscriptionMetadata(string subId, SubContentResult result)
    {
        try
        {
            var item = await AppManager.Instance.GetSubItem(subId);
            if (item is null)
            {
                return;
            }

            // subscription-userinfo: upload/download/total (bytes) + expire (epoch seconds).
            var info = ParseUserInfo(result.SubscriptionUserInfo);
            if (info is not null)
            {
                item.UploadUsed = info.Value.Upload;
                item.DownloadUsed = info.Value.Download;
                item.TotalTraffic = info.Value.Total;
                item.Expire = info.Value.Expire;
                item.UserInfoUpdated = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            }

            // Directives: null header => leave unchanged; "0" => clear; "base64:.." => decoded.
            var announce = DecodeSubDirective(result.Announce);
            if (announce is not null)
            {
                item.Announce = announce;
            }
            var supportUrl = DecodeSubDirective(result.SupportUrl);
            if (supportUrl is not null)
            {
                item.SupportUrl = supportUrl;
            }
            var webPageUrl = DecodeSubDirective(result.WebPageUrl);
            if (webPageUrl is not null)
            {
                item.WebPageUrl = webPageUrl;
            }
            // Real provider title (used as the meta-bar heading; TitleText falls back to Remarks).
            var profileTitle = DecodeSubDirective(result.ProfileTitle);
            if (profileTitle is not null)
            {
                item.ProfileTitle = profileTitle;
            }

            // Stamp the last-update time (epoch seconds, matching TaskManager) so the meta-bar
            // subtitle reflects manual refreshes too, not just scheduled auto-updates.
            item.UpdateTime = DateTimeOffset.Now.ToUnixTimeSeconds();

            await SQLiteHelper.Instance.UpdateAsync(item);

            //  Сведения записаны — говорим об этом ВСЛУХ. Шапка группы серверов читает запись
            //  подписки только при смене DataContext, а он не меняется, пока не поменялось имя:
            //  без этого сигнала свежие трафик и срок лежали бы в базе, а на экране оставались
            //  прежние цифры до перезапуска приложения.
            AppEvents.SubscriptionMetaChanged.Publish(item.Id);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("SaveSubscriptionMetadata", ex);
        }
    }

    /// <summary>
    /// Parses the `subscription-userinfo` header value, e.g.
    /// `upload=4520000000; download=210000000000; total=536870912000; expire=1749954800`.
    /// Splits on ';' into key=value pairs; a value that is not a long skips that pair.
    /// Returns null when nothing usable was found (caller then leaves stored metadata unchanged).
    /// 1:1 port of Android SubscriptionUserInfo.parse.
    /// </summary>
    private static (long Upload, long Download, long Total, long Expire)? ParseUserInfo(string? raw)
    {
        if (string.IsNullOrEmpty(raw))
        {
            return null;
        }

        long upload = 0, download = 0, total = 0, expire = 0;
        var any = false;
        foreach (var part in raw.Split(';'))
        {
            var i = part.IndexOf('=');
            if (i <= 0)
            {
                continue;
            }
            var key = part.Substring(0, i).Trim().ToLowerInvariant();
            if (!long.TryParse(part.Substring(i + 1).Trim(), out var val))
            {
                continue;
            }
            any = true;
            switch (key)
            {
                case "upload": upload = val; break;
                case "download": download = val; break;
                case "total": total = val; break;
                case "expire": expire = val; break;
            }
        }

        return any ? (upload, download, total, expire) : null;
    }

    /// <summary>
    /// Decodes a Happ/Incy subscription directive header value. Returns null when the header is
    /// absent (leave the stored value unchanged); "" when the value is "0" (clear); otherwise the
    /// plaintext ("base64:"-prefixed values are Base64-decoded, falling back to the raw value on
    /// error). 1:1 port of Android AngConfigManager.decodeSubDirective.
    /// </summary>
    private static string? DecodeSubDirective(string? raw)
    {
        if (raw is null)
        {
            return null;
        }
        var v = raw.Trim();
        if (v == "0")
        {
            return string.Empty;
        }
        if (v.StartsWith("base64:"))
        {
            try
            {
                return Encoding.UTF8.GetString(Convert.FromBase64String(v.Substring("base64:".Length))).Trim();
            }
            catch
            {
                return v;
            }
        }
        return v;
    }
}
