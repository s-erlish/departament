namespace departament.Desktop.Common;

/// <summary>
/// Байты и скорость в тексте, одним счётом с Android (<c>_Ext.kt</c>: <c>toTrafficString</c>
/// и <c>toSpeedString</c>, тесты <c>UnitFormatTest</c>): «12,4 ГБ», «240 КБ/с», «1,2 МБ/с». По 1024,
/// единицы по-русски в русском интерфейсе и по-английски в английском («12.4 GB», «1.2 MB/s»), между
/// числом и единицей неразрывный пробел: единица не уезжает на следующую строку.
/// <para/>
/// Скорость под щитом раньше печатал движковый <c>Utils.HumanFy</c>: английские «KB/s» посреди
/// русского интерфейса, где трафик рядом уже читался «1 ГБ / 100 ГБ», и два разных нуля: «0 KB/s»
/// до подключения и «0.0 B/s» после (HumanFy ставит десятую долю даже нулю). Объём, в свою очередь,
/// печатали три копии форматтера по-разному: карточка подписки срезала «,0», а «Купить подписку»
/// ставила ТОЧКУ посреди русского текста («100.0 ГБ»).
/// </summary>
public static class ByteSize
{
    /// <summary>U+00A0: число и единица не расходятся по строкам.</summary>
    private const char UnitSpace = '\u00A0';

    /// <summary>
    /// Объём: «0 Б», «512 Б», «1023 Б», «1,0 КБ», «12,4 ГБ». Байты целыми, дальше по 1024 с единицы,
    /// до которой дорос объём, и всегда с одним знаком после запятой.
    /// </summary>
    public static string Bytes(long bytes)
    {
        var units = Units();
        if (bytes < 1024)
        {
            return $"{Math.Max(0, bytes)}{UnitSpace}{units[0]}";
        }

        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return $"{OneDecimal(value)}{UnitSpace}{units[unit]}";
    }

    /// <summary>
    /// Скорость: «0 КБ/с», «0,4 КБ/с», «99,9 КБ/с», «240 КБ/с», «1,2 МБ/с».
    /// <para/>
    /// Начинается с КБ/с: скорость меньше килобайта всё равно пишется в КБ/с, а не третьей единицей.
    /// Один знак после запятой до 100, дальше без него; к следующей единице — с 1000, так что до
    /// запятой не больше трёх цифр. Ноль одним видом, «0 КБ/с», и в покое, и под туннелем: раньше
    /// строка читалась то «0», то «0.0». Самые широкие строки этой формы отдаёт
    /// <see cref="SpeedSamples"/> — под них вид резервирует место.
    /// </summary>
    public static string Speed(long bytesPerSecond)
    {
        var units = Units();
        var value = Math.Max(0, bytesPerSecond) / 1024.0;
        var unit = 1;
        while (value >= 999.5 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        var figure = value < 0.05
            ? "0"
            : value < 99.95 ? OneDecimal(value) : value.ToString("0", CultureInfo.InvariantCulture);
        return L.F("Common_PerSecond", $"{figure}{UnitSpace}{units[unit]}");
    }

    /// <summary>
    /// Скорость из замера движка. ServiceLib отдаёт её не в байтах, а в КиБ за секунду: Xray делит
    /// счётчики на 1024 (StatisticsXrayService.linkBase), sing-box на 1000. <see cref="Speed"/>, как и
    /// Android, считает в байтах, поэтому здесь перевод обратно. У sing-box показанная скорость
    /// выходит на 2,4% выше настоящей: делитель 1000 вместо 1024 стоит в самом движке.
    /// </summary>
    public static string EngineSpeed(long kibPerSecond) => Speed(kibPerSecond * 1024);

    /// <summary>
    /// Самые длинные строки скорости на текущем языке, по две на единицу («99,9 МБ/с» и «999 МБ/с»):
    /// вид меряет их своим шрифтом и резервирует ширину под самую широкую, чтобы строка «↑ скорость ·
    /// время · ↓ скорость» не ходила туда-сюда при смене значений. Меряется каждая единица: ширина
    /// букв «К» и «М» разная, а цифры под tnum одинаковые.
    /// </summary>
    public static IEnumerable<string> SpeedSamples()
    {
        var units = Units();
        for (var i = 1; i < units.Length; i++)
        {
            yield return L.F("Common_PerSecond", $"{OneDecimal(99.9)}{UnitSpace}{units[i]}");
            yield return L.F("Common_PerSecond", $"999{UnitSpace}{units[i]}");
        }
    }

    /// <summary>
    /// Число с одним знаком после запятой. Цифры собираются инвариантно (никаких чужих цифр и
    /// разрядных пробелов), меняется только разделитель: запятая по-русски, точка по-английски.
    /// Тот же приём, что у Android.
    /// </summary>
    private static string OneDecimal(double value)
    {
        var text = value.ToString("0.0", CultureInfo.InvariantCulture);
        return L.Instance.CurrentLang == "en" ? text : text.Replace('.', ',');
    }

    private static string[] Units() => L.T("Common_ByteUnits").Split(',');
}
