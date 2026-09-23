using Avalonia.Animation;
using Avalonia.Layout;
using Avalonia.VisualTree;
using v2rayN.Desktop.Common;
using v2rayN.Desktop.ViewModels;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Вкладка «Настройки» — шесть разделов Incy-карточками (Подключение · Обход блокировок ·
/// Производительность · Интерфейс · Подписка · О приложении). Состав строк — screens.md.
///
/// <para><b>Три архетипа строки, и каждый правый элемент честен:</b>
///   • <b>окошко у значения</b> (значение + каретка, общий <see cref="ValuePicker"/>) — Режим · DNS ·
///     Пинг · Оформление · Язык · Масштаб · Автообновление · Число Mux;
///   • <b>шеврон</b> — строка уходит на суб-страницу: Прокси по приложениям · Локальный прокси ·
///     Маршрутизация · Файлы ресурсов · Журнал · Проверить обновления · Резервное копирование ·
///     Схемы URL · О приложении;
///   • <b>тумблер</b> — булево: Обход локальной сети · IPv6 · Mux · Фрагментация ·
///     Облегчённый режим · Запуск с системой.</para>
///
/// <para><b>Нажатие.</b> У строки настроек только ХОВЕР — так в прототипе (класс <c>.srow</c> несёт
/// один <c>:hover</c>, прогиб 0.985 живёт на <c>.row</c> Главной и Аккаунта). Строка настроек —
/// якорь раскрытого окошка, и окошко лежит ВНУТРИ неё: прогиб масштабировал бы вместе со строкой и
/// его, а motion.md запрещает масштаб под окошком («от него текст внутри дёргается»). Единая
/// лестница (<see cref="PressFeedback"/>) поэтому гасится одним сеттером в разметке; на других
/// экранах она работает как работала. Указательный путь один — <c>Tapped</c>: ровно один вызов на
/// тап, и без трансформа на строке дребезг PointerExited/Entered (дефект «тап через раз», 1e884ad9)
/// невозможен по построению.</para>
///
/// <para><b>Контракт «окошка».</b> Карточка не обрезает содержимое (иначе окошко нижней строки
/// срезается её кромкой), значит маскировать углы больше нечем — скругление крайних строк карточки
/// считает <see cref="ValuePopup.RowCorners"/> в <see cref="ApplyRowCorners"/>. Зависимая строка
/// «Число соединений Mux» при этом СЧИТАЕТСЯ только когда видна.</para>
///
/// <para><b>Доступность.</b> Каждая строка-действие фокусируема (Tab) и активируется Enter/Space, с
/// авто-кольцом FocusAdorner; тумблеры сняты с таб-стопа — стопом владеет строка (иначе на строку
/// приходилось бы два стопа). OFF-модель: ни одна строка не запускает ядро.</para>
/// </summary>
public partial class SettingsView : UserControl
{
    //  «Зависимая строка» (motion.md): раскрытие 320 мс. Уход — 75% темпа, как везде в репозитории.
    private static readonly TimeSpan DependentRevealIn = TimeSpan.FromMilliseconds(320);
    private static readonly TimeSpan DependentRevealOut = TimeSpan.FromMilliseconds(240);

    private CancellationTokenSource? _muxRevealAnim;

    public SettingsView()
    {
        InitializeComponent();

        // Runtime: bind the whole screen to the real config-backed ViewModel. Design-time uses the
        // axaml Design.DataContext (sample strings) so the previewer still renders.
        if (!Design.IsDesignMode)
        {
            DataContext = new SettingsViewModel();
        }

        // --- Строки-ОКОШКИ: ширина из каталога компонента, тап по строке переключает окошко. ---
        WirePicker(RowMode, PickMode, ValuePopup.Widths.Mode);
        WirePicker(RowDns, PickDns, ValuePopup.Widths.Dns);
        WirePicker(RowPingMethod, PickPing, ValuePopup.Widths.Ping);
        WirePicker(RowAppearance, PickLook, ValuePopup.Widths.Look);
        WirePicker(RowLanguage, PickLanguage, ValuePopup.Widths.Language);
        WirePicker(RowUiScale, PickUiScale, ValuePopup.Widths.UiScale);
        WirePicker(RowSubAutoUpdate, PickAutoUpdate, ValuePopup.Widths.AutoUpdate);
        WirePicker(RowMuxCount, PickMuxCount, ValuePopup.Widths.MuxCount);

        // --- Строки-НАВИГАЦИИ (шеврон): тап кладёт Incy суб-страницу на общий стек оболочки ---
        WireRow(RowPerApp, () => OpenPage(new PerAppProxyPage(), refresh: true));
        WireRow(RowLocalProxy, () => OpenPage(new LocalProxyPage()));
        WireRow(RowRouting, () => OpenPage(new RoutingSubView()));
        WireRow(RowAssets, () => OpenPage(new GeoFilesPage()));
        WireRow(RowLog, () => OpenPage(new LogPage()));
        WireRow(RowCheckUpdate, () => OpenPage(new CheckUpdateView()));
        WireRow(RowBackup, () => OpenPage(new BackupPage()));
        WireRow(RowUrlScheme, () => OpenPage(new UrlSchemesPage()));
        WireRow(RowAbout, () => OpenPage(new AboutPage()));

        // --- Тумблер-строки: тап по всей строке (58) + Enter/Space переключают тумблер ---
        WireToggleRow(RowBypassLan, SwitchBypassLan);
        WireToggleRow(RowIpv6, SwitchIpv6);
        WireToggleRow(RowMux, SwitchMux);
        WireToggleRow(RowFragment, SwitchFragment);
        WireToggleRow(RowLiteMode, SwitchLiteMode);
        WireToggleRow(RowBoot, SwitchBoot);

        // Зависимая строка «Число соединений Mux» существует только при включённом Mux.
        SwitchMux.IsCheckedChanged += (_, _) => SetMuxCountVisible(SwitchMux.IsChecked == true, animate: true);
        SetMuxCountVisible(SwitchMux.IsChecked == true, animate: false);
    }

    private SettingsViewModel? Vm => DataContext as SettingsViewModel;

    // ===================== Строки: активация тапом + клавиатурой =====================

    /// <summary>Делает строку-действие фокусируемой (таб-стоп + Enter/Space = тот же жест, что тап).
    /// Единственный указательный путь — <c>Tapped</c> (жест «нажал+отпустил на строке»): ровно один
    /// вызов на тап. Это безопасно ровно потому, что прогиб единой лестницы применяется к содержимому
    /// строки, а не к ней самой — границы строки под нажатием не двигаются, дребезга
    /// PointerExited/Entered, который отменял жест до 1e884ad9, физически не возникает.</summary>
    private static void WireRow(Border row, Action activate)
    {
        row.Focusable = true;
        row.IsTabStop = true;
        row.Tapped += (_, _) => activate();
        row.KeyDown += (_, e) =>
        {
            if (e.Key is Key.Enter or Key.Space)
            {
                activate();
                e.Handled = true;
            }
        };
    }

    /// <summary>Строка с «окошком у значения»: ширина окошка задаётся здесь (она per-caller), якорем
    /// служит САМА СТРОКА — смещения tokens.md (top 48 / right 10) отмеряются от её правого верхнего
    /// угла, а не от правой половины. Тап по любому месту строки переключает окошко.</summary>
    private static void WirePicker(Border row, ValuePicker picker, double width)
    {
        picker.PopupWidth = width;
        //  Тап ВНУТРИ раскрытого окошка — это выбор пункта, а не повторное нажатие на строку.
        //  Окошко лежит В ДЕРЕВЕ строки (иначе оно выпадает из масштабирования интерфейса), поэтому
        //  его собственный Tapped всплывает СЮДА вторым событием: без этой проверки выбор пункта
        //  закрывал окошко и тут же открывал обратно — владелец видел, что «меню открывается заново».
        row.Focusable = true;
        row.IsTabStop = true;
        row.Tapped += (_, e) =>
        {
            if (!SubPageUtil.OriginatedIn<ValuePopup>(e.Source))
            {
                picker.Toggle();
            }
        };
        row.KeyDown += (_, e) =>
        {
            if (e.Key is Key.Enter or Key.Space)
            {
                picker.Toggle();
                e.Handled = true;
            }
        };
    }


    /// <summary>Тумблер-строка: тап по всей строке (guard OriginatedInToggle гасит двойной ход, когда
    /// источник — сам тумблер) + Enter/Space переключают привязанный тумблер. Строка — единственный
    /// таб-стоп (сам тумблер снят с фокуса в стиле RowSwitch).</summary>
    private static void WireToggleRow(Border row, ToggleSwitch sw)
    {
        row.Focusable = true;
        row.IsTabStop = true;
        row.Tapped += (_, e) => ToggleFromRow(sw, e);
        row.KeyDown += (_, e) =>
        {
            if (e.Key is Key.Enter or Key.Space)
            {
                sw.IsChecked = !(sw.IsChecked ?? false);
                e.Handled = true;
            }
        };
    }

    // ===================== Скругление крайних строк (контракт «окошка») =====================

    /// <summary>
    /// Пока карточка обрезала содержимое, углы маскировала она. Теперь обрезки нет (иначе окошко
    /// последней строки срезается её нижней кромкой), поэтому скругляться обязаны сами крайние строки
    /// — расчёт общий для всех потребителей компонента, <see cref="ValuePopup.RowCorners"/>.
    /// Считаются ТОЛЬКО видимые строки: скрытая зависимая строка не должна забирать себе нижние углы.
    /// </summary>
    private void ApplyRowCorners(params Border[][] cards)
    {
        foreach (var rows in cards)
        {
            var visible = rows.Where(r => r.IsVisible).ToArray();
            for (var i = 0; i < visible.Length; i++)
            {
                visible[i].CornerRadius = ValuePopup.RowCorners(i, visible.Length, CardRadius);
            }
            //  Скрытая строка не должна вернуться в раскладку с чужими углами.
            foreach (var hidden in rows.Where(r => !r.IsVisible))
            {
                hidden.CornerRadius = new CornerRadius(0);
            }
        }
    }

    //  Скругление карточки раздела (Radius.SetCard в разметке). Внутренний радиус строки считает
    //  RowCorners как «радиус карточки минус её контур».
    private const double CardRadius = 16;

    private void RefreshRowCorners() => ApplyRowCorners(
        [RowMode, RowPerApp, RowBypassLan, RowIpv6, RowDns, RowPingMethod, RowLocalProxy],
        [RowMux, RowMuxCount, RowFragment],
        [RowLiteMode],
        [RowAppearance, RowLanguage, RowUiScale, RowBoot],
        [RowSubAutoUpdate, RowRouting, RowAssets],
        [RowLog, RowCheckUpdate, RowBackup, RowUrlScheme, RowAbout]);

    // ===================== Зависимая строка «Число соединений Mux» =====================

    /// <summary>
    /// Раскрытие/сворачивание зависимой строки по ВЫСОТЕ за 320 мс (motion.md «Зависимая строка»).
    /// Высота, а не прозрачность: строка обязана освобождать место, иначе под выключенным Mux в
    /// карточке зиял бы пустой ряд. Разделитель уезжает вместе со строкой — они лежат в одной группе.
    ///
    /// <para>Обрезка включается ТОЛЬКО на время анимации: постоянная обрезала бы окошко этой самой
    /// строки (её значение тоже открывается окошком) — ровно та ошибка, от которой карточка выше
    /// перестала обрезать содержимое.</para>
    /// </summary>
    private async void SetMuxCountVisible(bool show, bool animate)
    {
        _muxRevealAnim?.Cancel();

        if (!animate || MotionState.IsLite)
        {
            MuxCountGroup.Height = double.NaN;
            MuxCountGroup.ClipToBounds = false;
            MuxCountGroup.IsVisible = show;
            RefreshRowCorners();
            return;
        }

        var cts = new CancellationTokenSource();
        _muxRevealAnim = cts;

        var target = MeasuredHeight(MuxCountGroup);
        MuxCountGroup.ClipToBounds = true;

        if (show)
        {
            MuxCountGroup.IsVisible = true;
            MuxCountGroup.Height = 0;
            RefreshRowCorners();
            await AnimateHeight(MuxCountGroup, 0, target, DependentRevealIn, cts.Token);
            if (cts.IsCancellationRequested)
            {
                return;
            }
            //  Отпускаем высоту обратно в авто и снимаем обрезку — иначе окошко строки было бы срезано.
            MuxCountGroup.Height = double.NaN;
            MuxCountGroup.ClipToBounds = false;
        }
        else
        {
            await AnimateHeight(MuxCountGroup, target, 0, DependentRevealOut, cts.Token);
            if (cts.IsCancellationRequested)
            {
                return;
            }
            MuxCountGroup.IsVisible = false;
            MuxCountGroup.Height = double.NaN;
            MuxCountGroup.ClipToBounds = false;
            RefreshRowCorners();
        }
    }

    /// <summary>Натуральная высота группы. Мерить обязательно в видимом состоянии: невидимый
    /// <see cref="Layoutable"/> отдаёт DesiredSize = 0.</summary>
    private static double MeasuredHeight(Control control)
    {
        var wasVisible = control.IsVisible;
        var wasHeight = control.Height;
        control.IsVisible = true;
        control.Height = double.NaN;
        control.Measure(new Size(control.Bounds.Width > 0 ? control.Bounds.Width : double.PositiveInfinity, double.PositiveInfinity));
        var h = control.DesiredSize.Height;
        control.IsVisible = wasVisible;
        control.Height = wasHeight;
        return h > 0 ? h : 59;   // 58 строка + 1 разделитель — на случай, если мерить ещё нечего
    }

    private static async Task AnimateHeight(Control control, double from, double to, TimeSpan duration, CancellationToken token)
    {
        var anim = new Animation
        {
            Duration = duration,
            Easing = Motion.Ease.OutQuart,
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Avalonia.Styling.Setter(Layoutable.HeightProperty, from) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Avalonia.Styling.Setter(Layoutable.HeightProperty, to) } },
            },
        };
        try
        {
            await anim.RunAsync(control, token);
        }
        catch (OperationCanceledException)
        {
            // перебито новым переключением — конечное состояние поставит уже оно
        }
    }

    // ===================== Навигация / вспомогательное =====================

    /// <summary>Открывает Incy суб-страницу IN-APP, кладя её на общий стек «назад» оболочки
    /// (MainWindow.OpenSubPage) — НИКАКИХ отдельных окон. При <paramref name="refresh"/> по возврате
    /// перечитывает значения строк, которые страница могла изменить.</summary>
    private void OpenPage(Control page, bool refresh = false)
    {
        // Открытое окошко на уходящем экране закрываем: оно живёт В ДЕРЕВЕ этой страницы и иначе
        // осталось бы висеть над суб-страницей.
        CloseAllPopups();

        // Подписку на обновление вешаем ДО OpenSubPage, чтобы значения строк освежились перед снятием
        // страницы со стека (обе подписки на BackRequested отработают по возврату).
        if (refresh && page is ISubPage sub)
        {
            sub.BackRequested += (_, _) => Vm?.RefreshDisplayValues();
        }
        if (TopLevel.GetTopLevel(this) is MainWindow main)
        {
            main.OpenSubPage(page);
        }
    }

    private void CloseAllPopups()
    {
        foreach (var picker in this.GetVisualDescendants().OfType<ValuePicker>())
        {
            picker.Close();
        }
    }

    /// <summary>Тап по строке-тумблеру переключает тумблер — но не когда сам тумблер и был источником
    /// тапа (он уже переключился сам).</summary>
    private static void ToggleFromRow(ToggleSwitch sw, TappedEventArgs e)
    {
        if (OriginatedInToggle(e.Source))
        {
            return;
        }
        sw.IsChecked = !(sw.IsChecked ?? false);
    }

    private static bool OriginatedInToggle(object? source)
    {
        var visual = source as Visual;
        while (visual is not null)
        {
            if (visual is ToggleSwitch)
            {
                return true;
            }
            visual = visual.GetVisualParent();
        }
        return false;
    }
}
