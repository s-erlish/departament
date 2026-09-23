namespace ServiceLib.Services;

/// <summary>
/// Обновление Geo-баз и наборов правил (geoip/geosite, bin/srss). Это ДАННЫЕ маршрутизации, а не код:
/// их по-прежнему можно обновить из «Файлов ресурсов» и по расписанию автообновления.
///
/// Здесь же раньше жили загрузка ядер (Xray, sing-box, mihomo) последней версии с их GitHub и обновление
/// самого приложения из 2dust/v2rayN. Обе убраны. Ядра едут вместе с приложением и закреплены в выпуске:
/// незакреплённый sing-box 1.14 однажды уже отверг конфиг приложения и сломал подключение. Приложение
/// обновляется только из своих выпусков, см. AppUpdateManager.
/// </summary>
public class UpdateService(Config config, Func<bool, string, Task> updateFunc)
{
    private readonly Config? _config = config;
    private readonly Func<bool, string, Task>? _updateFunc = updateFunc;
    private readonly int _timeout = 30;

    public async Task UpdateGeoFileAll()
    {
        await UpdateGeoFiles();
        await UpdateOtherFiles();
        await UpdateSrsFileAll();
        await UpdateFunc(true, string.Format(ResUI.MsgDownloadGeoFileSuccessfully, "geo"));
    }

    #region Geo private

    private async Task UpdateGeoFiles()
    {
        var geoUrl = string.IsNullOrEmpty(_config?.ConstItem.GeoSourceUrl)
            ? Global.GeoUrl
            : _config.ConstItem.GeoSourceUrl;

        List<string> files = ["geosite", "geoip"];
        foreach (var geoName in files)
        {
            var fileName = $"{geoName}.dat";
            var targetPath = Utils.GetBinPath($"{fileName}");
            var url = string.Format(geoUrl, geoName);

            await DownloadGeoFile(url, fileName, targetPath);
        }
    }

    private async Task UpdateOtherFiles()
    {
        //If it is not in China area, no update is required
        if (_config.ConstItem.GeoSourceUrl.IsNotEmpty())
        {
            return;
        }

        foreach (var url in Global.OtherGeoUrls)
        {
            var fileName = Path.GetFileName(url);
            var targetPath = Utils.GetBinPath($"{fileName}");

            await DownloadGeoFile(url, fileName, targetPath);
        }
    }

    private async Task UpdateSrsFileAll()
    {
        var geoipFiles = new List<string>();
        var geoSiteFiles = new List<string>();

        // Collect from routing rules
        var routingItems = await AppManager.Instance.RoutingItems();
        foreach (var routing in routingItems)
        {
            var rules = JsonUtils.Deserialize<List<RulesItem>>(routing.RuleSet);
            foreach (var item in rules ?? [])
            {
                AddPrefixedItems(item.Ip, Global.GeoIPPrefix, geoipFiles);
                AddPrefixedItems(item.Domain, Global.GeoSitePrefix, geoSiteFiles);
            }
        }

        // Collect from DNS configuration
        var dnsItem = await AppManager.Instance.GetDNSItem(ECoreType.sing_box);
        if (dnsItem != null)
        {
            ExtractDnsRuleSets(dnsItem.NormalDNS, geoipFiles, geoSiteFiles);
            ExtractDnsRuleSets(dnsItem.TunDNS, geoipFiles, geoSiteFiles);
        }

        // Append default items
        geoSiteFiles.AddRange(["google", "cn", "geolocation-cn", "category-ads-all"]);

        // Download files
        var path = Utils.GetBinPath("srss");
        if (!Directory.Exists(path))
        {
            Directory.CreateDirectory(path);
        }

        foreach (var item in geoipFiles.Distinct())
        {
            await UpdateSrsFile("geoip", item);
        }

        foreach (var item in geoSiteFiles.Distinct())
        {
            await UpdateSrsFile("geosite", item);
        }
    }

    private void AddPrefixedItems(List<string>? items, string prefix, List<string> output)
    {
        if (items == null)
        {
            return;
        }

        foreach (var item in items)
        {
            if (item.StartsWith(prefix))
            {
                output.Add(item.Substring(prefix.Length));
            }
        }
    }

    private void ExtractDnsRuleSets(string? dnsJson, List<string> geoipFiles, List<string> geoSiteFiles)
    {
        if (string.IsNullOrEmpty(dnsJson))
        {
            return;
        }

        try
        {
            var dns = JsonUtils.Deserialize<Dns4Sbox>(dnsJson);
            if (dns?.rules != null)
            {
                foreach (var rule in dns.rules)
                {
                    ExtractSrsRuleSets(rule, geoipFiles, geoSiteFiles);
                }
            }
        }
        catch { }
    }

    private void ExtractSrsRuleSets(Rule4Sbox? rule, List<string> geoipFiles, List<string> geoSiteFiles)
    {
        if (rule == null)
        {
            return;
        }

        AddPrefixedItems(rule.rule_set, "geosite-", geoSiteFiles);
        AddPrefixedItems(rule.rule_set, "geoip-", geoipFiles);

        // Handle nested rules recursively
        if (rule.rules != null)
        {
            foreach (var nestedRule in rule.rules)
            {
                ExtractSrsRuleSets(nestedRule, geoipFiles, geoSiteFiles);
            }
        }
    }

    private async Task UpdateSrsFile(string type, string srsName)
    {
        var srsUrl = string.IsNullOrEmpty(_config.ConstItem.SrsSourceUrl)
                        ? Global.SingboxRulesetUrl
                        : _config.ConstItem.SrsSourceUrl;

        var fileName = $"{type}-{srsName}.srs";
        var targetPath = Path.Combine(Utils.GetBinPath("srss"), fileName);
        var url = string.Format(srsUrl, type, $"{type}-{srsName}", srsName);

        await DownloadGeoFile(url, fileName, targetPath);
    }

    private async Task DownloadGeoFile(string url, string fileName, string targetPath)
    {
        var tmpFileName = Utils.GetTempPath(Utils.GetGuid());

        DownloadService downloadHandle = new();
        downloadHandle.UpdateCompleted += (sender2, args) =>
        {
            if (args.Success)
            {
                _ = UpdateFunc(false, string.Format(ResUI.MsgDownloadGeoFileSuccessfully, fileName));

                try
                {
                    if (File.Exists(tmpFileName))
                    {
                        File.Copy(tmpFileName, targetPath, true);

                        File.Delete(tmpFileName);
                        //await    UpdateFunc(true, "");
                    }
                }
                catch (Exception ex)
                {
                    _ = UpdateFunc(false, ex.Message);
                }
            }
            else
            {
                _ = UpdateFunc(false, args.Msg);
            }
        };
        downloadHandle.Error += (sender2, args) =>
        {
            _ = UpdateFunc(false, args.GetException().Message);
        };

        await downloadHandle.DownloadFileAsync(url, tmpFileName, true, _timeout);
    }

    #endregion Geo private

    private async Task UpdateFunc(bool notify, string msg)
    {
        await _updateFunc?.Invoke(notify, msg);
    }
}
