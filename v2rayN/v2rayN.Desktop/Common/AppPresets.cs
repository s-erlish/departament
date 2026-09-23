using System.Text.Json;

namespace v2rayN.Desktop.Common;

/// <summary>
/// Именованный ОБРАТИМЫЙ набор для «Прокси по приложениям»: список имён процессов Windows, который
/// одним тумблером уходит мимо туннеля и тем же тумблером возвращается обратно.
/// </summary>
/// <param name="Key">Устойчивый идентификатор — по нему помнится, что набор добавил. Не показывается.</param>
/// <param name="TitleKey">Ключ локализации для заголовка строки.</param>
/// <param name="HintKey">Ключ локализации для подписи строки.</param>
/// <param name="Processes">Имена исполняемых файлов ровно так, как их сообщает Windows.</param>
public sealed record AppPreset(
    string Key,
    string TitleKey,
    string HintKey,
    IReadOnlyList<string> Processes)
{
    /// <summary>Запасной текст заголовка (ru, en) — см. <see cref="AppPresets.Text"/>.</summary>
    public required (string Ru, string En) TitleFallback { get; init; }

    /// <summary>Запасной текст подписи (ru, en) — см. <see cref="AppPresets.Text"/>.</summary>
    public required (string Ru, string En) HintFallback { get; init; }

    public string Title => AppPresets.Text(TitleKey, TitleFallback);

    public string Hint => AppPresets.Text(HintKey, HintFallback);
}

/// <summary>
/// Готовые наборы для «Прокси по приложениям» и запись о том, что каждый из них на самом деле добавил.
///
/// Перенос ПОВЕДЕНИЯ андроидного <c>handler/RussianAppsPreset.kt</c>, а не его состава: там набор про
/// российские приложения, которым не нравится зарубежный адрес выхода, здесь — про задержку. Три
/// свойства несущие, и все три оттуда:
///
/// 1. <b>Это набор, а не скрытый список.</b> У него есть имя, один тумблер применяет и снимает его,
///    и каждый процесс из набора виден в списке, на который набор действует, и снимается поштучно.
///    Зашитый список, который молча правит маршрутизацию, неотличим от бага.
/// 2. <b>Снятие возвращает ровно то, что применение взяло.</b> <see cref="Apply"/> запоминает то, что
///    ДЕЙСТВИТЕЛЬНО ДОБАВИЛ, — не весь набор, — и <see cref="Owned"/> — это то, что вернёт
///    <see cref="Release"/>. Процесс, отмеченный человеком до применения набора, переживёт его снятие,
///    потому что набор никогда его не присваивал.
/// 3. <b>Он сам по себе не перезапускает туннель.</b> Применение правит только выбор В ПАМЯТИ страницы;
///    записывает и перезагружает общий путь выхода, и только если что-то изменилось. Включить набор и
///    выключить обратно — ноль записей и ноль перезапусков.
///
/// <b>ВЫКЛЮЧЕНЫ ПО УМОЛЧАНИЮ</b>, в отличие от набора российских приложений: никто не просил выпускать
/// игры из туннеля из коробки, и молча сделать это значит удивить того, кто не играет.
///
/// <para>ОБ ИМЕНАХ ПРОЦЕССОВ. Каждое ниже сверено с источником — страницами поддержки самих издателей,
/// рекомендациями по фаерволу для магазинов и античитов и базами процессов, где записан канонический
/// исполняемый файл игры. Неверное имя МОЛЧИТ: оно не совпадает ни с одним процессом и просто не
/// маршрутизирует, поэтому угадывать хуже, чем не добавлять. Процесс, который никогда не запущен, не
/// стоит ничего — правило маршрутизации просто не срабатывает.</para>
///
/// <para>ЧЕГО ЗДЕСЬ НЕТ НАМЕРЕННО. Никаких общих рантаймов: Minecraft — это <c>javaw.exe</c>, у Warface
/// собственный бинарник называется буквально <c>Game.exe</c>, апдейтер Blizzard — <c>Agent.exe</c>;
/// вывести любой из них мимо туннеля значит увести туда постороннее ПО — единственная ошибка, которой
/// у этого файла быть не должно. Сервисы античитов (<c>vgc.exe</c>/<c>vgm.exe</c>, <c>BEService.exe</c>,
/// <c>EasyAntiCheat.exe</c>) тоже отсутствуют: это драйверы и службы, чей трафик — лицензия и
/// телеметрия, а не тот самый поток игры, и разводить маршрут античита с маршрутом его игры —
/// различие, которое лучше не создавать.</para>
/// </summary>
public static class AppPresets
{
    /// <summary>
    /// ИГРЫ. Соревновательные тайтлы, где задержка и есть продукт, плюс <c>RiotClientServices.exe</c> —
    /// это не витрина, а жёсткая зависимость: VALORANT и League без него не стартуют.
    /// </summary>
    public static readonly AppPreset Games = new(
        "games",
        "PerApp_PresetGames",
        "PerApp_PresetGamesHint",
        [
            "cs2.exe",                          // Counter-Strike 2
            "dota2.exe",                        // Dota 2
            "VALORANT-Win64-Shipping.exe",      // VALORANT (сама игра)
            "VALORANT.exe",                     // VALORANT (обёртка Riot)
            "RiotClientServices.exe",           // Клиент Riot — нужен VALORANT и League
            "League of Legends.exe",            // League of Legends (в имени действительно есть пробелы)
            "LeagueClient.exe",                 // Клиент League
            "LeagueClientUx.exe",               // Хост интерфейса клиента League
            "FortniteClient-Win64-Shipping.exe",// Fortnite
            "r5apex.exe",                       // Apex Legends
            "EscapeFromTarkov.exe",             // Escape from Tarkov
            "GTA5.exe",                         // GTA V / GTA Online
            "TslGame.exe",                      // PUBG: BATTLEGROUNDS
            "RustClient.exe",                   // Rust (имя процесса игры от Facepunch)
            "Overwatch.exe",                    // Overwatch 2
            "WorldOfTanks.exe",                 // World of Tanks
            "RainbowSix.exe",                   // Rainbow Six Siege
            "destiny2.exe",                     // Destiny 2
            "BF2042.exe",                       // Battlefield 2042
            "cod.exe",                          // Call of Duty (общий лаунчер современных частей)
        ])
    {
        TitleFallback = ("Игры", "Games"),
        HintFallback = (
            "Соревновательные игры идут мимо VPN — задержка ниже",
            "Competitive games skip the VPN — lower latency"),
    };

    /// <summary>
    /// ВИТРИНЫ — ОТДЕЛЬНЫМ ТУМБЛЕРОМ, и это разделение и есть смысл.
    ///
    /// Вывести <c>steam.exe</c> мимо туннеля — значит вывести загрузки и магазин. Загрузки обычно того и
    /// стоят: это самая большая передача, которую делает машина, и туннель её только замедляет. Но
    /// МАГАЗИН после этого видит настоящее подключение, а значит его регион, цены и доступность идут за
    /// реальным адресом, а не за адресом выхода. Это решение про деньги и каталог, а не про задержку, и
    /// ему нечего делать на тумблере, который чинит пинг, — поэтому тумблер второй, и строка говорит,
    /// что он делает.
    /// </summary>
    public static readonly AppPreset Launchers = new(
        "launchers",
        "PerApp_PresetLaunchers",
        "PerApp_PresetLaunchersHint",
        [
            "steam.exe",                // Steam
            "Battle.net.exe",           // Battle.net
            "EpicGamesLauncher.exe",    // Epic Games Launcher
            "EADesktop.exe",            // Приложение EA
            "upc.exe",                  // Ubisoft Connect
        ])
    {
        TitleFallback = ("Игровые лаунчеры", "Game launchers"),
        HintFallback = (
            "Загрузки быстрее, но магазин увидит ваш настоящий регион",
            "Faster downloads, but the store sees your real region"),
    };

    public static readonly IReadOnlyList<AppPreset> All = [Games, Launchers];

    #region локализация

    // Подписи — В ОДНУ СТРОКУ. В эталонном кадре у этих двух строк подписи нет вовсе, и рост строки с
    // 58 до 74 ломает ритм карточки; но у «Игровых лаунчеров» последствие не самоочевидно — магазин
    // после этого видит настоящий регион и цены, — и строка обязана его назвать. Одна короткая фраза
    // держит и ритм, и честность; развёрнутое обоснование живёт в комментариях к самим наборам.

    /// <summary>
    /// Подпись строки набора. Ключи <c>PerApp_Preset*</c> принадлежат общей таблице (Common/L.Settings.cs),
    /// и когда они там есть — побеждают они. Пока их там нет, <see cref="L"/> по контракту возвращает САМ
    /// КЛЮЧ, и строка показала бы пользователю «PerApp_PresetGames». Поэтому здесь лежит запасная пара
    /// ru/en: экран остаётся читаемым до того, как ключи доедут в таблицу, и ни строчки кода менять после
    /// этого не придётся.
    /// </summary>
    public static string Text(string key, (string Ru, string En) fallback)
    {
        var value = L.T(key);
        return value == key
            ? (L.Instance.CurrentLang == "en" ? fallback.En : fallback.Ru)
            : value;
    }

    #endregion локализация

    #region владение

    private const string FileName = "departament_presets.json";
    private const string Tag = "AppPresets";

    private static readonly object _lock = new();
    private static Dictionary<string, List<string>>? _owned;

    /// <summary>
    /// <b>Набор применён, когда ВСЕ его программы сейчас отмечены.</b> Тумблер читает фактический
    /// выбор, а не записанное владение.
    ///
    /// Владение отвечает на другой вопрос — «что снять при выключении» — и на роль состояния тумблера
    /// не годится: набор, которому нечего было добавлять (все его программы человек отметил сам),
    /// записывал пустое владение и при следующем заходе показывался ВЫКЛЮЧЕННЫМ, хотя действует; а
    /// снятая руками галочка владения не трогала, и тумблер продолжал обещать применённый набор,
    /// от которого осталось девятнадцать программ из двадцати.
    ///
    /// Оба случая этот вопрос решает сам собой, и отдельная сверка владения с выбором больше не нужна.
    /// </summary>
    /// <param name="preset">Набор, о котором спрашивают.</param>
    /// <param name="chosen">Текущий выбор, без учёта регистра.</param>
    public static bool IsApplied(AppPreset preset, ISet<string> chosen) =>
        preset.Processes.Count > 0 && preset.Processes.All(chosen.Contains);

    /// <summary>
    /// Запоминает записи, которые набор только что добавил, чтобы <see cref="Release"/> вернул ровно их.
    ///
    /// ТОЛЬКО В ПАМЯТИ. На диск владение уходит одним действием со списком процессов — см.
    /// <see cref="Commit"/>; почему именно так, написано там.
    /// </summary>
    /// <param name="preset">Набор, который включают.</param>
    /// <param name="current">Текущий выбор, без учёта регистра.</param>
    /// <returns>Записи, которые НАДО ДОБАВИТЬ — возможно, пусто, если всё уже было выбрано.</returns>
    public static IReadOnlyList<string> Apply(AppPreset preset, ISet<string> current)
    {
        var added = preset.Processes.Where(p => !current.Contains(p)).ToList();
        lock (_lock)
        {
            var owned = Load();
            // ОБЪЕДИНЕНИЕ, а не замена. Владение накапливается, пока набор включён, и обнуляется
            // только выключением (<see cref="Release"/> убирает ключ целиком). Перезапись теряла
            // прежнее: человек снял одну галочку из двадцати, тумблер погас, человек включил набор
            // заново — владением становилась та одна возвращённая программа, и выключение оставляло
            // на месте девятнадцать, которые поставил сам набор.
            var kept = owned.TryGetValue(preset.Key, out var prev) ? new List<string>(prev) : [];
            foreach (var name in added)
            {
                if (!kept.Contains(name, StringComparer.OrdinalIgnoreCase))
                {
                    kept.Add(name);
                }
            }
            owned[preset.Key] = kept;
        }
        return added;
    }

    /// <summary>
    /// Записи, которые надо СНЯТЬ при выключении набора: только то, что добавил <see cref="Apply"/>.
    ///
    /// Процесс, выбранный человеком до применения набора, набору не принадлежит и остаётся — снятие
    /// набора не имеет права отнимать решение, которое человек принял сам.
    ///
    /// Тоже только в памяти: пара «включил и выключил» не должна оставлять на диске ни следа.
    /// </summary>
    public static IReadOnlyList<string> Release(AppPreset preset)
    {
        lock (_lock)
        {
            var owned = Load();
            var list = owned.TryGetValue(preset.Key, out var v) ? v : [];
            owned.Remove(preset.Key);
            return list;
        }
    }

    /// <summary>
    /// Публикует владение — одним действием с сохранением списка процессов.
    ///
    /// Раньше <see cref="Apply"/> и <see cref="Release"/> писали файл в момент тумблера, а список
    /// процессов сохранялся только при уходе со страницы. Две половины одного факта расходились на
    /// любом выходе мимо стрелки «назад»: включить набор и закрыть приложение прямо на этом экране
    /// значило получить при следующем запуске включённый тумблер и НИ ОДНОЙ отмеченной программы.
    /// Тумблер обещал применённый набор, которого нет.
    ///
    /// Обрезки по итоговому выбору здесь НЕТ и не должно быть: состояние тумблера читается прямо из
    /// выбора (<see cref="IsApplied"/>), а владение отвечает на другой вопрос — «что снять при
    /// выключении». Программу, которую поставил набор, а человек снял руками, набор всё ещё обязан
    /// помнить: снятие уже снятого — ничего, а забывчивость оставила бы её включённой навсегда.
    /// </summary>
    public static void Commit()
    {
        lock (_lock)
        {
            Load();
            Persist();
        }
    }

    private static Dictionary<string, List<string>> Load()
    {
        if (_owned is not null)
        {
            return _owned;
        }
        try
        {
            var path = Utils.GetConfigPath(FileName);
            if (File.Exists(path))
            {
                _owned = JsonSerializer.Deserialize<Dictionary<string, List<string>>>(File.ReadAllText(path));
            }
        }
        catch (Exception ex)
        {
            // Нечитаемая запись читается как «набор не применён» — это безопасный ответ: тумблер
            // показывает «выключено», а включение заново запишет то, что добавит. Выбор пользователя
            // при этом не трогается в любом случае.
            Logging.SaveLog(Tag, ex);
        }
        return _owned ??= new Dictionary<string, List<string>>();
    }

    /// <summary>
    /// Запись владения — во временный файл, потом переименование, как <c>ConfigHandler.SaveConfig</c>.
    ///
    /// Прямой <c>File.WriteAllText</c> сначала обрезает файл до нуля и только потом пишет: падение,
    /// выключение питания или закрытие крышки в этом промежутке оставляли на диске ПУСТОЙ или
    /// оборванный JSON. Читается он как «набор не применён» (см. <see cref="Load"/>), а значит
    /// приложение забывает, какие процессы оно добавило само, и снять их выключением набора уже
    /// нечем — они остаются в списке пользователя навсегда.
    ///
    /// Имя временного файла уникально по процессу и вызову: два экземпляра приложения на одном
    /// каталоге настроек (единственность может быть потеряна, а переносимую сборку просто запускают
    /// дважды) иначе перезаписали бы общий черновик друг друга. Переименование — единственный шаг
    /// публикации, поэтому читатель видит либо старый файл, либо новый, но не половину.
    /// </summary>
    private static void Persist()
    {
        var path = Utils.GetConfigPath(FileName);
        var tempPath = $"{path}.{Environment.ProcessId}.{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllText(tempPath, JsonSerializer.Serialize(_owned ?? []));
            File.Move(tempPath, path, true);
            tempPath = string.Empty;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(Tag, ex);
        }
        finally
        {
            // Неудавшаяся запись не оставляет черновик: имена уникальны, иначе они копились бы в
            // каталоге настроек навсегда.
            if (tempPath.IsNotEmpty())
            {
                try
                {
                    File.Delete(tempPath);
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(Tag, ex);
                }
            }
        }
    }

    #endregion владение
}
