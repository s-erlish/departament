namespace v2rayN.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP2. Server list / subscription meta.  Keys: Servers_*, Sub_* (+ Common_*).
// Views: ServerListView, CompactServersView, SubscriptionMetaView(.axaml/.cs).
// Inventory: LOCALIZATION_PLAN.md §2.2. Add each key with Add("Servers_X", "ru", "en").
// This is the ONLY L file WP2 edits.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterServers()
    {
        // ── Server list (ServerListView, CompactServersView) ──
        Add("Servers_MakeDefault", "Сделать основным", "Make default");
        Add("Servers_Duplicate", "Дублировать", "Duplicate");
        Add("Servers_ShareQr", "Поделиться · QR-код", "Share · QR code");
        Add("Servers_ShareLink", "Поделиться · ссылка", "Share · link");
        //  Пусто у ТОЛЬКО ЧТО зарегистрировавшегося, а у него подписки ещё нет и взяться ей неоткуда.
        //  «Добавьте подписку» отправляло искать то, чего у человека нет; правильный следующий шаг это
        //  купить, и тогда серверы приезжают сами, вручную добавлять ничего не нужно.
        Add("Servers_Empty", "Серверов пока нет", "No servers yet");
        Add("Servers_EmptyHint", "Купите подписку, и серверы появятся здесь сами", "Buy a subscription and the servers will show up here on their own");
        Add("Servers_SearchPlaceholder", "Поиск серверов…", "Search servers…");

        // ── Пинг в строке сервера (screens.md «Список серверов») ──
        //  Единица замера отдельным ключом: «133 мс» / «133 ms». Узел, который не ответил на проверку,
        //  показывается дефисом на месте числа, красным, как на Android (MainRecyclerAdapter.PING_FAILED
        //  и цвет pingBad): вердикт несёт цвет, а знак говорит «числа нет». Прежнее «n/a» было
        //  английским сокращением посреди русского интерфейса.
        Add("Servers_Ms", "мс", "ms");
        Add("Servers_PingFailed", "-", "-");

        // ── Subscription meta-bar (SubscriptionMetaView) ──
        Add("Sub_Delete", "Удалить подписку", "Delete subscription");
        Add("Sub_DeleteConfirm", "Удалить подписку?", "Delete subscription?");
        Add("Sub_Support", "Поддержка", "Support");
        Add("Sub_Expired", "Просрочено", "Expired");
        Add("Sub_Until", "до {0:dd.MM.yyyy}", "until {0:dd.MM.yyyy}");

        // ── Подтверждения пинга и обновления подписки (motion.md «Пинг и обновление подписки») ──
        Add("Sub_ToastPinged", "Задержка обновлена", "Latency updated");
        Add("Sub_ToastRefreshed", "Подписка обновлена · {0}", "Subscription updated · {0}");
    }
}
