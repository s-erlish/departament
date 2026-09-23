namespace v2rayN.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// Самообновление: экран «Проверить обновление», уведомление в строке окна и подтверждение
// перезапуска. Ключи Update_*.
//
// Отдельный файл со своим хуком (как L.Start.cs), чтобы работа над обновлением не правила файлы
// соседних пакетов. Тексты состояний повторяют departament для Android (strings_editors.xml, A-36):
// один продукт — одни слова. Тире в тексте интерфейса нет (00-rules 1.4.11), многоточие — «…».
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterUpdate()
    {
        // ── Экран ──
        Add("Update_Title", "Проверить обновление", "Check for updates");
        Add("Update_PreRelease", "Искать предварительный выпуск", "Look for pre-releases");
        Add("Update_PreReleaseHint", "Ранние сборки с новыми функциями", "Early builds with new features");
        Add("Update_Check", "Проверить обновление", "Check for updates");
        Add("Update_Foot", "departament {0}", "departament {0}");
        Add("Update_FootToday", "departament {0} · проверено в {1}", "departament {0} · checked at {1}");
        Add("Update_FootDate", "departament {0} · проверено {1}", "departament {0} · checked on {1}");

        // ── Состояния: заголовок и строка под ним ──
        Add("Update_IdleTitle", "Установлена версия {0}", "Version {0} is installed");
        Add("Update_IdleLine", "Проверка ещё не запускалась.", "No check has run yet.");
        Add("Update_CheckingTitle", "Проверяем обновления…", "Checking for updates…");
        Add("Update_CheckingLine", "Установлена версия {0}.", "Version {0} is installed.");
        Add("Update_NoneTitle", "Обновлений нет", "No updates");
        Add("Update_NoneLine", "Установлена последняя версия {0}.", "The latest version {0} is installed.");
        Add("Update_NoReleaseLine", "Пока не опубликовано ни одной версии.", "No version has been published yet.");
        Add("Update_FoundTitle", "Доступна версия {0}", "Version {0} is available");
        Add("Update_FoundPreTitle", "Доступна предварительная версия {0}", "Pre-release {0} is available");
        Add("Update_FoundLine", "Скачаем и установим обновление. Данные и подписка останутся на месте.",
            "We will download and install the update. Your data and subscription stay in place.");
        Add("Update_DownloadingTitle", "Скачиваем версию {0}…", "Downloading version {0}…");
        Add("Update_DownloadingSize", "{0} МБ из {1} МБ", "{0} MB of {1} MB");
        Add("Update_DownloadingDone", "Скачано {0} МБ", "{0} MB downloaded");
        Add("Update_VerifyingTitle", "Проверяем файл…", "Verifying the file…");
        Add("Update_VerifyingLine", "Сверяем его с контрольной суммой выпуска.", "Checking it against the release checksum.");
        Add("Update_ReadyTitle", "Обновление {0} готово", "Update {0} is ready");
        // Строка под «готово» зависит от подключения, как и подтверждение: без подключения рвать нечего,
        // а с ним новый запуск подключается снова сам (UpdateReconnect).
        Add("Update_ReadyLine", "Перезапустите приложение, чтобы закончить установку.", "Restart the app to finish installing.");
        Add("Update_ReadyLineConnected", "Перезапустите приложение, чтобы закончить установку. Подключение прервётся на несколько секунд и восстановится само.",
            "Restart the app to finish installing. The connection drops for a few seconds and comes back on its own.");
        Add("Update_InstallingTitle", "Устанавливаем обновление…", "Installing the update…");
        Add("Update_InstallingLine", "Приложение закроется и откроется снова.", "The app closes and opens again.");

        // ── Отказы: у каждого своя причина и свой следующий шаг ──
        Add("Update_ErrOfflineTitle", "Нет подключения к интернету", "No internet connection");
        Add("Update_ErrOfflineLine", "Проверьте сеть и повторите.", "Check your network and try again.");
        Add("Update_ErrCheckTitle", "Не удалось проверить обновления", "Could not check for updates");
        Add("Update_ErrUnreachableLine", "Не удалось связаться с сервером обновлений. Проверьте сеть и повторите.",
            "Could not reach the update server. Check your network and try again.");
        Add("Update_ErrServerLine", "Сервер обновлений ответил с ошибкой. Повторите позже.",
            "The update server returned an error. Try again later.");
        Add("Update_ErrRateLine", "GitHub временно ограничил число проверок. Повторите через час.",
            "GitHub has temporarily limited checks. Try again in an hour.");
        Add("Update_ErrUnavailableTitle", "Обновления недоступны", "Updates are unavailable");
        Add("Update_ErrPlatformLine", "Автообновление работает в версии для Windows. Новые версии есть на странице загрузки.",
            "Automatic updates work in the Windows version. New versions are on the download page.");
        Add("Update_ErrNoPackageLine", "Для вашей системы сборки в новой версии нет.", "The new version has no build for your system.");
        Add("Update_ErrDownloadTitle", "Не удалось скачать обновление", "Could not download the update");
        Add("Update_ErrDownloadLine", "Связь прервалась или не хватило места. Повторите.",
            "The connection dropped or there was not enough space. Try again.");
        Add("Update_ErrRedirectTitle", "Загрузка остановлена", "Download stopped");
        Add("Update_ErrRedirectLine", "Сервер перенаправил загрузку на чужой адрес. Скачайте обновление со страницы загрузки.",
            "The server redirected the download to an unknown address. Get the update from the download page.");
        Add("Update_ErrChecksumTitle", "Файл повреждён", "The file is damaged");
        Add("Update_ErrChecksumLine", "Скачанный файл не совпал с контрольной суммой, поэтому он удалён. Повторите загрузку.",
            "The downloaded file did not match its checksum, so it was deleted. Download it again.");
        Add("Update_ErrForeignTitle", "Файл не от departament", "Not a departament file");
        Add("Update_ErrForeignLine", "Скачанный файл оказался не пакетом departament, поэтому он удалён. Обновитесь со страницы загрузки.",
            "The downloaded file is not a departament package, so it was deleted. Update from the download page.");
        Add("Update_ErrInstallerTitle", "Установщик не найден", "Installer not found");
        Add("Update_ErrInstallerLine", "Рядом с приложением нет AmazTool. Скачайте новую версию со страницы загрузки.",
            "AmazTool is missing next to the app. Get the new version from the download page.");
        Add("Update_ErrInstallTitle", "Не удалось установить обновление", "Could not install the update");
        Add("Update_ErrInstallLine", "Работает прежняя версия {0}. Повторите или обновитесь со страницы загрузки.",
            "Version {0} is still running. Try again or update from the download page.");

        // ── Действия ──
        Add("Update_Download", "Обновить", "Update");
        Add("Update_Cancel", "Отменить", "Cancel");
        Add("Update_Restart", "Перезапустить", "Restart");
        Add("Update_OpenReleases", "Открыть страницу загрузки", "Open the download page");

        // ── Уведомление в строке окна ──
        Add("Update_ChipAvailable", "Обновить до {0}", "Update to {0}");
        Add("Update_ChipAvailableHint", "Доступна версия {0}. Скачать и подготовить к установке", "Version {0} is available. Download it and get it ready");
        Add("Update_ChipDownloading", "Скачиваем {0} %", "Downloading {0} %");
        Add("Update_ChipDownloadingPlain", "Скачиваем…", "Downloading…");
        Add("Update_ChipVerifying", "Проверяем файл…", "Verifying…");
        Add("Update_ChipReady", "Перезапустить", "Restart");
        Add("Update_ChipReadyHint", "Обновление {0} готово к установке", "Update {0} is ready to install");
        Add("Update_ChipInstalling", "Устанавливаем…", "Installing…");
        Add("Update_ChipFailed", "Не удалось обновить", "Update failed");

        // ── Подтверждение перезапуска ──
        Add("Update_ConfirmTitle", "Перезапустить сейчас?", "Restart now?");
        Add("Update_ConfirmConnected", "Подключение прервётся на несколько секунд и восстановится само.",
            "The connection drops for a few seconds and comes back on its own.");
        Add("Update_ConfirmIdle", "Приложение закроется и откроется уже в версии {0}.", "The app closes and opens again in version {0}.");
    }
}
