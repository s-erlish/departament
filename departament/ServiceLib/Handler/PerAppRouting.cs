namespace ServiceLib.Handler;

/// <summary>
/// Правила, которые экран «По приложениям» (PerAppProxyPage) вписывает в действующий набор правил.
/// Метки в Remarks отличают их от правил человека: экран переписывает только свои.
///
/// В режиме «Только выбранные» правил два: выбранные программы — через VPN, всё остальное —
/// напрямую. Второе стоит СРАЗУ за первым. Раньше оно дописывалось в конец набора, и это ломало
/// две вещи. Генераторы DNS (V2rayDnsService, SingboxDnsService) по последнему правилу «всё напрямую»
/// решают, что VPN не нужен и DNS тоже, — и запросы всех программ, в том числе пущенных через VPN,
/// уходили открытым текстом к DNS провайдера. А в наборах, которые сами кончаются правилом «всё
/// через VPN» («Весь трафик через прокси»), до дописанного правила очередь не доходила вовсе, и режим
/// молча не работал: через VPN шло всё.
/// </summary>
public static class PerAppRouting
{
    public const string MarkerBypass = "__departament_perapp_bypass";
    public const string MarkerInclude = "__departament_perapp_include";
    public const string MarkerCatchAll = "__departament_perapp_catchall";

    /// <summary>
    /// Ставит «всё остальное напрямую» сразу за правилом выбранных программ. Нужно для наборов,
    /// сохранённых прежними версиями, где оно стоит в конце; новый экран сразу пишет правильно.
    /// Возвращает копию набора — сохранённый не трогает, — или тот же набор, если менять нечего.
    /// </summary>
    public static RoutingItem? WithCatchAllAfterInclude(RoutingItem? routing)
    {
        if (routing is null || routing.RuleSet.IsNullOrEmpty())
        {
            return routing;
        }
        var rules = JsonUtils.Deserialize<List<RulesItem>>(routing.RuleSet);
        if (rules is null)
        {
            return routing;
        }
        var include = rules.FindIndex(r => r.Remarks == MarkerInclude);
        var catchAll = rules.FindIndex(r => r.Remarks == MarkerCatchAll);
        if (include < 0 || catchAll < 0 || catchAll == include + 1)
        {
            return routing;
        }

        var rule = rules[catchAll];
        rules.RemoveAt(catchAll);
        rules.Insert(rules.FindIndex(r => r.Remarks == MarkerInclude) + 1, rule);

        var copy = JsonUtils.DeepCopy(routing);
        copy.RuleSet = JsonUtils.Serialize(rules, false);
        return copy;
    }
}
