using System.Text.Json.Serialization;

namespace v2rayN.Desktop.Account.Dto;

// Client subscription endpoints of the Departament backend. Ported 1:1 from
// V2rayNG auth/dto/SubscriptionDtos.kt.
//
//  GET   /client/subscription                    -> PrimarySubscriptionDto  (the ACTIVE/root sub)
//  GET   /client/subscription/all                -> SubscriptionAllDto      (root + secondaries)
//  PATCH /client/subscription/{scope}/{id}/name  (body RenameRequestDto)
//  GET   /client/subscription/qr?uuid=…          (PNG bytes)
//  POST  /client/subscription/{scope}/{id}/add-devices (body AddDevicesRequestDto)
//  GET   /client/subscriptions/upgrade-quote?targetTariffId=…  -> UpgradeQuoteDto
//  POST  /client/subscriptions/upgrade           (body UpgradeRequestDto)

/// <summary>GET /client/subscription/all</summary>
public sealed class SubscriptionAllDto
{
    public List<SubInfoDto> Items { get; set; } = new();
}

/// <summary>A single subscription (root or secondary).</summary>
public sealed class SubInfoDto
{
    /// <summary>"root" | "secondary" — used as the {scope} path segment. (in /all)</summary>
    public string Type { get; set; } = "root";

    /// <summary>The subscription id — also the id the auto-renew endpoint expects. (in /all)</summary>
    public string Id { get; set; } = "";

    // NOT present on /all items — only on the GET /client/subscription summary / connect payload.
    public string RemnawaveUuid { get; set; } = "";
    public SubResponseWrapper? Subscription { get; set; }
    public string? TariffDisplayName { get; set; }

    // in /all — the user-set label, then the backend default label ("Подписка #N").
    public string? DisplayName { get; set; }
    public string? DefaultLabel { get; set; }
    public int? SubscriptionIndex { get; set; }

    // in /all — tariff + selected price-option this sub renews on.
    public string? TariffId { get; set; }
    public string? TariffPriceOptionId { get; set; }

    // in /all — deviceCount = EXTRA devices purchased; totalDevices = total device slots.
    public int DeviceCount { get; set; }
    public int TotalDevices { get; set; }

    // NOT present on /all — always 0 from this endpoint. Kept for API compat.
    public int ConnectedDevices { get; set; }
    public bool AutoRenewEnabled { get; set; }
    public string? ExpireAtIso { get; set; }
    public bool IsTrial { get; set; }
    public double? TariffPrice { get; set; }
    public string? TariffCurrency { get; set; }
    public double? RenewalPrice { get; set; }

    /// <summary>
    /// Best-effort tariff badge name ("Base" / "Plus") derived from THIS sub's own fields, used as a
    /// LAST-RESORT fallback when the tariff catalog can't resolve the sub. Uses ONLY the authoritative
    /// summary <see cref="TariffDisplayName"/>; the raw remnawave product name is intentionally
    /// EXCLUDED because it goes stale after an upgrade. Generic service names yield null (badge hidden).
    /// </summary>
    public string? TariffBadgeName()
    {
        var name = TariffDisplayName?.Trim();
        if (name.IsNullOrEmpty() || IsGenericServiceName(name))
        {
            return null;
        }
        return name;
    }

    private static bool IsGenericServiceName(string name)
    {
        var n = name.Trim().ToLowerInvariant();
        return n == "departament vpn" || n == "departament";
    }
}

/// <summary>
/// GET /client/subscription — the authoritative ACTIVE (root) subscription summary. Richer than the
/// root entry inside /all: it carries the raw remnawave record (connect URL) and the friendly tariff
/// name.
/// </summary>
public sealed class PrimarySubscriptionDto
{
    public SubResponseWrapper? Subscription { get; set; }
    public string? TariffDisplayName { get; set; }

    // The active subscription's tariff id, when the summary exposes it. Key spelling varies.
    [JsonPropertyName("tariffId")]
    public string? TariffId { get; set; }

    [JsonPropertyName("tariff_id")]
    public string? TariffIdAlt1 { set => SetTariffId(value); }

    [JsonPropertyName("tariffUuid")]
    public string? TariffIdAlt2 { set => SetTariffId(value); }

    [JsonPropertyName("tariffID")]
    public string? TariffIdAlt3 { set => SetTariffId(value); }

    public double? AutoRenewNextChargeAmount { get; set; }
    public string? AutoRenewNextChargeAt { get; set; }
    public string? AutoRenewCurrency { get; set; }
    public string? Message { get; set; }

    private void SetTariffId(string? value)
    {
        if (TariffId.IsNullOrEmpty() && value.IsNotEmpty())
        {
            TariffId = value;
        }
    }

    /// <summary>The raw remnawave record for the active subscription, if any.</summary>
    public RawSubDto? Raw() => Subscription?.Raw();

    /// <summary>
    /// The tariff id for the active subscription: the summary's own <see cref="TariffId"/> when
    /// present, else the one the raw remnawave record carries.
    /// </summary>
    public string? ActiveTariffId()
    {
        if (TariffId.IsNotEmpty())
        {
            return TariffId;
        }
        var rawId = Raw()?.TariffId;
        return rawId.IsNotEmpty() ? rawId : null;
    }

    /// <summary>
    /// True when this payload actually carries an active subscription. When the account has none the
    /// backend returns an empty `subscription` and only a message.
    /// </summary>
    public bool HasActiveSubscription()
    {
        var r = Raw();
        var rawHasContent = r != null &&
            (r.SubscriptionUrl.IsNotEmpty() || !r.ExpireAt.IsNullOrEmpty() || !r.Status.IsNullOrEmpty());
        return rawHasContent || !TariffDisplayName.IsNullOrEmpty();
    }
}

/// <summary>
/// Wrapper around the Remnawave subscription payload. The backend nests the raw record under
/// `response`, or occasionally under `data.response`; <see cref="Raw"/> returns whichever is present.
/// </summary>
public sealed class SubResponseWrapper
{
    public RawSubDto? Response { get; set; }
    public SubDataWrapper? Data { get; set; }

    public RawSubDto? Raw() => Response ?? Data?.Response;
}

public sealed class SubDataWrapper
{
    public RawSubDto? Response { get; set; }
}

/// <summary>The raw Remnawave subscription record.</summary>
public sealed class RawSubDto
{
    public string SubscriptionUrl { get; set; } = "";
    public int HwidDeviceLimit { get; set; }
    public long? TrafficLimitBytes { get; set; }

    // Some payloads carry the used traffic flat as `trafficUsed` instead of userTraffic.usedTrafficBytes.
    public long? TrafficUsed { get; set; }
    public UserTrafficDto UserTraffic { get; set; } = new();
    public string? ExpireAt { get; set; }
    public string? Status { get; set; }

    // Friendly names the backend sometimes attaches to the raw record.
    public string? ProductName { get; set; }
    public string? SubscriptionProductName { get; set; }

    // The tariff id, when the backend attaches it to the raw record.
    [JsonPropertyName("tariffId")]
    public string? TariffId { get; set; }

    [JsonPropertyName("tariff_id")]
    public string? TariffIdAlt1 { set => SetTariffId(value); }

    [JsonPropertyName("tariffUuid")]
    public string? TariffIdAlt2 { set => SetTariffId(value); }

    [JsonPropertyName("tariffID")]
    public string? TariffIdAlt3 { set => SetTariffId(value); }

    private void SetTariffId(string? value)
    {
        if (TariffId.IsNullOrEmpty() && value.IsNotEmpty())
        {
            TariffId = value;
        }
    }

    /// <summary>
    /// A non-positive (null OR &lt;= 0) traffic limit means an unlimited plan. The backend usually sends
    /// null for unlimited, but occasionally sends a concrete 0 — that must NOT read as a real 0-byte cap
    /// (which produced the "used / 0 Б" + empty-bar bug). Matches <see cref="IsUnlimitedDevices"/> and
    /// Home's <c>total &lt;= 0</c> parity check.
    /// </summary>
    public bool IsUnlimitedTraffic() => TrafficLimitBytes is null or <= 0;

    /// <summary>hwidDeviceLimit &lt;= 0 means an unlimited device plan.</summary>
    public bool IsUnlimitedDevices() => HwidDeviceLimit <= 0;
}

public sealed class UserTrafficDto
{
    public long UsedTrafficBytes { get; set; }
}

#region request bodies

public sealed class RenameRequestDto
{
    public string Name { get; set; } = "";

    public RenameRequestDto()
    {
    }

    public RenameRequestDto(string name) => Name = name;
}

public sealed class AddDevicesRequestDto
{
    public int ExtraDevices { get; set; }
    public string Method { get; set; } = "";
    public string? PaymentMethod { get; set; }

    public AddDevicesRequestDto()
    {
    }

    public AddDevicesRequestDto(int extraDevices, string method, string? paymentMethod = null)
    {
        ExtraDevices = extraDevices;
        Method = method;
        PaymentMethod = paymentMethod;
    }
}

public sealed class UpgradeRequestDto
{
    public string TargetTariffId { get; set; } = "";
    public string Method { get; set; } = "";
    public string? PaymentMethod { get; set; }
    public string SubscriptionUuid { get; set; } = "";

    public UpgradeRequestDto()
    {
    }

    public UpgradeRequestDto(string targetTariffId, string method, string? paymentMethod, string subscriptionUuid)
    {
        TargetTariffId = targetTariffId;
        Method = method;
        PaymentMethod = paymentMethod;
        SubscriptionUuid = subscriptionUuid;
    }
}

/// <summary>
/// Body for PATCH /client/auto-renew and PATCH /client/secondary-subscriptions/{id}/auto-renew. BOTH
/// routes read the `enabled` key (updateAutoRenewSchema / Boolean(req.body.enabled)) — the wire key is
/// forced to `enabled` so toggling actually takes effect (the former `autoRenew` key was silently
/// ignored by the backend).
/// </summary>
public sealed class AutoRenewRequestDto
{
    [JsonPropertyName("enabled")]
    public bool AutoRenew { get; set; }

    public AutoRenewRequestDto()
    {
    }

    public AutoRenewRequestDto(bool autoRenew) => AutoRenew = autoRenew;
}

/// <summary>
/// Body for the pure device top-up POST /client/subscription/{scope}/{id}/add-devices. Unlike the
/// legacy <see cref="AddDevicesRequestDto"/>, PaymentMethod is an int (2..13), matching addDevicesSchema.
/// method is "balance" (settles from wallet) or "platega" (returns a card checkout URL).
/// </summary>
public sealed class AddDevicesPurchaseRequestDto
{
    public int ExtraDevices { get; set; }
    public string Method { get; set; } = "";
    public int? PaymentMethod { get; set; }

    public AddDevicesPurchaseRequestDto()
    {
    }

    public AddDevicesPurchaseRequestDto(int extraDevices, string method, int? paymentMethod = null)
    {
        ExtraDevices = extraDevices;
        Method = method;
        PaymentMethod = paymentMethod;
    }
}

#endregion request bodies

/// <summary>
/// Response of POST /client/subscription/{scope}/{id}/add-devices. The backend has two shapes: a
/// "balance" top-up settles immediately → {ok,newDeviceLimit,newBalance}; a "platega" top-up returns a
/// card checkout → {paymentUrl,orderId,paymentId,finalAmount}. All fields are nullable so either shape
/// deserializes.
/// </summary>
public sealed class AddDevicesResultDto
{
    // "balance" shape
    public bool? Ok { get; set; }
    public int? NewDeviceLimit { get; set; }
    public double? NewBalance { get; set; }

    // "platega" shape (card checkout)
    public string? PaymentUrl { get; set; }
    public string? OrderId { get; set; }
    public string? PaymentId { get; set; }
    public double? FinalAmount { get; set; }

    /// <summary>True when a card checkout URL was issued (method "platega"); poll GET /client/payments.</summary>
    public bool RequiresCheckout() => !PaymentUrl.IsNullOrEmpty();
}
