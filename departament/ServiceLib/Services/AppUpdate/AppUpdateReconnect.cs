namespace ServiceLib.Services.AppUpdate;

/// <summary>
/// Метка передачи пакета установщику: <c>guiTemps/update/pending.json</c>. Пишет её версия, которая уходит
/// в установку, в момент «Перезапустить»; читает и сразу удаляет первый запуск после неё
/// (<c>AppUpdateManager.EnsureReconciled</c>). По метке этот запуск понимает две вещи: удалась ли установка
/// (<see cref="Tag"/> против запущенной версии) и было ли подключение, которое нужно вернуть
/// (<see cref="Reconnect"/>).
///
/// <para>Метка одноразовая, а не настройка: «подключаться при запуске» у приложения нет и не появляется
/// (оно стартует отключённым). Подключение возвращает только запуск, который пришёл следом за установкой.</para>
/// </summary>
public sealed class AppUpdatePendingInstall
{
    /// <summary>Тег выпуска, переданного установщику.</summary>
    public string? Tag { get; set; }

    public bool PreRelease { get; set; }

    /// <summary>Версия, которая передала пакет. Если установка откатится, запустится снова она.</summary>
    public string? From { get; set; }

    /// <summary>Когда пакет передан установщику, UTC.</summary>
    public DateTime? HandedOffUtc { get; set; }

    /// <summary>Подключение в момент «Перезапустить»; null — подключения не было.</summary>
    public AppUpdateReconnectMarker? Reconnect { get; set; }

    public string ToJson() => JsonSerializer.Serialize(this);

    /// <summary>Разбирает метку. Битый или чужой файл — null: такой запуск ведёт себя как обычный.</summary>
    public static AppUpdatePendingInstall? TryParse(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }
        try
        {
            return JsonSerializer.Deserialize<AppUpdatePendingInstall>(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

/// <summary>Что было подключено в момент «Перезапустить».</summary>
public sealed class AppUpdateReconnectMarker
{
    /// <summary>IndexId сервера, к которому было подключение.</summary>
    public string? ServerId { get; set; }

    /// <summary>Режим «весь трафик» (TUN). false — через прокси.</summary>
    public bool Tun { get; set; }
}

/// <summary>Почему подключение возвращается или нет.</summary>
public enum AppUpdateReconnectVerdict
{
    /// <summary>Вернуть: подключиться к серверу из <see cref="AppUpdateReconnect.ChooseServer"/>.</summary>
    Connect,

    /// <summary>Метки нет или подключения в момент «Перезапустить» не было.</summary>
    NotConnected,

    /// <summary>Метка старая (или из будущего): этот запуск пришёл не следом за установкой.</summary>
    Stale,

    /// <summary>Запущена не та версия, что уходила в установку, и не та, что её передала.</summary>
    OtherVersion,

    /// <summary>Пользователь уже сам подключился, отключился или выбрал сервер: его решение главнее.</summary>
    UserActed,

    /// <summary>Режим не восстановить (TUN без прав), а подключать в другом режиме, чем был, нельзя.</summary>
    ModeUnavailable,
}

/// <summary>
/// Вернуть ли подключение после перезапуска ради обновления. Здесь только решение, без базы и без UI:
/// сам запуск подключения делает оболочка тем же путём, что и тап по щиту.
/// </summary>
public static class AppUpdateReconnect
{
    /// <summary>
    /// Сколько метка остаётся в силе. Обычно новый запуск приходит через 3–5 с после «Перезапустить»; в
    /// худшем случае установщик ждёт выхода приложения 30 с и ещё повторяет занятые файлы. Пять минут —
    /// с запасом на медленный диск, но не столько, чтобы запуск рукой спустя время подключился сам.
    /// </summary>
    public static readonly TimeSpan MaxAge = TimeSpan.FromMinutes(5);

    /// <summary>Допуск на перевод часов назад (синхронизация времени во время перезапуска).</summary>
    public static readonly TimeSpan MaxClockSkew = TimeSpan.FromMinutes(1);

    /// <summary>
    /// Шаг первый, без обращения к базе: можно ли возвращать подключение вообще.
    /// </summary>
    /// <param name="pending">Метка, прочитанная этим запуском (или null).</param>
    /// <param name="nowUtc">Текущее время, UTC.</param>
    /// <param name="running">Запущенная версия.</param>
    /// <param name="userActed">Пользователь уже тронул подключение или выбор сервера, либо подключение уже идёт.</param>
    /// <param name="tunNow">Режим «весь трафик» доступен и включён в этом запуске.</param>
    public static AppUpdateReconnectVerdict Screen(AppUpdatePendingInstall? pending, DateTime nowUtc, AppVersion running,
        bool userActed, bool tunNow)
    {
        if (pending?.Reconnect is not { } marker)
        {
            return AppUpdateReconnectVerdict.NotConnected;
        }

        if (pending.HandedOffUtc is not { } handedOff)
        {
            return AppUpdateReconnectVerdict.Stale;
        }
        var age = nowUtc - AsUtc(handedOff);
        if (age > MaxAge || age < -MaxClockSkew)
        {
            return AppUpdateReconnectVerdict.Stale;
        }

        // Новая версия (установка удалась) или прежняя (установщик откатил замену и поднял её снова): в обоих
        // случаях приложение закрыл перезапуск ради обновления. Любая третья версия — чужой запуск.
        if (!IsVersion(pending.Tag, running) && !IsVersion(pending.From, running))
        {
            return AppUpdateReconnectVerdict.OtherVersion;
        }

        if (userActed)
        {
            return AppUpdateReconnectVerdict.UserActed;
        }

        // Режим — тот же, что был, или никакого: подключение «весь трафик» вместо «через прокси» (и
        // наоборот) пользователь не выбирал. На Windows приложение всегда запущено от администратора
        // (app.manifest), установщик наследует эти права, новый запуск получает их от него без запроса UAC,
        // и TUN доступен снова. На Linux и macOS TUN держится на пароле sudo, который живёт только в памяти
        // процесса: после перезапуска режим понижен до прокси, и подключение не возвращается.
        if (marker.Tun != tunNow)
        {
            return AppUpdateReconnectVerdict.ModeUnavailable;
        }

        return AppUpdateReconnectVerdict.Connect;
    }

    /// <summary>
    /// Шаг второй: к какому серверу. Прежний, если он ещё есть (подписка могла обновиться на запуске);
    /// иначе текущий сервер по умолчанию; нет и его — null, и подключения не будет.
    /// </summary>
    public static string? ChooseServer(string? previousServerId, bool previousServerExists, string? defaultServerId)
    {
        if (previousServerExists && !string.IsNullOrEmpty(previousServerId))
        {
            return previousServerId;
        }
        return string.IsNullOrEmpty(defaultServerId) ? null : defaultServerId;
    }

    // Equals, а не ==: у AppVersion нет своего ==, и сравнение шло бы по ссылке.
    private static bool IsVersion(string? text, AppVersion running) =>
        AppVersion.TryParse(text, out var version) && version.Equals(running);

    private static DateTime AsUtc(DateTime value) => value.Kind switch
    {
        DateTimeKind.Utc => value,
        DateTimeKind.Local => value.ToUniversalTime(),
        _ => DateTime.SpecifyKind(value, DateTimeKind.Utc),
    };
}
