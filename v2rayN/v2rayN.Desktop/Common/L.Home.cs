namespace v2rayN.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP1. Home / shield.  Keys: Home_* (+ Common_* / Status_* references).
// Views: ConnectHeroView(.axaml/.cs), HomeView, CompactHomeView, HomeViewModel.
// Inventory: LOCALIZATION_PLAN.md §2.1. Add each key with Add("Home_X", "ru", "en").
// This is the ONLY L file WP1 edits.
//
// Cross-WP references used by the Home code-behind/VM (NOT defined here):
//   • Common_* (L.Common.cs, WP0): Common_CouldntConnect, Common_AddSubscription,
//     Common_AddFromClipboard, Common_AddViaQr, Common_ServersPlural,
//     Common_ProvidersPlural.
//   • Status_* (L.Shell.cs, WP6): Status_Connecting / Status_Connected, the shield's
//     connecting/connected caption is the SAME shared status string the status bar uses,
//     so Home reuses WP6's keys rather than redefining them (per plan §2.1/§2.6).
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterHome()
    {
        // ── Shield status captions (idle / no-server) ──
        Add("Home_NotConnected", "Не подключено", "Not connected");
        Add("Home_ChooseServer", "Выберите сервер", "Choose a server");

        // Error-shield retry affordance (ConnectHeroView RetryHint), not in the §2.1 table
        // but a user-facing literal in an owned Home view, so localized here.
        Add("Home_RetryHint", "Нажмите, чтобы повторить", "Tap to retry");

        //  ПОЧЕМУ НЕ ПОДКЛЮЧИЛОСЬ. Пилюля говорит ЧТО случилось («Не удалось подключиться»),
        //  подсказка под ней — ПОЧЕМУ и ЧТО ДЕЛАТЬ (00-rules §9.4). Раньше здесь всегда стояло
        //  «Нажмите, чтобы повторить»: повторять одно и то же, не зная причины, бессмысленно, а
        //  настоящая причина уходила в журнал, которого никто не видит. Разбор причины —
        //  HomeViewModel.DescribeConnectFailure по ECoreStartFailure и тексту ядра.
        //  Без «слева»: подсказка живёт в ConnectHeroView, а он общий для обеих раскладок — в
        //  широкой список действительно слева, в компактной он под щитом. Указание на сторону
        //  отправляло половину пользователей смотреть в пустое место.
        Add("Home_FailNoServer", "Сервер не выбран. Выберите сервер из списка и повторите.", "No server selected. Pick one from the list and try again.");
        Add("Home_FailCoreMissing", "Не хватает файлов приложения: ядро VPN не найдено. Переустановите приложение.", "The app is missing files: the VPN core wasn't found. Reinstall the app.");
        Add("Home_FailConfig", "Настройки сервера не подходят. Обновите подписку и повторите.", "The server settings don't fit. Refresh the subscription and try again.");
        Add("Home_FailPortBusy", "Локальный порт занят другой программой. Закройте другой VPN и повторите.", "A local port is taken by another program. Close the other VPN and try again.");
        Add("Home_FailNoRights", "Не хватает прав на сетевой адаптер. Запустите приложение от имени администратора.", "Not enough rights for the network adapter. Run the app as administrator.");
        Add("Home_FailServerRefused", "Сервер не отвечает. Выберите другой сервер или повторите позже.", "The server isn't responding. Pick another server or try again later.");
        Add("Home_FailCoreRejected", "Ядро отклонило настройки сервера. Обновите подписку и выберите сервер заново.", "The core rejected the server settings. Refresh the subscription and pick a server again.");
        Add("Home_FailUnknown", "Ядро VPN не запустилось. Причина записана в журнале: «Настройки», раздел «Журнал».", "The VPN core didn't start. The reason is in the log: Settings, Log section.");

        // ── Empty / onboarding (no subscriptions) ──
        Add("Home_Welcome", "Приветствуем!", "Welcome!");
        Add("Home_NoSubs", "Пока нет подписок", "No subscriptions yet");
        Add("Home_NoSubsHint", "Купите подписку, и серверы появятся сами", "Buy a subscription and the servers will appear on their own");
        Add("Home_GoToAccount", "Перейти в аккаунт", "Go to account");

        // First-run onboarding hero (OnboardingView): active-verb title names the exact job, subtitle
        // tells the concrete "how" and promises immediacy. Home_Welcome/Home_NoSubsHint kept above for
        // any other consumer; the onboarding screen uses these. Divider is the short sentence-case form
        // of Onboarding_OrSignIn (L.Account.cs), inside the two-hairline "или ..." rule.

        // ── Server-list grouping / meta (HomeViewModel) ──
        // Fallback group name for servers without a subscription remark.
        Add("Home_MyServers", "Мои серверы", "My servers");
        // Composed meta line: {0} = "{n} servers" plural, {1} = "{n} providers" plural.
        // The middle dot is locale-neutral and stays in the template.
        Add("Home_ServersProvidersMeta", "{0} · {1}", "{0} · {1}");

        // ── Routing-mode banner (HomeView / CompactHomeView) ──
        // Shown when "all traffic" (TUN) was requested but the app lacks the rights, so traffic
        // actually flows through the system proxy. Not in the §2.1 table but user-facing literals
        // in owned Home views (the mode name itself comes from StatusBar.RoutingModeDisplay).
        Add("Home_TunUnavailable", "Режим «весь трафик» недоступен без прав администратора", "Whole-traffic mode isn't available without administrator rights");
        Add("Home_RestartElevated", "Перезапустить с правами", "Restart as administrator");

        // ── Account chip (HomeAccountChip) ──
        // Registered per plan §2.1 for reuse; HomeAccountChip.axaml is outside WP1's file scope,
        // so its literal is not converted here; this key is available for whoever owns that view.
        Add("Home_ManageAccount", "Управление аккаунтом", "Manage account");
    }
}
