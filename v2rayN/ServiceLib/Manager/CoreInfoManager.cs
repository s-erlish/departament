namespace ServiceLib.Manager;

public sealed class CoreInfoManager
{
    private static readonly Lazy<CoreInfoManager> _instance = new(() => new());
    private List<CoreInfo>? _coreInfo;
    public static CoreInfoManager Instance => _instance.Value;

    public CoreInfoManager()
    {
        InitCoreInfo();
    }

    public CoreInfo? GetCoreInfo(ECoreType coreType)
    {
        if (_coreInfo == null)
        {
            InitCoreInfo();
        }
        return _coreInfo?.FirstOrDefault(t => t.CoreType == coreType);
    }

    public List<CoreInfo> GetCoreInfo()
    {
        if (_coreInfo == null)
        {
            InitCoreInfo();
        }
        return _coreInfo ?? [];
    }

    public string GetCoreExecFile(CoreInfo? coreInfo, out string msg)
    {
        var fileName = string.Empty;
        msg = string.Empty;
        foreach (var name in coreInfo?.CoreExes)
        {
            var vName = Utils.GetBinPath(Utils.GetExeName(name), coreInfo.CoreType.ToString());
            if (File.Exists(vName))
            {
                fileName = vName;
                break;
            }
        }
        if (fileName.IsNullOrEmpty())
        {
            msg = string.Format(ResUI.NotFoundCore, Utils.GetBinPath("", coreInfo?.CoreType.ToString()), coreInfo?.CoreExes?.LastOrDefault(), coreInfo?.Url);
            Logging.SaveLog(msg);
        }
        return fileName;
    }

    private void InitCoreInfo()
    {
        _coreInfo =
        [
            new CoreInfo
                {
                    CoreType = ECoreType.v2rayN,
                    Url = GetCoreUrl(ECoreType.v2rayN),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.v2fly,
                    CoreExes = ["v2ray"],
                    Arguments = "{0}",
                    Url = GetCoreUrl(ECoreType.v2fly),
                    Environment = new Dictionary<string, string?>()
                    {
                        { Global.V2RayLocalAsset, Utils.GetBinPath("") },
                    },
                },

                new CoreInfo
                {
                    CoreType = ECoreType.v2fly_v5,
                    CoreExes = ["v2ray"],
                    Arguments = "run -c {0} -format jsonv5",
                    Url = GetCoreUrl(ECoreType.v2fly_v5),
                    Environment = new Dictionary<string, string?>()
                    {
                        { Global.V2RayLocalAsset, Utils.GetBinPath("") },
                    },
                },

                new CoreInfo
                {
                    CoreType = ECoreType.Xray,
                    CoreExes = ["xray"],
                    Arguments = "run -c {0}",
                    Url = GetCoreUrl(ECoreType.Xray),
                    Environment = new Dictionary<string, string?>()
                    {
                        { Global.XrayLocalAsset, Utils.GetBinPath("") },
                        { Global.XrayLocalCert, Utils.GetBinPath("") },
                    },
                },

                new CoreInfo
                {
                    CoreType = ECoreType.mihomo,
                    CoreExes = GetMihomoCoreExes(),
                    Arguments = "-f {0}" + PortableMode(),
                    Url = GetCoreUrl(ECoreType.mihomo),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.hysteria,
                    CoreExes = ["hysteria"],
                    Arguments = "",
                    Url = GetCoreUrl(ECoreType.hysteria),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.naiveproxy,
                    CoreExes = [ "naive", "naiveproxy"],
                    Arguments = "{0}",
                    Url = GetCoreUrl(ECoreType.naiveproxy),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.tuic,
                    CoreExes = ["tuic-client", "tuic"],
                    Arguments = "-c {0}",
                    Url = GetCoreUrl(ECoreType.tuic),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.sing_box,
                    CoreExes = ["sing-box-client", "sing-box"],
                    Arguments = "run -c {0} --disable-color",
                    Url = GetCoreUrl(ECoreType.sing_box),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.juicity,
                    CoreExes = ["juicity-client", "juicity"],
                    Arguments = "run -c {0}",
                    Url = GetCoreUrl(ECoreType.juicity)
                },

                new CoreInfo
                {
                    CoreType = ECoreType.hysteria2,
                    CoreExes = ["hysteria-windows-amd64", "hysteria-linux-amd64", "hysteria"],
                    Arguments = "",
                    Url = GetCoreUrl(ECoreType.hysteria2),
                },

                new CoreInfo
                {
                    CoreType = ECoreType.brook,
                    CoreExes = ["brook_windows_amd64", "brook_linux_amd64", "brook"],
                    Arguments = " {0}",
                    Url = GetCoreUrl(ECoreType.brook),
                    AbsolutePath = true,
                },

                new CoreInfo
                {
                    CoreType = ECoreType.overtls,
                    CoreExes = [ "overtls-bin", "overtls"],
                    Arguments = "-r client -c {0}",
                    Url =  GetCoreUrl(ECoreType.overtls),
                    AbsolutePath = false,
                },

                new CoreInfo
                {
                    CoreType = ECoreType.shadowquic,
                    CoreExes = [ "shadowquic" ],
                    Arguments = "-c {0}",
                    Url =  GetCoreUrl(ECoreType.shadowquic),
                    AbsolutePath = false,
                },

                new CoreInfo
                {
                    CoreType = ECoreType.mieru,
                    CoreExes = [ "mieru" ],
                    Arguments = "run",
                    Url =  GetCoreUrl(ECoreType.mieru),
                    AbsolutePath = false,
                    Environment = new Dictionary<string, string?>()
                    {
                        { "MIERU_CONFIG_JSON_FILE", "{0}" },
                    },
                },
        ];
    }

    private static string PortableMode()
    {
        return $" -d {Utils.GetBinPath("").AppendQuotes()}";
    }

    private static string GetCoreUrl(ECoreType eCoreType)
    {
        return $"{Global.GithubUrl}/{Global.CoreUrls[eCoreType]}/releases";
    }

    private static List<string>? GetMihomoCoreExes()
    {
        var names = new List<string>();

        if (Utils.IsWindows())
        {
            names.Add("mihomo-windows-amd64-v1");
            names.Add("mihomo-windows-amd64-compatible");
            names.Add("mihomo-windows-amd64");
            names.Add("mihomo-windows-arm64");
        }
        else if (Utils.IsLinux())
        {
            names.Add("mihomo-linux-amd64-v1");
            names.Add("mihomo-linux-amd64");
            names.Add("mihomo-linux-arm64");
            names.Add("mihomo-linux-riscv64");
            names.Add("mihomo-linux-loong64-abi2");
        }
        else if (Utils.IsMacOS())
        {
            names.Add("mihomo-darwin-amd64-v1");
            names.Add("mihomo-darwin-amd64");
            names.Add("mihomo-darwin-arm64");
        }

        names.Add("clash");
        names.Add("mihomo");

        return names;
    }
}
