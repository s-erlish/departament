using System.Collections;
using System.Text.Json.Serialization.Metadata;
using AwesomeAssertions;
using Xunit;

namespace ServiceLib.Tests.Common;

/// <summary>
/// Config читается и пишется по метаданным, собранным при компиляции (ConfigJsonContext), а не
/// отражением. Проверяем, что для файла настроек это ничего не меняет: каждый параметр, заполненный
/// значением не по умолчанию, пишется в тот же JSON и читается обратно так же, как отражением.
/// </summary>
public class ConfigJsonTests
{
    // Те же настройки, что в JsonUtils, но строго на отражении: эталон прежнего поведения.
    private static readonly JsonSerializerOptions ReflectionWriteAll = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        TypeInfoResolver = new DefaultJsonTypeInfoResolver(),
    };

    private static readonly JsonSerializerOptions ReflectionWriteCompact = new()
    {
        WriteIndented = false,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        TypeInfoResolver = new DefaultJsonTypeInfoResolver(),
    };

    [Fact]
    public void SaveConfigWritesTheSameJsonAsReflection()
    {
        var config = Filled<Config>();

        JsonUtils.Serialize(config, true, true).Should().Be(JsonSerializer.Serialize(config, ReflectionWriteAll));
    }

    [Fact]
    public void DeepCopyWritesTheSameJsonAsReflection()
    {
        var config = Filled<Config>();

        JsonUtils.Serialize(config, false).Should().Be(JsonSerializer.Serialize(config, ReflectionWriteCompact));
    }

    [Fact]
    public void LoadConfigReadsEverySettingBack()
    {
        var json = JsonSerializer.Serialize(Filled<Config>(), ReflectionWriteAll);

        var read = JsonUtils.Deserialize<Config>(json);

        JsonSerializer.Serialize(read, ReflectionWriteAll).Should().Be(json);
    }

    [Fact]
    public void LoadConfigStillIgnoresNameCaseAndComments()
    {
        var json = JsonSerializer.Serialize(Filled<Config>(), ReflectionWriteAll);
        var camel = Regex.Replace(json, "\"([A-Z])([A-Za-z0-9_]*)\":", m => $"\"{char.ToLowerInvariant(m.Groups[1].Value[0])}{m.Groups[2].Value}\":");

        var read = JsonUtils.Deserialize<Config>("// правлено руками\n" + camel);

        JsonSerializer.Serialize(read, ReflectionWriteAll).Should().Be(json);
    }

    // Каждое записываемое свойство получает значение НЕ по умолчанию: свойство, потерянное при чтении,
    // тогда видно по разнице, а не прячется за совпавшим умолчанием. Незнакомый тип свойства валит
    // тест, чтобы новый параметр конфига не выпал из проверки молча.
    private static T Filled<T>() where T : new() => (T)FillObject(typeof(T), 0);

    private static object FillObject(Type type, int depth)
    {
        var obj = Activator.CreateInstance(type)!;
        foreach (var p in type.GetProperties(BindingFlags.Public | BindingFlags.Instance))
        {
            if (!p.CanWrite || p.GetIndexParameters().Length > 0)
            {
                continue;
            }
            p.SetValue(obj, ValueFor(p.PropertyType, p.GetValue(obj), $"{type.Name}.{p.Name}", depth));
        }
        return obj;
    }

    private static object? ValueFor(Type type, object? current, string name, int depth)
    {
        var t = Nullable.GetUnderlyingType(type) ?? type;
        if (t == typeof(string))
        {
            return $"{name}-v";
        }
        if (t == typeof(bool))
        {
            return current is not true;
        }
        if (t == typeof(int))
        {
            return (current as int? ?? 0) + 7;
        }
        if (t == typeof(long))
        {
            return (current as long? ?? 0) + 7;
        }
        if (t == typeof(double))
        {
            return (current as double? ?? 0) + 0.5;
        }
        if (t.IsEnum)
        {
            foreach (var v in Enum.GetValues(t))
            {
                if (!Equals(v, current))
                {
                    return v;
                }
            }
            throw new NotSupportedException($"{name}: enum {t} has a single value");
        }
        if (t.IsGenericType && t.GetGenericTypeDefinition() == typeof(List<>))
        {
            var list = (IList)Activator.CreateInstance(t)!;
            list.Add(ValueFor(t.GetGenericArguments()[0], null, name, depth + 1));
            return list;
        }
        if (t.IsClass && depth < 4)
        {
            return FillObject(t, depth + 1);
        }
        throw new NotSupportedException($"{name}: {type}");
    }
}
