using System.Text.Json.Serialization;

namespace v2rayN.Desktop.Account.Dto;

// Remaining client endpoints: upgrade quote, devices, promo codes, referral stats.
// Ported 1:1 from V2rayNG auth/dto/MiscDtos.kt.
//
//  GET  /client/subscriptions/upgrade-quote?targetTariffId=… -> UpgradeQuoteDto
//  GET  /client/devices?uuid=…                               -> DevicesDto
//  POST /client/devices/delete                                (body DeleteDeviceRequestDto)
//  POST /client/promo-code/check                              -> PromoDto
//  POST /client/promo-code/activate                           (body PromoRequestDto)
//  GET  /client/referral-stats                                -> ReferralStatsDto

/// <summary>GET /client/subscriptions/upgrade-quote</summary>
public sealed class UpgradeQuoteDto
{
    public double Amount { get; set; }
    public int EffectiveDays { get; set; }
    public string Currency { get; set; } = "";
}

/// <summary>
/// GET /client/devices — tolerant to the different HWID-list shapes the backend / Remnawave proxy
/// may return (flat `items`/`devices`/`hwidDevices`, or nested `response.devices`).
/// <see cref="Devices"/> normalizes all of them.
///
/// The alternates declare a getter on purpose. System.Text.Json SILENTLY SKIPS a set-only property
/// whose type is a collection — the setter is never invoked, so `devices` and `hwidDevices` were
/// declared, documented, and dead: only `items` and the nested `response.devices` ever bound, and a
/// backend answering with `{"devices":[…]}` produced the empty Devices screen this class exists to
/// prevent. (Set-only STRING funnels, as used for the field alternates below, DO bind — which is why
/// the gap went unnoticed.) A getter makes the property writable in the serializer's eyes; returning
/// null keeps it out of any serialized output and stops it being treated as a collection to populate.
/// </summary>
public sealed class DevicesDto
{
    [JsonPropertyName("items")]
    public List<DeviceDto> Items { get; set; } = new();

    [JsonPropertyName("devices")]
    public List<DeviceDto>? ItemsAltDevices { get => null; set => FunnelItems(value); }

    [JsonPropertyName("hwidDevices")]
    public List<DeviceDto>? ItemsAltHwid { get => null; set => FunnelItems(value); }

    // Remnawave HWID endpoint shape: { response: { total, devices: [...] } }
    public DevicesWrapperDto? Response { get; set; }

    private void FunnelItems(List<DeviceDto>? value)
    {
        if (Items.Count == 0 && value is { Count: > 0 })
        {
            Items = value;
        }
    }

    /// <summary>The device list regardless of whether the backend returns it flat or nested.</summary>
    public List<DeviceDto> Devices()
    {
        if (Items.Count > 0)
        {
            return Items;
        }
        if (Response?.Devices is { Count: > 0 } nested)
        {
            return nested;
        }
        return new List<DeviceDto>();
    }
}

/// <summary>Remnawave-style nested wrapper: { response: { total, devices: [...] } }.</summary>
public sealed class DevicesWrapperDto
{
    [JsonPropertyName("devices")]
    public List<DeviceDto> Devices { get; set; } = new();

    [JsonPropertyName("items")]
    public List<DeviceDto>? DevicesAltItems { get => null; set => Funnel(value); }

    [JsonPropertyName("hwidDevices")]
    public List<DeviceDto>? DevicesAltHwid { get => null; set => Funnel(value); }

    public int Total { get; set; }

    private void Funnel(List<DeviceDto>? value)
    {
        if (Devices.Count == 0 && value is { Count: > 0 })
        {
            Devices = value;
        }
    }
}

/// <summary>
/// A device bound to a subscription (HWID). Field names carry alternates because the backend /
/// Remnawave may label the same value differently (model vs deviceModel, updatedAt vs lastActiveAt).
/// </summary>
public sealed class DeviceDto
{
    public string Hwid { get; set; } = "";
    public string? Platform { get; set; }

    [JsonPropertyName("deviceModel")]
    public string? DeviceModel { get; set; }

    [JsonPropertyName("model")]
    public string? DeviceModelAlt1 { set => SetDeviceModel(value); }

    [JsonPropertyName("deviceName")]
    public string? DeviceModelAlt2 { set => SetDeviceModel(value); }

    [JsonPropertyName("device")]
    public string? DeviceModelAlt3 { set => SetDeviceModel(value); }

    [JsonPropertyName("appVersion")]
    public string? AppVersion { get; set; }

    [JsonPropertyName("osVersion")]
    public string? AppVersionAlt1 { set => SetAppVersion(value); }

    [JsonPropertyName("userAgent")]
    public string? AppVersionAlt2 { set => SetAppVersion(value); }

    [JsonPropertyName("lastActiveAt")]
    public string? LastActiveAt { get; set; }

    [JsonPropertyName("updatedAt")]
    public string? LastActiveAtAlt1 { set => SetLastActiveAt(value); }

    [JsonPropertyName("lastSeen")]
    public string? LastActiveAtAlt2 { set => SetLastActiveAt(value); }

    [JsonPropertyName("createdAt")]
    public string? LastActiveAtAlt3 { set => SetLastActiveAt(value); }

    private void SetDeviceModel(string? value)
    {
        if (DeviceModel.IsNullOrEmpty() && value.IsNotEmpty())
        {
            DeviceModel = value;
        }
    }

    private void SetAppVersion(string? value)
    {
        if (AppVersion.IsNullOrEmpty() && value.IsNotEmpty())
        {
            AppVersion = value;
        }
    }

    private void SetLastActiveAt(string? value)
    {
        if (LastActiveAt.IsNullOrEmpty() && value.IsNotEmpty())
        {
            LastActiveAt = value;
        }
    }
}

/// <summary>Parsed devices plus the raw HTTP status/body, so the UI can surface a diagnostic.</summary>
public sealed class DevicesResult
{
    public List<DeviceDto> Devices { get; set; } = new();
    public int HttpCode { get; set; }
    public string RawBody { get; set; } = "";

    public DevicesResult()
    {
    }

    public DevicesResult(List<DeviceDto> devices, int httpCode, string rawBody)
    {
        Devices = devices;
        HttpCode = httpCode;
        RawBody = rawBody;
    }
}

public sealed class DeleteDeviceRequestDto
{
    public string Hwid { get; set; } = "";
    public string Uuid { get; set; } = "";

    public DeleteDeviceRequestDto()
    {
    }

    public DeleteDeviceRequestDto(string hwid, string uuid)
    {
        Hwid = hwid;
        Uuid = uuid;
    }
}

#region promo codes

public sealed class PromoRequestDto
{
    public string Code { get; set; } = "";

    public PromoRequestDto()
    {
    }

    public PromoRequestDto(string code) => Code = code;
}

/// <summary>POST /client/promo-code/check</summary>
public sealed class PromoDto
{
    public string Type { get; set; } = "";
    public double? DiscountPercent { get; set; }
    public int? DurationDays { get; set; }
}

#endregion promo codes

/// <summary>GET /client/referral-stats</summary>
public sealed class ReferralStatsDto
{
    public string ReferralCode { get; set; } = "";
    public double ReferralPercent { get; set; }
    public int TotalReferrals { get; set; }
    public double TotalEarned { get; set; }
    public string Currency { get; set; } = "";
}
