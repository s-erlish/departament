using ServiceLib.Enums;
using ServiceLib.Models.Dto;

namespace v2rayN.Desktop.Common;

/// <summary>
/// Presentation helpers that turn a real <see cref="ProfileItemModel"/> into the Incy row/hero
/// strings (protocol chip + transport line). Single source of truth shared by the server-list
/// converters and the connect-hero code-behind — so protocol/transport are derived from live
/// engine data, never hardcoded.
/// </summary>
public static class ProfileDisplay
{
    /// <summary>Protocol chip text: <see cref="EConfigType"/> → upper-case token (VLESS, SHADOWSOCKS, …).</summary>
    public static string Protocol(EConfigType configType) => configType.ToString().ToUpperInvariant();

    /// <summary>
    /// Protocol chip text for a whole row. A CUSTOM (raw xray-json) node shows its wrapped proxy
    /// outbound's real protocol (VLESS / VMESS / …) from introspection; it falls back to "CUSTOM"
    /// only when nothing could be introspected. Ordinary nodes use their <see cref="EConfigType"/>.
    /// </summary>
    public static string Protocol(ProfileItemModel? item)
    {
        if (item == null)
        {
            return string.Empty;
        }
        if (item.ConfigType == EConfigType.Custom && !string.IsNullOrWhiteSpace(item.ProtocolDisplay))
        {
            return item.ProtocolDisplay.Trim().ToUpperInvariant();
        }
        return Protocol(item.ConfigType);
    }

    /// <summary>Transport line: "TCP · REALITY" from Network + StreamSecurity (empty security → NONE).</summary>
    public static string Transport(string? network, string? streamSecurity)
    {
        var net = NormalizeNetwork(network);
        var sec = string.IsNullOrWhiteSpace(streamSecurity) ? "NONE" : streamSecurity.Trim().ToUpperInvariant();
        return $"{net} · {sec}";
    }

    /// <summary>Transport line for a whole profile row.</summary>
    public static string Transport(ProfileItemModel? item) => item == null ? string.Empty : Transport(item.Network, item.StreamSecurity);

    /// <summary>
    /// Network → display token. The engine renames the "tcp" network to "raw" on load, so both
    /// "raw" and empty must read as «TCP» to match Android exactly. Everything else is upper-cased.
    /// </summary>
    private static string NormalizeNetwork(string? network)
    {
        var n = network?.Trim();
        if (string.IsNullOrEmpty(n))
        {
            return "TCP";
        }
        return n.ToLowerInvariant() switch
        {
            "raw" or "tcp" => "TCP",
            _ => n.ToUpperInvariant(),
        };
    }

    // ── Count lines ─────────────────────────────────────────────────────────
    // Locale-aware plural via the L table (RU {one, few, many}: 1 сервер / 2 сервера /
    // 5 серверов; EN {one, other}: 1 server / 5 servers). Forms live in L.Common.cs and
    // switch live with the UI language — the old RU-only PluralRu helper is gone.

    /// <summary>"N servers" / «N серверов» in the current UI language.</summary>
    public static string Servers(int n) => L.Plural("Common_ServersPlural", n);

    /// <summary>"N providers" / «N провайдеров» in the current UI language.</summary>
    public static string Providers(int n) => L.Plural("Common_ProvidersPlural", n);
}
