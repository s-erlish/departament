namespace v2rayN.Desktop.Account.Dto;

// Public (unauthenticated) endpoints of the Departament backend. Ported 1:1 from
// V2rayNG auth/dto/PublicDtos.kt.
//
//  GET /public/config        -> PublicConfigDto
//  GET /public/tariffs       -> TariffCatalogDto
//  GET /public/server-status -> List<ServerStatusDto>

/// <summary>GET /public/config</summary>
public sealed class PublicConfigDto
{
    public string TelegramBotUsername { get; set; } = "";
    public string PublicAppUrl { get; set; } = "";
    public string SiteUrl { get; set; } = "";
    public List<PlategaMethodDto> PlategaMethods { get; set; } = new();
    public bool TrialEnabled { get; set; }
    public double DefaultReferralPercent { get; set; }
}

/// <summary>A selectable Platega payment method.</summary>
public sealed class PlategaMethodDto
{
    public string Id { get; set; } = "";
    public string Label { get; set; } = "";
}

/// <summary>GET /public/tariffs</summary>
public sealed class TariffCatalogDto
{
    public List<TariffGroupDto> Items { get; set; } = new();
}

/// <summary>A named group (category) of tariffs.</summary>
public sealed class TariffGroupDto
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Emoji { get; set; } = "";
    public List<TariffDto> Tariffs { get; set; } = new();
}

/// <summary>A single tariff/plan. `trafficLimitBytes == null` means unlimited traffic.</summary>
public sealed class TariffDto
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public int DurationDays { get; set; }
    public long? TrafficLimitBytes { get; set; }
    public int IncludedDevices { get; set; }
    public double PricePerExtraDevice { get; set; }
    public int MaxExtraDevices { get; set; }
    public double Price { get; set; }
    public string Currency { get; set; } = "";
    public List<PriceOptionDto> PriceOptions { get; set; } = new();

    public bool IsUnlimitedTraffic() => TrafficLimitBytes is null or <= 0;
}

/// <summary>A duration/price option for a tariff.</summary>
public sealed class PriceOptionDto
{
    public string Id { get; set; } = "";
    public int DurationDays { get; set; }
    public double Price { get; set; }
    public int SortOrder { get; set; }
}

/// <summary>One entry of GET /public/server-status.</summary>
public sealed class ServerStatusDto
{
    public string CountryCode { get; set; } = "";
    public bool Online { get; set; }
}
