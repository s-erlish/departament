namespace ServiceLib.Models.Dto;

[Serializable]
public class ProfileItemModel : ReactiveObject
{
    [Reactive]
    public bool IsActive { get; set; }

    public string IndexId { get; set; }
    public EConfigType ConfigType { get; set; }

    // Protocol label shown on the row chip. For a CUSTOM (raw xray-json) node this is the wrapped
    // proxy outbound's real protocol (VLESS / VMESS / …) resolved by introspection, so the chip
    // reads the real protocol instead of "CUSTOM". Empty for ordinary nodes (chip uses ConfigType).
    public string? ProtocolDisplay { get; set; }

    public string Remarks { get; set; }
    public string Address { get; set; }
    public int Port { get; set; }
    public string Network { get; set; }
    public string StreamSecurity { get; set; }
    public string Subid { get; set; }
    public string SubRemarks { get; set; }
    public int Sort { get; set; }

    [Reactive]
    public int Delay { get; set; }

    public decimal Speed { get; set; }

    [Reactive]
    public string DelayVal { get; set; }

    [Reactive]
    public string SpeedVal { get; set; }

    [Reactive]
    public string IpInfo { get; set; }

    [Reactive]
    public string TodayUp { get; set; }

    [Reactive]
    public string TodayDown { get; set; }

    [Reactive]
    public string TotalUp { get; set; }

    [Reactive]
    public string TotalDown { get; set; }

    public string GetSummary()
    {
        // Show only the human server remark — never leak engine tokens like [Custom][Xray].
        return Remarks;
    }
}
