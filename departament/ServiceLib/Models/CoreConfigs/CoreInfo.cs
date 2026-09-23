namespace ServiceLib.Models.CoreConfigs;

[Serializable]
public class CoreInfo
{
    public ECoreType CoreType { get; set; }
    public List<string>? CoreExes { get; set; }
    public string? Arguments { get; set; }
    public string? Url { get; set; }
    public bool AbsolutePath { get; set; }
    public IDictionary<string, string?> Environment { get; set; } = new Dictionary<string, string?>();
}
