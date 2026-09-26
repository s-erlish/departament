using AwesomeAssertions;
using Xunit;

namespace ServiceLib.Tests.Handler;

/// <summary>
/// «Только выбранные» на экране «По приложениям»: правило «всё остальное напрямую» должно стоять сразу
/// за правилом выбранных программ. Стоя в конце набора, оно переключало DNS всех программ на прямой
/// (утечка мимо VPN), а в наборах со своим «всё через VPN» в конце не срабатывало вовсе.
/// </summary>
public class PerAppRoutingTests
{
    private static RulesItem Rule(string remarks, string outbound) =>
        new() { Id = remarks, Remarks = remarks, OutboundTag = outbound, Enabled = true };

    private static RoutingItem Routing(params RulesItem[] rules) =>
        new() { Id = "r", Remarks = "set", RuleSet = JsonUtils.Serialize(rules.ToList(), false) };

    private static List<string?> Order(RoutingItem? routing) =>
        JsonUtils.Deserialize<List<RulesItem>>(routing!.RuleSet)!.Select(r => r.Remarks).ToList();

    [Fact]
    public void OldCatchAllAtTheEndMovesRightAfterTheIncludeRule()
    {
        var saved = Routing(
            Rule(PerAppRouting.MarkerInclude, Global.ProxyTag),
            Rule("ru-direct", Global.DirectTag),
            Rule("all-proxy", Global.ProxyTag),
            Rule(PerAppRouting.MarkerCatchAll, Global.DirectTag));

        var fixedSet = PerAppRouting.WithCatchAllAfterInclude(saved);

        Order(fixedSet).Should().Equal(PerAppRouting.MarkerInclude, PerAppRouting.MarkerCatchAll, "ru-direct", "all-proxy");
        // The stored set is left as it was — only the copy used for this connection changes.
        Order(saved).Last().Should().Be(PerAppRouting.MarkerCatchAll);
    }

    [Fact]
    public void AlreadyInPlaceOrNoPerAppRulesIsLeftAlone()
    {
        var inPlace = Routing(
            Rule(PerAppRouting.MarkerInclude, Global.ProxyTag),
            Rule(PerAppRouting.MarkerCatchAll, Global.DirectTag),
            Rule("ru-direct", Global.DirectTag));
        var plain = Routing(Rule("ru-direct", Global.DirectTag), Rule("all-proxy", Global.ProxyTag));

        PerAppRouting.WithCatchAllAfterInclude(inPlace).Should().BeSameAs(inPlace);
        PerAppRouting.WithCatchAllAfterInclude(plain).Should().BeSameAs(plain);
        PerAppRouting.WithCatchAllAfterInclude(null).Should().BeNull();
    }
}
