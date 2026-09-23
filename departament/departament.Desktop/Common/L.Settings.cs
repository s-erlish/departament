namespace departament.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP3. Settings + sub-pages.
// Keys: Settings_*, Dns_*, Routing_*, PerApp_*, Ping_*, Geo_*, About_*, Backup_*,
//       UrlSchemes_*, Provider_* (+ Common_* references).
// Views: SettingsView(.axaml/.cs), SettingsViewModel, DnsSubView, RoutingSubView,
//        PerAppProxyPage, PingSettingsPage, GeoFilesPage, AboutPage, BackupPage,
//        UrlSchemesPage, ProviderSettingsPage, ThemeSettingViewModel.
// Inventory: LOCALIZATION_PLAN.md §2.3.
// NOTE (WP0 already done): the language-switch wiring in SettingsViewModel.CycleLanguageAsync
//       and ThemeSettingViewModel already calls L.Instance.SetLanguage(...) and the reboot
//       notice is dropped. WP3 only needs to convert the Resolve*Text() resolvers to
//       language-aware output. This is the ONLY L file WP3 edits.
// Locale-neutral tokens kept as-is in BOTH languages (never keyed here): TUN, DNS, IPv6,
//       FakeIP, Mux, SOCKS5, TCP/HTTP/ICMP, HWID, User-Agent, depv://, geoip.dat, geosite.dat,
//       Cloudflare/Google/AdGuard, protocol names, and language endonyms.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterSettings()
    {
        // ── SettingsView: section headers + rows ──
        Add("Settings_SecConnection", "Подключение", "Connection");
        Add("Settings_Mode", "Режим", "Mode");
        Add("Settings_ModeProxy", "Только прокси", "Proxy only");
        Add("Settings_PerApp", "Прокси по приложениям", "Per-app proxy");
        Add("Settings_BypassLan", "Обход локальной сети", "Bypass local network");
        Add("Settings_BypassLanHint", "Прямой доступ к устройствам в локальной сети", "Direct access to devices on the local network");
        Add("Settings_Ipv6Hint", "Включить IPv6-адресацию в туннеле", "Enable IPv6 addressing in the tunnel");
        Add("Settings_Ping", "Пинг", "Ping");
        Add("Settings_LocalProxy", "Локальный прокси", "Local proxy");
        Add("Settings_LocalProxyHint", "Порт и SOCKS5-авторизация", "Port and SOCKS5 authentication");
        Add("Settings_Socks5Auth", "SOCKS5-авторизация", "SOCKS5 authentication");

        Add("Settings_SecBypass", "Обход блокировок", "Bypass censorship");
        Add("Settings_Mux", "Мультиплексирование (Mux)", "Multiplexing (Mux)");
        Add("Settings_MuxHint", "Объединяет запросы в один канал соединения", "Combines requests into a single connection channel");
        Add("Settings_MuxCount", "Число соединений Mux", "Mux connection count");
        Add("Settings_Fragment", "Фрагментация пакетов", "Packet fragmentation");
        Add("Settings_FragmentHint", "Разбивает TLS-рукопожатие против DPI", "Splits the TLS handshake to defeat DPI");

        Add("Settings_SecPerformance", "Производительность", "Performance");
        Add("Settings_LiteMode", "Облегчённый режим", "Lite mode");
        Add("Settings_LiteModeHint", "Отключает анимации и тени", "Disables animations and shadows");

        Add("Settings_SecInterface", "Интерфейс", "Interface");
        Add("Settings_Appearance", "Оформление", "Appearance");
        Add("Settings_Language", "Язык", "Language");
        Add("Settings_UiScale", "Масштаб интерфейса", "Interface scale");
        Add("Settings_Autostart", "Запуск с системой", "Launch with the system");
        Add("Settings_AutostartHint", "Открывать при входе в систему", "Open when you sign in");
        Add("Settings_HideTray", "Скрыть значок в трее", "Hide the tray icon");
        Add("Settings_HideTrayHint", "Не показывать значок в области уведомлений", "Keep the icon out of the notification area");

        Add("Settings_SecSubscription", "Подписка", "Subscription");
        Add("Settings_SubAutoUpdate", "Автообновление подписки", "Auto-update subscription");
        Add("Settings_Routing", "Маршрутизация", "Routing");
        Add("Settings_RoutingHint", "Правила proxy, direct и block", "proxy, direct and block rules");
        Add("Settings_GeoFiles", "Файлы ресурсов", "Resource files");
        Add("Settings_GeoFilesHint", "Geo-базы для маршрутизации", "Geo databases for routing");

        Add("Settings_About", "О приложении", "About");
        Add("Settings_Log", "Журнал", "Log");
        Add("Settings_LogHint", "Лог ядра за текущий сеанс", "Core log for the current session");
        Add("Settings_CheckUpdate", "Проверить обновления", "Check for updates");
        Add("Settings_Backup", "Резервное копирование", "Backup");
        Add("Settings_BackupHint", "Сохранить и восстановить настройки", "Save and restore settings");
        Add("Settings_UrlSchemes", "Схемы URL-адресов", "URL schemes");
        Add("Settings_UrlSchemesHint", "Быстрые команды depv://", "Quick depv:// commands");

        // ── Пароль sudo (режим «весь трафик» на Linux и macOS, SudoPasswordInputView) ──
        // Раньше диалог брал строки движка (ResUI): тире посреди русской фразы и кнопка фиксированной
        // ширины, в которую «Подтвердить» не помещалось. Приписка «если из-за ошибки проверки
        // приложение начнёт работать некорректно, перезапустите его» убрана: совет без причины
        // только пугает, а неверный пароль теперь назван прямо под полем.
        Add("Sudo_Title", "Пароль sudo", "sudo password");
        Add("Sudo_Hint",
            "Нужен, чтобы включить режим «весь трафик». Пароль проверяется в терминале и нигде не сохраняется, поэтому после перезапуска приложения его нужно ввести снова.",
            "Needed to turn on whole-traffic mode. The password is checked in a terminal and never stored, so enter it again after the app restarts.");
        Add("Sudo_Confirm", "Подтвердить", "Confirm");
        Add("Sudo_Wrong", "Неверный пароль. Попробуйте ещё раз.", "Wrong password. Try again.");

        // ── SettingsViewModel: display-value resolvers (language-aware) ──
        Add("Settings_PerAppExcept", "кроме", "except");
        Add("Settings_PerAppOnly", "только", "only");
        Add("Settings_ThemeLight", "Светлая", "Light");
        Add("Settings_ThemeDark", "Тёмная", "Dark");
        // Четыре РАВНЫХ пункта «Оформления» (screens.md). Чёрно-белая больше не надстройка над базой.
        Add("Settings_ThemeMono", "Чёрно-белая", "Monochrome");
        Add("Settings_ThemeSystem", "Как в системе", "Match system");
        Add("Settings_LangRussian", "Русский", "Russian");
        Add("Settings_LangSystem", "Системный", "System");

        // ── RoutingSubView ──
        Add("Routing_RuleSets", "Наборы правил", "Rule sets");
        // Число правил склоняется: «1 правило», «4 правила», «8 правил». Раньше шаблон «{0} правил»
        // писал «4 правил» под встроенным набором «Весь трафик через прокси».
        AddPlural(
            "Routing_RulesPlural",
            new[] { "правило", "правила", "правил" },
            new[] { "rule", "rules" });
        // Встроенные наборы движка хранятся под китайскими именами апстрима («V4-绕过大陆(Whitelist)»
        // и т. п.). Показываем их по-русски, теми же словами, что готовые наборы на Android.
        Add("Routing_PresetBasic", "Базовый набор", "Basic set");
        Add("Routing_PresetBlocked", "Прокси для заблокированных сайтов", "Proxy for blocked sites");
        Add("Routing_PresetGlobal", "Весь трафик через прокси", "All traffic through the proxy");
        Add("Routing_Active", "Активен", "Active");
        Add("Routing_DomainStrategy", "Доменная стратегия", "Domain strategy");
        Add("Routing_DomainResolution", "Разрешение доменов", "Domain resolution");
        Add("Routing_DomainHint", "Как ядро сопоставляет домены с правилами", "How the core matches domains against rules");
        Add("Routing_Maintenance", "Обслуживание", "Maintenance");
        Add("Routing_DefaultRules", "Стандартные правила", "Default rules");
        Add("Routing_DefaultRulesHint", "Пересоздать встроенные наборы правил", "Rebuild the built-in rule sets");
        Add("Routing_DsAsIs", "Как есть", "As is");
        Add("Routing_DsIpIfNonMatch", "IP при несовпадении", "IP if no match");
        Add("Routing_DsIpOnDemand", "IP по запросу", "IP on demand");

        // ── PerAppProxyPage ──
        Add("PerApp_SplitTunnel", "Раздельное туннелирование", "Split tunneling");
        Add("PerApp_SplitTunnelHint", "Выберите, какие программы идут через VPN", "Choose which apps go through the VPN");
        Add("PerApp_AddExe", "Добавить .exe", "Add .exe");
        Add("PerApp_TunHint", "Работает в режиме TUN (sing-box). Правила применяются при следующем подключении.", "Works in TUN mode (sing-box). Rules apply on the next connection.");
        Add("PerApp_ProgramFileType", "Программа", "Program");

        // ── PingSettingsPage (only Real / TCP rows) ──
        Add("Ping_RealTitle", "Реальная задержка", "Real latency");
        // Short row label used by SettingsViewModel.ResolvePingMethodText («Реальная»); TCP/HTTP/ICMP stay as tokens.
        Add("Ping_Real", "Реальная", "Real");

        // ── GeoFilesPage ──
        Add("Geo_NotDownloaded", "Не загружен", "Not downloaded");
        Add("Geo_ReadFailed", "Не удалось прочитать файл", "Could not read the file");
        Add("Geo_Updating", "Обновление…", "Updating…");
        Add("Geo_Downloading", "Загрузка баз…", "Downloading databases…");
        Add("Geo_Done", "Готово. Базы обновлены.", "Done. Databases updated.");
        Add("Geo_Failed", "Не удалось обновить: ", "Update failed: ");

        // ── AboutPage ──
        Add("About_TitleVersion", "departament · Версия {0}", "departament · Version {0}");
        Add("About_SystemInfo", "ОС: {0}\nАрхитектура: {1}\n.NET: {2}", "OS: {0}\nArchitecture: {1}\n.NET: {2}");
        // Когда среда не назвала себя. Пишется В СТРОКУ «ОС: …», поэтому строчная и без точки.
        Add("About_SystemUnknown", "не определена", "not detected");

        // ── BackupPage ──
        Add("Backup_Intro", "Файл с серверами и настройками", "A file with your servers and settings");
        Add("Backup_Saving", "Сохранение…", "Saving…");
        Add("Backup_Saved", "Копия сохранена: {0}", "Backup saved: {0}");
        Add("Backup_SaveFailed", "Не удалось сохранить копию.", "Couldn't save the backup.");
        Add("Backup_ExportError", "Ошибка экспорта: ", "Export error: ");
        Add("Backup_Restoring", "Восстановление… Приложение перезапустится.", "Restoring… The app will restart.");
        Add("Backup_ImportError", "Ошибка импорта: ", "Import error: ");

        // ── UrlSchemesPage ──
        Add("UrlSchemes_Registration", "Регистрация схемы depv://", "depv:// scheme registration");
        Add("UrlSchemes_OpenApp", "Открыть приложение", "Open the app");
        Add("UrlSchemes_Stop", "Остановить соединение", "Stop the connection");
        Add("UrlSchemes_Toggle", "Переключить соединение", "Toggle the connection");
        Add("UrlSchemes_Import", "Импорт (автоопределение типа)", "Import (auto-detect type)");
        Add("UrlSchemes_Registered", "Схема зарегистрирована. Ссылки depv:// открывают departament.", "Scheme registered. depv:// links open departament.");
        Add("UrlSchemes_NotRegistered", "Схема не зарегистрирована.", "Scheme not registered.");
        Add("UrlSchemes_NoPath", "Не удалось определить путь к программе.", "Couldn't determine the app's path.");
        Add("UrlSchemes_RegisterFailed", "Не удалось зарегистрировать: ", "Registration failed: ");
        Add("UrlSchemes_RemoveFailed", "Не удалось удалить: ", "Removal failed: ");

        // ═════════════════════════════════════════════════════════════════════════════════════════
        // Подэкраны настроек по единому лекалу (screens.md «Подэкраны»). Строки как в спецификации.
        // ═════════════════════════════════════════════════════════════════════════════════════════

        // ── Пинг ──
        Add("Ping_TcpTitle", "TCP-соединение", "TCP connection");

        // ── Прокси по приложениям ──
        Add("PerApp_Mode", "Режим", "Mode");
        Add("PerApp_ModeExcept", "Кроме выбранных", "Except selected");
        Add("PerApp_ModeOnly", "Только выбранные", "Only selected");
        Add("PerApp_Search", "Поиск по приложениям", "Search apps");
        Add("PerApp_Programs", "Программы", "Programs");
        Add("PerApp_EmptyTitle", "Программы не найдены", "No programs found");
        Add("PerApp_EmptyText", "Измените запрос или добавьте .exe вручную.", "Change the query or add an .exe manually.");
        Add("PerApp_Chosen", "Выбрано {0}", "{0} selected");
        Add("PerApp_PagePrev", "Предыдущая страница", "Previous page");
        Add("PerApp_PageNext", "Следующая страница", "Next page");
        Add("PerApp_PageN", "Страница {0}", "Page {0}");

        // ── Локальный прокси ──
        Add("LocalProxy_Intro", "Доступ для программ на этом компьютере", "Access for programs on this computer");
        Add("LocalProxy_Params", "Параметры", "Parameters");
        Add("LocalProxy_Address", "Адрес", "Address");
        Add("LocalProxy_PortSocks", "Порт SOCKS5", "SOCKS5 port");
        Add("LocalProxy_PortHttp", "Порт HTTP", "HTTP port");
        Add("LocalProxy_PortHttpSame", "Тот же вход принимает и HTTP", "The same inbound also accepts HTTP");
        Add("LocalProxy_Access", "Доступ", "Access");
        Add("LocalProxy_AuthHint", "Логин и пароль при подключении", "Username and password when connecting");
        Add("LocalProxy_Login", "Логин", "Username");
        Add("LocalProxy_Password", "Пароль", "Password");
        Add("LocalProxy_BlockUdp", "Блокировать UDP", "Block UDP");
        Add("LocalProxy_BlockUdpHint", "Запретить UDP через прокси", "Disallow UDP through the proxy");
        Add("LocalProxy_Foot", "Порты меняются только при отключённом туннеле.", "Ports can only be changed while the tunnel is off.");
        Add("LocalProxy_FootLive", "Туннель включён, порт менять нельзя. Отключитесь, чтобы изменить его.", "The tunnel is on, the port can't be changed. Disconnect to change it.");

        // ── Маршрутизация ──
        // Ключей редактора правил («Правила», «Добавить правило», «Сбросить») тут нет намеренно:
        // редактора в приложении не существует, и владелец решил его не заводить. Строка, которой
        // некуда вести, не станет живее оттого, что у неё есть перевод.
        Add("Routing_NoRules", "Правил пока нет", "No rules yet");
        Add("Routing_Resetting", "Пересоздаём…", "Rebuilding…");

        // ── Файлы ресурсов ──
        Add("Geo_Source", "Источник Geo-файлов", "Geo file source");
        Add("Geo_SourceRow", "Источник", "Source");
        Add("Geo_Files", "Файлы", "Files");
        Add("Geo_Update", "Обновить", "Update");

        //  ── Наборы «Прокси по приложениям» ──
        Add("PerApp_PresetGames", "Игры", "Games");
        Add("PerApp_PresetGamesHint", "Соревновательные игры идут мимо VPN: задержка ниже", "Competitive games skip the VPN: lower latency");
        Add("PerApp_PresetLaunchers", "Игровые лаунчеры", "Game launchers");
        Add("PerApp_PresetLaunchersHint", "Загрузки быстрее, но магазин увидит ваш настоящий регион", "Faster downloads, but the store sees your real region");

        // ── Журнал ──
        Add("Log_Title", "Журнал", "Log");
        Add("Log_Search", "Поиск по журналу", "Search the log");
        Add("Log_EmptyTitle", "Записей пока нет", "No entries yet");
        Add("Log_EmptyText", "Журнал заполнится при следующем подключении.", "The log will fill up on the next connection.");
        Add("Log_NoMatchTitle", "Ничего не найдено", "Nothing found");
        Add("Log_NoMatchText", "По этому запросу записей нет. Попробуйте другой.", "No entries match this query. Try another one.");
        Add("Log_Copy", "Копировать", "Copy");
        Add("Log_Clear", "Очистить", "Clear");
        Add("Log_Copied", "Журнал скопирован", "Log copied");
        Add("Log_Lines", "{0} строк", "{0} lines");

        // ── Проверить обновление: ключи Update_* живут в L.Update.cs ──

        // ── Резервное копирование ──
        Add("Backup_SecData", "Данные", "Data");
        Add("Backup_SaveCopy", "Сохранить копию", "Save a backup");
        Add("Backup_SaveCopyHint", "Все серверы и настройки в один файл", "All servers and settings in one file");
        Add("Backup_RestoreFile", "Восстановить из файла", "Restore from a file");
        Add("Backup_RestoreFileHint", "Заменит текущие серверы", "Replaces the current servers");
        Add("Backup_SecCloud", "Облако", "Cloud");
        Add("Backup_WebDav", "Настройки WebDAV", "WebDAV settings");
        Add("Backup_WebDavNotSet", "Не настроено", "Not configured");
        Add("Backup_WebDavUrl", "Адрес сервера", "Server address");
        Add("Backup_WebDavIntro", "Копия хранится на вашем сервере WebDAV", "The backup is kept on your own WebDAV server");
        Add("Backup_WebDavServer", "Сервер", "Server");
        Add("Backup_WebDavFolder", "Папка", "Folder");
        Add("Backup_SecActions", "Действия", "Actions");
        Add("Backup_WebDavCheck", "Проверить подключение", "Test the connection");
        Add("Backup_CloudUpload", "Выгрузить копию", "Upload a backup");
        Add("Backup_CloudRestore", "Восстановить из облака", "Restore from the cloud");
        Add("Backup_Working", "Выполняем…", "Working…");

        // ── Схемы URL-адресов ──
        Add("UrlSchemes_Intro", "Быстрые команды для запуска из браузера и ярлыков", "Quick commands to launch from the browser and shortcuts");
        Add("UrlSchemes_ConnectHint", "Подключиться к текущему серверу", "Connect to the current server");
        Add("UrlSchemes_DisconnectHint", "Отключиться", "Disconnect");
        Add("UrlSchemes_SubHint", "Добавить подписку по ссылке", "Add a subscription by link");
        Add("UrlSchemes_Copied", "Схема скопирована", "Scheme copied");
        Add("UrlSchemes_RegisterRow", "Открывать ссылки depv://", "Open depv:// links");

        // ── О приложении ──
        // Только три рабочие ссылки (сайт · обратная связь · обновления). Ключей под «Исходный код»,
        // «Лицензии открытого ПО», «Канал в Telegram» и «Политику конфиденциальности» тут нет: адресов
        // под них не существует, и владелец решил эти строки не заводить. «Идентификатор» заменён
        // «Системой»: андроидного идентификатора пакета на ПК нет.
        Add("About_SecApp", "Приложение", "Application");
        Add("About_VersionRow", "Версия", "Version");
        Add("About_SecLinks", "Ссылки и документы", "Links and documents");
        Add("About_Feedback", "Обратная связь", "Feedback");
        Add("About_CheckUpdates", "Проверить обновления", "Check for updates");
        Add("About_Copied", "Скопировано", "Copied");
        Add("About_Site", "Сайт departament", "departament website");
        Add("About_System", "Система", "System");

    }
}
