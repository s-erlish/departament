using Avalonia.Animation.Easings;

namespace departament.Desktop.Common;

/// <summary>
/// Единый C#-каталог моушен-токенов (Фаза 0 мастер-плана вход/авторизация/синхронизация). Зеркалит
/// XAML-набор из <c>Assets/GlobalResources.axaml</c>: кривые = те же <see cref="SplineEasing"/>
/// контрольные точки, длительности = те же значения, что документированы литералами в
/// <c>Duration="…"</c> (XAML-компилятор Avalonia НЕ поддерживает интринсик <c>x:TimeSpan</c>, поэтому
/// C# держит длительности как <see cref="TimeSpan"/>).
///
/// Это ЕДИНСТВЕННЫЙ источник правды для C#-аниматоров (смена вкладок · кроссфейд оболочки · суб-страницы ·
/// success-момент входа · entrance chip · settle синхронизации). Раньше <c>MainWindow</c> пере-объявлял
/// <c>_easeOutQuint</c>/<c>_easeStandard</c> и магические константы длительностей — теперь они берутся
/// отсюда, чтобы XAML и C# не расходились. Дисциплина значений: ТОЛЬКО ease-out, выход быстрее входа,
/// без bounce/elastic (правила движения в дизайне программы). Reduced-motion (<see cref="MotionState"/>)
/// снапит анимации в конечное состояние — см. <see cref="Dur.Instant"/>.
/// </summary>
public static class Motion
{
    /// <summary>Длительности единой шкалы темпа (мс). Названия 1:1 с таблицей литералов в GlobalResources.</summary>
    public static class Dur
    {
        /// <summary>0 мс — lite / reduced-motion фолбэк: анимация мгновенно снапится в конечное состояние.</summary>
        public static readonly TimeSpan Instant = TimeSpan.Zero;

        /// <summary>90 мс — палец-вниз (press-in), кривая <see cref="Ease.OutQuart"/>.</summary>
        public static readonly TimeSpan PressIn = TimeSpan.FromMilliseconds(90);

        /// <summary>160 мс — отпускание / малое оседание (press-out · quick), кривая <see cref="Ease.OutQuint"/>.</summary>
        public static readonly TimeSpan PressOut = TimeSpan.FromMilliseconds(160);

        /// <summary>220 мс — смена состояния / tint-crossfade (двусторонняя), кривая <see cref="Ease.Standard"/>.</summary>
        public static readonly TimeSpan State = TimeSpan.FromMilliseconds(220);

        /// <summary>
        /// 280 мс — переезд полоски активного раздела навигации, кривая <see cref="Ease.OutQuart"/>
        /// (motion.md «Навигация»: полоска ОДНА на всю панель и ПЕРЕЕЗЖАЕТ — 280мс ease-out-quart).
        /// Отдельная ступень, а не <see cref="State"/> 220: полоска проходит целую треть панели
        /// (или слот рейла), и на 220 этот путь читается дёрганым — тогда как 220 отвечает за смену
        /// цвета/состояния НА МЕСТЕ. Обе навигации (рейл + нижний бар) обязаны брать это значение
        /// отсюда, иначе они снова разъедутся.
        /// </summary>
        public static readonly TimeSpan Nav = TimeSpan.FromMilliseconds(280);

        /// <summary>300 мс — раскрытие: вход экрана, entrance chip, settle синхронизации, кривая <see cref="Ease.OutQuint"/>.</summary>
        public static readonly TimeSpan Reveal = TimeSpan.FromMilliseconds(300);

        /// <summary>Раскрытие «окошка у значения»: СРЕЗ сверху вниз, 260 мс. Масштаб здесь
        /// не используется — от него дёргается текст внутри (motion.md).</summary>
        public static readonly TimeSpan Pop = TimeSpan.FromMilliseconds(260);

        /// <summary>Прозрачность того же окошка, 180 мс — короче среза, чтобы оно проявилось
        /// раньше, чем доедет нижняя кромка.</summary>
        public static readonly TimeSpan PopFade = TimeSpan.FromMilliseconds(180);

        /// <summary>150 мс — выход экрана / суб-страницы (короче входа), кривая <see cref="Ease.Standard"/>.</summary>
        public static readonly TimeSpan Exit = TimeSpan.FromMilliseconds(150);

        /// <summary>200 мс — кроссфейд 3-путёвого оверлея оболочки, кривая <see cref="Ease.Standard"/>.</summary>
        public static readonly TimeSpan Shell = TimeSpan.FromMilliseconds(200);

        /// <summary>450 мс — ОДИН решительный hand-off auth→home, кривая <see cref="Ease.OutExpo"/>.</summary>
        public static readonly TimeSpan Slow = TimeSpan.FromMilliseconds(450);

        /// <summary>600 мс — ОДИН hero-момент: connect-сонар, кривая <see cref="Ease.OutQuint"/>.</summary>
        public static readonly TimeSpan Emphasis = TimeSpan.FromMilliseconds(600);

        /// <summary>40 мс — стаггер списка / задержка entrance chip.</summary>
        public static readonly TimeSpan Stagger = TimeSpan.FromMilliseconds(40);
    }

    /// <summary>
    /// Кривые = 1:1 с XAML <c>Ease.*</c> из GlobalResources. НЕ встроенные QuarticEaseOut/QuinticEaseOut
    /// (у них другие контрольные точки). Типизированы как базовый <see cref="Easing"/>, чтобы напрямую
    /// подставляться в <c>Animation.Easing</c> и вызывать <c>.Ease(t)</c>.
    /// </summary>
    public static class Ease
    {
        /// <summary>ease_out_quart (0.25,1,0.5,1) — press-feedback, малые оседания.</summary>
        public static readonly Easing OutQuart = new SplineEasing(0.25, 1, 0.5, 1);

        /// <summary>ease_out_quint (0.22,1,0.36,1) — уверенные reveal / settle, glow, сонар.</summary>
        public static readonly Easing OutQuint = new SplineEasing(0.22, 1, 0.36, 1);

        /// <summary>ease_standard (0.2,0,0,1) — tint / crossfade, двусторонние смены состояния, реверс.</summary>
        public static readonly Easing Standard = new SplineEasing(0.2, 0, 0, 1);

        /// <summary>ease_out_expo (0.16,1,0.3,1) — самый решительный ease-out, ЗАРЕЗЕРВИРОВАН под hand-off auth→home.</summary>
        public static readonly Easing OutExpo = new SplineEasing(0.16, 1, 0.3, 1);
    }
}
