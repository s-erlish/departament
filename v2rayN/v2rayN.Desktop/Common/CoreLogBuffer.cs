namespace v2rayN.Desktop.Common;

/// <summary>
/// Кольцевой буфер строк журнала ядра за текущий сеанс — источник данных подэкрана «Журнал».
///
/// Зачем он отдельно от <c>MsgViewModel</c>: та модель НЕ хранит журнал. Она перекладывает строки из
/// очереди в <c>Interaction</c> и отдаёт их прямо в текстовый редактор представления — накопленный
/// текст живёт в контроле. Подэкран «Журнал» создаётся заново на каждое открытие, поэтому, читая
/// оттуда, он всякий раз показывал бы пустоту, а поиск и «Копировать» работали бы по тому, что
/// натекло с момента открытия. Журнал должен переживать закрытие экрана — значит, хранить его должен
/// не экран.
///
/// Буфер ОГРАНИЧЕН <see cref="Capacity"/> строками: журнал ядра пишется непрерывно, и неограниченный
/// список за долгий сеанс съел бы память. Предел тот же, что у <c>MsgViewModel.NumMaxMsg</c>, чтобы
/// две поверхности одного журнала не расходились в глубине.
///
/// Подписка ленивая — первым обращением. Экран, открытый в середине сеанса, увидит всё, что накопилось
/// с первого обращения к буферу; чтобы буфер покрывал сеанс ЦЕЛИКОМ, достаточно одного вызова
/// <see cref="Start"/> при запуске приложения (App/MainWindow — файлы соседнего агента, заявка в отчёте).
/// Пока этого вызова нет, пустое состояние экрана говорит правду: записей действительно ещё нет.
/// </summary>
public static class CoreLogBuffer
{
    /// <summary>Сколько строк держим. Совпадает с <c>MsgViewModel.NumMaxMsg</c>.</summary>
    public const int Capacity = 500;

    private static readonly object _lock = new();
    private static readonly Queue<string> _lines = new(Capacity);
    private static IDisposable? _subscription;

    /// <summary>Пришла новая строка. Поднимается на потоке издателя — подписчик обязан сам уйти
    /// в UI-поток, если собирается трогать интерфейс.</summary>
    public static event EventHandler<string>? LineAppended;

    /// <summary>Журнал очищен (кнопка «Очистить»).</summary>
    public static event EventHandler? Cleared;

    /// <summary>Начинает сбор, если он ещё не начат. Идемпотентно.</summary>
    public static void Start()
    {
        lock (_lock)
        {
            _subscription ??= AppEvents.SendMsgViewRequested.AsObservable().Subscribe(Append);
        }
    }

    /// <summary>Снимок буфера сверху вниз. Копия — вызывающий волен держать её сколько угодно.</summary>
    public static List<string> Snapshot()
    {
        Start();
        lock (_lock)
        {
            return _lines.ToList();
        }
    }

    public static void Clear()
    {
        lock (_lock)
        {
            _lines.Clear();
        }
        Cleared?.Invoke(null, EventArgs.Empty);
    }

    private static void Append(string? content)
    {
        if (content.IsNullOrEmpty())
        {
            return;
        }

        // Издатель шлёт кусками, а не строками: один вызов может нести несколько переводов строки
        // (или ни одного). Режем сами, иначе «строка журнала» перестала бы соответствовать строке.
        var parts = content!.Replace("\r\n", "\n").Split('\n');
        foreach (var raw in parts)
        {
            var line = raw.TrimEnd();
            if (line.Length == 0)
            {
                continue;
            }
            lock (_lock)
            {
                _lines.Enqueue(line);
                while (_lines.Count > Capacity)
                {
                    _lines.Dequeue();
                }
            }
            LineAppended?.Invoke(null, line);
        }
    }
}
