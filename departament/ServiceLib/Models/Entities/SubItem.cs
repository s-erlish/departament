namespace ServiceLib.Models.Entities;

[Serializable]
public class SubItem
{
    [PrimaryKey]
    public string Id { get; set; }

    public string Remarks { get; set; }

    public string Url { get; set; }

    public string MoreUrl { get; set; }

    public bool Enabled { get; set; } = true;

    public string UserAgent { get; set; } = string.Empty;

    public int Sort { get; set; }

    public string? Filter { get; set; }

    public int AutoUpdateInterval { get; set; }

    public long UpdateTime { get; set; }

    public string? ConvertTarget { get; set; }

    public string? PrevProfile { get; set; }

    public string? NextProfile { get; set; }

    public int? PreSocksPort { get; set; }

    public string? Memo { get; set; }

    // ---- subscription-userinfo metadata (bytes / epoch-seconds) ----
    // Populated from the `subscription-userinfo` response header on each update
    // (Remnawave / 3x-ui / Marzban / Happ / Hiddify panels). Data-driven: 0 until
    // a subscription that actually carries the header has been fetched.
    // NOTE on migration: these columns are added automatically to an existing
    // guiNDB.db by sqlite-net's MigrateTable (ALTER TABLE ADD COLUMN), which the
    // startup call AppManager.InitConfig -> SQLiteHelper.CreateTable<SubItem>()
    // already performs. Old rows read back the CLR defaults below (0 / "" / false);
    // existing callers are untouched because they never reference these members.

    public long UploadUsed { get; set; }       // bytes, header `upload`

    public long DownloadUsed { get; set; }      // bytes, header `download`

    public long TotalTraffic { get; set; }      // bytes, header `total`; <= 0 == unlimited

    public long Expire { get; set; }            // epoch SECONDS, header `expire`; <= 0 == no expiry

    public long UserInfoUpdated { get; set; }   // epoch millis, when the metadata above was captured

    // ---- Happ/Incy-style subscription directives ----
    public bool Pinned { get; set; }            // pinned subscriptions sort first / become the default tab

    public string Announce { get; set; } = string.Empty;      // banner text, `announce` header/directive

    public string SupportUrl { get; set; } = string.Empty;    // `support-url` (e.g. a Telegram link)

    public string WebPageUrl { get; set; } = string.Empty;    // `profile-web-page-url`

    public string ProfileTitle { get; set; } = string.Empty;  // display title, `profile-title` header
}
