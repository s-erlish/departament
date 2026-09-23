namespace ServiceLib.Services.CoreConfig;

public partial class CoreConfigSingboxService
{
    private void ConvertGeo2Ruleset()
    {
        static void AddRuleSets(List<string> ruleSets, List<string>? rule_set)
        {
            if (rule_set != null)
            {
                ruleSets.AddRange(rule_set);
            }
        }
        var geosite = "geosite";
        var geoip = "geoip";
        var ruleSets = new List<string>();

        //convert route geosite & geoip to ruleset
        foreach (var rule in _coreConfig.route.rules.Where(t => t.geosite?.Count > 0).ToList() ?? [])
        {
            rule.rule_set ??= [];
            rule.rule_set.AddRange(rule?.geosite?.Select(t => $"{geosite}-{t}").ToList() ?? []);
            rule.geosite = null;
            AddRuleSets(ruleSets, rule.rule_set);
        }
        foreach (var rule in _coreConfig.route.rules.Where(t => t.geoip?.Count > 0).ToList() ?? [])
        {
            rule.rule_set ??= [];
            rule.rule_set.AddRange(rule?.geoip?.Select(t => $"{geoip}-{t}").ToList() ?? []);
            rule.geoip = null;
            AddRuleSets(ruleSets, rule.rule_set);
        }

        //convert dns geosite & geoip to ruleset
        foreach (var rule in _coreConfig.dns?.rules.Where(t => t.geosite?.Count > 0).ToList() ?? [])
        {
            rule.rule_set ??= [];
            rule.rule_set.AddRange(rule?.geosite?.Select(t => $"{geosite}-{t}").ToList() ?? []);
            rule.geosite = null;
        }
        foreach (var rule in _coreConfig.dns?.rules.Where(t => t.geoip?.Count > 0).ToList() ?? [])
        {
            rule.rule_set ??= [];
            rule.rule_set.AddRange(rule?.geoip?.Select(t => $"{geoip}-{t}").ToList() ?? []);
            rule.geoip = null;
        }
        foreach (var dnsRule in _coreConfig.dns?.rules.Where(t => t.rule_set?.Count > 0).ToList() ?? [])
        {
            AddRuleSets(ruleSets, dnsRule.rule_set);
        }
        //rules in rules
        foreach (var item in _coreConfig.dns?.rules.Where(t => t.rules?.Count > 0).Select(t => t.rules).ToList() ?? [])
        {
            foreach (var item2 in item ?? [])
            {
                AddRuleSets(ruleSets, item2.rule_set);
            }
        }

        //load custom ruleset file
        List<Ruleset4Sbox> customRulesets = [];

        //  Маршрута может не быть (свежая база до первой инициализации) — GenDns рядом это проверяет,
        //  а здесь обращение без проверки роняло сборку конфига целиком.
        var routing = context.RoutingItem;
        if (routing != null && routing.CustomRulesetPath4Singbox.IsNotEmpty())
        {
            var result = EmbedUtils.LoadResource(routing.CustomRulesetPath4Singbox);
            if (result.IsNotEmpty())
            {
                customRulesets = (JsonUtils.Deserialize<List<Ruleset4Sbox>>(result) ?? [])
                    .Where(t => t.tag != null)
                    .Where(t => t.type != null)
                    .Where(t => t.format != null)
                    .ToList();
            }
        }

        //Local srs files address
        var localSrss = Utils.GetBinPath("srss");

        //Add ruleset srs
        _coreConfig.route.rule_set = [];
        foreach (var item in new HashSet<string>(ruleSets))
        {
            if (item.IsNullOrEmpty())
            { continue; }
            var customRuleset = customRulesets.FirstOrDefault(t => t.tag != null && t.tag.Equals(item));
            if (customRuleset is null)
            {
                var pathSrs = Path.Combine(localSrss, $"{item}.srs");
                if (File.Exists(pathSrs))
                {
                    customRuleset = new()
                    {
                        type = "local",
                        format = "binary",
                        tag = item,
                        path = pathSrs
                    };
                }
                else
                {
                    var srsUrl = string.IsNullOrEmpty(_config.ConstItem.SrsSourceUrl)
                        ? Global.SingboxRulesetUrl
                        : _config.ConstItem.SrsSourceUrl;

                    //  Качать — через proxy, как и раньше, но формой 1.14: download_detour с 1.14
                    //  объявлен устаревшим и в 1.16 роняет ядро, а неявный «клиент по умолчанию»
                    //  (поле просто опустить) устарел так же. http_client ядра до 1.14 не знают, но
                    //  приложение их и не встречает: в сборке 1.14.1, а обновление ядра из
                    //  приложения идёт только вперёд, к стабильным выпускам.
                    customRuleset = new()
                    {
                        type = "remote",
                        format = "binary",
                        tag = item,
                        url = string.Format(srsUrl, item.StartsWith(geosite) ? geosite : geoip, item),
                        http_client = new() { detour = Global.ProxyTag },
                    };
                }
            }
            _coreConfig.route.rule_set.Add(customRuleset);
        }
    }
}
