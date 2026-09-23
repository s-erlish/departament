using System.Data;

namespace ServiceLib.Handler;

public static class ConfigHandler
{
    private static readonly string _configRes = Global.ConfigFileName;
    private static readonly string _tag = "ConfigHandler";

    #region ConfigHandler

    /// <summary>
    /// Load the application configuration file
    /// If the file exists, deserialize it from JSON
    /// If not found, create a new Config object with default settings
    /// Initialize default values for missing configuration sections
    /// </summary>
    /// <returns>Config object containing application settings or null if there's an error</returns>
    public static Config? LoadConfig()
    {
        Config? config = null;
        var configPath = Utils.GetConfigPath(_configRes);
        var result = EmbedUtils.LoadResource(configPath);
        if (result.IsNotEmpty())
        {
            config = JsonUtils.Deserialize<Config>(result);

            // The file had content but would not parse. JsonUtils.Deserialize swallows the exception
            // and returns null (JsonUtils.cs:77-80), so this used to fall silently into the
            // `config ??= new Config()` below — a factory reset that drops IndexId, SubIndexId,
            // language, TUN mode and every other setting, which the next SaveConfig then writes back
            // over the damaged file, making the loss permanent and untraceable.
            //
            // Keep starting with defaults (refusing to launch is worse), but preserve the original
            // bytes next to the config first, so a damaged file is diagnosable and the user's settings
            // are recoverable instead of gone. Best-effort: a failure to copy must not block startup.
            if (config is null)
            {
                Logging.SaveLog($"{_tag}: config file exists but could not be parsed, falling back to defaults");
                try
                {
                    File.Copy(configPath, $"{configPath}.bad", true);
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            }
        }
        else
        {
            if (File.Exists(configPath))
            {
                // The file is there but yielded nothing. Two very different situations hide behind
                // that, and treating them the same cost the user a working app:
                //
                //  • EMPTY (0 bytes). A crash or a kill between creating and writing the file leaves
                //    exactly this. There is nothing in it to protect, yet the old code returned null,
                //    which makes InitApp return false and Program.Main call Environment.Exit(0) — the
                //    app just never opens, with no window and no message, on EVERY subsequent launch,
                //    until someone finds and deletes the file by hand. Servers live in guiNDB.db, not
                //    here, so starting from defaults costs at most the UI preferences and gets the
                //    user back into a working app.
                //
                //  • NON-EMPTY but unreadable (locked by another writer, AV scan, permissions). Here
                //    the bytes ARE the user's settings, so falling back to defaults would overwrite
                //    them on the next save. Retry the read a couple of times for the transient case,
                //    and only if it still fails refuse to start — same as before.
                var length = -1L;
                try
                {
                    length = new FileInfo(configPath).Length;
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }

                if (length == 0)
                {
                    Logging.SaveLog($"{_tag}: config file is empty, starting from defaults");
                }
                else
                {
                    for (var attempt = 0; attempt < 2 && result.IsNullOrEmpty(); attempt++)
                    {
                        Thread.Sleep(100);
                        result = EmbedUtils.LoadResource(configPath);
                    }

                    if (result.IsNullOrEmpty())
                    {
                        Logging.SaveLog("LoadConfig Exception");
                        return null;
                    }

                    config = JsonUtils.Deserialize<Config>(result);
                    if (config is null)
                    {
                        Logging.SaveLog($"{_tag}: config file exists but could not be parsed, falling back to defaults");
                        try
                        {
                            File.Copy(configPath, $"{configPath}.bad", true);
                        }
                        catch (Exception ex)
                        {
                            Logging.SaveLog(_tag, ex);
                        }
                    }
                }
            }
        }

        config ??= new Config();

        config.CoreBasicItem ??= new()
        {
            LogEnabled = false,
            Loglevel = "warning",
        };

        if (config.Inbound == null)
        {
            config.Inbound = [];
            InItem inItem = new()
            {
                Protocol = nameof(EInboundProtocol.socks),
                LocalPort = 10808,
                UdpEnabled = true,
                SniffingEnabled = true,
                RouteOnly = false,
            };

            config.Inbound.Add(inItem);
        }
        else
        {
            if (config.Inbound.Count > 0)
            {
                config.Inbound.First().Protocol = nameof(EInboundProtocol.socks);
            }
        }

        config.RoutingBasicItem ??= new();
        if (config.RoutingBasicItem.DomainStrategy.IsNullOrEmpty())
        {
            config.RoutingBasicItem.DomainStrategy = Global.DomainStrategies.First();
        }

        config.KcpItem ??= new KcpItem
        {
            Mtu = 1350,
            Tti = 50,
            UplinkCapacity = 12,
            DownlinkCapacity = 100,
            CwndMultiplier = 1,
            MaxSendingWindow = 2 * 1024 * 1024,
        };
        config.KcpItem.CwndMultiplier = config.KcpItem.CwndMultiplier <= 0 ? 1 : config.KcpItem.CwndMultiplier;
        config.KcpItem.MaxSendingWindow = config.KcpItem.MaxSendingWindow <= 0 ? (2 * 1024 * 1024) : config.KcpItem.MaxSendingWindow;
        config.GrpcItem ??= new GrpcItem
        {
            IdleTimeout = 60,
            HealthCheckTimeout = 20,
            PermitWithoutStream = false,
            InitialWindowsSize = 0,
        };
        config.TunModeItem ??= new TunModeItem
        {
            // departament: a fresh config is TUN by default (whole-device routing) — ModeText shows «TUN».
            EnableTun = true,
            Mtu = 9000,
            IcmpRouting = Global.TunIcmpRoutingPolicies.First(),
            EnableLegacyProtect = false,
        };
        config.GuiItem ??= new();
        if (!Global.RootCertProviders.Contains(config.GuiItem.RootCertProvider))
        {
            config.GuiItem.RootCertProvider = Global.RootCertProviders.First();
        }
        config.MsgUIItem ??= new();

        config.UiItem ??= new();
        config.UiItem.MainColumnItem ??= [];
        config.UiItem.WindowSizeItem ??= [];

        if (config.UiItem.CurrentLanguage.IsNullOrEmpty())
        {
            // departament: default a fresh config to Russian UI. English (and every other language)
            // stays fully available and switchable via the «Язык» row; this only sets the initial value.
            config.UiItem.CurrentLanguage = Global.Languages[5]; // "ru"
        }

        config.ConstItem ??= new ConstItem();

        config.SimpleDNSItem ??= InitBuiltinSimpleDNS();
        config.SimpleDNSItem.GlobalFakeIp ??= true;
        config.SimpleDNSItem.BootstrapDNS ??= Global.DomainPureIPDNSAddress.FirstOrDefault();
        config.SimpleDNSItem.ServeStale ??= false;
        config.SimpleDNSItem.ParallelQuery ??= false;
        MigrateSimpleDnsDefaults(config.SimpleDNSItem);

        config.SpeedTestItem ??= new();
        if (config.SpeedTestItem.SpeedTestTimeout < 10)
        {
            config.SpeedTestItem.SpeedTestTimeout = 10;
        }
        if (config.SpeedTestItem.SpeedTestUrl.IsNullOrEmpty())
        {
            config.SpeedTestItem.SpeedTestUrl = Global.SpeedTestUrls.First();
        }
        if (config.SpeedTestItem.SpeedPingTestUrl.IsNullOrEmpty())
        {
            config.SpeedTestItem.SpeedPingTestUrl = Global.SpeedPingTestUrls.First();
        }
        if (config.SpeedTestItem.MixedConcurrencyCount < 1)
        {
            config.SpeedTestItem.MixedConcurrencyCount = 5;
        }
        if (config.SpeedTestItem.UdpTestTarget.IsNullOrEmpty())
        {
            config.SpeedTestItem.UdpTestTarget = Global.UdpTestTargets.First();
        }
        if (config.SpeedTestItem.PingMethod.IsNullOrEmpty())
        {
            // departament: default latency probe = real delay through the core (Android parity).
            config.SpeedTestItem.PingMethod = nameof(ESpeedActionType.Realping);
        }

        config.Mux4RayItem ??= new()
        {
            Concurrency = 8,
            XudpConcurrency = 16,
            XudpProxyUDP443 = "reject"
        };

        config.Mux4SboxItem ??= new()
        {
            // departament: Mux OFF by default — empty Protocol gates mux off in SingboxOutboundService
            // (see `Protocol.IsNotEmpty()` guard). The Settings Mux toggle writes a real protocol when on.
            Protocol = string.Empty,
            MaxConnections = 8
        };

        config.HysteriaItem ??= new()
        {
            UpMbps = 100,
            DownMbps = 100
        };
        config.ClashUIItem ??= new();
        config.ClashUIItem.ConnectionsColumnItem ??= [];
        config.SystemProxyItem ??= new();
        config.WebDavItem ??= new();
        config.CheckUpdateItem ??= new();
        config.Fragment4RayItem ??= new()
        {
            Packets = "tlshello",
            Length = "50-100",
            Interval = "10-20",
            MaxSplit = "0"
        };
        config.GlobalHotkeys ??= [];

        if (config.SystemProxyItem.SystemProxyExceptions.IsNullOrEmpty())
        {
            config.SystemProxyItem.SystemProxyExceptions = Utils.IsWindows() ? Global.SystemProxyExceptionsWindows : Global.SystemProxyExceptionsLinux;
        }

        return config;
    }

    /// <summary>
    /// Serialises every config save. The write-temp-then-move below is only atomic against a CRASH;
    /// it used NOT to be atomic against a SECOND concurrent save, because both shared the one
    /// "&lt;res&gt;_temp" path. Saves genuinely do overlap: several call sites are fire-and-forget
    /// (ThemeSettingViewModel :44/:57/:69, SettingsViewModel :541, MainWindow.axaml.cs :1582),
    /// TaskManager saves from its own timer thread every 20 minutes (TaskManager.cs:45), and a
    /// subscription import saves from a thread-pool task (AddBatchServersCommon, :1648).
    ///
    /// Interleaved, that destroyed the live config:
    ///   A: WriteAllTextAsync(temp, contentA)  -> temp holds a COMPLETE config
    ///   B: WriteAllTextAsync(temp, contentB)  -> FileMode.Create TRUNCATES temp to 0, starts writing
    ///   A: File.Move(temp, config, true)      -> moves B's HALF-WRITTEN file over the live config
    /// The next launch then read a truncated guiNConfig.json, and LoadConfig turns that into silent
    /// data loss: a zero-length file returns null (:29-33) so the app exits without a word, and a
    /// partial-but-non-empty file fails Deserialize and falls through to `config ??= new Config()`
    /// (:36) — a factory reset that discards IndexId, SubIndexId, language, TUN mode and every
    /// setting, which is then written back over the file on the next save.
    /// </summary>
    private static readonly SemaphoreSlim _saveConfigLock = new(1, 1);

    /// <summary>
    /// Save the configuration to a file
    /// First writes to a temporary file, then replaces the original file
    /// </summary>
    /// <param name="config">Configuration object to be saved</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> SaveConfig(Config config)
    {
        await _saveConfigLock.WaitAsync();
        var tempPath = string.Empty;
        try
        {
            //save temp file
            var resPath = Utils.GetConfigPath(_configRes);
            // The semaphore above only serialises writers INSIDE this process. The scratch file must
            // therefore be unique per WRITER, not a single shared "<res>_temp": two app processes on
            // the same config directory (the single-instance gate can be lost — see Program.OnStartup —
            // and a portable install can simply be launched twice) would otherwise replay the exact
            // interleaving described above ACROSS processes, and the semaphore cannot see them. A
            // process-and-call unique name makes the write private and keeps File.Move (rename(2)) the
            // one atomic publish step, so a reader only ever sees the old or the new file, never a
            // half-written one.
            tempPath = $"{resPath}.{Environment.ProcessId}.{Guid.NewGuid():N}.tmp";

            var content = JsonUtils.Serialize(config, true, true);
            if (content.IsNullOrEmpty())
            {
                return -1;
            }
            await File.WriteAllTextAsync(tempPath, content);

            //rename
            File.Move(tempPath, resPath, true);
            tempPath = string.Empty;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return -1;
        }
        finally
        {
            // A failed save must not leave its scratch file behind: the names are unique now, so
            // without this they would accumulate in guiConfigs forever.
            if (tempPath.IsNotEmpty())
            {
                try
                {
                    File.Delete(tempPath);
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            }
            _saveConfigLock.Release();
        }

        return 0;
    }

    #endregion ConfigHandler

    #region Server

    /// <summary>
    /// Add a server profile to the configuration
    /// Dispatches the request to the appropriate method based on the config type
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Server profile to add</param>
    /// <returns>Result of the operation (0 if successful, -1 if failed)</returns>
    public static async Task<int> AddServer(Config config, ProfileItem profileItem)
    {
        var item = await AppManager.Instance.GetProfileItem(profileItem.IndexId);
        if (item is null)
        {
            item = profileItem;
        }
        else
        {
            item.CoreType = profileItem.CoreType;
            item.Remarks = profileItem.Remarks;
            item.Address = profileItem.Address;
            item.Port = profileItem.Port;

            item.Username = profileItem.Username;
            item.Password = profileItem.Password;

            item.Network = profileItem.Network;

            item.StreamSecurity = profileItem.StreamSecurity;
            item.Sni = profileItem.Sni;
            item.AllowInsecure = profileItem.AllowInsecure;
            item.Fingerprint = profileItem.Fingerprint;
            item.Alpn = profileItem.Alpn;

            item.PublicKey = profileItem.PublicKey;
            item.ShortId = profileItem.ShortId;
            item.SpiderX = profileItem.SpiderX;
            item.Mldsa65Verify = profileItem.Mldsa65Verify;
            item.MuxEnabled = profileItem.MuxEnabled;
            item.Cert = profileItem.Cert;
            item.CertSha = profileItem.CertSha;
            item.EchConfigList = profileItem.EchConfigList;
            item.VerifyPeerCertByName = profileItem.VerifyPeerCertByName;
            item.Finalmask = profileItem.Finalmask;
            item.ProtoExtra = profileItem.ProtoExtra;
            item.TransportExtra = profileItem.TransportExtra;
        }

        var ret = item.ConfigType switch
        {
            EConfigType.VMess => await AddVMessServer(config, item),
            EConfigType.Shadowsocks => await AddShadowsocksServer(config, item),
            EConfigType.SOCKS => await AddSocksServer(config, item),
            EConfigType.HTTP => await AddHttpServer(config, item),
            EConfigType.Trojan => await AddTrojanServer(config, item),
            EConfigType.VLESS => await AddVlessServer(config, item),
            EConfigType.Hysteria2 => await AddHysteria2Server(config, item),
            EConfigType.TUIC => await AddTuicServer(config, item),
            EConfigType.WireGuard => await AddWireguardServer(config, item),
            EConfigType.Anytls => await AddAnytlsServer(config, item),
            EConfigType.Naive => await AddNaiveServer(config, item),
            _ => -1,
        };
        return ret;
    }

    /// <summary>
    /// Add or edit a VMess server
    /// Validates and processes VMess-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">VMess profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddVMessServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.VMess;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with
        {
            VmessSecurity = profileItem.GetProtocolExtra().VmessSecurity?.TrimEx()
        });
        profileItem.Network = profileItem.Network.TrimEx();
        profileItem.StreamSecurity = profileItem.StreamSecurity.TrimEx();

        if (!Global.VmessSecurities.Contains(profileItem.GetProtocolExtra().VmessSecurity))
        {
            return -1;
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Remove multiple servers from the configuration
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="indexes">List of server profiles to remove</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> RemoveServers(Config config, List<ProfileItem> indexes)
    {
        var subid = "TempRemoveSubId";
        foreach (var item in indexes)
        {
            item.Subid = subid;
        }

        await SQLiteHelper.Instance.UpdateAllAsync(indexes);
        await RemoveServersViaSubid(config, subid, false);

        return 0;
    }

    /// <summary>
    /// Clone server profiles
    /// Creates copies of the specified server profiles with "-clone" appended to the remarks
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="indexes">List of server profiles to clone</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> CopyServer(Config config, List<ProfileItem> indexes)
    {
        foreach (var it in indexes)
        {
            var item = await AppManager.Instance.GetProfileItem(it.IndexId);
            if (item is null)
            {
                continue;
            }

            var profileItem = JsonUtils.DeepCopy(item);
            profileItem.IndexId = string.Empty;
            profileItem.Remarks = $"{item.Remarks}-clone";

            if (profileItem.ConfigType == EConfigType.Custom)
            {
                profileItem.Address = Utils.GetConfigPath(profileItem.Address);
                if (await AddCustomServer(config, profileItem, false) == 0)
                {
                }
            }
            else
            {
                await AddServerCommon(config, profileItem, true);
            }
        }

        return 0;
    }

    /// <summary>
    /// Set the default server by its index ID
    /// Updates the configuration to use the specified server as default
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="indexId">Index ID of the server to set as default</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> SetDefaultServerIndex(Config config, string? indexId)
    {
        if (indexId.IsNullOrEmpty())
        {
            return -1;
        }

        config.IndexId = indexId;

        await SaveConfig(config);

        return 0;
    }

    /// <summary>
    /// Set a default server from the provided list of profiles
    /// Ensures there's always a valid default server selected
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="lstProfile">List of profile models to choose from</param>
    /// <returns>Result of SetDefaultServerIndex operation</returns>
    public static async Task<int> SetDefaultServer(Config config, List<ProfileItemModel> lstProfile)
    {
        if (lstProfile.Exists(t => t.IndexId == config.IndexId))
        {
            return 0;
        }

        if (await SQLiteHelper.Instance.TableAsync<ProfileItem>().FirstOrDefaultAsync(t => t.IndexId == config.IndexId) != null)
        {
            return 0;
        }
        if (lstProfile.Count > 0)
        {
            return await SetDefaultServerIndex(config, lstProfile.FirstOrDefault(t => t.Port > 0)?.IndexId);
        }

        var item = await SQLiteHelper.Instance.TableAsync<ProfileItem>().FirstOrDefaultAsync(t => t.Port > 0);
        return await SetDefaultServerIndex(config, item?.IndexId);
    }

    /// <summary>
    /// Get the current default server profile
    /// If the current default is invalid, selects a new default
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <returns>The default profile item or null if none exists</returns>
    public static async Task<ProfileItem?> GetDefaultServer(Config config)
    {
        var item = await AppManager.Instance.GetProfileItem(config.IndexId);
        if (item is null)
        {
            var item2 = await SQLiteHelper.Instance.TableAsync<ProfileItem>().FirstOrDefaultAsync();
            await SetDefaultServerIndex(config, item2?.IndexId);
            return item2;
        }

        return item;
    }

    /// <summary>
    /// Move a server in the list to a different position
    /// Supports moving to top, up, down, bottom or specific position
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="lstProfile">List of server profiles</param>
    /// <param name="index">Index of the server to move</param>
    /// <param name="eMove">Direction to move the server</param>
    /// <param name="pos">Target position when using EMove.Position</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> MoveServer(Config config, List<ProfileItem> lstProfile, int index, EMove eMove, int pos = -1)
    {
        var count = lstProfile.Count;
        if (index < 0 || index > lstProfile.Count - 1)
        {
            return -1;
        }

        for (var i = 0; i < lstProfile.Count; i++)
        {
            ProfileExManager.Instance.SetSort(lstProfile[i].IndexId, (i + 1) * 10);
        }

        var sort = 0;
        switch (eMove)
        {
            case EMove.Top:
                {
                    if (index == 0)
                    {
                        return 0;
                    }
                    sort = ProfileExManager.Instance.GetSort(lstProfile.First().IndexId) - 1;

                    break;
                }
            case EMove.Up:
                {
                    if (index == 0)
                    {
                        return 0;
                    }
                    sort = ProfileExManager.Instance.GetSort(lstProfile[index - 1].IndexId) - 1;

                    break;
                }

            case EMove.Down:
                {
                    if (index == count - 1)
                    {
                        return 0;
                    }
                    sort = ProfileExManager.Instance.GetSort(lstProfile[index + 1].IndexId) + 1;

                    break;
                }
            case EMove.Bottom:
                {
                    if (index == count - 1)
                    {
                        return 0;
                    }
                    sort = ProfileExManager.Instance.GetSort(lstProfile[^1].IndexId) + 1;

                    break;
                }
            case EMove.Position:
                sort = (pos * 10) + 1;
                break;
        }

        ProfileExManager.Instance.SetSort(lstProfile[index].IndexId, sort);
        return await Task.FromResult(0);
    }

    /// <summary>
    /// Add a custom server configuration from a file
    /// Copies the configuration file to the app's config directory
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Profile item with the file path in Address</param>
    /// <param name="blDelete">Whether to delete the source file after copying</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddCustomServer(Config config, ProfileItem profileItem, bool blDelete)
    {
        var fileName = profileItem.Address;
        if (!File.Exists(fileName))
        {
            return -1;
        }
        var ext = Path.GetExtension(fileName);
        var newFileName = $"{Utils.GetGuid()}{ext}";
        //newFileName = Path.Combine(Utile.GetTempPath(), newFileName);

        try
        {
            File.Copy(fileName, Utils.GetConfigPath(newFileName));
            if (blDelete)
            {
                File.Delete(fileName);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            return -1;
        }

        profileItem.Address = newFileName;
        profileItem.ConfigType = EConfigType.Custom;
        if (profileItem.Remarks.IsNullOrEmpty())
        {
            profileItem.Remarks = $"import custom@{DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")}";
        }

        await AddServerCommon(config, profileItem, true);

        return 0;
    }

    /// <summary>
    /// Edit an existing custom server configuration
    /// Updates the server's properties without changing the file
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Profile item with updated properties</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> EditCustomServer(Config config, ProfileItem profileItem)
    {
        var item = await AppManager.Instance.GetProfileItem(profileItem.IndexId);
        if (item is null)
        {
            item = profileItem;
        }
        else
        {
            item.Remarks = profileItem.Remarks;
            item.Address = profileItem.Address;
            item.CoreType = profileItem.CoreType;
            item.DisplayLog = profileItem.DisplayLog;
            item.PreSocksPort = profileItem.PreSocksPort;
        }

        if (await SQLiteHelper.Instance.UpdateAsync(item) > 0)
        {
            return 0;
        }
        else
        {
            return -1;
        }

        //ToJsonFile(config);
    }

    /// <summary>
    /// Add or edit a Shadowsocks server
    /// Validates and processes Shadowsocks-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Shadowsocks profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddShadowsocksServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.Shadowsocks;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with
        {
            SsMethod = profileItem.GetProtocolExtra().SsMethod?.TrimEx()
        });

        if (!AppManager.Instance.GetShadowsocksSecurities(profileItem).Contains(profileItem.GetProtocolExtra().SsMethod))
        {
            return -1;
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit a SOCKS server
    /// Processes SOCKS-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">SOCKS profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddSocksServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.SOCKS;

        profileItem.Address = profileItem.Address.TrimEx();

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit an HTTP server
    /// Processes HTTP-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">HTTP profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddHttpServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.HTTP;

        profileItem.Address = profileItem.Address.TrimEx();

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit a Trojan server
    /// Validates and processes Trojan-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Trojan profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddTrojanServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.Trojan;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        if (profileItem.StreamSecurity.IsNullOrEmpty())
        {
            profileItem.StreamSecurity = Global.StreamSecurity;
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit a Hysteria2 server
    /// Validates and processes Hysteria2-specific settings
    /// Sets the core type to sing_box as required by Hysteria2
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Hysteria2 profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddHysteria2Server(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.Hysteria2;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.Fingerprint = string.Empty;
        profileItem.Alpn = string.Empty;
        //profileItem.Alpn = "h3";
        profileItem.Network = string.Empty;

        if (profileItem.StreamSecurity.IsNullOrEmpty())
        {
            profileItem.StreamSecurity = Global.StreamSecurity;
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }
        var protocolExtra = profileItem.GetProtocolExtra();
        profileItem.SetProtocolExtra(protocolExtra with
        {
            SalamanderPass = protocolExtra.SalamanderPass?.TrimEx(),
            HopInterval = protocolExtra.HopInterval?.TrimEx(),
        });

        if (!protocolExtra.Hy2RealmUrl.IsNullOrEmpty())
        {
            var realmResult = HyRealm.TryParse(protocolExtra.Hy2RealmUrl, out var realm);
            if (!realmResult || realm is null)
            {
                return -1;
            }
            if (realm.StunList.Count == 0)
            {
                realm = realm with
                {
                    StunList = Global.DefaultRealmStunList,
                };
            }
            profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with
            {
                Hy2RealmUrl = realm.ToUri(),
            });
        }
        var isGecko = !protocolExtra.GeckoMinPacketSize.IsNullOrEmpty() || !protocolExtra.GeckoMaxPacketSize.IsNullOrEmpty();
        if (isGecko)
        {
            var minPacketSize = protocolExtra.GeckoMinPacketSize.ToInt();
            var maxPacketSize = protocolExtra.GeckoMaxPacketSize.ToInt();
            if (minPacketSize <= 0
                || minPacketSize > maxPacketSize
                || maxPacketSize > 2048)
            {
                minPacketSize = 512;
                maxPacketSize = 1200;
            }
            profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with
            {
                GeckoMinPacketSize = minPacketSize.ToString(),
                GeckoMaxPacketSize = maxPacketSize.ToString(),
            });
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit a TUIC server
    /// Validates and processes TUIC-specific settings
    /// Sets the core type to sing_box as required by TUIC
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">TUIC profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddTuicServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.TUIC;
        profileItem.CoreType = ECoreType.sing_box;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Username = profileItem.Username.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.Network = string.Empty;
        profileItem.Fingerprint = string.Empty;

        var congestionControl = profileItem.GetProtocolExtra().CongestionControl;
        if (!Global.TuicCongestionControls.Contains(congestionControl))
        {
            congestionControl = Global.TuicCongestionControls.FirstOrDefault()!;
        }
        profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with { CongestionControl = congestionControl });

        if (profileItem.StreamSecurity.IsNullOrEmpty())
        {
            profileItem.StreamSecurity = Global.StreamSecurity;
        }
        if (profileItem.Alpn.IsNullOrEmpty())
        {
            profileItem.Alpn = "h3";
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit a WireGuard server
    /// Validates and processes WireGuard-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">WireGuard profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddWireguardServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.WireGuard;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        var wgReserved = profileItem.GetProtocolExtra().WgReserved?.TrimEx();
        if (!wgReserved.IsNullOrEmpty()
            && !wgReserved.Contains(','))
        {
            // Base64 format, convert to standard format
            try
            {
                var bytes = Convert.FromBase64String(wgReserved);
                var reserved = new byte[3];
                Array.Copy(bytes, reserved, Math.Min(bytes.Length, 3));

                wgReserved = string.Join(", ", reserved);
            }
            catch
            {
                // If conversion fails, keep the original value
            }
        }
        profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with
        {
            WgPublicKey = profileItem.GetProtocolExtra().WgPublicKey?.TrimEx(),
            WgPresharedKey = profileItem.GetProtocolExtra().WgPresharedKey?.TrimEx(),
            WgInterfaceAddress = profileItem.GetProtocolExtra().WgInterfaceAddress?.TrimEx(),
            WgReserved = wgReserved,
            WgMtu = profileItem.GetProtocolExtra().WgMtu is null or <= 0 ? Global.TunMtus.First() : profileItem.GetProtocolExtra().WgMtu,
        });

        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Add or edit an Anytls server
    /// Validates and processes Anytls-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Anytls profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddAnytlsServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.Anytls;
        profileItem.CoreType = ECoreType.sing_box;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.Network = string.Empty;
        if (profileItem.StreamSecurity.IsNullOrEmpty())
        {
            profileItem.StreamSecurity = Global.StreamSecurity;
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }
        await AddServerCommon(config, profileItem, toFile);
        return 0;
    }

    /// <summary>
    /// Add or edit a Naive server
    /// Validates and processes Naive-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Naive profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddNaiveServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.Naive;
        profileItem.CoreType = ECoreType.sing_box;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Username = profileItem.Username.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.Fingerprint = string.Empty;
        profileItem.Alpn = string.Empty;
        profileItem.Network = string.Empty;
        profileItem.AllowInsecure = string.Empty;
        if (profileItem.StreamSecurity.IsNullOrEmpty())
        {
            profileItem.StreamSecurity = Global.StreamSecurity;
        }
        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }
        await AddServerCommon(config, profileItem, toFile);
        return 0;
    }

    /// <summary>
    /// Sort the server list by the specified column
    /// Updates the sort order in the profile extension data
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="subId">Subscription ID to filter servers</param>
    /// <param name="colName">Column name to sort by</param>
    /// <param name="asc">Sort in ascending order if true, descending if false</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> SortServers(Config config, string subId, string colName, bool asc)
    {
        var lstModel = await AppManager.Instance.ProfileModels(subId, "");
        if (lstModel.Count <= 0)
        {
            return -1;
        }
        var lstServerStat = (config.GuiItem.EnableStatistics ? StatisticsManager.Instance.ServerStat : null) ?? [];
        var lstProfileExs = await ProfileExManager.Instance.GetProfileExs();
        var lstProfile = (from t in lstModel
                          join t2 in lstServerStat on t.IndexId equals t2.IndexId into t2b
                          from t22 in t2b.DefaultIfEmpty()
                          join t3 in lstProfileExs on t.IndexId equals t3.IndexId into t3b
                          from t33 in t3b.DefaultIfEmpty()
                          select new ProfileItemModel
                          {
                              IndexId = t.IndexId,
                              ConfigType = t.ConfigType,
                              Remarks = t.Remarks,
                              Address = t.Address,
                              Port = t.Port,
                              //Security = t.Security,
                              Network = t.Network,
                              StreamSecurity = t.StreamSecurity,
                              Delay = t33?.Delay ?? 0,
                              Speed = t33?.Speed ?? 0,
                              Sort = t33?.Sort ?? 0,
                              IpInfo = t33?.IpInfo ?? string.Empty,
                              TodayDown = (t22?.TodayDown ?? 0).ToString("D16"),
                              TodayUp = (t22?.TodayUp ?? 0).ToString("D16"),
                              TotalDown = (t22?.TotalDown ?? 0).ToString("D16"),
                              TotalUp = (t22?.TotalUp ?? 0).ToString("D16"),
                          }).ToList();

        Enum.TryParse(colName, true, out EServerColName name);

        if (asc)
        {
            lstProfile = name switch
            {
                EServerColName.ConfigType => lstProfile.OrderBy(t => t.ConfigType).ToList(),
                EServerColName.Remarks => lstProfile.OrderBy(t => t.Remarks).ToList(),
                EServerColName.Address => lstProfile.OrderBy(t => t.Address).ToList(),
                EServerColName.Port => lstProfile.OrderBy(t => t.Port).ToList(),
                EServerColName.Network => lstProfile.OrderBy(t => t.Network).ToList(),
                EServerColName.StreamSecurity => lstProfile.OrderBy(t => t.StreamSecurity).ToList(),
                EServerColName.DelayVal => lstProfile.OrderBy(t => t.Delay).ToList(),
                EServerColName.SpeedVal => lstProfile.OrderBy(t => t.Speed).ToList(),
                EServerColName.IpInfo => lstProfile.OrderBy(t => t.IpInfo).ToList(),
                EServerColName.SubRemarks => lstProfile.OrderBy(t => t.Subid).ToList(),
                EServerColName.TodayDown => lstProfile.OrderBy(t => t.TodayDown).ToList(),
                EServerColName.TodayUp => lstProfile.OrderBy(t => t.TodayUp).ToList(),
                EServerColName.TotalDown => lstProfile.OrderBy(t => t.TotalDown).ToList(),
                EServerColName.TotalUp => lstProfile.OrderBy(t => t.TotalUp).ToList(),
                _ => lstProfile
            };
        }
        else
        {
            lstProfile = name switch
            {
                EServerColName.ConfigType => lstProfile.OrderByDescending(t => t.ConfigType).ToList(),
                EServerColName.Remarks => lstProfile.OrderByDescending(t => t.Remarks).ToList(),
                EServerColName.Address => lstProfile.OrderByDescending(t => t.Address).ToList(),
                EServerColName.Port => lstProfile.OrderByDescending(t => t.Port).ToList(),
                EServerColName.Network => lstProfile.OrderByDescending(t => t.Network).ToList(),
                EServerColName.StreamSecurity => lstProfile.OrderByDescending(t => t.StreamSecurity).ToList(),
                EServerColName.DelayVal => lstProfile.OrderByDescending(t => t.Delay).ToList(),
                EServerColName.SpeedVal => lstProfile.OrderByDescending(t => t.Speed).ToList(),
                EServerColName.IpInfo => lstProfile.OrderByDescending(t => t.IpInfo).ToList(),
                EServerColName.SubRemarks => lstProfile.OrderByDescending(t => t.Subid).ToList(),
                EServerColName.TodayDown => lstProfile.OrderByDescending(t => t.TodayDown).ToList(),
                EServerColName.TodayUp => lstProfile.OrderByDescending(t => t.TodayUp).ToList(),
                EServerColName.TotalDown => lstProfile.OrderByDescending(t => t.TotalDown).ToList(),
                EServerColName.TotalUp => lstProfile.OrderByDescending(t => t.TotalUp).ToList(),
                _ => lstProfile
            };
        }

        for (var i = 0; i < lstProfile.Count; i++)
        {
            ProfileExManager.Instance.SetSort(lstProfile[i].IndexId, (i + 1) * 10);
        }
        switch (name)
        {
            case EServerColName.DelayVal:
                {
                    var maxSort = lstProfile.Max(t => t.Sort) + 10;
                    foreach (var item in lstProfile.Where(item => item.Delay <= 0))
                    {
                        ProfileExManager.Instance.SetSort(item.IndexId, maxSort);
                    }

                    break;
                }
            case EServerColName.SpeedVal:
                {
                    var maxSort = lstProfile.Max(t => t.Sort) + 10;
                    foreach (var item in lstProfile.Where(item => item.Speed <= 0))
                    {
                        ProfileExManager.Instance.SetSort(item.IndexId, maxSort);
                    }

                    break;
                }
        }

        return 0;
    }

    /// <summary>
    /// Add or edit a VLESS server
    /// Validates and processes VLESS-specific settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">VLESS profile to add</param>
    /// <param name="toFile">Whether to save to file</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddVlessServer(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigType = EConfigType.VLESS;

        profileItem.Address = profileItem.Address.TrimEx();
        profileItem.Password = profileItem.Password.TrimEx();
        profileItem.Network = profileItem.Network.TrimEx();
        profileItem.StreamSecurity = profileItem.StreamSecurity.TrimEx();

        var vlessEncryption = profileItem.GetProtocolExtra().VlessEncryption?.TrimEx();
        var flow = profileItem.GetProtocolExtra().Flow?.TrimEx() ?? string.Empty;
        profileItem.SetProtocolExtra(profileItem.GetProtocolExtra() with
        {
            VlessEncryption = vlessEncryption.IsNullOrEmpty() ? Global.None : vlessEncryption,
            Flow = Global.Flows.Contains(flow) ? flow : Global.Flows.First(),
        });

        if (profileItem.Password.IsNullOrEmpty())
        {
            return -1;
        }

        await AddServerCommon(config, profileItem, toFile);

        return 0;
    }

    /// <summary>
    /// Remove duplicate servers from a subscription
    /// Compares servers based on their properties rather than just names
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="subId">Subscription ID to deduplicate</param>
    /// <returns>Tuple with total count and remaining count after deduplication</returns>
    public static async Task<Tuple<int, int>> DedupServerList(Config config, string subId)
    {
        var lstProfile = await AppManager.Instance.ProfileItems(subId);
        if (lstProfile == null)
        {
            return new Tuple<int, int>(0, 0);
        }

        List<ProfileItem> lstKeep = [];
        List<ProfileItem> lstRemove = [];
        if (!config.GuiItem.KeepOlderDedupl)
        {
            lstProfile.Reverse();
        }

        foreach (var item in lstProfile)
        {
            if (item.IsComplex())
            {
                lstKeep.Add(item);
                continue;
            }

            if (lstKeep.Exists(i => CompareProfileItem(i, item, false)))
            {
                lstRemove.Add(item);
            }
            else
            {
                lstKeep.Add(item);
            }
        }
        await RemoveServers(config, lstRemove);

        return new Tuple<int, int>(lstProfile.Count, lstKeep.Count);
    }

    /// <summary>
    /// Common server addition logic used by all server types
    /// Sets common properties and handles sorting and persistence
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="profileItem">Profile item to add</param>
    /// <param name="toFile">Whether to save to database</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> AddServerCommon(Config config, ProfileItem profileItem, bool toFile = true)
    {
        profileItem.ConfigVersion = 4;

        if (profileItem.StreamSecurity.IsNotEmpty())
        {
            if (profileItem.StreamSecurity is not Global.StreamSecurity
                 and not Global.StreamSecurityReality)
            {
                profileItem.StreamSecurity = string.Empty;
            }
            else
            {
                if (profileItem.Fingerprint.IsNullOrEmpty() && profileItem.StreamSecurity == Global.StreamSecurityReality)
                {
                    profileItem.Fingerprint = config.CoreBasicItem.DefFingerprint;
                }
            }
        }

        if (profileItem.Network.IsNotEmpty() && !Global.Networks.Contains(profileItem.Network))
        {
            profileItem.Network = Global.DefaultNetwork;
        }

        var maxSort = -1;
        if (profileItem.IndexId.IsNullOrEmpty())
        {
            profileItem.IndexId = Utils.GetGuid(false);
            maxSort = ProfileExManager.Instance.GetMaxSort();
        }
        if (!toFile && maxSort < 0)
        {
            maxSort = ProfileExManager.Instance.GetMaxSort();
        }
        if (maxSort > 0)
        {
            ProfileExManager.Instance.SetSort(profileItem.IndexId, maxSort + 1);
        }

        if (toFile)
        {
            //profileItem.SetProtocolExtra();
            profileItem.SetProtocolExtra(profileItem.GetProtocolExtra());
            await SQLiteHelper.Instance.ReplaceAsync(profileItem);
        }
        return 0;
    }

    /// <summary>
    /// Compare two profile items to determine if they represent the same server
    /// Used for deduplication and server matching
    /// </summary>
    /// <param name="o">First profile item</param>
    /// <param name="n">Second profile item</param>
    /// <param name="remarks">Whether to compare remarks</param>
    /// <returns>True if the profiles match, false otherwise</returns>
    private static bool CompareProfileItem(ProfileItem? o, ProfileItem? n, bool remarks)
    {
        if (o == null || n == null)
        {
            return false;
        }

        var oProtocolExtra = o.GetProtocolExtra();
        var nProtocolExtra = n.GetProtocolExtra();
        var oTransport = o.GetTransportExtra();
        var nTransport = n.GetTransportExtra();

        return o.ConfigType == n.ConfigType
               && AreEqual(o.Address, n.Address)
               && o.Port == n.Port
               && AreEqual(o.Password, n.Password)
               && AreEqual(o.Username, n.Username)
               && AreEqual(oProtocolExtra.VlessEncryption, nProtocolExtra.VlessEncryption)
               && AreEqual(oProtocolExtra.SsMethod, nProtocolExtra.SsMethod)
               && AreEqual(oProtocolExtra.VmessSecurity, nProtocolExtra.VmessSecurity)
               && AreEqual(o.Network, n.Network)
               && AreEqual(oTransport.RawHeaderType, nTransport.RawHeaderType)
               && AreEqual(oTransport.Host, nTransport.Host)
               && AreEqual(oTransport.Path, nTransport.Path)
               && AreEqual(oTransport.XhttpMode, nTransport.XhttpMode)
               && AreEqual(oTransport.XhttpExtra, nTransport.XhttpExtra)
               && AreEqual(oTransport.GrpcAuthority, nTransport.GrpcAuthority)
               && AreEqual(oTransport.GrpcServiceName, nTransport.GrpcServiceName)
               && AreEqual(oTransport.GrpcMode, nTransport.GrpcMode)
               && AreEqual(oTransport.KcpHeaderType, nTransport.KcpHeaderType)
               && AreEqual(oTransport.KcpSeed, nTransport.KcpSeed)
               && (o.ConfigType == EConfigType.Trojan || o.StreamSecurity == n.StreamSecurity)
               && AreEqual(oProtocolExtra.Flow, nProtocolExtra.Flow)
               && AreEqual(oProtocolExtra.SalamanderPass, nProtocolExtra.SalamanderPass)
               && AreEqual(o.Sni, n.Sni)
               && AreEqual(o.Alpn, n.Alpn)
               && AreEqual(o.Fingerprint, n.Fingerprint)
               && AreEqual(o.PublicKey, n.PublicKey)
               && AreEqual(o.ShortId, n.ShortId)
               && AreEqual(o.Finalmask, n.Finalmask)
               && (!remarks || o.Remarks == n.Remarks);

        static bool AreEqual(string? a, string? b)
        {
            return string.Equals(a, b) || (string.IsNullOrEmpty(a) && string.IsNullOrEmpty(b));
        }
    }

    /// <summary>
    /// Searches the specified collection for a profile item that matches the target profile item based on a series of
    /// criteria.
    /// </summary>
    /// <remarks>The method attempts to find a match by comparing the target's remarks, address, port, and
    /// password in various combinations. The search is performed in order of specificity, starting with the most
    /// detailed comparison. If no match is found at any stage, the method returns null.</remarks>
    /// <param name="source">An enumerable collection of profile items to search. This parameter can be null.</param>
    /// <param name="target">The profile item to match against items in the source collection. This parameter can be null.</param>
    /// <returns>A profile item from the source collection that matches the target item according to defined criteria; otherwise,
    /// null if no match is found or if either parameter is null.</returns>
    private static ProfileItem? FindMatchedProfileItem(IEnumerable<ProfileItem>? source, ProfileItem? target)
    {
        if (source == null || target == null)
        {
            return null;
        }

        var matchedItem = source.FirstOrDefault(t => CompareProfileItem(t, target, true));
        if (matchedItem != null)
        {
            return matchedItem;
        }

        if (target.Remarks.IsNotEmpty())
        {
            matchedItem = source.FirstOrDefault(t => t.Remarks == target.Remarks);
            if (matchedItem != null)
            {
                return matchedItem;
            }
        }

        if (target.Address.IsNotEmpty() && target.Port > 0 && target.Password.IsNotEmpty())
        {
            matchedItem = source.FirstOrDefault(t =>
                IsSameText(t.Address, target.Address) &&
                t.Port == target.Port &&
                IsSameText(t.Password, target.Password));
            if (matchedItem != null)
            {
                return matchedItem;
            }
        }

        if (target.Address.IsNotEmpty() && target.Port > 0)
        {
            matchedItem = source.FirstOrDefault(t =>
                IsSameText(t.Address, target.Address) &&
                t.Port == target.Port);
            if (matchedItem != null)
            {
                return matchedItem;
            }
        }

        if (target.Address.IsNotEmpty())
        {
            matchedItem = source.FirstOrDefault(t => IsSameText(t.Address, target.Address));
            if (matchedItem != null)
            {
                return matchedItem;
            }
        }

        return null;

        static bool IsSameText(string? left, string? right)
        {
            if (left.IsNullOrEmpty() || right.IsNullOrEmpty())
            {
                return false;
            }

            return string.Equals(left.TrimEx(), right.TrimEx(), StringComparison.OrdinalIgnoreCase);
        }
    }

    /// <summary>
    /// Remove a single server profile by its index ID
    /// Deletes the configuration file if it's a custom config
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="indexId">Index ID of the profile to remove</param>
    /// <returns>0 if successful</returns>
    private static async Task<int> RemoveProfileItem(Config config, string indexId)
    {
        try
        {
            var item = await AppManager.Instance.GetProfileItem(indexId);
            if (item == null)
            {
                return 0;
            }
            if (item.ConfigType == EConfigType.Custom)
            {
                File.Delete(Utils.GetConfigPath(item.Address));
            }

            await SQLiteHelper.Instance.DeleteAsync(item);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }

        return 0;
    }

    /// <summary>
    /// Create a group server that combines multiple servers for load balancing
    /// Generates a PolicyGroup profile with references to the sub-items
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="subItem">Sub-item for grouping</param>
    /// <returns>Result object with success state and data</returns>
    public static async Task<RetResult> AddGroupAllServer(Config config, SubItem? subItem)
    {
        var result = new RetResult();

        var subId = subItem?.Id;
        if (subId.IsNullOrEmpty())
        {
            result.Success = false;
            return result;
        }

        var indexId = Utils.GetGuid(false);
        var remark = $"{subItem.Remarks} - {ResUI.TbConfigTypePolicyGroup}";
        var profile = new ProfileItem
        {
            IndexId = indexId,
            CoreType = ECoreType.Xray,
            ConfigType = EConfigType.PolicyGroup,
            Remarks = remark,
            IsSub = false
        };
        if (!subId.IsNullOrEmpty())
        {
            profile.Subid = subId;
        }
        var extraItem = new ProtocolExtraItem
        {
            MultipleLoad = EMultipleLoad.LeastPing,
            GroupType = profile.ConfigType.ToString(),
            SubChildItems = subId,
            Filter = Global.PolicyGroupDefaultAllFilter,
        };
        profile.SetProtocolExtra(extraItem);
        var ret = await AddServerCommon(config, profile, true);
        result.Success = ret == 0;
        result.Data = indexId;
        return result;
    }

    private static string CombineWithDefaultAllFilter(string regionPattern)
    {
        return $"^(?!.*(?:{Global.PolicyGroupExcludeKeywords})).*(?:{regionPattern}).*$";
    }

    private static readonly Dictionary<string, string> PolicyGroupRegionFilters = new()
    {
        { "JP", "日本|\\b[Jj][Pp]\\b|🇯🇵|[Jj]apan" },
        { "US", "美国|\\b[Uu][Ss]\\b|🇺🇸|[Uu]nited [Ss]tates|\\b[Uu][Ss][Aa]\\b" },
        { "HK", "香港|\\b[Hh][Kk]\\b|🇭🇰|[Hh]ong ?[Kk]ong" },
        { "TW", "台湾|台灣|\\b[Tt][Ww]\\b|🇹🇼|[Tt]aiwan" },
        { "KR", "韩国|\\b[Kk][Rr]\\b|🇰🇷|[Kk]orea" },
        { "SG", "新加坡|\\b[Ss][Gg]\\b|🇸🇬|[Ss]ingapore" },
        { "DE", "德国|\\b[Dd][Ee]\\b|🇩🇪|[Gg]ermany" },
        { "FR", "法国|\\b[Ff][Rr]\\b|🇫🇷|[Ff]rance" },
        { "GB", "英国|\\b[Gg][Bb]\\b|🇬🇧|[Uu]nited [Kk]ingdom|[Bb]ritain" },
        { "CA", "加拿大|🇨🇦|[Cc]anada" },
        { "AU", "澳大利亚|\\b[Aa][Uu]\\b|🇦🇺|[Aa]ustralia" },
        { "RU", "俄罗斯|\\b[Rr][Uu]\\b|🇷🇺|[Rr]ussia" },
        { "BR", "巴西|\\b[Bb][Rr]\\b|🇧🇷|[Bb]razil" },
        { "IN", "印度|🇮🇳|[Ii]ndia" },
        { "VN", "越南|\\b[Vv][Nn]\\b|🇻🇳|[Vv]ietnam" },
        { "ID", "印度尼西亚|\\b[Ii][Dd]\\b|🇮🇩|[Ii]ndonesia" },
        { "MX", "墨西哥|\\b[Mm][Xx]\\b|🇲🇽|[Mm]exico" }
    };

    public static async Task<RetResult> AddGroupRegionServer(Config config, SubItem? subItem)
    {
        var result = new RetResult();
        var subId = subItem?.Id;
        if (subId.IsNullOrEmpty())
        {
            result.Success = false;
            return result;
        }
        var childProfiles = await AppManager.Instance.ProfileItems(subId);
        List<string> indexIdList = [];

        foreach (var regionFilter in PolicyGroupRegionFilters)
        {
            var indexId = Utils.GetGuid(false);
            var remark = $"{subItem.Remarks} - {ResUI.TbConfigTypePolicyGroup} - {regionFilter.Key}";
            var profile = new ProfileItem
            {
                IndexId = indexId,
                CoreType = ECoreType.Xray,
                ConfigType = EConfigType.PolicyGroup,
                Remarks = remark,
                IsSub = false
            };
            if (!subId.IsNullOrEmpty())
            {
                profile.Subid = subId;
            }
            var extraItem = new ProtocolExtraItem
            {
                MultipleLoad = EMultipleLoad.LeastPing,
                GroupType = profile.ConfigType.ToString(),
                SubChildItems = subId,
                Filter = CombineWithDefaultAllFilter(regionFilter.Value),
            };
            profile.SetProtocolExtra(extraItem);

            var matchedChildProfiles = childProfiles?.Where(p =>
                    p != null &&
                    p.IsValid() &&
                    !p.ConfigType.IsComplexType() &&
                    (extraItem.Filter.IsNullOrEmpty() || Regex.IsMatch(p.Remarks, extraItem.Filter))
                )
                .ToList() ?? [];
            if (matchedChildProfiles.Count == 0)
            {
                continue;
            }

            var ret = await AddServerCommon(config, profile, true);
            if (ret == 0)
            {
                indexIdList.Add(indexId);
            }
        }
        result.Success = indexIdList.Count > 0;
        result.Data = indexIdList;
        return result;
    }

    /// <summary>
    /// Get a SOCKS server profile for pre-SOCKS functionality
    /// Used when TUN mode is enabled or when a custom config has a pre-SOCKS port
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="node">Server node that might need pre-SOCKS</param>
    /// <param name="coreType">Core type being used</param>
    /// <returns>A SOCKS profile item or null if not needed</returns>
    public static ProfileItem? GetPreSocksItem(Config config, ProfileItem node, ECoreType coreType)
    {
        ProfileItem? itemSocks = null;
        var enableLegacyProtect = config.TunModeItem.EnableLegacyProtect;
        if (node.ConfigType != EConfigType.Custom
            && coreType != ECoreType.sing_box
            && config.TunModeItem.EnableTun
            && enableLegacyProtect)
        {
            itemSocks = new ProfileItem()
            {
                CoreType = ECoreType.sing_box,
                ConfigType = EConfigType.SOCKS,
                Address = Global.Loopback,
                Port = AppManager.Instance.GetLocalPort(EInboundProtocol.socks)
            };
        }
        else if (node.ConfigType == EConfigType.Custom
            && node.PreSocksPort is > 0 and <= 65535)
        {
            var preCoreType = config.TunModeItem.EnableTun ? ECoreType.sing_box : ECoreType.Xray;
            itemSocks = new ProfileItem()
            {
                CoreType = preCoreType,
                ConfigType = EConfigType.SOCKS,
                Address = Global.Loopback,
                Port = node.PreSocksPort.Value,
            };
        }
        return itemSocks;
    }

    /// <summary>
    /// Remove servers with invalid test results (timeout)
    /// Useful for cleaning up subscription lists
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="subid">Subscription ID to filter servers</param>
    /// <returns>Number of removed servers or -1 if failed</returns>
    public static async Task<int> RemoveInvalidServerResult(Config config, string subid)
    {
        var lstModel = await AppManager.Instance.ProfileModels(subid, "");
        lstModel.RemoveAll(t => t.ConfigType.IsComplexType());
        if (lstModel is { Count: <= 0 })
        {
            return -1;
        }
        var lstProfileExs = await ProfileExManager.Instance.GetProfileExs();
        var lstProfile = (from t in lstModel
                          join t2 in lstProfileExs on t.IndexId equals t2.IndexId
                          where t2.Delay == -1
                          select t).ToList();

        await RemoveServers(config, JsonUtils.Deserialize<List<ProfileItem>>(JsonUtils.Serialize(lstProfile)));

        return lstProfile.Count;
    }

    #endregion Server

    #region Batch add servers

    /// <summary>
    /// Add multiple servers from string data (common protocols)
    /// Parses the string data into server profiles
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="strData">String data containing server information</param>
    /// <param name="subid">Subscription ID to associate with the servers</param>
    /// <param name="isSub">Whether this is from a subscription</param>
    /// <returns>Number of successfully imported servers or -1 if failed</returns>
    private static async Task<int> AddBatchServersCommon(Config config, string strData, string subid, bool isSub)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }

        var subFilter = string.Empty;
        if (isSub && subid.IsNotEmpty())
        {
            subFilter = (await AppManager.Instance.GetSubItem(subid))?.Filter ?? "";
        }

        var countServers = 0;
        List<ProfileItem> lstAdd = [];
        var arrData = strData.Split(Environment.NewLine.ToCharArray()).Where(t => !t.IsNullOrEmpty());
        if (isSub)
        {
            arrData = arrData.Distinct();
        }
        foreach (var str in arrData)
        {
            //maybe sub
            if (!isSub && (str.StartsWith(Global.HttpsProtocol) || str.StartsWith(Global.HttpProtocol)))
            {
                if (await AddSubItem(config, str) == 0)
                {
                    countServers++;
                }
                continue;
            }
            var profileItem = FmtHandler.ResolveConfig(str, out var msg);
            if (profileItem is null)
            {
                continue;
            }

            //exist sub items //filter
            if (isSub && subid.IsNotEmpty() && subFilter.IsNotEmpty())
            {
                if (!Regex.IsMatch(profileItem.Remarks, subFilter))
                {
                    continue;
                }
            }
            profileItem.Subid = subid;
            profileItem.IsSub = isSub;

            var addStatus = profileItem.ConfigType switch
            {
                EConfigType.VMess => await AddVMessServer(config, profileItem, false),
                EConfigType.Shadowsocks => await AddShadowsocksServer(config, profileItem, false),
                EConfigType.SOCKS => await AddSocksServer(config, profileItem, false),
                EConfigType.Trojan => await AddTrojanServer(config, profileItem, false),
                EConfigType.VLESS => await AddVlessServer(config, profileItem, false),
                EConfigType.Hysteria2 => await AddHysteria2Server(config, profileItem, false),
                EConfigType.TUIC => await AddTuicServer(config, profileItem, false),
                EConfigType.WireGuard => await AddWireguardServer(config, profileItem, false),
                EConfigType.Anytls => await AddAnytlsServer(config, profileItem, false),
                EConfigType.Naive => await AddNaiveServer(config, profileItem, false),
                _ => -1,
            };

            if (addStatus == 0)
            {
                countServers++;
                lstAdd.Add(profileItem);
            }
        }

        if (lstAdd.Count > 0)
        {
            await SQLiteHelper.Instance.InsertAllAsync(lstAdd);
        }

        await SaveConfig(config);
        return countServers;
    }

    /// <summary>
    /// Add servers from custom configuration formats (sing-box, v2ray, etc.)
    /// Handles various configuration formats and imports them as custom configs
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="strData">String data containing server information</param>
    /// <param name="subid">Subscription ID to associate with the servers</param>
    /// <param name="isSub">Whether this is from a subscription</param>
    /// <returns>Number of successfully imported servers or -1 if failed</returns>
    private static async Task<int> AddBatchServers4Custom(Config config, string strData, string subid, bool isSub)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }

        var subItem = await AppManager.Instance.GetSubItem(subid);
        var subRemarks = subItem?.Remarks;
        var preSocksPort = subItem?.PreSocksPort;

        // A departament / Remnawave "XRAY_JSON" body is an array of FULL Xray configs (each carrying
        // its own routing rules + dns + outbounds). We store every element AS-IS as a CUSTOM node so
        // the provider's routing/ad-block/geo rules are preserved and applied at connect time — the
        // faithful Android way. SingboxFmt/V2rayFmt.ResolveFullArray write the raw element to a file
        // and AddCustomServer imports it (ConfigType=Custom, CoreType=Xray). The real protocol /
        // transport / ping are recovered later by introspecting the wrapped proxy outbound.
        List<ProfileItem>? lstProfiles = null;
        //Is sing-box array configuration
        if (lstProfiles is null || lstProfiles.Count <= 0)
        {
            lstProfiles = SingboxFmt.ResolveFullArray(strData, subRemarks);
        }
        //Is v2ray array configuration
        if (lstProfiles is null || lstProfiles.Count <= 0)
        {
            lstProfiles = V2rayFmt.ResolveFullArray(strData, subRemarks);
        }
        if (lstProfiles is { Count: > 0 })
        {
            var count = 0;
            foreach (var it in lstProfiles)
            {
                it.Subid = subid;
                it.IsSub = isSub;
                it.PreSocksPort = preSocksPort;
                if (await AddCustomServer(config, it, true) == 0)
                {
                    count++;
                }
            }
            if (count > 0)
            {
                return count;
            }
        }

        ProfileItem? profileItem = null;
        //Is sing-box configuration
        profileItem ??= SingboxFmt.ResolveFull(strData, subRemarks);
        //Is v2ray configuration
        profileItem ??= V2rayFmt.ResolveFull(strData, subRemarks);
        //Is Html Page
        if (profileItem is null && HtmlPageFmt.IsHtmlPage(strData))
        {
            return -1;
        }
        //Is Clash configuration
        profileItem ??= ClashFmt.ResolveFull(strData, subRemarks);
        //Is hysteria configuration
        profileItem ??= Hysteria2Fmt.ResolveFull2(strData, subRemarks);
        if (profileItem is null || profileItem.Address.IsNullOrEmpty())
        {
            return -1;
        }

        profileItem.Subid = subid;
        profileItem.IsSub = isSub;
        profileItem.PreSocksPort = preSocksPort;
        if (await AddCustomServer(config, profileItem, true) == 0)
        {
            return 1;
        }
        else
        {
            return -1;
        }
    }

    /// <summary>
    /// Add Shadowsocks servers from SIP008 format
    /// SIP008 is a JSON-based format for Shadowsocks servers
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="strData">String data in SIP008 format</param>
    /// <param name="subid">Subscription ID to associate with the servers</param>
    /// <param name="isSub">Whether this is from a subscription</param>
    /// <returns>Number of successfully imported servers or -1 if failed</returns>
    private static async Task<int> AddBatchServers4SsSIP008(Config config, string strData, string subid, bool isSub)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }

        var lstSsServer = ShadowsocksFmt.ResolveSip008(strData);
        if (lstSsServer?.Count > 0)
        {
            var counter = 0;
            foreach (var ssItem in lstSsServer)
            {
                ssItem.Subid = subid;
                ssItem.IsSub = isSub;
                if (await AddShadowsocksServer(config, ssItem) == 0)
                {
                    counter++;
                }
            }
            await SaveConfig(config);
            return counter;
        }

        return -1;
    }

    private static async Task<int> AddBatchServers4Wireguard(Config config, string strData, string subid, bool isSub)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }
        if (!(strData.Contains("[Interface]", StringComparison.OrdinalIgnoreCase)
              && strData.Contains("[Peer]", StringComparison.OrdinalIgnoreCase)))
        {
            return -1;
        }
        var lstServer = WireguardFmt.ResolveConfig(strData);
        if (lstServer?.Count > 0)
        {
            var counter = 0;
            foreach (var item in lstServer)
            {
                item.Subid = subid;
                item.IsSub = isSub;
                if (await AddWireguardServer(config, item) == 0)
                {
                    counter++;
                }
            }
            await SaveConfig(config);
            return counter;
        }
        return -1;
    }

    private static async Task<int> AddBatchServers4InnerUri(Config config, string strData, string subid, bool isSub)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }

        var lstServer = InnerFmt.Resolve(strData, subid);
        if (lstServer?.Count > 0)
        {
            var counter = 0;
            List<ProfileItem> lstAdd = [];
            foreach (var profileItem in lstServer)
            {
                profileItem.Subid = subid;
                profileItem.IsSub = isSub;

                var addStatus = profileItem.ConfigType switch
                {
                    EConfigType.VMess => await AddVMessServer(config, profileItem, false),
                    EConfigType.Shadowsocks => await AddShadowsocksServer(config, profileItem, false),
                    EConfigType.HTTP => await AddHttpServer(config, profileItem, false),
                    EConfigType.SOCKS => await AddSocksServer(config, profileItem, false),
                    EConfigType.Trojan => await AddTrojanServer(config, profileItem, false),
                    EConfigType.VLESS => await AddVlessServer(config, profileItem, false),
                    EConfigType.Hysteria2 => await AddHysteria2Server(config, profileItem, false),
                    EConfigType.TUIC => await AddTuicServer(config, profileItem, false),
                    EConfigType.WireGuard => await AddWireguardServer(config, profileItem, false),
                    EConfigType.Anytls => await AddAnytlsServer(config, profileItem, false),
                    EConfigType.Naive => await AddNaiveServer(config, profileItem, false),
                    EConfigType.PolicyGroup or EConfigType.ProxyChain => await AddServerCommon(config, profileItem, false),
                    _ => -1,
                };
                if (addStatus == 0)
                {
                    counter++;
                    lstAdd.Add(profileItem);
                }
            }
            if (lstAdd.Count > 0)
            {
                await SQLiteHelper.Instance.InsertAllAsync(lstAdd);
            }
            await SaveConfig(config);
            return counter;
        }

        return -1;
    }

    /// <summary>
    /// Main entry point for adding batch servers from various formats
    /// Tries different parsing methods to import as many servers as possible
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="strData">String data containing server information</param>
    /// <param name="subid">Subscription ID to associate with the servers</param>
    /// <param name="isSub">Whether this is from a subscription</param>
    /// <returns>Number of successfully imported servers or -1 if failed</returns>
    public static async Task<int> AddBatchServers(Config config, string strData, string subid, bool isSub)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }

        // A subscription refresh is a DESTRUCTIVE replace (delete the group, then import). Two of them
        // running at once on the same subid interleave into data loss, and they really do overlap:
        // the account import at launch (SubscriptionSyncManager -> SubscriptionHandler.UpdateProcess),
        // TaskManager's per-minute auto-update timer, and the user's own «обновить» all call this for
        // the SAME subscription with nothing serialising them. The losing interleaving is:
        //     A: snapshot(20 servers), DELETE  -> group empty
        //     B: snapshot(0 servers)           -> B's "previous state" is EMPTY
        //     A: parse fails -> restore(20)    -> group back to 20
        //     B: DELETE                        -> group empty again
        //     B: parse fails -> restore(0)     -> nothing to restore. All 20 servers gone for good.
        // The benign-looking variant is just as wrong: when both succeed, both generations are
        // inserted and the list silently DOUBLES. One writer per subscription at a time fixes both.
        var gate = isSub && subid.IsNotEmpty() ? GetSubImportLock(subid) : null;
        if (gate is not null)
        {
            await gate.WaitAsync();
        }
        try
        {
            //  Подписку могли удалить, пока шло скачивание (выход из аккаунта, «Удалить подписку»).
            //  Удаление берёт тот же замок (DeleteSubItem), значит, после него записи уже нет, и
            //  импортировать некуда: иначе серверы удалённой подписки возвращались сиротами.
            if (gate is not null && await AppManager.Instance.GetSubItem(subid) is null)
            {
                return -1;
            }
            if (gate is null)
            {
                return await AddBatchServersInternal(config, strData, subid, isSub);
            }
            await BeginGroupReplace();
            try
            {
                return await AddBatchServersInternal(config, strData, subid, isSub);
            }
            finally
            {
                EndGroupReplace();
            }
        }
        finally
        {
            gate?.Release();
        }
    }

    /// <summary>
    /// Прочитать список серверов так, чтобы ни одна подписка не была посреди замены своих серверов.
    /// Для экрана список читается ТОЛЬКО так (ProfilesViewModel.RefreshServers).
    ///
    /// Обновление подписки — это НЕСКОЛЬКО записей в базу подряд: удалить группу, вставить новое
    /// поколение, вернуть узнанным серверам прежние id (<see cref="KeepIndexIdsAcrossRefresh"/>).
    /// Чтение, попавшее между ними, видело группу пустой или с новыми id: читатель, который
    /// перечитывал группу, пока подписка десять раз обновлялась, заставал её пустой примерно в каждом
    /// пятом чтении и с чужими id — почти в каждом втором (SubscriptionReadGateTests без ворот). В жизни
    /// это запуск вошедшего пользователя, когда импорт аккаунта обновляет подписку ровно в момент
    /// первого чтения списка, и две подписки, из которых одна закончила обновляться и перечитывает весь
    /// список, пока вторая на середине. Пустое чтение ставило «Главную» пустой (у вошедшего —
    /// приветственная карточка вместо серверов), чтение с новыми id пересобирало все строки, и через
    /// долю секунды всё повторялось обратно.
    ///
    /// Поэтому это ворота «читатели — писатели»: чтений может быть сколько угодно сразу, но ни одно
    /// не идёт, пока какая-то подписка заменяет свои серверы, а замена начинается, только когда
    /// начатые чтения закончились. Замена длится десятки миллисекунд, на медленном диске — сотни;
    /// подождать её дешевле, чем показать полсписка.
    /// </summary>
    public static async Task<T> ReadServersSettledAsync<T>(Func<Task<T>> read)
    {
        while (true)
        {
            Task replaces;
            lock (_serversGateLock)
            {
                if (_groupReplaces == 0)
                {
                    _serverReads++;
                    break;
                }
                replaces = _groupReplacesDone.Task;
            }
            await replaces;
        }
        try
        {
            return await read();
        }
        finally
        {
            TaskCompletionSource? drained = null;
            lock (_serversGateLock)
            {
                if (--_serverReads == 0)
                {
                    drained = _serverReadsDone;
                    _serverReadsDone = null;
                }
            }
            drained?.TrySetResult();
        }
    }

    private static readonly object _serversGateLock = new();
    private static int _serverReads;
    private static int _groupReplaces;
    private static TaskCompletionSource _groupReplacesDone = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static TaskCompletionSource? _serverReadsDone;

    /// <summary>Замена группы начинается: новые чтения ждут, начатые дочитываются. См. <see cref="ReadServersSettledAsync{T}"/>.</summary>
    private static async Task BeginGroupReplace()
    {
        Task? reads = null;
        lock (_serversGateLock)
        {
            if (_groupReplaces++ == 0)
            {
                _groupReplacesDone = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            }
            if (_serverReads > 0)
            {
                _serverReadsDone ??= new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                reads = _serverReadsDone.Task;
            }
        }
        if (reads is not null)
        {
            await reads;
        }
    }

    private static void EndGroupReplace()
    {
        TaskCompletionSource? done = null;
        lock (_serversGateLock)
        {
            if (--_groupReplaces == 0)
            {
                done = _groupReplacesDone;
            }
        }
        done?.TrySetResult();
    }

    /// <summary>
    /// One import lock per subscription id. Bounded by the number of subscriptions the user has (a
    /// handful), so the dictionary never grows meaningfully; entries are intentionally kept for the
    /// life of the process so a refresh that starts while another finishes still meets the same gate.
    /// </summary>
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> _subImportLocks = new();

    private static SemaphoreSlim GetSubImportLock(string subid)
        => _subImportLocks.GetOrAdd(subid, static _ => new SemaphoreSlim(1, 1));

    private static async Task<int> AddBatchServersInternal(Config config, string strData, string subid, bool isSub)
    {
        List<ProfileItem>? lstOriSub = null;
        ProfileItem? activeProfile = null;
        // The json files behind the group's CUSTOM profiles (the departament / Remnawave XRAY_JSON
        // shape is exactly this). They are NOT deleted with the rows: a failed import puts the rows
        // back, and rows whose backing config file was already deleted are dead weight — they show in
        // the list and fail to connect, which is the same complaint wearing a different hat. The files
        // are dropped further down, only once a replacement really landed.
        var orphanCustomFiles = new List<string>();

        var counter = 0;
        try
        {
            if (isSub && subid.IsNotEmpty())
            {
                lstOriSub = await AppManager.Instance.ProfileItems(subid);
                activeProfile = lstOriSub?.FirstOrDefault(t => t.IndexId == config.IndexId);
                // INSIDE the try: this deletes rows, and anything it throws (it used to delete files
                // too, unguarded) must still reach the restore below instead of unwinding with the
                // group already emptied.
                await RemoveServersViaSubid(config, subid, true, orphanCustomFiles);
            }

            if (Utils.IsBase64String(strData))
            {
                counter = await AddBatchServersCommon(config, Utils.Base64Decode(strData), subid, isSub);
            }
            if (counter < 1)
            {
                counter = await AddBatchServersCommon(config, strData, subid, isSub);
            }
            if (counter < 1)
            {
                counter = await AddBatchServersCommon(config, Utils.Base64Decode(strData), subid, isSub);
            }

            if (counter < 1)
            {
                counter = await AddBatchServers4SsSIP008(config, strData, subid, isSub);
            }

            //maybe wireguard config
            if (counter < 1)
            {
                counter = await AddBatchServers4Wireguard(config, strData, subid, isSub);
            }

            //May be standard uri mixed with internal uri
            var innerUriCount = 0;
            if (Utils.IsBase64String(strData))
            {
                innerUriCount = await AddBatchServers4InnerUri(config, Utils.Base64Decode(strData), subid, isSub);
            }
            if (innerUriCount < 1)
            {
                innerUriCount = await AddBatchServers4InnerUri(config, strData, subid, isSub);
            }
            if (innerUriCount < 1)
            {
                innerUriCount = await AddBatchServers4InnerUri(config, Utils.Base64Decode(strData), subid, isSub);
            }
            if (innerUriCount > 0)
            {
                if (counter > 0)
                {
                    counter += innerUriCount;
                }
                else
                {
                    counter = innerUriCount;
                }
            }

            //maybe other sub
            if (counter < 1)
            {
                counter = await AddBatchServers4Custom(config, strData, subid, isSub);
            }
        }
        catch
        {
            // A parser THREW after the delete above — the same "old gone, new never written" hole as a
            // zero-count parse, just reached by an exception. The live case is an invalid
            // SubItem.Filter: AddBatchServersCommon calls Regex.IsMatch with it (:1613) and an
            // unparseable pattern throws ArgumentException, which unwinds all the way out to
            // SubscriptionHandler.UpdateProcess's catch (:49) — past the zero-count restore below.
            // Put the servers back, then let the exception continue to its existing handler so the
            // failure is still reported exactly as before. The custom json files were deliberately
            // NOT deleted, so the restored rows are whole, not hollow.
            await RestoreOriginalSubServers(subid, lstOriSub);
            throw;
        }

        // Nothing parsed, but the old servers for this subscription were already deleted above ->
        // restore them instead of leaving the user with an empty list.
        //
        // CAUSE: this method deletes BEFORE it has the replacement (RemoveServersViaSubid above), and
        // no parser here is guaranteed to produce anything. SubscriptionHandler.ProcessDownloadResult
        // only guards the EMPTY body case (:242) — a body that downloads fine but yields zero servers
        // sails straight through into this delete-then-fail window. That happens for real:
        //   • a captive-portal / hotspot login page or a proxy error page (HTML, 200 OK);
        //   • the panel answering with a JSON error object instead of the node list;
        //   • a truncated / corrupted base64 body from a dropped connection;
        //   • an invalid SubItem.Filter regex (handled by the catch above, which restores and rethrows).
        // In every one of these the subscription's whole server list vanished for good, which is the
        // other half of the reported "бывает, что просто сервера исчезают и все".
        if (counter < 1)
        {
            await RestoreOriginalSubServers(subid, lstOriSub);
        }
        else
        {
            // The replacement generation is in place, so the previous generation's custom json files
            // are finally unreferenced and can go. Doing it only HERE is the whole point: until this
            // line the old files are the only thing that makes the restore above worth anything.
            //  Сначала узнаём серверы прошлого поколения, пока их файлы ещё на диске: конфиг провайдера
            //  узнаётся по содержимому, и файл неизменившегося сервера остаётся за ним — такой файл из
            //  списка на удаление выходит.
            var keptFiles = await KeepIndexIdsAcrossRefresh(subid, lstOriSub);
            orphanCustomFiles.RemoveAll(keptFiles.Contains);
            DeleteCustomConfigFiles(orphanCustomFiles);
        }

        //Select active node
        if (activeProfile != null)
        {
            var lstSub = await AppManager.Instance.ProfileItems(subid);
            //  Выбранный сервер узнан и сохранил свой id — выбор уже верен. Искать замену по имени здесь
            //  нельзя: при двух одноимённых серверах поиск брал первый попавшийся, выбор перескакивал на
            //  соседа, и ядро перезапускалось на другом сервере.
            if (lstSub.All(t => t.IndexId != activeProfile.IndexId))
            {
                var existItem = FindMatchedProfileItem(lstSub, activeProfile);
                if (existItem != null)
                {
                    await ConfigHandler.SetDefaultServerIndex(config, existItem.IndexId);
                }
            }
        }

        //Keep the last traffic statistics
        if (lstOriSub != null)
        {
            var lstSub = await AppManager.Instance.ProfileItems(subid);
            foreach (var item in lstSub)
            {
                var existItem = FindMatchedProfileItem(lstOriSub, item);
                if (existItem != null)
                {
                    await StatisticsManager.Instance.CloneServerStatItem(existItem.IndexId, item.IndexId);
                }
            }
        }

        return counter;
    }

    /// <summary>
    /// ТОТ ЖЕ СЕРВЕР ОСТАЁТСЯ ТЕМ ЖЕ СЕРВЕРОМ ПОСЛЕ ОБНОВЛЕНИЯ ПОДПИСКИ.
    ///
    /// Обновление подписки — это «удалить группу и импортировать заново», и каждый импорт выдавал
    /// КАЖДОМУ серверу новый IndexId, даже если в подписке не поменялось ни байта. А IndexId — это
    /// всё, по чему приложение узнаёт сервер: по нему «Главная» сверяет строки списка, к нему
    /// привязаны задержка и порядок (ProfileExItem), по нему помнится выбранный сервер. Новый id
    /// значит «другой сервер»: список пересобирал все строки заново, пинги пропадали, выбор держался
    /// только на поиске по имени. Владелец видел это как «программа перегружается» — при запуске и
    /// при возвращении окна, когда аккаунт заново скачивает подписки.
    ///
    /// Здесь каждой новой строке, в которой узнаётся строка прошлого поколения, возвращается прежний
    /// id. Порядок узнавания — от надёжного к слабому:
    ///   1. конфиг провайдера (Custom, XRAY_JSON Remnawave) — по СОДЕРЖИМОМУ файла. Файл
    ///      неизменившегося сервера остаётся прежним (его имя уходит из списка на удаление, свежая копия
    ///      удаляется), поэтому запись не меняется вовсе и CoreManager, перезапуская ядро по старому
    ///      контексту, находит файл на месте;
    ///   2. конфиг провайдера с изменившимся содержимым — по имени, но только если имя единственное и
    ///      среди старых, и среди новых серверов. Без этого узла без remarks (все получают имя
    ///      подписки) и повторяющиеся имена сдвигали id на соседей вместе с их пингами;
    ///   3. обычный сервер — по полному совпадению настроек (<see cref="CompareProfileItem"/>).
    /// Каждый прежний id отдаётся не больше одного раза и только свободный. Переименование — UPDATE на
    /// месте одной транзакцией: порядок строк не меняется (сортировка по умолчанию идёт по порядку
    /// записи), а оборванная операция откатывается целиком. Группы (PolicyGroup/ProxyChain из той же
    /// подписки) ссылаются на детей по id, поэтому их ChildItems переписываются в той же транзакции.
    /// Лучшее усилие: ошибка до фиксации пишется в журнал и оставляет свежие id — как было раньше.
    /// </summary>
    /// <returns>Имена файлов прошлого поколения, которые остались за узнанными серверами: их удалять нельзя.</returns>
    private static async Task<HashSet<string>> KeepIndexIdsAcrossRefresh(string subid, List<ProfileItem>? lstOriSub)
    {
        var keptFiles = new HashSet<string>(StringComparer.Ordinal);
        if (lstOriSub is not { Count: > 0 })
        {
            return keptFiles;
        }

        List<(ProfileItem Fresh, ProfileItem Old, bool SameBody)> pairs = [];
        List<ProfileItem> groupUpdates = [];
        try
        {
            var fresh = await AppManager.Instance.ProfileItems(subid);
            if (fresh is not { Count: > 0 })
            {
                return keptFiles;
            }

            var inUse = new HashSet<string>(fresh.Select(t => t.IndexId), StringComparer.Ordinal);
            var pool = lstOriSub.Where(o => o.IsSub && o.IndexId.IsNotEmpty() && !inUse.Contains(o.IndexId)).ToList();
            var left = fresh.Where(t => t.IsSub).ToList();

            var hashes = new Dictionary<ProfileItem, string?>(ReferenceEqualityComparer.Instance);
            string? Hash(ProfileItem p)
            {
                if (!hashes.TryGetValue(p, out var h))
                {
                    h = CustomBodyHash(p);
                    hashes[p] = h;
                }
                return h;
            }

            void Pair(ProfileItem n, ProfileItem o, bool sameBody)
            {
                pairs.Add((n, o, sameBody));
                pool.Remove(o);
                left.Remove(n);
            }

            foreach (var n in left.Where(t => t.ConfigType == EConfigType.Custom).ToList())
            {
                if (Hash(n) is not { } hash)
                {
                    continue;
                }
                var o = pool.FirstOrDefault(x => x.ConfigType == EConfigType.Custom && x.CoreType == n.CoreType && Hash(x) == hash);
                if (o is not null)
                {
                    Pair(n, o, true);
                }
            }

            foreach (var n in left.Where(t => t.ConfigType == EConfigType.Custom).ToList())
            {
                if (n.Remarks.IsNullOrEmpty()
                    || left.Count(t => t.ConfigType == EConfigType.Custom && t.Remarks == n.Remarks) != 1)
                {
                    continue;
                }
                var olds = pool.Where(x => x.ConfigType == EConfigType.Custom && x.CoreType == n.CoreType && x.Remarks == n.Remarks).ToList();
                if (olds.Count == 1)
                {
                    Pair(n, olds[0], false);
                }
            }

            foreach (var n in left.Where(t => t.ConfigType != EConfigType.Custom).ToList())
            {
                var o = pool.FirstOrDefault(x => x.ConfigType != EConfigType.Custom && CompareProfileItem(x, n, true));
                if (o is not null)
                {
                    Pair(n, o, false);
                }
            }

            if (pairs.Count == 0)
            {
                return keptFiles;
            }

            var idMap = pairs.ToDictionary(p => p.Fresh.IndexId, p => p.Old.IndexId, StringComparer.Ordinal);
            foreach (var g in fresh.Where(t => t.ConfigType.IsGroupType()))
            {
                var extra = g.GetProtocolExtra();
                if (Utils.String2List(extra.ChildItems) is not { Count: > 0 } children)
                {
                    continue;
                }
                var remapped = children.Select(id => idMap.GetValueOrDefault(id, id)).ToList();
                if (!remapped.SequenceEqual(children, StringComparer.Ordinal))
                {
                    g.SetProtocolExtra(extra with { ChildItems = Utils.List2String(remapped) });
                    groupUpdates.Add(g);
                }
            }

            await SQLiteHelper.Instance.RunInTransactionAsync(conn =>
            {
                //  Группы — по их СВЕЖЕМУ id, до переименования: сама группа тоже может быть в парах.
                foreach (var g in groupUpdates)
                {
                    conn.Execute("update ProfileItem set ProtoExtra = ? where IndexId = ?", g.ProtoExtra, g.IndexId);
                }
                foreach (var (n, o, sameBody) in pairs)
                {
                    if (sameBody)
                    {
                        conn.Execute("update ProfileItem set IndexId = ?, Address = ? where IndexId = ?", o.IndexId, o.Address, n.IndexId);
                    }
                    else
                    {
                        conn.Execute("update ProfileItem set IndexId = ? where IndexId = ?", o.IndexId, n.IndexId);
                    }
                }
            });
        }
        catch (Exception ex)
        {
            //  До фиксации ничего не поменялось: свежие id и файлы остаются как есть.
            Logging.SaveLog(_tag, ex);
            return keptFiles;
        }

        //  Зафиксировано: у неизменившихся конфигов провайдера строка снова смотрит на прежний файл.
        //  Прежний файл остаётся, свежая копия больше никому не нужна.
        foreach (var (n, o, _) in pairs.Where(p => p.SameBody))
        {
            keptFiles.Add(o.Address);
            DeleteCustomConfigFiles([n.Address]);
        }
        return keptFiles;
    }

    /// <summary>Отпечаток содержимого файла конфига провайдера; null, если файла нет или это не Custom.</summary>
    private static string? CustomBodyHash(ProfileItem item)
    {
        if (item.ConfigType != EConfigType.Custom || item.Address.IsNullOrEmpty())
        {
            return null;
        }
        try
        {
            var path = File.Exists(item.Address) ? item.Address : Utils.GetConfigPath(item.Address);
            return File.Exists(path) ? Utils.GetMd5(File.ReadAllText(path)) : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Puts a subscription's previous servers back after a refresh deleted them and then imported
    /// nothing. <see cref="AddBatchServers"/> deletes BEFORE it has the replacement
    /// (RemoveServersViaSubid), so every path that ends with zero imported servers used to leave the
    /// user with an empty list and no way back.
    ///
    /// The restore is EXACT, not approximate: IndexId is the ProfileItem primary key
    /// (Models/Entities/ProfileItem.cs:156), so the rows return under their ORIGINAL ids and
    /// config.IndexId, ProfileExItem and ServerStatItem all keep resolving. Rows still present are
    /// filtered out first — RemoveServersViaSubid with isSub=1 only deletes `isSub = 1` rows, while the
    /// snapshot covers every row of the group — so re-inserting can never hit a primary-key conflict.
    /// Best-effort and idempotent: a failure here is logged, never thrown, and never blocks the caller.
    /// </summary>
    private static async Task RestoreOriginalSubServers(string subid, List<ProfileItem>? lstOriSub)
    {
        if (lstOriSub is not { Count: > 0 })
        {
            return;
        }

        try
        {
            var remaining = (await AppManager.Instance.ProfileItemIndexes(subid)) ?? [];
            var lstRestore = lstOriSub.Where(t => !remaining.Contains(t.IndexId)).ToList();
            if (lstRestore.Count > 0)
            {
                await SQLiteHelper.Instance.InsertAllAsync(lstRestore);
                Logging.SaveLog($"{_tag}: subscription update imported 0 servers, restored {lstRestore.Count} existing server(s) for subid {subid}");
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    #endregion Batch add servers

    #region Sub & Group

    /// <summary>
    /// Add a subscription item from URL
    /// Creates a new subscription with default settings
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="url">Subscription URL</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddSubItem(Config config, string url)
    {
        //already exists
        var count = await SQLiteHelper.Instance.TableAsync<SubItem>().CountAsync(e => e.Url == url);
        if (count > 0)
        {
            return 0;
        }
        SubItem subItem = new()
        {
            Id = string.Empty,
            Url = url,
            // Stamp the recognised v2rayNG-family UA on manually-added subs (paste / clipboard / QR)
            // exactly as the Telegram/account path does. Without it the row carries a blank UA and any
            // fetch that reads item.UserAgent directly (i.e. does not route through
            // SubscriptionHandler.ResolveSubUserAgent) would send a blank/branding UA and get the
            // «Приложение не поддерживается» placeholder instead of the real server list.
            UserAgent = Global.SubscriptionUserAgent
        };

        var uri = Utils.TryUri(url);
        if (uri == null)
        {
            return -1;
        }
        //Do not allow http protocol
        if (url.StartsWith(Global.HttpProtocol) && !Utils.IsPrivateNetwork(uri.IdnHost))
        {
            //TODO Temporary reminder to be removed later
            NoticeManager.Instance.Enqueue(ResUI.InsecureUrlProtocol);
            //return -1;
        }

        var queryVars = Utils.ParseQueryString(uri.Query);
        subItem.Remarks = queryVars["remarks"] ?? "import_sub";

        return await AddSubItem(config, subItem);
    }

    /// <summary>
    /// Add or update a subscription item
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="subItem">Subscription item to add or update</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddSubItem(Config config, SubItem subItem)
    {
        var item = await AppManager.Instance.GetSubItem(subItem.Id);
        if (item is null)
        {
            item = subItem;
        }
        else
        {
            item.Remarks = subItem.Remarks;
            item.Url = subItem.Url;
            item.MoreUrl = subItem.MoreUrl;
            item.Enabled = subItem.Enabled;
            item.AutoUpdateInterval = subItem.AutoUpdateInterval;
            item.UserAgent = subItem.UserAgent;
            item.Sort = subItem.Sort;
            item.Filter = subItem.Filter;
            item.UpdateTime = subItem.UpdateTime;
            item.ConvertTarget = subItem.ConvertTarget;
            item.PrevProfile = subItem.PrevProfile;
            item.NextProfile = subItem.NextProfile;
            item.PreSocksPort = subItem.PreSocksPort;
            item.Memo = subItem.Memo;
        }

        if (item.Id.IsNullOrEmpty())
        {
            item.Id = Utils.GetGuid(false);

            if (item.Sort <= 0)
            {
                var maxSort = 0;
                if (await SQLiteHelper.Instance.TableAsync<SubItem>().CountAsync() > 0)
                {
                    var lstSubs = await AppManager.Instance.SubItems();
                    maxSort = lstSubs.LastOrDefault()?.Sort ?? 0;
                }
                item.Sort = maxSort + 1;
            }
        }
        if (await SQLiteHelper.Instance.ReplaceAsync(item) > 0)
        {
            return 0;
        }
        else
        {
            return -1;
        }
    }

    /// <summary>
    /// Remove servers associated with a subscription ID
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="subid">Subscription ID</param>
    /// <param name="isSub">Whether to only remove servers marked as subscription items</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> RemoveServersViaSubid(Config config, string subid, bool isSub)
    {
        return await RemoveServersViaSubid(config, subid, isSub, null);
    }

    /// <summary>
    /// Remove servers associated with a subscription ID.
    ///
    /// Two hard-won rules live here, both of which cost real user data before:
    ///
    /// 1. Only the CUSTOM files of rows this call actually deletes may be touched. The old code
    ///    collected every Custom row of the group and deleted its backing json REGARDLESS of
    ///    <paramref name="isSub"/>, while the DELETE with isSub=1 spares `isSub = 0` rows. So a
    ///    manually added custom profile that happens to sit in a subscription's group silently lost
    ///    the file behind it on the next subscription refresh: the row stayed in the list, the
    ///    config it points at was gone, and connecting to it failed forever after.
    ///
    /// 2. Deleting the files is CLEANUP, not part of the data operation, so it must never throw.
    ///    <see cref="AddBatchServers"/> calls this to make room for a fresh import; an exception
    ///    escaping here (a locked file on Windows, an empty Address whose GetConfigPath resolves to
    ///    the guiConfigs DIRECTORY) unwound past its restore path with the rows already deleted, and
    ///    the whole subscription was gone. Each delete is therefore best-effort and logged.
    ///
    /// <paramref name="deferredFiles"/> lets a caller that may need to PUT THE ROWS BACK take the
    /// file list instead of the deletion: the files stay on disk until the caller knows the
    /// replacement really landed (see <see cref="AddBatchServers"/>).
    /// </summary>
    private static async Task<int> RemoveServersViaSubid(Config config, string subid, bool isSub, List<string>? deferredFiles)
    {
        if (subid.IsNullOrEmpty())
        {
            return -1;
        }
        var customProfile = isSub
            ? await SQLiteHelper.Instance.TableAsync<ProfileItem>()
                .Where(t => t.Subid == subid && t.ConfigType == EConfigType.Custom && t.IsSub == true)
                .ToListAsync()
            : await SQLiteHelper.Instance.TableAsync<ProfileItem>()
                .Where(t => t.Subid == subid && t.ConfigType == EConfigType.Custom)
                .ToListAsync();
        if (isSub)
        {
            await SQLiteHelper.Instance.ExecuteAsync($"delete from ProfileItem where isSub = 1 and subid = '{subid}'");
        }
        else
        {
            await SQLiteHelper.Instance.ExecuteAsync($"delete from ProfileItem where subid = '{subid}'");
        }

        var files = customProfile.Select(t => t.Address).Where(t => t.IsNotEmpty()).ToList();
        if (deferredFiles is not null)
        {
            deferredFiles.AddRange(files);
        }
        else
        {
            DeleteCustomConfigFiles(files);
        }

        return 0;
    }

    /// <summary>
    /// Best-effort removal of the json files behind CUSTOM profiles. Cleanup only: a failure is
    /// logged and swallowed so it can never abort — or unwind out of — the caller's data operation.
    /// An empty name is skipped (GetConfigPath("") is the guiConfigs DIRECTORY, and File.Delete on a
    /// directory throws).
    /// </summary>
    private static void DeleteCustomConfigFiles(IEnumerable<string> fileNames)
    {
        foreach (var name in fileNames)
        {
            if (name.IsNullOrEmpty())
            {
                continue;
            }
            try
            {
                File.Delete(Utils.GetConfigPath(name));
            }
            catch (Exception ex)
            {
                Logging.SaveLog(_tag, ex);
            }
        }
    }

    /// <summary>
    /// Delete a subscription item and all its associated servers
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="id">Subscription ID to delete</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> DeleteSubItem(Config config, string id)
    {
        //  Тот же замок, что у импорта этой подписки (AddBatchServers): удаление не вклинивается в
        //  середину импорта, а импорт, дождавшись замка, видит, что подписки больше нет.
        var gate = id.IsNotEmpty() ? GetSubImportLock(id) : null;
        if (gate is not null)
        {
            await gate.WaitAsync();
        }
        try
        {
            var item = await AppManager.Instance.GetSubItem(id);
            if (item is null)
            {
                return 0;
            }
            //  Две записи подряд: между ними серверы уже без подписки и на экране на миг стали бы
            //  группой «Мои серверы». Экран читает список только до или после них (ReadServersSettledAsync).
            await BeginGroupReplace();
            try
            {
                await SQLiteHelper.Instance.DeleteAsync(item);
                await RemoveServersViaSubid(config, id, false);
            }
            finally
            {
                EndGroupReplace();
            }

            if (item.Id == config.SubIndexId)
            {
                var subs = await AppManager.Instance.SubItems();
                config.SubIndexId = subs.LastOrDefault()?.Id;
            }

            return 0;
        }
        finally
        {
            gate?.Release();
        }
    }

    /// <summary>
    /// Move servers to a different group (subscription)
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="lstProfile">List of profiles to move</param>
    /// <param name="subid">Target subscription ID</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> MoveToGroup(Config config, List<ProfileItem> lstProfile, string subid)
    {
        foreach (var item in lstProfile)
        {
            item.Subid = subid;
        }
        await SQLiteHelper.Instance.UpdateAllAsync(lstProfile);

        return 0;
    }

    #endregion Sub & Group

    #region Routing

    /// <summary>
    /// Save a routing item to the database
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="item">Routing item to save</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> SaveRoutingItem(Config config, RoutingItem item)
    {
        if (item.Id.IsNullOrEmpty())
        {
            item.Id = Utils.GetGuid(false);
        }

        if (await SQLiteHelper.Instance.ReplaceAsync(item) > 0)
        {
            return 0;
        }
        else
        {
            return -1;
        }
    }

    /// <summary>
    /// Add multiple routing rules to a routing item
    /// </summary>
    /// <param name="routingItem">Routing item to add rules to</param>
    /// <param name="strData">JSON string containing rules data</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> AddBatchRoutingRules(RoutingItem routingItem, string strData)
    {
        if (strData.IsNullOrEmpty())
        {
            return -1;
        }

        var lstRules = JsonUtils.Deserialize<List<RulesItem>>(strData);
        if (lstRules == null)
        {
            return -1;
        }

        foreach (var item in lstRules)
        {
            item.Id = Utils.GetGuid(false);
        }
        routingItem.RuleNum = lstRules.Count;
        routingItem.RuleSet = JsonUtils.Serialize(lstRules, false);

        if (routingItem.Id.IsNullOrEmpty())
        {
            routingItem.Id = Utils.GetGuid(false);
        }

        if (await SQLiteHelper.Instance.ReplaceAsync(routingItem) > 0)
        {
            return 0;
        }
        else
        {
            return -1;
        }
    }

    /// <summary>
    /// Move a routing rule within a rules list
    /// Supports moving to top, up, down, bottom or specific position
    /// </summary>
    /// <param name="rules">List of routing rules</param>
    /// <param name="index">Index of the rule to move</param>
    /// <param name="eMove">Direction to move the rule</param>
    /// <param name="pos">Target position when using EMove.Position</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> MoveRoutingRule(List<RulesItem> rules, int index, EMove eMove, int pos = -1)
    {
        var count = rules.Count;
        if (index < 0 || index > rules.Count - 1)
        {
            return -1;
        }
        switch (eMove)
        {
            case EMove.Top:
                {
                    if (index == 0)
                    {
                        return 0;
                    }
                    var item = JsonUtils.DeepCopy(rules[index]);
                    rules.RemoveAt(index);
                    rules.Insert(0, item);

                    break;
                }
            case EMove.Up:
                {
                    if (index == 0)
                    {
                        return 0;
                    }
                    var item = JsonUtils.DeepCopy(rules[index]);
                    rules.RemoveAt(index);
                    rules.Insert(index - 1, item);

                    break;
                }

            case EMove.Down:
                {
                    if (index == count - 1)
                    {
                        return 0;
                    }
                    var item = JsonUtils.DeepCopy(rules[index]);
                    rules.RemoveAt(index);
                    rules.Insert(index + 1, item);

                    break;
                }
            case EMove.Bottom:
                {
                    if (index == count - 1)
                    {
                        return 0;
                    }
                    var item = JsonUtils.DeepCopy(rules[index]);
                    rules.RemoveAt(index);
                    rules.Add(item);

                    break;
                }
            case EMove.Position:
                {
                    var removeItem = rules[index];
                    var item = JsonUtils.DeepCopy(rules[index]);
                    rules.Insert(pos, item);
                    rules.Remove(removeItem);
                    break;
                }
        }
        return await Task.FromResult(0);
    }

    /// <summary>
    /// Set the default routing configuration
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="routingItem">Routing item to set as default</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> SetDefaultRouting(Config config, RoutingItem routingItem)
    {
        var items = await AppManager.Instance.RoutingItems();
        if (items.Any(t => t.Id == routingItem.Id && t.IsActive == true))
        {
            return -1;
        }

        foreach (var item in items)
        {
            if (item.Id == routingItem.Id)
            {
                item.IsActive = true;
            }
            else
            {
                item.IsActive = false;
            }
        }

        await SQLiteHelper.Instance.UpdateAllAsync(items);

        return 0;
    }

    /// <summary>
    /// Get the current default routing configuration
    /// If no default is set, selects the first available routing item
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <returns>The default routing item</returns>
    public static async Task<RoutingItem> GetDefaultRouting(Config config)
    {
        var item = await SQLiteHelper.Instance.TableAsync<RoutingItem>().FirstOrDefaultAsync(it => it.IsActive == true);
        if (item is null)
        {
            var item2 = await SQLiteHelper.Instance.TableAsync<RoutingItem>().FirstOrDefaultAsync();
            await SetDefaultRouting(config, item2);
            return item2;
        }

        return item;
    }

    /// <summary>
    /// Initialize routing rules from built-in or external templates
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="blImportAdvancedRules">Whether to import advanced rules</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> InitRouting(Config config, bool blImportAdvancedRules = false)
    {
        if (config.ConstItem.RouteRulesTemplateSourceUrl.IsNullOrEmpty())
        {
            await InitBuiltinRouting(config, blImportAdvancedRules);
        }
        else
        {
            await InitExternalRouting(config, blImportAdvancedRules);
        }

        return 0;
    }

    /// <summary>
    /// Initialize routing rules from external templates
    /// Downloads and processes routing templates from a URL
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="blImportAdvancedRules">Whether to import advanced rules</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> InitExternalRouting(Config config, bool blImportAdvancedRules = false)
    {
        var downloadHandle = new DownloadService();
        var templateContent = await downloadHandle.TryDownloadString(config.ConstItem.RouteRulesTemplateSourceUrl, true, "");
        if (templateContent.IsNullOrEmpty())
        {
            return await InitBuiltinRouting(config, blImportAdvancedRules); // fallback
        }

        var template = JsonUtils.Deserialize<RoutingTemplate>(templateContent);
        if (template == null)
        {
            return await InitBuiltinRouting(config, blImportAdvancedRules); // fallback
        }

        var items = await AppManager.Instance.RoutingItems();
        var maxSort = items.Count;
        if (!blImportAdvancedRules && items.Where(t => t.Remarks.StartsWith(template.Version)).ToList().Count > 0)
        {
            return 0;
        }
        for (var i = 0; i < template.RoutingItems.Length; i++)
        {
            var item = template.RoutingItems[i];

            if (item.Url.IsNullOrEmpty() && item.RuleSet.IsNullOrEmpty())
            {
                continue;
            }

            var ruleSetsString = !item.RuleSet.IsNullOrEmpty()
                ? item.RuleSet
                : await downloadHandle.TryDownloadString(item.Url, true, "");

            if (ruleSetsString.IsNullOrEmpty())
            {
                continue;
            }

            item.Remarks = $"{template.Version}-{item.Remarks}";
            item.Enabled = true;
            item.Sort = ++maxSort;
            item.Url = string.Empty;

            await AddBatchRoutingRules(item, ruleSetsString);

            //first rule as default at first startup
            if (!blImportAdvancedRules && i == 0)
            {
                await SetDefaultRouting(config, item);
            }
        }

        return 0;
    }

    /// <summary>
    /// Initialize built-in routing rules
    /// Creates default routing configurations (whitelist, blacklist, global)
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="blImportAdvancedRules">Whether to import advanced rules</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> InitBuiltinRouting(Config config, bool blImportAdvancedRules = false)
    {
        var ver = "V4-";
        var items = await AppManager.Instance.RoutingItems();

        //TODO Temporary code to be removed later
        var lockItem = items?.FirstOrDefault(t => t.Locked == true);
        if (lockItem != null)
        {
            await ConfigHandler.RemoveRoutingItem(lockItem);
            items = await AppManager.Instance.RoutingItems();
        }

        if (!blImportAdvancedRules && items.Count(u => u.Remarks.StartsWith(ver)) > 0)
        {
            //migrate
            //TODO Temporary code to be removed later
            if (config.RoutingBasicItem.RoutingIndexId.IsNotEmpty())
            {
                var item = items.FirstOrDefault(t => t.Id == config.RoutingBasicItem.RoutingIndexId);
                if (item != null)
                {
                    await SetDefaultRouting(config, item);
                }
                config.RoutingBasicItem.RoutingIndexId = string.Empty;
            }

            return 0;
        }

        var maxSort = items.Count;
        //Bypass the mainland
        var item2 = new RoutingItem()
        {
            Remarks = $"{ver}绕过大陆(Whitelist)",
            Url = string.Empty,
            Sort = maxSort + 1,
        };
        await AddBatchRoutingRules(item2, EmbedUtils.GetEmbedText(Global.CustomRoutingFileName + "white"));

        //Blacklist
        var item3 = new RoutingItem()
        {
            Remarks = $"{ver}黑名单(Blacklist)",
            Url = string.Empty,
            Sort = maxSort + 2,
        };
        await AddBatchRoutingRules(item3, EmbedUtils.GetEmbedText(Global.CustomRoutingFileName + "black"));

        //Global
        var item1 = new RoutingItem()
        {
            Remarks = $"{ver}全局(Global)",
            Url = string.Empty,
            Sort = maxSort + 3,
        };
        await AddBatchRoutingRules(item1, EmbedUtils.GetEmbedText(Global.CustomRoutingFileName + "global"));

        if (!blImportAdvancedRules)
        {
            await SetDefaultRouting(config, item2);
        }
        return 0;
    }

    /// <summary>
    /// Remove a routing item from the database
    /// </summary>
    /// <param name="routingItem">Routing item to remove</param>
    public static async Task RemoveRoutingItem(RoutingItem routingItem)
    {
        await SQLiteHelper.Instance.DeleteAsync(routingItem);
    }

    #endregion Routing

    #region DNS

    /// <summary>
    /// Initialize built-in DNS configurations
    /// Creates default DNS items for V2Ray and sing-box
    /// Also checks existing DNS items and disables those with empty NormalDNS
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <returns>0 if successful</returns>
    public static async Task<int> InitBuiltinDNS(Config config)
    {
        var items = await AppManager.Instance.DNSItems();

        // Check existing DNS items and disable those with empty NormalDNS
        var needsUpdate = false;
        foreach (var existingItem in items)
        {
            if (existingItem.NormalDNS.IsNullOrEmpty() && existingItem.Enabled)
            {
                existingItem.Enabled = false;
                needsUpdate = true;
            }
        }

        // Update items if any changes were made
        if (needsUpdate)
        {
            await SQLiteHelper.Instance.UpdateAllAsync(items);
        }

        if (items.Count <= 0)
        {
            var item = new DNSItem()
            {
                Remarks = "V2ray",
                CoreType = ECoreType.Xray,
                Enabled = false,
            };
            await SaveDNSItems(config, item);

            var item2 = new DNSItem()
            {
                Remarks = "sing-box",
                CoreType = ECoreType.sing_box,
                Enabled = false,
            };
            await SaveDNSItems(config, item2);
        }

        return 0;
    }

    /// <summary>
    /// Save a DNS item to the database
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="item">DNS item to save</param>
    /// <returns>0 if successful, -1 if failed</returns>
    public static async Task<int> SaveDNSItems(Config config, DNSItem item)
    {
        if (item == null)
        {
            return -1;
        }

        if (item.Id.IsNullOrEmpty())
        {
            item.Id = Utils.GetGuid(false);
        }

        if (await SQLiteHelper.Instance.ReplaceAsync(item) > 0)
        {
            return 0;
        }
        else
        {
            return -1;
        }
    }

    /// <summary>
    /// Get an external DNS configuration from URL
    /// Downloads and processes DNS templates
    /// </summary>
    /// <param name="type">Core type (Xray or sing-box)</param>
    /// <param name="url">URL of the DNS template</param>
    /// <returns>DNS item with configuration from the URL</returns>
    public static async Task<DNSItem> GetExternalDNSItem(ECoreType type, string url)
    {
        var currentItem = await AppManager.Instance.GetDNSItem(type);

        var downloadHandle = new DownloadService();
        var templateContent = await downloadHandle.TryDownloadString(url, true, "");
        if (templateContent.IsNullOrEmpty())
        {
            return currentItem;
        }

        var template = JsonUtils.Deserialize<DNSItem>(templateContent);
        if (template == null)
        {
            return currentItem;
        }

        if (!template.NormalDNS.IsNullOrEmpty())
        {
            template.NormalDNS = await downloadHandle.TryDownloadString(template.NormalDNS, true, "");
        }

        if (!template.TunDNS.IsNullOrEmpty())
        {
            template.TunDNS = await downloadHandle.TryDownloadString(template.TunDNS, true, "");
        }

        template.Id = currentItem.Id;
        template.Enabled = currentItem.Enabled;
        template.Remarks = currentItem.Remarks;
        template.CoreType = type;

        return template;
    }

    #endregion DNS

    #region Simple DNS

    public static SimpleDNSItem InitBuiltinSimpleDNS()
    {
        return new SimpleDNSItem()
        {
            UseSystemHosts = false,
            AddCommonHosts = true,
            FakeIP = false,
            GlobalFakeIp = true,
            BlockBindingQuery = true,
            DirectDNS = Global.DomainDirectDNSAddress.FirstOrDefault(),
            RemoteDNS = Global.DomainRemoteDNSAddress.FirstOrDefault(),
            BootstrapDNS = Global.DomainPureIPDNSAddress.FirstOrDefault(),
            DefaultsVersion = SimpleDnsDefaultsVersion,
        };
    }

    /// <summary>Версия встроенных умолчаний DNS. 1 — прямой и bootstrap-DNS на Яндексе.</summary>
    private const int SimpleDnsDefaultsVersion = 1;

    //  Умолчания апстрима, которые InitBuiltinSimpleDNS писал в конфиг сам, без человека: v2rayN 7.21+
    //  (с него начат departament) — DNSPod 119.29.29.29; 7.14–7.20 — DoH AliDNS для прямого DNS и
    //  223.5.5.5 для bootstrap (поле появилось в 7.17). Старые версии — на случай конфига, принесённого
    //  из папки апстрима. Сравнение точное: значение, набранное руками, хоть на пробел иное, — выбор.
    private static readonly string[] UpstreamDirectDnsDefaults = ["119.29.29.29", "https://dns.alidns.com/dns-query"];

    private static readonly string[] UpstreamBootstrapDnsDefaults = ["119.29.29.29", "223.5.5.5"];

    /// <summary>
    /// Один раз переводит китайские умолчания апстрима на умолчания departament (Яндекс).
    ///
    /// Прямой DNS и bootstrap в departament для ПК не показаны ни на одном экране, поэтому у всех,
    /// кто ставил приложение до этой версии, в конфиге так и лежит то, что записал апстрим. Меняется
    /// только поле, которое ТОЧНО равно одному из тех умолчаний; своё значение не трогаем.
    ///
    /// Прямой DNS не трогаем и тогда, когда заданы ожидаемые IP (у апстрима они пусты, значит, их
    /// ставил человек). Они проверяют ответы именно прямого DNS: ответ вне списка Xray молча
    /// отбрасывает и спрашивает удалённый DNS. Такую пару подбирали под свой резолвер, и подменить
    /// резолвер под ней значило бы поменять смысл чужой настройки.
    ///
    /// Один раз — по <see cref="SimpleDNSItem.DefaultsVersion"/>: если потом кто-то вернёт себе
    /// 119.29.29.29 руками, это его выбор, и следующий запуск его не отменит.
    /// </summary>
    /// <returns>true, если блок доведён до текущей версии сейчас; false, если уже был доведён.</returns>
    public static bool MigrateSimpleDnsDefaults(SimpleDNSItem item)
    {
        if (item.DefaultsVersion >= SimpleDnsDefaultsVersion)
        {
            return false;
        }

        if (UpstreamDirectDnsDefaults.Contains(item.DirectDNS) && item.DirectExpectedIPs.IsNullOrEmpty())
        {
            item.DirectDNS = Global.DomainDirectDNSAddress.First();
        }
        if (UpstreamBootstrapDnsDefaults.Contains(item.BootstrapDNS))
        {
            item.BootstrapDNS = Global.DomainPureIPDNSAddress.First();
        }

        item.DefaultsVersion = SimpleDnsDefaultsVersion;
        return true;
    }

    public static async Task<SimpleDNSItem> GetExternalSimpleDNSItem(string url)
    {
        var downloadHandle = new DownloadService();
        var templateContent = await downloadHandle.TryDownloadString(url, true, "");
        if (templateContent.IsNullOrEmpty())
        {
            return null;
        }

        var template = JsonUtils.Deserialize<SimpleDNSItem>(templateContent);
        if (template == null)
        {
            return null;
        }

        return template;
    }

    #endregion Simple DNS

    #region Custom Config

    public static async Task<int> InitBuiltinFullConfigTemplate(Config config)
    {
        var items = await AppManager.Instance.FullConfigTemplateItem();
        if (items.Count <= 0)
        {
            var item = new FullConfigTemplateItem()
            {
                Remarks = "V2ray",
                CoreType = ECoreType.Xray,
            };
            await SaveFullConfigTemplate(config, item);

            var item2 = new FullConfigTemplateItem()
            {
                Remarks = "sing-box",
                CoreType = ECoreType.sing_box,
            };
            await SaveFullConfigTemplate(config, item2);
        }

        return 0;
    }

    public static async Task<int> SaveFullConfigTemplate(Config config, FullConfigTemplateItem item)
    {
        if (item == null)
        {
            return -1;
        }

        if (item.Id.IsNullOrEmpty())
        {
            item.Id = Utils.GetGuid(false);
        }

        if (await SQLiteHelper.Instance.ReplaceAsync(item) > 0)
        {
            return 0;
        }
        else
        {
            return -1;
        }
    }

    #endregion Custom Config

    #region Regional Presets

    /// <summary>
    /// Apply regional presets for geo-specific configurations
    /// Sets up geo files, routing rules, and DNS for specific regions
    /// </summary>
    /// <param name="config">Current configuration</param>
    /// <param name="type">Type of preset (Default, Russia, Iran)</param>
    /// <returns>True if successful</returns>
    public static async Task<bool> ApplyRegionalPreset(Config config, EPresetType type)
    {
        switch (type)
        {
            case EPresetType.Default:
                config.ConstItem.GeoSourceUrl = "";
                config.ConstItem.SrsSourceUrl = "";
                config.ConstItem.RouteRulesTemplateSourceUrl = "";

                await SQLiteHelper.Instance.DeleteAllAsync<DNSItem>();
                await InitBuiltinDNS(config);

                config.SimpleDNSItem = InitBuiltinSimpleDNS();
                break;

            case EPresetType.Russia:
                config.ConstItem.GeoSourceUrl = Global.GeoFilesSources[1];
                config.ConstItem.SrsSourceUrl = Global.SingboxRulesetSources[1];
                config.ConstItem.RouteRulesTemplateSourceUrl = Global.RoutingRulesSources[1];

                var xrayDnsRussia = await GetExternalDNSItem(ECoreType.Xray, Global.DNSTemplateSources[1] + "v2ray.json");
                var singboxDnsRussia = await GetExternalDNSItem(ECoreType.sing_box, Global.DNSTemplateSources[1] + "sing_box.json");
                var simpleDnsRussia = await GetExternalSimpleDNSItem(Global.DNSTemplateSources[1] + "simple_dns.json");

                if (simpleDnsRussia == null)
                {
                    xrayDnsRussia.Enabled = true;
                    singboxDnsRussia.Enabled = true;
                    config.SimpleDNSItem = InitBuiltinSimpleDNS();
                }
                else
                {
                    config.SimpleDNSItem = simpleDnsRussia;
                }
                await SaveDNSItems(config, xrayDnsRussia);
                await SaveDNSItems(config, singboxDnsRussia);
                break;

            case EPresetType.Iran:
                config.ConstItem.GeoSourceUrl = Global.GeoFilesSources[2];
                config.ConstItem.SrsSourceUrl = Global.SingboxRulesetSources[2];
                config.ConstItem.RouteRulesTemplateSourceUrl = Global.RoutingRulesSources[2];

                var xrayDnsIran = await GetExternalDNSItem(ECoreType.Xray, Global.DNSTemplateSources[2] + "v2ray.json");
                var singboxDnsIran = await GetExternalDNSItem(ECoreType.sing_box, Global.DNSTemplateSources[2] + "sing_box.json");
                var simpleDnsIran = await GetExternalSimpleDNSItem(Global.DNSTemplateSources[2] + "simple_dns.json");

                if (simpleDnsIran == null)
                {
                    xrayDnsIran.Enabled = true;
                    singboxDnsIran.Enabled = true;
                    config.SimpleDNSItem = InitBuiltinSimpleDNS();
                }
                else
                {
                    config.SimpleDNSItem = simpleDnsIran;
                }
                await SaveDNSItems(config, xrayDnsIran);
                await SaveDNSItems(config, singboxDnsIran);
                break;
        }

        return true;
    }

    #endregion Regional Presets

    #region UIItem

    public static WindowSizeItem? GetWindowSizeItem(Config config, string typeName)
    {
        var sizeItem = config?.UiItem?.WindowSizeItem?.FirstOrDefault(t => t.TypeName == typeName);
        if (sizeItem == null || sizeItem.Width <= 0 || sizeItem.Height <= 0)
        {
            return null;
        }

        return sizeItem;
    }

    public static int SaveWindowSizeItem(Config config, string typeName, double width, double height)
    {
        var sizeItem = config?.UiItem?.WindowSizeItem?.FirstOrDefault(t => t.TypeName == typeName);
        if (sizeItem == null)
        {
            sizeItem = new WindowSizeItem { TypeName = typeName };
            config.UiItem.WindowSizeItem.Add(sizeItem);
        }

        sizeItem.Width = (int)width;
        sizeItem.Height = (int)height;

        return 0;
    }

    public static int SaveMainGirdHeight(Config config, double height1, double height2)
    {
        var uiItem = config.UiItem ?? new();

        uiItem.MainGirdHeight1 = (int)(height1 + 0.1);
        uiItem.MainGirdHeight2 = (int)(height2 + 0.1);

        return 0;
    }

    #endregion UIItem
}
