using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Win32;
using v2rayN.Desktop.Account.Dto;

namespace v2rayN.Desktop.Account;

/// <summary>
/// Encrypted-at-rest store for the app session JWT (7-day, no refresh), the cached user profile, a
/// stable device id (HWID), and the uuid-&gt;guid map of subscriptions we manage. Port of V2rayNG
/// auth/AuthTokenStore.kt (MMKV + Android Keystore) using an AES-encrypted file under guiConfigs.
///
/// The AES-256 key is derived from a machine-stable secret (the Windows MachineGuid, else the Linux
/// machine-id, else the machine/user name) so the blob decrypts across restarts on the same machine
/// but is opaque if copied elsewhere. Tokens and subscription URLs are never logged.
/// </summary>
public static class AuthTokenStore
{
    private const string FileName = "departament_auth.dat";
    private const string Tag = "AuthTokenStore";

    private static readonly object _lock = new();
    private static StoreData? _data;
    private static byte[]? _key;
    private static string? _machineSeed;

    private sealed class StoreData
    {
        public string? Token { get; set; }
        public long ExpiresAt { get; set; }
        public string? UserJson { get; set; }
        public string? DeviceId { get; set; }
        public Dictionary<string, string> ManagedGuids { get; set; } = new();
    }

    #region public API

    /// <summary>
    /// Stable per-device HWID (32 lowercase hex) that survives reinstall, computed once and reused.
    /// First run derives it from MD5(MachineGuid) to match the UUID-without-dashes format the backend
    /// expects, so the panel keeps a single device slot per physical machine. Random uuid fallback.
    /// </summary>
    public static string DeviceId()
    {
        lock (_lock)
        {
            var data = Data();
            if (data.DeviceId.IsNotEmpty())
            {
                return data.DeviceId!;
            }
            var generated = ComputeStableDeviceId();
            data.DeviceId = generated;
            Persist();
            return generated;
        }
    }

    /// <summary>Persists a new session. No refresh token in this backend.</summary>
    public static void SaveSession(string token, long? expiresAt = null, UserProfileDto? user = null)
    {
        lock (_lock)
        {
            var data = Data();
            data.Token = token;
            data.ExpiresAt = expiresAt ?? 0L;
            data.UserJson = user != null ? JsonSerializer.Serialize(user, ApiJson.Options) : null;
            // An explicit sign-in is the ONE act allowed to replace a blob this process could not read:
            // the user is standing in front of the app handing us a fresh session, and a permanently
            // corrupt file must not condemn them to signing in again on every launch.
            _blobUnreadable = false;
            Persist();
        }
    }

    /// <summary>Updates just the cached user profile (keeps the current token).</summary>
    public static void SaveUser(UserProfileDto user)
    {
        lock (_lock)
        {
            Data().UserJson = JsonSerializer.Serialize(user, ApiJson.Options);
            Persist();
        }
    }

    public static string? GetToken()
    {
        lock (_lock)
        {
            return Data().Token;
        }
    }

    public static long GetExpiresAt()
    {
        lock (_lock)
        {
            return Data().ExpiresAt;
        }
    }

    public static UserProfileDto? GetUser()
    {
        lock (_lock)
        {
            var json = Data().UserJson;
            if (json.IsNullOrEmpty())
            {
                return null;
            }
            try
            {
                return JsonSerializer.Deserialize<UserProfileDto>(json!, ApiJson.Options);
            }
            catch
            {
                return null;
            }
        }
    }

    public static bool IsLoggedIn()
    {
        lock (_lock)
        {
            return Data().Token.IsNotEmpty();
        }
    }

    /// <summary>uuid -&gt; local subscription guid map of subscriptions owned by the auth flow.</summary>
    public static Dictionary<string, string> GetManagedGuids()
    {
        lock (_lock)
        {
            return new Dictionary<string, string>(Data().ManagedGuids);
        }
    }

    public static void SetManagedGuids(IDictionary<string, string> map)
    {
        lock (_lock)
        {
            Data().ManagedGuids = new Dictionary<string, string>(map);
            Persist();
        }
    }

    /// <summary>
    /// Ends the session but keeps the account's local footprint: the token, its expiry and the cached
    /// profile go, the uuid-&gt;guid map STAYS.
    ///
    /// This is what an EXPIRED/REVOKED JWT gets (<see cref="AccountSession.EndSession"/>). The 7-day
    /// token dying says nothing about the user's subscriptions — they are still theirs and must still be
    /// on the machine when they sign back in. Keeping the map is also what makes that sign-in an UPDATE:
    /// <see cref="SubscriptionSyncManager"/> reuses the stored guid per uuid, so the same subscription is
    /// refreshed in place instead of being re-added beside the old one.
    /// </summary>
    public static void ClearSession()
    {
        lock (_lock)
        {
            var data = Data();
            data.Token = null;
            data.ExpiresAt = 0L;
            data.UserJson = null;
            Persist();
        }
    }

    /// <summary>
    /// Clears the session AND the managed-subscription references — EXPLICIT LOGOUT ONLY. Called after
    /// <see cref="SubscriptionSyncManager.RemoveAllManaged"/> has actually removed those subscriptions,
    /// so the map is dropped once it describes nothing. Keeps deviceId. A dead JWT takes
    /// <see cref="ClearSession"/> instead — see the note there.
    /// </summary>
    public static void Clear()
    {
        lock (_lock)
        {
            var data = Data();
            data.Token = null;
            data.ExpiresAt = 0L;
            data.UserJson = null;
            data.ManagedGuids = new Dictionary<string, string>();
            Persist();
        }
    }

    #endregion public API

    #region persistence

    private static StoreData Data() => _data ??= Load();

    /// <summary>
    /// Set when a blob EXISTS on disk that this process could not turn into a <see cref="StoreData"/>.
    /// While it is set <see cref="Persist"/> WRITES NOTHING.
    ///
    /// The read used to swallow every failure into "start fresh", and "fresh" is then written straight
    /// back: the very first <see cref="DeviceId"/> call — which happens on EVERY api request, because
    /// the HWID header is built from it — finds no device id in the blank store, generates one and
    /// calls Persist, renaming a blank blob over the real one. A read that failed for a reason that has
    /// nothing to do with the contents (an antivirus or backup agent holding the file for a few ms on
    /// Windows, a momentary permission error, a machine seed that could not be read so the key came out
    /// different) therefore did not just sign the user out for that run — it DESTROYED the session AND
    /// the uuid-&gt;guid map, permanently, so the next import re-added every account subscription beside
    /// the ones already there. Exactly the shape of «вылетает аккаунт», and the same doctrine as
    /// 12fc34fe applies: we could not read it, so we do not change it.
    ///
    /// The one deliberate act allowed to replace an unreadable blob is an explicit sign-in
    /// (<see cref="SaveSession"/>) — otherwise a genuinely corrupt file could never be replaced and the
    /// user would be signing in on every launch forever.
    /// </summary>
    private static bool _blobUnreadable;

    /// <summary>Attempts for a read that failed transiently, and the pause between them.</summary>
    private const int LoadAttempts = 3;

    private const int LoadRetryDelayMs = 60;

    private static StoreData Load()
    {
        string path;
        try
        {
            path = Utils.GetConfigPath(FileName);
            if (!File.Exists(path))
            {
                // No blob at all: a genuinely first run. Writing is safe — there is nothing to lose.
                return new StoreData();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(Tag, ex);
            _blobUnreadable = true;
            return new StoreData();
        }

        for (var attempt = 1; attempt <= LoadAttempts; attempt++)
        {
            try
            {
                var blob = File.ReadAllBytes(path);
                var json = Decrypt(blob);
                if (json.IsNullOrEmpty())
                {
                    break;
                }
                var data = JsonSerializer.Deserialize<StoreData>(json!);
                if (data != null)
                {
                    return data;
                }
                break;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Somebody else is holding the file. This read happens once per launch, so a couple of
                // short retries cost nothing and cover the whole realistic window.
                Logging.SaveLog(Tag, ex);
                if (attempt < LoadAttempts)
                {
                    Thread.Sleep(LoadRetryDelayMs);
                }
            }
            catch (Exception ex)
            {
                // Undecryptable or not JSON — retrying cannot change the answer.
                Logging.SaveLog(Tag, ex);
                break;
            }
        }

        _blobUnreadable = true;
        return new StoreData();
    }

    /// <summary>
    /// Writes the blob ATOMICALLY: a full temp file first, then a single rename over the live one.
    /// A plain in-place write is not atomic — a crash, a power cut or a full disk part-way through
    /// leaves a truncated blob that no longer decrypts, and <see cref="Load"/> can only read that as a
    /// blank store: the user is silently signed out AND the uuid-&gt;guid map is gone, so the next
    /// import re-adds every account subscription beside the ones already on the machine. The rename is
    /// atomic on both NTFS and ext4, so the file on disk is always either the old blob or the new one.
    /// </summary>
    private static void Persist()
    {
        // Never write over a blob we failed to read — see _blobUnreadable. The session for this run is
        // lost either way; the subscriptions, their servers and the uuid->guid map are not.
        if (_blobUnreadable)
        {
            return;
        }
        try
        {
            var json = JsonSerializer.Serialize(_data ?? new StoreData());
            var blob = Encrypt(json);
            var path = Utils.GetConfigPath(FileName);
            var temp = path + ".tmp";
            File.WriteAllBytes(temp, blob);
            File.Move(temp, path, true);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(Tag, ex);
        }
    }

    #endregion persistence

    #region crypto + machine identity

    private static byte[] Key => _key ??= SHA256.HashData(Encoding.UTF8.GetBytes("departament-vpn|auth|v1|" + MachineSeed()));

    private static byte[] Encrypt(string plaintext)
    {
        using var aes = Aes.Create();
        aes.Key = Key;
        aes.GenerateIV();
        using var enc = aes.CreateEncryptor();
        var pt = Encoding.UTF8.GetBytes(plaintext);
        var ct = enc.TransformFinalBlock(pt, 0, pt.Length);
        var result = new byte[aes.IV.Length + ct.Length];
        Buffer.BlockCopy(aes.IV, 0, result, 0, aes.IV.Length);
        Buffer.BlockCopy(ct, 0, result, aes.IV.Length, ct.Length);
        return result;
    }

    private static string? Decrypt(byte[] blob)
    {
        if (blob.Length <= 16)
        {
            return null;
        }
        using var aes = Aes.Create();
        aes.Key = Key;
        var iv = new byte[16];
        Buffer.BlockCopy(blob, 0, iv, 0, 16);
        aes.IV = iv;
        using var dec = aes.CreateDecryptor();
        var pt = dec.TransformFinalBlock(blob, 16, blob.Length - 16);
        return Encoding.UTF8.GetString(pt);
    }

    private static string ComputeStableDeviceId()
    {
        var guid = ReadMachineGuid();
        if (guid.IsNullOrEmpty())
        {
            return Guid.NewGuid().ToString("N");
        }
        try
        {
            var bytes = MD5.HashData(Encoding.UTF8.GetBytes(guid!));
            return Convert.ToHexString(bytes).ToLowerInvariant();
        }
        catch
        {
            return Guid.NewGuid().ToString("N");
        }
    }

    private static string MachineSeed()
    {
        if (_machineSeed != null)
        {
            return _machineSeed;
        }
        var guid = ReadMachineGuid();
        _machineSeed = guid.IsNotEmpty() ? guid! : $"{Environment.MachineName}|{Environment.UserName}";
        return _machineSeed;
    }

    /// <summary>Raw machine identifier: Windows MachineGuid, then Linux machine-id. Null when unavailable.</summary>
    private static string? ReadMachineGuid()
    {
        try
        {
            if (OperatingSystem.IsWindows())
            {
                using var baseKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
                using var sub = baseKey.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
                if (sub?.GetValue("MachineGuid") is string val && val.IsNotEmpty())
                {
                    return val;
                }
            }
            else
            {
                foreach (var path in new[] { "/etc/machine-id", "/var/lib/dbus/machine-id" })
                {
                    if (File.Exists(path))
                    {
                        var id = File.ReadAllText(path).Trim();
                        if (id.IsNotEmpty())
                        {
                            return id;
                        }
                    }
                }
            }
        }
        catch
        {
            // ignore — fall back to the machine/user-name seed
        }
        return null;
    }

    #endregion crypto + machine identity
}
