using v2rayN.Desktop.Common;

namespace v2rayN.Desktop.Views;

/// <summary>
/// «Журнал» — подэкран настроек по единому лекалу (screens.md «Подэкраны»): поле поиска → записи →
/// карточка с «Копировать» и «Очистить».
///
/// Раньше журнал показывало легаси-представление <c>MsgView</c>: англоязычный ResUI, редактор
/// AvaloniaEdit, четыре контрола в ряд над ним и никакого пустого состояния. <c>MsgView</c> НЕ трогаем —
/// его использует инлайн-панель сообщений оболочки; это отдельный экран.
///
/// Данные берутся из <see cref="CoreLogBuffer"/>, а не из <c>MsgViewModel</c>: та модель журнал не
/// хранит, она перекладывает строки прямо в текстовый редактор представления. Экран создаётся заново
/// на каждое открытие, поэтому, читая оттуда, он всякий раз показывал бы пустоту.
///
/// Пустых состояний ДВА, и это разные сообщения:
///   • журнал пуст — «Записей пока нет» / «Журнал заполнится при следующем подключении.»;
///   • по запросу ничего не нашлось — «Ничего не найдено».
/// Одна формулировка на оба случая заставляла бы гадать, журнал пуст или запрос неудачен.
/// </summary>
public partial class LogPage : UserControl, ISubPage
{
    // Сколько строк рисуем. Буфер держит 500, но отрисовать их в ItemsControl без виртуализации —
    // это заметная пауза на открытии; журнал читают с конца, поэтому показываем последние.
    private const int MaxRendered = 300;

    private List<string> _lines = new();

    public event EventHandler? BackRequested;

    public LogPage()
    {
        InitializeComponent();

        _lines = CoreLogBuffer.Snapshot();

        txtFilter.GetObservable(TextBox.TextProperty).Subscribe(_ => ApplyFilter());

        RowCopy.Tapped += async (_, _) => await CopyAsync();
        RowClear.Tapped += (_, _) =>
        {
            CoreLogBuffer.Clear();
            _lines = new List<string>();
            ApplyFilter();
        };

        // Живое дополнение: строки приходят на потоке издателя, поэтому в UI уходим сами.
        CoreLogBuffer.LineAppended += OnLineAppended;
        DetachedFromVisualTree += (_, _) => CoreLogBuffer.LineAppended -= OnLineAppended;

        btnBack.Click += (_, _) => BackRequested?.Invoke(this, EventArgs.Empty);

        ApplyFilter();
    }

    private void OnLineAppended(object? sender, string line) =>
        Dispatcher.UIThread.Post(() =>
        {
            _lines.Add(line);
            if (_lines.Count > CoreLogBuffer.Capacity)
            {
                _lines.RemoveRange(0, _lines.Count - CoreLogBuffer.Capacity);
            }
            ApplyFilter();
        });

    private void ApplyFilter()
    {
        var q = txtFilter.Text?.Trim();
        var hasQuery = q.IsNotEmpty();

        var matched = hasQuery
            ? _lines.Where(l => l.Contains(q!, StringComparison.OrdinalIgnoreCase)).ToList()
            : _lines;

        var shown = matched.Count > MaxRendered
            ? matched.Skip(matched.Count - MaxRendered).ToList()
            : matched.ToList();

        listLines.ItemsSource = shown;
        LinesCard.IsVisible = shown.Count > 0;
        EmptyCard.IsVisible = shown.Count == 0;

        // Две разные причины пустоты — два разных текста.
        txtEmptyTitle.Text = hasQuery ? L.T("Log_NoMatchTitle") : L.T("Log_EmptyTitle");
        txtEmptyText.Text = hasQuery ? L.T("Log_NoMatchText") : L.T("Log_EmptyText");

        txtLineCount.Text = _lines.Count > 0 ? L.F("Log_Lines", _lines.Count) : string.Empty;
    }

    /// <summary>Копируем ВЕСЬ журнал, а не отфильтрованный кусок: копию просят, чтобы отдать её в
    /// поддержку, и обрезанная по случайному запросу выборка там бесполезна.</summary>
    private async Task CopyAsync()
    {
        if (_lines.Count == 0)
        {
            return;
        }
        await SubPageUtil.CopyAsync(this, string.Join(Environment.NewLine, _lines));
        txtCopyState.Text = L.T("Log_Copied");
    }
}
