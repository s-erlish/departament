namespace v2rayN.Desktop.Common;

// ─────────────────────────────────────────────────────────────────────────────
// OWNER: WP5. Buy / Devices / Payment history.  Keys: Buy_*, Devices_*, History_*
//        (+ Common_* references).
// Views: BuyView, BuyViewModel, DevicesView, DevicesViewModel, PaymentHistoryView,
//        PaymentHistoryViewModel.
// Inventory: LOCALIZATION_PLAN.md §2.5. Add each key with Add("Buy_X", "ru", "en").
// This is the ONLY L file WP5 edits.
// ─────────────────────────────────────────────────────────────────────────────

public sealed partial class L
{
    partial void RegisterBuy()
    {
        // ── Buy («Купить подписку») ──
        Add("Buy_Paid", "Подписка оплачена", "Subscription paid");
        Add("Buy_PaidSubtitle", "Серверы уже добавлены, можно подключаться", "Servers are already added, you can connect");
        // Пояснение под заголовком подэкрана (лекало: заголовок 22/700 → пояснение 13).
        Add("Buy_Note", "Выберите тариф и срок", "Choose a plan and a term");
        Add("Buy_ErrTitle", "Тарифы не загрузились", "Plans didn't load");
        // Бейдж у тарифа, который уже оплачен (Android buy_current).
        Add("Buy_Current", "Текущий", "Current");
        // Подпись под ценой за месяц в свёрнутой карточке (Android buy_per_month).
        Add("Buy_PerMonth", "в месяц", "per month");
        // Выгода длинного срока против самого короткого (Android buy_saving).
        Add("Buy_Saving", "Выгода {0}", "Save {0}");
        Add("Buy_AdditionalDevices", "Дополнительные устройства", "Additional devices");
        Add("Buy_AddDevice", "Добавить устройство", "Add device");
        Add("Buy_Total", "Итого", "Total");
        Add("Buy_Pay", "Оплатить", "Pay");
        Add("Buy_PaymentMethod", "Способ оплаты", "Payment method");
        Add("Buy_Processing", "Платёж обрабатывается…", "Processing payment…");
        Add("Buy_ErrLoadPlans", "Не удалось загрузить тарифы. Проверьте соединение и повторите.", "Couldn't load plans. Check your connection and try again.");
        Add("Buy_NoPlans", "Тарифы недоступны", "No plans available");
        Add("Buy_ChoosePeriod", "Выберите срок подписки", "Choose a subscription period");
        Add("Buy_NoPaymentMethods", "Способы оплаты недоступны", "No payment methods available");
        Add("Buy_FromBalance", "С баланса · {0}", "From balance · {0}");
        Add("Buy_PaymentError", "Ошибка оплаты", "Payment error");
        Add("Buy_DevicesTraffic", "Устройства: {0} · Трафик: {1}", "Devices: {0} · Traffic: {1}");

        // ── Devices («Устройства») ──
        Add("Devices_Subtitle", "Устройства, подключённые к вашей подписке", "Devices connected to your subscription");
        Add("Devices_ThisDevice", "Это устройство", "This device");
        Add("Devices_Remove", "Удалить", "Remove");
        Add("Devices_RemoveConfirm", "Удалить устройство?", "Remove device?");
        Add("Devices_Foot",
            "Удалённое устройство отключается от подписки и освобождает слот.",
            "A removed device is disconnected from your subscription and frees up a slot.");
        Add("Devices_Empty", "Нет подключённых устройств", "No connected devices");
        Add("Devices_EmptyHint", "Устройства появятся здесь после первого подключения к VPN.", "Devices will appear here after your first VPN connection.");
        Add("Devices_NoSub", "Активная подписка не найдена", "No active subscription found");
        Add("Devices_NoSubHint", "Оформите подписку в разделе «Аккаунт», чтобы подключать устройства.", "Get a subscription in the Account section to connect devices.");
        Add("Devices_GoToAccount", "Перейти в аккаунт", "Go to account");
        Add("Devices_UnlinkBody", "Устройство «{0}» отключится от подписки и освободит слот.", "Device \"{0}\" will be disconnected from your subscription and free up a slot.");
        Add("Devices_UnlinkFailed", "Не удалось удалить устройство. Попробуйте позже.", "Couldn't remove the device. Try again later.");
        Add("Devices_Unlinked", "Устройство удалено", "Device removed");
        Add("Devices_ErrLoad", "Не удалось загрузить устройства. Попробуйте позже.", "Couldn't load devices. Try again later.");
        Add("Devices_PlatformActive", "{0} · Активно: {1}", "{0} · Active: {1}");
        Add("Devices_Active", "Активно: {0}", "Active: {0}");
        Add("Devices_Id", "ID: {0}", "ID: {0}");
        Add("Devices_Unknown", "Неизвестное устройство", "Unknown device");

        // ── Payment history («История платежей») ──
        Add("History_Empty", "Платежей пока нет", "No payments yet");
        Add("History_EmptyHint",
            "Здесь появятся пополнения баланса и оплаты тарифов.",
            "Balance top-ups and plan payments will show up here.");
        Add("History_ErrLoad", "Не удалось загрузить историю платежей", "Couldn't load payment history");
        Add("History_StatusPaid", "Оплачено", "Paid");
        Add("History_StatusProcessing", "В обработке", "Processing");
        Add("History_StatusFailed", "Ошибка", "Failed");
        Add("History_StatusCanceled", "Отменён", "Canceled");

        // Design-time sample rows (previewer only; never shipped to users).
        Add("History_SampleTopUp", "Пополнение баланса", "Balance top-up");
        Add("History_SamplePlan", "Тариф Base", "Base plan");
    }
}
