namespace ServiceLib.Handler.Fmt;

public class V2rayFmt : BaseFmt
{
    public static List<ProfileItem>? ResolveFullArray(string strData, string? subRemarks)
    {
        var configObjects = JsonUtils.Deserialize<object[]>(strData);
        if (configObjects is not { Length: > 0 })
        {
            return null;
        }

        List<ProfileItem> lstResult = [];
        foreach (var configObject in configObjects)
        {
            var objectString = JsonUtils.Serialize(configObject);
            var profileIt = ResolveFull(objectString, subRemarks);
            if (profileIt != null)
            {
                lstResult.Add(profileIt);
            }
        }

        return lstResult;
    }

    public static ProfileItem? ResolveFull(string strData, string? subRemarks)
    {
        var config = JsonUtils.ParseJson(strData);
        // A Remnawave / departament XRAY_JSON template may omit "inbounds"/"routing" and carry
        // only "outbounds"; require just "outbounds" so such templates still parse as a fallback
        // custom config when typed extraction did not apply.
        if (config?["outbounds"] == null)
        {
            return null;
        }

        var fileName = WriteAllText(strData);

        var profileItem = new ProfileItem
        {
            CoreType = ECoreType.Xray,
            Address = fileName,
            Remarks = config?["remarks"]?.ToString() ?? subRemarks ?? "v2ray_custom"
        };

        return profileItem;
    }
}
