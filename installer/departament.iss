; Установщик departament для Windows (Inno Setup 6).
;
; Собирается в CI (.github/workflows/build.yml) из папки публикации, той же, что уходит в архив
; самообновления. Установщик нужен для первой установки. Дальше программа обновляется сама, из
; выпусков на GitHub, через AmazTool, и установщик для этого больше не запускается.
;
;   ISCC /DAppVersion=1.0.0 /DNumericVersion=1.0.0.0 /DSourceDir=<dist> /DIconFile=<AppIcon.ico> /O<out> departament.iss
;
; Программа манифестирована как requireAdministrator, поэтому и установщик просит права
; администратора. С ними он ставит программу в Program Files для всех пользователей и закрывает
; запущенную копию штатно, без второго запроса UAC посреди установки.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef NumericVersion
  #define NumericVersion "0.0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist"
#endif
#ifndef IconFile
  #define IconFile "..\departament\departament.Desktop\Assets\AppIcon.ico"
#endif

[Setup]
; AppId менять нельзя: по нему Windows узнаёт установленную программу, и новая установка встаёт
; поверх прежней, а не рядом. Тот же идентификатор знает программа (App.InstallerAppId): она сверяет
; версию в «Приложениях» с собой после самообновления.
AppId={{60C53B95-8ECB-4307-A76B-756BEAC48B89}
AppName=departament VPN
AppVersion={#AppVersion}
AppVerName=departament VPN {#AppVersion}
AppPublisher=departament
AppPublisherURL=https://github.com/s-erlish/departament
AppSupportURL=https://github.com/s-erlish/departament
AppUpdatesURL=https://github.com/s-erlish/departament/releases
VersionInfoVersion={#NumericVersion}
VersionInfoProductName=departament VPN
VersionInfoDescription=Установка departament VPN
DefaultDirName={autopf}\departament
DisableDirPage=auto
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputBaseFilename=departament-setup
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\departament.exe
UninstallDisplayName=departament VPN
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; Запущенную программу закрывает [Code] (CloseRunningApp): штатно, через departament.exe --quit, чтобы
; ядро остановилось и системный прокси был снят. Restart Manager Windows тут не помогает: на закрытие
; окна программа уходит в трей, а не выходит.
CloseApplications=no
RestartApplications=no
ShowLanguageDialog=no
LanguageDetectionMethod=uilanguage

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
ru.DesktopIcon=Значок на рабочем столе
en.DesktopIcon=Desktop shortcut
ru.LaunchApp=Запустить departament от имени администратора
en.LaunchApp=Launch departament as administrator
ru.RemoveUserData=Удалить также настройки, серверы и вход в аккаунт?%n%nЕсли оставить, при повторной установке всё будет на месте.
en.RemoveUserData=Also remove settings, servers and account sign-in?%n%nIf you keep them, everything will be in place after reinstalling.

[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
; Каталоги с учётными данными создаём САМИ, чтобы закрыть их права ещё до первого запуска программы
; (см. [Run] ниже). Иначе их создала бы программа, и первый же записанный туда файл успел бы
; полежать с правами Program Files, где читать разрешено всем.
;   guiConfigs — пропуск аккаунта (departament_auth.dat) и база с URL подписки (guiNDB.db);
;   binConfigs — сгенерированный конфиг ядра, в нём uuid/пароль узла открытым текстом;
;   guiBackups — backup_*.zip: обычный zip, без шифрования, с полной копией guiConfigs внутри;
;   guiTemps   — туда резервное копирование раскладывает копию всего guiConfigs перед упаковкой.
; Последние два обязательны: без них защита обходится одним нажатием «Резервное копирование».
; guiLogs намеренно не трогаем — там только журнал приложения, без токенов и адресов подписки.
Name: "{app}\guiConfigs"
Name: "{app}\binConfigs"
Name: "{app}\guiBackups"
Name: "{app}\guiTemps"

[Icons]
Name: "{autoprograms}\departament VPN"; Filename: "{app}\departament.exe"
Name: "{autodesktop}\departament VPN"; Filename: "{app}\departament.exe"; Tasks: desktopicon

[Run]
; Права на каталоги с учётными данными: снять наследование и оставить ровно два разрешения — SYSTEM
; и встроенную группу администраторов, с наследованием внутрь ((OI)(CI)). По умолчанию Program Files
; разрешает ЧИТАТЬ всем, то есть пропуск аккаунта, URL подписки и конфиг ядра с паролем узла были
; открыты любому другому пользователю машины и любой программе, запущенной без прав.
;
; Группы названы SID'ами, а не именами: на русской Windows встроенная группа зовётся «СИСТЕМА», и
; команда с английским именем там просто не нашла бы её. S-1-5-18 — SYSTEM, S-1-5-32-544 —
; администраторы. Программа, установщик, деинсталлятор и AmazTool работают от администратора, поэтому
; доступ к своим данным никто из них не теряет.
;
; То же самое умеет и сама программа (DataFolderSecurity) — на каждом запуске и идемпотентно. Здесь
; это нужно для ПЕРВОГО запуска, там — для тех, кто уже установил программу раньше: дальше она
; обновляется через AmazTool, и установщик у них больше не запускается никогда.
Filename: "{sys}\icacls.exe"; Parameters: """{app}\guiConfigs"" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F /grant:r *S-1-5-32-544:(OI)(CI)F"; Flags: runhidden
Filename: "{sys}\icacls.exe"; Parameters: """{app}\binConfigs"" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F /grant:r *S-1-5-32-544:(OI)(CI)F"; Flags: runhidden
Filename: "{sys}\icacls.exe"; Parameters: """{app}\guiBackups"" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F /grant:r *S-1-5-32-544:(OI)(CI)F"; Flags: runhidden
Filename: "{sys}\icacls.exe"; Parameters: """{app}\guiTemps"" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F /grant:r *S-1-5-32-544:(OI)(CI)F"; Flags: runhidden

; Галочка на последней странице включена сразу. runascurrentuser — запуск с правами самого установщика,
; то есть от имени администратора. Без флага Inno запускает postinstall-программу от исходного, не
; повышенного пользователя, а программе с requireAdministrator Windows такой запуск отказывает:
; «CreateProcess не выполнен; код 740. Запрошенная операция требует повышения» — ровно это
; владелец и увидел в конце установки. shellexec — вторая страховка: запуск через оболочку Windows,
; которая при нехватке прав показывает запрос UAC, а не ошибку с кодом.
Filename: "{app}\departament.exe"; Description: "{cm:LaunchApp}"; Flags: nowait postinstall skipifsilent runascurrentuser shellexec

[UninstallDelete]
; Служебное самообновления и файлы, которые могли прийти с обновлениями после установки (ядра,
; наборы правил): установщик их не ставил и сам о них не знает.
Type: filesandordirs; Name: "{app}\.update"
Type: files; Name: "{app}\AmazTool.exe.tmp"
Type: filesandordirs; Name: "{app}\bin"

[Code]
function IsOurPath(const Path: String): Boolean;
var
  Prefix: String;
begin
  Prefix := AddBackslash(ExpandConstant('{app}'));
  Result := (Path <> '') and (CompareText(Copy(Path, 1, Length(Prefix)), Prefix) = 0);
end;

{ Процессы с этим именем, запущенные ИЗ ПАПКИ УСТАНОВКИ. Копию программы в другой папке не трогаем. }
function OurProcesses(const ExeName: String; Terminate: Boolean): Integer;
var
  Locator, Service, Items, Item: Variant;
  I: Integer;
  Path: String;
begin
  Result := 0;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Service := Locator.ConnectServer('.', 'root\CIMV2');
    Items := Service.ExecQuery('SELECT ProcessId, ExecutablePath FROM Win32_Process WHERE Name = ''' + ExeName + '''');
    for I := 0 to Items.Count - 1 do
    begin
      Item := Items.ItemIndex(I);
      Path := '';
      if not VarIsNull(Item.ExecutablePath) then
        Path := Item.ExecutablePath;
      if IsOurPath(Path) then
      begin
        Result := Result + 1;
        if Terminate then
        begin
          Log('Terminating ' + Path);
          Item.Terminate();
        end;
      end;
    end;
  except
    Log('WMI: ' + GetExceptionMessage);
  end;
end;

{ Закрыть запущенную программу штатно: departament.exe --quit передаёт просьбу работающей копии, и та
  выходит тем же путём, что «Выход» в трее: ядро остановлено, системный прокси снят. Убитый процесс
  оставил бы прокси на мёртвом порту, и интернет не работал бы до следующего запуска. Не вышла за 15 с,
  значит, закрываем силой. Ядра и помощник обновления из папки установки закрываются в любом случае. }
procedure CloseRunningApp();
var
  Exe: String;
  ResultCode, Waited: Integer;
begin
  Exe := ExpandConstant('{app}\departament.exe');
  if FileExists(Exe) and (OurProcesses('departament.exe', False) > 0) then
  begin
    Log('Asking departament to quit');
    Exec(Exe, '--quit', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Waited := 0;
    while (OurProcesses('departament.exe', False) > 0) and (Waited < 15000) do
    begin
      Sleep(250);
      Waited := Waited + 250;
    end;
    if OurProcesses('departament.exe', False) > 0 then
    begin
      Log('departament did not quit in 15 s');
      OurProcesses('departament.exe', True);
      Sleep(500);
    end;
  end;
  OurProcesses('AmazTool.exe', True);
  OurProcesses('xray.exe', True);
  OurProcesses('sing-box.exe', True);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  CloseRunningApp();
  Result := '';
end;

function InitializeUninstall(): Boolean;
begin
  CloseRunningApp();
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
begin
  if CurUninstallStep <> usPostUninstall then
    Exit;

  { Автозапуск и ссылки departamentvpn:// программа прописывает сама (AutostartHelper, RegisterAuthScheme):
    без программы они вели бы в пустоту. }
  Exec(ExpandConstant('{sys}\schtasks.exe'), '/Delete /TN "departament" /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'departament');
  RegDeleteKeyIncludingSubkeys(HKCU, 'Software\Classes\departamentvpn');

  if UninstallSilent then
    Exit;
  if MsgBox(CustomMessage('RemoveUserData'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
  begin
    DelTree(ExpandConstant('{app}\guiConfigs'), True, True, True);
    DelTree(ExpandConstant('{app}\guiLogs'), True, True, True);
    DelTree(ExpandConstant('{app}\guiTemps'), True, True, True);
    DelTree(ExpandConstant('{app}\binConfigs'), True, True, True);
    RemoveDir(ExpandConstant('{app}'));
  end;
end;
