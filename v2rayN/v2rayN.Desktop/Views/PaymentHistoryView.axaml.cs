using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Суб-экран «История платежей»: бесшовный тулбар (назад + заголовок) и полный список платежей с
/// четырьмя состояниями (скелет / список / пусто / ошибка). Порт Android
/// activity_payment_history.xml + PaymentHistoryActivity.kt. DATA-DRIVEN: всё биндится к
/// <see cref="PaymentHistoryViewModel"/> (GET /client/payments, кэш-first) — пусто до реального
/// ответа API.
///
/// Самодостаточен: DataContext ставит сам (хост создаёт вьюху без DataContext), design-time —
/// пример списка со всеми четырьмя вариантами статус-чипа для превьювера. Навигацию наружу отдаёт
/// событиями: <see cref="BackRequested"/> (шеврон «назад») и <see cref="BuyRequested"/>
/// (CTA «Купить подписку» пустого состояния) — хост решает, куда вести.
/// </summary>
public partial class PaymentHistoryView : UserControl
{
    /// <summary>Нажат тулбарный «назад» — хост закрывает суб-экран (аналог home-as-up).</summary>
    public event EventHandler? BackRequested;

    /// <summary>Нажата CTA «Купить подписку» в пустом состоянии — хост открывает покупку (аналог btn_history_buy → BuyTariffActivity).</summary>
    public event EventHandler? BuyRequested;

    public PaymentHistoryView()
    {
        InitializeComponent();
        DataContext = Design.IsDesignMode ? PaymentHistoryViewModel.CreateDesign() : new PaymentHistoryViewModel();
    }

    private void OnBackClick(object? sender, RoutedEventArgs e)
    {
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    private void OnBuyClick(object? sender, RoutedEventArgs e)
    {
        BuyRequested?.Invoke(this, EventArgs.Empty);
    }
}
