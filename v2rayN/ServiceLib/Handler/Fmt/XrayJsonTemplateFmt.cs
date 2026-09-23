namespace ServiceLib.Handler.Fmt;

/// <summary>
/// Introspection helper for CUSTOM (raw xray-json / Remnawave "XRAY_JSON" template) profiles.
///
/// A departament/Remnawave subscription element is a FULL Xray config (its own routing rules + dns +
/// outbounds). We deliberately store it AS-IS as an <see cref="EConfigType.Custom"/> node so the
/// provider's routing/ad-block/geo rules are preserved and applied at connect time (the faithful
/// Android way). To still show the real protocol/transport on the row and to make ping work, this
/// helper peeks at the stored JSON and reports the wrapped proxy outbound's protocol, transport
/// network, security and real server address/port.
///
/// Mirrors Android's <c>V2rayConfig.getProxyOutbound()</c> + <c>MainRecyclerAdapter.customProtoInfo</c>.
/// </summary>
public class XrayJsonTemplateFmt : BaseFmt
{
    // Real proxy outbound protocols (lower-case). Helper outbounds (freedom / blackhole / dns /
    // direct / loopback / block) are intentionally excluded so we always report the actual server.
    private static readonly HashSet<string> _proxyProtocols = new(StringComparer.OrdinalIgnoreCase)
    {
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http",
    };

    /// <summary>Protocol / transport / server parsed from a CUSTOM node's wrapped proxy outbound.</summary>
    public record CustomProtoInfo(string Protocol, string? Network, string? Security, string? Address, int Port);

    // Parsing the stored raw config on every list rebuild would be wasteful, so cache per file path.
    // A stored custom config is written once to a unique file, so its content is immutable per path;
    // a null result is cached too (config has no identifiable proxy outbound → show "CUSTOM").
    private static readonly ConcurrentDictionary<string, CustomProtoInfo?> _cache = new();

    /// <summary>
    /// Introspect a CUSTOM node by reading its stored JSON file (<see cref="ProfileItem.Address"/>).
    /// Returns <c>null</c> for non-custom nodes or when no proxy outbound can be identified.
    /// </summary>
    public static CustomProtoInfo? Introspect(ProfileItem? node)
        => node == null ? null : IntrospectByAddress(node.Address, node.ConfigType);

    /// <summary>Introspect a CUSTOM row model (server-list build path).</summary>
    public static CustomProtoInfo? Introspect(ProfileItemModel? node)
        => node == null ? null : IntrospectByAddress(node.Address, node.ConfigType);

    private static CustomProtoInfo? IntrospectByAddress(string? address, EConfigType configType)
    {
        if (configType != EConfigType.Custom)
        {
            return null;
        }

        var path = ResolveAddressPath(address);
        if (path == null)
        {
            return null;
        }

        if (_cache.TryGetValue(path, out var cached))
        {
            return cached;
        }

        CustomProtoInfo? info = null;
        try
        {
            info = IntrospectFromJson(File.ReadAllText(path));
        }
        catch (Exception ex)
        {
            Logging.SaveLog("XrayJsonTemplateFmt", ex);
        }
        _cache[path] = info;
        return info;
    }

    /// <summary>
    /// Introspect a raw Xray-json string. Returns <c>null</c> when it is not a JSON object with an
    /// identifiable proxy outbound.
    /// </summary>
    public static CustomProtoInfo? IntrospectFromJson(string? raw)
    {
        if (raw.IsNullOrEmpty())
        {
            return null;
        }

        V2rayConfig? config;
        try
        {
            config = JsonUtils.Deserialize<V2rayConfig>(raw);
        }
        catch
        {
            return null;
        }

        var outbound = GetProxyOutbound(config);
        if (outbound == null)
        {
            return null;
        }

        // xray's classic "tcp" network is the app's "raw" (typed nodes store "raw" too), so map it
        // for a consistent transport chip. Everything else is passed through as-authored.
        var network = outbound.streamSettings?.network;
        if (network == Global.RawNetworkAlias)
        {
            network = Global.DefaultNetwork;
        }

        var (address, port) = GetOutboundServer(outbound);
        return new CustomProtoInfo(
            outbound.protocol ?? string.Empty,
            network,
            outbound.streamSettings?.security,
            address,
            port);
    }

    /// <summary>
    /// The first real proxy outbound of a config (skips freedom / blackhole / dns / direct helpers),
    /// mirroring Android's <c>getProxyOutbound()</c>.
    /// </summary>
    public static Outbounds4Ray? GetProxyOutbound(V2rayConfig? config)
    {
        if (config?.outbounds is not { Count: > 0 })
        {
            return null;
        }
        return config.outbounds.FirstOrDefault(o => o.protocol.IsNotEmpty() && _proxyProtocols.Contains(o.protocol));
    }

    /// <summary>True when the protocol names a real proxy outbound (vless/vmess/trojan/ss/socks/http).</summary>
    public static bool IsProxyProtocol(string? protocol) => protocol.IsNotEmpty() && _proxyProtocols.Contains(protocol!);

    //  Ключи — без учёта регистра, как читает конфиг само ядро (encoding/json в Go); комментарии и
    //  висячие запятые не должны стоить узлу защиты его серверов.
    private static readonly JsonNodeOptions _hostsNodeOptions = new() { PropertyNameCaseInsensitive = true };

    private static readonly JsonDocumentOptions _hostsDocumentOptions = new()
    {
        CommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    /// <summary>
    /// Домены серверов, до которых ядро узла CUSTOM дозванивается само, — из его сохранённого файла.
    ///
    /// У CUSTOM в <see cref="ProfileItem.Address"/> лежит имя файла, а не сервер, поэтому защита
    /// адреса узла в сборщике контекста до настоящего хоста не доходила. В режиме «весь трафик»
    /// это петля: DNS-запрос за адресом VPN-сервера уходит в туннель, sing-box отдаёт его
    /// удалённому DNS через proxy, то есть через тот же Xray, а Xray ждёт этот самый адрес, чтобы
    /// дозвониться. Сразу после подключения спасает кеш DNS системы, когда он истекает — трафик
    /// встаёт. Хост из этого списка sing-box разрешает прямым DNS, мимо туннеля.
    ///
    /// Битый или чужой файл даёт пустой список, исключений наружу нет: подключение не должно
    /// падать из-за того, что защиту не удалось собрать.
    /// </summary>
    public static List<string> GetServerHosts(ProfileItem? node)
    {
        if (node?.ConfigType != EConfigType.Custom)
        {
            return [];
        }

        var path = ResolveAddressPath(node.Address);
        if (path == null)
        {
            return [];
        }

        try
        {
            return GetServerHostsFromJson(File.ReadAllText(path));
        }
        catch (Exception ex)
        {
            Logging.SaveLog("XrayJsonTemplateFmt", ex);
            return [];
        }
    }

    /// <summary>
    /// Домены серверов из сырого конфига: у Xray — <c>settings.vnext[].address</c>,
    /// <c>settings.servers[].address</c>, плоский <c>settings.address</c> новых VLESS/VMess и
    /// <c>downloadSettings.address</c> у xhttp (прямо в настройках или внутри <c>extra</c>); у
    /// sing-box — <c>outbounds[].server</c>. Обходятся ВСЕ исходящие, а не первый прокси: цепочки и
    /// балансировщики дозваниваются до каждого. IP-адреса в DNS не ходят и в список не попадают.
    /// </summary>
    public static List<string> GetServerHostsFromJson(string? raw)
    {
        var hosts = new List<string>();
        if (raw.IsNullOrEmpty())
        {
            return hosts;
        }

        try
        {
            if (JsonNode.Parse(raw, _hostsNodeOptions, _hostsDocumentOptions) is not JsonObject root
                || root["outbounds"] is not JsonArray outbounds)
            {
                return hosts;
            }

            foreach (var outbound in outbounds.OfType<JsonObject>())
            {
                AddServerHost(hosts, outbound["server"]);

                if (outbound["settings"] is JsonObject settings)
                {
                    foreach (var item in ObjectsOf(settings["vnext"]).Concat(ObjectsOf(settings["servers"])))
                    {
                        AddServerHost(hosts, item["address"]);
                    }
                    //  Только строка: у WireGuard здесь МАССИВ адресов своего интерфейса, не сервер.
                    AddServerHost(hosts, settings["address"]);
                }

                if (outbound["streamSettings"] is JsonObject stream)
                {
                    foreach (var xhttp in new[] { stream["xhttpSettings"], stream["splithttpSettings"] }.OfType<JsonObject>())
                    {
                        AddServerHost(hosts, (xhttp["downloadSettings"] as JsonObject)?["address"]);
                        AddServerHost(hosts, ((xhttp["extra"] as JsonObject)?["downloadSettings"] as JsonObject)?["address"]);
                    }
                }
            }
        }
        catch
        {
            //  Недочитанный файл (например, повтор ключа) — отдаём то, что успели собрать.
        }

        return hosts;
    }

    private static IEnumerable<JsonObject> ObjectsOf(JsonNode? node) =>
        node is JsonArray array ? array.OfType<JsonObject>() : [];

    private static void AddServerHost(List<string> hosts, JsonNode? node)
    {
        if (node is not JsonValue value || !value.TryGetValue(out string? host))
        {
            return;
        }

        //  Нижний регистр: имена в DNS к регистру безразличны, а правило домена в sing-box
        //  сравнивает строки, и «Vpn.Example.com» из шаблона не совпал бы с запросом.
        host = host.Trim().ToLowerInvariant();
        if (Utils.IsDomain(host) && !hosts.Contains(host))
        {
            hosts.Add(host);
        }
    }

    private static (string? address, int port) GetOutboundServer(Outbounds4Ray outbound)
    {
        var settings = outbound.settings;
        if (settings == null)
        {
            return (null, 0);
        }

        var vnext = settings.vnext?.FirstOrDefault();
        if (vnext != null)
        {
            return (vnext.address, vnext.port);
        }

        var server = settings.servers?.FirstOrDefault();
        if (server != null)
        {
            return (server.address, server.port);
        }

        if (settings.address != null)
        {
            return (settings.address.ToString(), settings.port ?? 0);
        }

        return (null, 0);
    }

    private static string? ResolveAddressPath(string? address)
    {
        if (address.IsNullOrEmpty())
        {
            return null;
        }
        if (File.Exists(address))
        {
            return address;
        }
        var p = Utils.GetConfigPath(address);
        return File.Exists(p) ? p : null;
    }
}
