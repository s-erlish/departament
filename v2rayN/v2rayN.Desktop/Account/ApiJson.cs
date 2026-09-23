using System.Text.Json;
using System.Text.Json.Serialization;

namespace v2rayN.Desktop.Account;

/// <summary>
/// Shared System.Text.Json options for the Departament backend DTOs. Port of V2rayNG auth/ApiGson.kt.
///
/// The backend legitimately omits or nulls string fields — a Telegram-only user has no `email`, a
/// fresh account has no `currency`, etc. The null-tolerant string converter maps any JSON null (or a
/// bool/number token) to a non-null string so a partial payload never throws. camelCase naming +
/// case-insensitive matching mirror Gson's field mapping; nulls are omitted on write like Gson.
/// </summary>
public static class ApiJson
{
    public static readonly JsonSerializerOptions Options = Build();

    private static JsonSerializerOptions Build()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            // Case-SENSITIVE (Gson parity): distinct alternates like `tariffId` vs `tariffID` must not
            // collide, and the backend sends exact camelCase keys the CamelCase policy already matches.
            PropertyNameCaseInsensitive = false,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
            NumberHandling = JsonNumberHandling.AllowReadingFromString,
        };
        options.Converters.Add(new NullTolerantStringConverter());
        return options;
    }

    /// <summary>
    /// The first list that actually has entries, else an empty list (never null). Used by the DTOs that
    /// accept several envelope spellings for the same collection: resolving at read time instead of
    /// funnelling during parsing makes the result independent of the order the keys arrive in, and lets
    /// a populated list win over an empty one when a payload carries both.
    /// </summary>
    public static List<T> FirstNonEmpty<T>(params List<T>?[] candidates)
    {
        foreach (var c in candidates)
        {
            if (c is { Count: > 0 })
            {
                return c;
            }
        }
        return new List<T>();
    }

    /// <summary>
    /// Reads a list out of a raw envelope node that is EITHER the array itself (<c>{ data: [...] }</c>)
    /// or an object wrapping it under one of <paramref name="keys"/> (<c>{ data: { items: [...] } }</c>).
    /// Returns null when the node is absent or holds neither shape — a node we cannot read must not
    /// become a parse failure for the whole response, which is why it is taken as a raw
    /// <see cref="JsonElement"/> rather than bound to a concrete type.
    /// </summary>
    public static List<T>? ListFrom<T>(JsonElement node, params string[] keys)
    {
        try
        {
            if (node.ValueKind == JsonValueKind.Array)
            {
                return node.Deserialize<List<T>>(Options);
            }
            if (node.ValueKind != JsonValueKind.Object)
            {
                return null;
            }
            foreach (var key in keys)
            {
                if (node.TryGetProperty(key, out var el) && el.ValueKind == JsonValueKind.Array)
                {
                    return el.Deserialize<List<T>>(Options);
                }
            }
        }
        catch (JsonException)
        {
            // A node shaped like a list but holding something else is simply not our list.
        }
        return null;
    }
}

/// <summary>
/// Maps any JSON null (or a bool/number scalar) to a non-null string, mirroring ApiGson's null-tolerant
/// String adapter so every DTO string is non-null regardless of what the backend sends.
/// </summary>
public sealed class NullTolerantStringConverter : JsonConverter<string>
{
    public override string Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        switch (reader.TokenType)
        {
            case JsonTokenType.Null:
                return string.Empty;
            case JsonTokenType.String:
                return reader.GetString() ?? string.Empty;
            case JsonTokenType.True:
                return "true";
            case JsonTokenType.False:
                return "false";
            case JsonTokenType.Number:
                // Preserve the raw numeric text so an id/amount typed as a JSON number still maps.
                return reader.GetDouble().ToString(System.Globalization.CultureInfo.InvariantCulture);
            default:
                reader.Skip();
                return string.Empty;
        }
    }

    public override void Write(Utf8JsonWriter writer, string value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value);
    }
}
