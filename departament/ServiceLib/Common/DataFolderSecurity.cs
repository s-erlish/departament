using System.Security.AccessControl;
using System.Security.Principal;

namespace ServiceLib.Common;

/// <summary>
/// Закрывает каталоги с учётными данными от ЧУЖИХ пользователей той же машины.
///
/// <para><b>Зачем.</b> Программа ставится в <c>C:\Program Files\departament</c> и манифестирована как
/// <c>requireAdministrator</c>, поэтому <see cref="Utils.HasWritePermission"/> всегда true и данные
/// остаются рядом с exe, а не в профиле пользователя. У Program Files права по умолчанию такие:
/// писать — администратор, а ЧИТАТЬ — все. Всё перечисленное ниже читал любой другой аккаунт этого
/// компьютера и любая программа, запущенная без всяких прав.</para>
///
/// <para><b>Какие каталоги и что в них.</b></para>
/// <list type="bullet">
///   <item><c>guiConfigs</c> — <c>departament_auth.dat</c> (пропуск аккаунта) и <c>guiNDB.db</c>,
///   где URL подписки лежит открытым текстом;</item>
///   <item><c>binConfigs</c> — сгенерированный конфиг ядра с uuid/паролем узла;</item>
///   <item><c>guiBackups</c> — <c>backup_*.zip</c> от резервного копирования: ОБЫЧНЫЙ zip, без
///   шифрования, с полной копией guiConfigs внутри. Кладётся при копировании на WebDAV и как
///   предохранительная копия перед восстановлением, и остаётся лежать;</item>
///   <item><c>guiTemps</c> — промежуточная копия ВСЕГО guiConfigs, которую резервное копирование
///   раскладывает здесь перед упаковкой (<c>BackupAndRestoreViewModel.CreateZipFileFromDirectory</c>),
///   и скачанный с WebDAV архив при восстановлении.</item>
/// </list>
///
/// <para>Последние два — не перестраховка: без них защита обходится одним нажатием «Резервное
/// копирование» в интерфейсе, после которого читаемая всем копия тех же данных остаётся на диске
/// насовсем. <c>guiLogs</c> в список НЕ входит осознанно: туда пишет только
/// <see cref="Logging"/>, и ни токена, ни URL подписки там не появляется.</para>
///
/// <para>Шифрование блоба тут не спасало: ключ выводится из MachineGuid
/// (<c>HKLM\SOFTWARE\Microsoft\Cryptography</c>), а его тоже читают все — см. AuthTokenStore. Шифр
/// защищает от переноса файла на ДРУГУЮ машину, и только от этого; «чужой на той же машине» —
/// вопрос прав доступа, а не шифра, и решается здесь.</para>
///
/// <para><b>Что делаем.</b> Снимаем наследование и оставляем ровно два разрешения: SYSTEM и
/// встроенную группу администраторов, оба с наследованием внутрь. Уже лежащие внутри файлы
/// перечислять не нужно: их права были унаследованными, и Windows пересчитывает их сама, когда
/// меняется ACL каталога. Программа всегда идёт от администратора, поэтому доступ к собственным
/// данным не теряет; установщик и деинсталлятор — тоже (оба <c>PrivilegesRequired=admin</c>), и
/// AmazTool наследует права запустившего его приложения.</para>
///
/// <para>Группы называются SID'ами, а не именами: на русской Windows встроенная «СИСТЕМА» именно так
/// и называется, и сравнение по имени развалилось бы на первой же нерусской локали.</para>
///
/// <para><b>Владелец каталога.</b> Владельца не меняем. У него остаётся неявное право переписать ACL,
/// но владелец здесь — тот администратор, который поставил программу; для второго, ОБЫЧНОГО
/// пользователя машины (ровно тот случай, ради которого всё это) владения нет, значит и обойти
/// закрытые права он не может.</para>
/// </summary>
public static class DataFolderSecurity
{
    private static readonly string _tag = "DataFolderSecurity";

    /// <summary>
    /// Приводит права каталогов данных к нужному виду. Идемпотентно и безопасно: любая неудача
    /// уходит в журнал и не мешает запуску — программа без этой правки работает ровно как раньше.
    ///
    /// <para>Зовётся из <see cref="Manager.AppManager.InitApp"/> ДО первой записи конфига: новые
    /// файлы наследуют права каталога, поэтому порядок важен.</para>
    /// </summary>
    public static void Harden()
    {
        // Ловим ВСЁ и здесь, а не только внутри HardenFolder. Этот метод зовётся из InitApp, то есть
        // с пути запуска приложения: любое исключение, ушедшее отсюда наверх, означало бы, что
        // программа не открылась вовсе. Ни проверка прав (WindowsIdentity.GetCurrent), ни получение
        // пути (GetConfigPath заодно создаёт каталог и умеет упереться в диск или длину пути) такой
        // цены не стоят. Не удалось закрыть права — работаем как раньше, со строкой в журнале.
        try
        {
            // Не-Windows: прав такого вида там нет, каталоги живут по unix-режимам.
            if (!OperatingSystem.IsWindows())
            {
                return;
            }

            // Данные в профиле пользователя (портативный/непривилегированный запуск): чужой профиль и
            // так не читается, а закрыть каталог «только администраторам» здесь означало бы отрезать
            // от него самого владельца данных.
            if (Environment.GetEnvironmentVariable(Global.LocalAppData) == "1")
            {
                return;
            }

            // Без прав администратора ACL всё равно не сменить, а частично применённый — хуже, чем никакой.
            if (!Utils.IsAdministrator())
            {
                return;
            }

            HardenFolder(Utils.GetConfigPath());
            HardenFolder(Utils.GetBinConfigPath());
            HardenFolder(Utils.GetTempPath());
            HardenFolder(Utils.GetBackupPath(string.Empty));
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
    }

    [SupportedOSPlatform("windows")]
    private static void HardenFolder(string path)
    {
        try
        {
            var dir = new DirectoryInfo(path);
            if (!dir.Exists)
            {
                dir.Create();
                dir.Refresh();
            }

            var security = dir.GetAccessControl(AccessControlSections.Access);
            if (IsHardened(security))
            {
                return;
            }

            // Снять наследование БЕЗ переноса унаследованных правил в явные: иначе «Все» осталось бы
            // на месте, просто уже своей записью.
            security.SetAccessRuleProtection(true, false);
            foreach (FileSystemAccessRule rule in security.GetAccessRules(true, false, typeof(SecurityIdentifier)))
            {
                security.RemoveAccessRuleSpecific(rule);
            }
            foreach (var sid in Keepers())
            {
                security.AddAccessRule(new FileSystemAccessRule(
                    sid,
                    FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                    PropagationFlags.None,
                    AccessControlType.Allow));
            }
            dir.SetAccessControl(security);

            Logging.SaveLog($"{_tag}: {dir.Name} закрыт для посторонних (SYSTEM + администраторы)");
        }
        catch (Exception ex)
        {
            // Ни одна причина отказа не стоит несостоявшегося запуска: не вышло — работаем как прежде.
            Logging.SaveLog(_tag, ex);
        }
    }

    /// <summary>
    /// Каталог уже в нужном виде: наследование снято и ни одного разрешения сверх наших двух.
    /// Проверяем ИМЕННО состав, а не только флаг наследования, — иначе ручная правка прав снаружи
    /// осталась бы незамеченной навсегда.
    /// </summary>
    [SupportedOSPlatform("windows")]
    private static bool IsHardened(DirectorySecurity security)
    {
        if (!security.AreAccessRulesProtected)
        {
            return false;
        }

        var keepers = Keepers().Select(x => x.Value).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (FileSystemAccessRule rule in security.GetAccessRules(true, true, typeof(SecurityIdentifier)))
        {
            if (!keepers.Contains(rule.IdentityReference.Value))
            {
                return false;
            }
        }
        return true;
    }

    /// <summary>Кому оставляем доступ. Только SID: имена этих групп локализованы.</summary>
    [SupportedOSPlatform("windows")]
    private static SecurityIdentifier[] Keepers() =>
    [
        new(WellKnownSidType.LocalSystemSid, null),
        new(WellKnownSidType.BuiltinAdministratorsSid, null),
    ];
}
