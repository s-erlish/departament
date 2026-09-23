using System.ComponentModel;

namespace v2rayN.Desktop.Common;

/// <summary>
/// departament localization core (Option a from LOCALIZATION_PLAN.md).
///
/// A single live string table for the custom "departament" UI, selectable between
/// Russian (<c>ru</c>) and English (<c>en</c>) with LIVE switching — no restart.
///
/// Usage:
///   • AXAML:  add <c>xmlns:loc="clr-namespace:v2rayN.Desktop.Common"</c> to the view root,
///             then <c>Text="{loc:T Home_NotConnected}"</c>. The <see cref="T"/> markup
///             extension binds to <c>L.Instance[Key]</c>, so every open binding re-pulls
///             live when the language changes.
///   • Code:   <c>L.T("Key")</c> for a plain string, <c>L.F("Key", args)</c> for a
///             positional-placeholder template, <c>L.Plural("Key", n)</c> for a locale-aware
///             "{n} word" count line.
///   • Switch: <c>L.Instance.SetLanguage("en")</c> — updates the language, syncs
///             <see cref="Thread.CurrentUICulture"/> (so the ResUI/engine layer follows),
///             refreshes all <c>{loc:T}</c> bindings, and raises <see cref="LanguageChanged"/>.
///
/// The table is split into per-area <c>partial class L</c> files (L.Common.cs, L.Home.cs,
/// L.Servers.cs, L.Settings.cs, L.Account.cs, L.Buy.cs, L.Shell.cs) so parallel work
/// packages never edit the same file. This core file owns only the mechanism.
/// </summary>
public sealed partial class L : INotifyPropertyChanged
{
    private static readonly Lazy<L> _instance = new(() => new L());

    /// <summary>The singleton live table. First access constructs it from the saved config language.</summary>
    public static L Instance => _instance.Value;

    // One entry per key: (Russian, English). Populated by the per-area partial Register* hooks.
    private readonly Dictionary<string, (string Ru, string En)> _table = new(StringComparer.Ordinal);

    // Plural forms: RU = {one, few, many}, EN = {one, other}. Used by Plural(...).
    private readonly Dictionary<string, (string[] Ru, string[] En)> _plurals = new(StringComparer.Ordinal);

    // Guards the "log a missing key only once" behaviour.
    private readonly HashSet<string> _missingLogged = new(StringComparer.Ordinal);

    // Live observers created by {loc:T} bindings — one per open binding. Held by WEAK reference so the
    // long-lived singleton never pins a control (see the Observe(...) region for the full rationale).
    private readonly List<(string Key, WeakReference<IObserver<string>> Ref)> _observers = new();
    private readonly object _observersLock = new();

    /// <summary>Current UI language code (<c>ru</c>/<c>en</c>). Initialized from the saved config.</summary>
    public string CurrentLang { get; private set; } = "ru";

    public event PropertyChangedEventHandler? PropertyChanged;

    /// <summary>Raised after <see cref="SetLanguage"/>, for code-behind/VMs that set text imperatively.</summary>
    public event EventHandler? LanguageChanged;

    private L()
    {
        // Read the persisted language before anything binds. Guarded because the XAML previewer
        // (design mode) and very-early init may not have a live config yet — fall back to ru.
        try
        {
            var lang = AppManager.Instance.Config?.UiItem?.CurrentLanguage;
            if (!string.IsNullOrWhiteSpace(lang))
            {
                CurrentLang = lang;
            }
        }
        catch
        {
            // keep default "ru"
        }

        // Each area contributes its own keys from its own partial file (implemented → called,
        // unimplemented → elided). WP0 owns Common; WP1–WP6 own the rest.
        RegisterCommon();
        RegisterHome();
        RegisterServers();
        RegisterSettings();
        RegisterAccount();
        RegisterBuy();
        RegisterShell();
        RegisterStart();
        RegisterUpdate();
    }

    // ── Per-area registration hooks (implemented in the L.<Area>.cs partials) ──
    partial void RegisterCommon();
    partial void RegisterHome();
    partial void RegisterServers();
    partial void RegisterSettings();
    partial void RegisterAccount();
    partial void RegisterBuy();
    partial void RegisterShell();

    // Начальный экран + экран прогрузки (L.Start.cs). Заведён своим хуком, а не дописан в
    // Home/Common, чтобы блок 7 не правил файлы соседних пакетов работ — см. шапку L.Start.cs.
    partial void RegisterStart();

    // Самообновление: экран, уведомление в строке окна, строка настроек (L.Update.cs).
    partial void RegisterUpdate();

    /// <summary>Register one key with its Russian and English value. Called from the partials.</summary>
    private void Add(string key, string ru, string en) => _table[key] = (ru, en);

    /// <summary>Register one plural key. <paramref name="ru"/> = {one, few, many}; <paramref name="en"/> = {one, other}.</summary>
    private void AddPlural(string key, string[] ru, string[] en) => _plurals[key] = (ru, en);

    /// <summary>
    /// Indexer used by <c>{loc:T Key}</c> bindings. Returns the string for the current language.
    /// Fallback for a missing/empty value: en → ru → the key itself (logged once).
    /// </summary>
    public string this[string key]
    {
        get
        {
            if (string.IsNullOrEmpty(key))
            {
                return string.Empty;
            }

            if (_table.TryGetValue(key, out var v))
            {
                var val = CurrentLang == "en" ? v.En : v.Ru;
                if (!string.IsNullOrEmpty(val))
                {
                    return val;
                }
                if (!string.IsNullOrEmpty(v.En))
                {
                    return v.En;
                }
                if (!string.IsNullOrEmpty(v.Ru))
                {
                    return v.Ru;
                }
            }

            LogMissing(key);
            return key;
        }
    }

    /// <summary>Static accessor for code-behind / view-models.</summary>
    public static string T(string key) => Instance[key];

    /// <summary><see cref="string.Format(IFormatProvider, string, object?[])"/> over a keyed template,
    /// formatted with the current UI culture (e.g. <c>L.F("Account_ValidUntil", date)</c>).</summary>
    public static string F(string key, params object[] args)
        => string.Format(CultureInfo.CurrentUICulture, Instance[key], args);

    /// <summary>Locale-aware "{n} word" for a plural key (replaces the hardcoded RU-only PluralRu).</summary>
    public static string Plural(string key, int n) => Instance.PluralImpl(key, n);

    private string PluralImpl(string key, int n)
    {
        if (_plurals.TryGetValue(key, out var forms))
        {
            var word = CurrentLang == "en" ? SelectEn(forms.En, n) : SelectRu(forms.Ru, n);
            return $"{n} {word}";
        }

        LogMissing(key);
        return n.ToString(CultureInfo.CurrentUICulture);
    }

    /// <summary>English plural selector: 1 → one, everything else → other. Forms = {one, other}.</summary>
    private static string SelectEn(string[] forms, int n)
    {
        if (forms.Length == 0)
        {
            return string.Empty;
        }
        return Math.Abs(n) == 1 ? forms[0] : forms[^1];
    }

    /// <summary>Russian plural selector: one (1), few (2–4), many (0, 5–20, teens). Forms = {one, few, many}.</summary>
    private static string SelectRu(string[] forms, int n)
    {
        if (forms.Length == 0)
        {
            return string.Empty;
        }
        var abs = Math.Abs(n);
        var mod100 = abs % 100;
        if (mod100 is >= 11 and <= 14)
        {
            return forms[Math.Min(2, forms.Length - 1)];
        }
        return (abs % 10) switch
        {
            1 => forms[0],
            2 or 3 or 4 => forms[Math.Min(1, forms.Length - 1)],
            _ => forms[Math.Min(2, forms.Length - 1)],
        };
    }

    /// <summary>
    /// Switch the live language. In order: set <see cref="CurrentLang"/>, sync
    /// <see cref="Thread.CurrentUICulture"/> (keeps the ResUI/engine layer consistent),
    /// refresh every open <c>{loc:T}</c> binding, then raise <see cref="LanguageChanged"/>.
    /// </summary>
    public void SetLanguage(string code)
    {
        if (string.IsNullOrWhiteSpace(code))
        {
            return;
        }

        CurrentLang = code;

        try
        {
            Thread.CurrentThread.CurrentUICulture = new CultureInfo(code);
        }
        catch (CultureNotFoundException)
        {
            // Unknown culture name — leave the current UI culture unchanged.
        }

        // Push the new translation to every open {loc:T} binding. This is the mechanism that makes the
        // static XAML labels re-render live — see the Observe(...) region for why an observable is used
        // instead of the (Avalonia-inert) "Item[]" indexer-invalidation convention.
        PushToObservers();

        // Kept for backward compatibility with any INotifyPropertyChanged consumer. Note: Avalonia 12's
        // binding system does NOT act on the WPF-era "Item[]" indexer-refresh convention (its INPC
        // accessor only matches the exact property name, or an empty/null name), so this alone never
        // refreshed the {loc:T} bindings — that is what PushToObservers() above now fixes.
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs("Item[]"));
        LanguageChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Ensure the singleton exists (constructs it from config). Call once at startup,
    /// before the first window builds, so the first frame renders in the persisted language.</summary>
    public static void Init() => _ = Instance;

    // ──────────────────────────── Live per-key observable ────────────────────────────
    //
    // ROOT CAUSE of the "English switch leaves labels in Russian" bug:
    // The old {loc:T Key} bound to L.Instance[Key] (a reflection indexer binding) and SetLanguage raised
    // PropertyChanged("Item[]"). That is the WPF convention for "all indexers changed", but Avalonia 12's
    // INPC accessor (InpcPropertyAccessorPlugin) only re-reads when the raised property name is empty/null
    // or exactly matches the accessor's name ("Item") — it does NOT special-case "Item[]". So the open
    // indexer bindings never re-pulled, and every static {loc:T} label stayed frozen in the startup
    // language. (Strings re-applied imperatively via LanguageChanged updated fine, matching the report.)
    //
    // FIX: {loc:T Key} now binds to a per-key IObservable<string> (via the LocExtension stream binding).
    // The observable emits the current translation on subscribe and again on every SetLanguage, and
    // Avalonia natively pushes each emitted value straight to the target property (Text, Header, Content,
    // ToolTip.Tip, Watermark, …) with zero per-view wiring.
    //
    // LEAK SAFETY: the singleton is long-lived and there are hundreds of bindings, so it must never hold a
    // strong reference to a binding/control. Each subscriber is stored by WeakReference; SetLanguage
    // prunes dead entries, and disposing the subscription (which Avalonia does when a binding is replaced)
    // unregisters it eagerly. Net effect: controls stay collectable and the registry self-cleans.

    /// <summary>
    /// A per-key live string stream: emits <c>this[key]</c> immediately on subscribe and again on every
    /// <see cref="SetLanguage"/>. Consumed by the <see cref="T"/> markup extension so every open
    /// <c>{loc:T Key}</c> binding updates the instant the language changes.
    /// </summary>
    public IObservable<string> Observe(string key) => new KeyObservable(this, key);

    private void RegisterObserver(string key, IObserver<string> observer)
    {
        lock (_observersLock)
        {
            _observers.Add((key, new WeakReference<IObserver<string>>(observer)));
        }
    }

    private void UnregisterObserver(IObserver<string> observer)
    {
        lock (_observersLock)
        {
            _observers.RemoveAll(o => !o.Ref.TryGetTarget(out var t) || ReferenceEquals(t, observer));
        }
    }

    private void PushToObservers()
    {
        List<(string Key, WeakReference<IObserver<string>> Ref)> snapshot;
        lock (_observersLock)
        {
            // Drop entries whose control/binding has been collected, then snapshot so we can notify
            // outside the lock (observer callbacks re-enter the target property setters).
            _observers.RemoveAll(o => !o.Ref.TryGetTarget(out _));
            snapshot = _observers.ToList();
        }

        foreach (var (key, weak) in snapshot)
        {
            if (weak.TryGetTarget(out var observer))
            {
                observer.OnNext(this[key]);
            }
        }
    }

    /// <summary>Per-key observable. Kept tiny and allocation-light — one instance per <c>{loc:T}</c> binding.</summary>
    private sealed class KeyObservable : IObservable<string>
    {
        private readonly L _owner;
        private readonly string _key;

        public KeyObservable(L owner, string key)
        {
            _owner = owner;
            _key = key;
        }

        public IDisposable Subscribe(IObserver<string> observer)
        {
            // Immediate value so the target renders correctly on first layout, then track it (weakly)
            // for future language switches.
            observer.OnNext(_owner[_key]);
            _owner.RegisterObserver(_key, observer);
            return new Unsubscriber(_owner, observer);
        }

        private sealed class Unsubscriber : IDisposable
        {
            private readonly L _owner;
            private IObserver<string>? _observer;

            public Unsubscriber(L owner, IObserver<string> observer)
            {
                _owner = owner;
                _observer = observer;
            }

            public void Dispose()
            {
                if (_observer is not null)
                {
                    _owner.UnregisterObserver(_observer);
                    _observer = null;
                }
            }
        }
    }

    private void LogMissing(string key)
    {
        if (_missingLogged.Add(key))
        {
            Logging.SaveLog($"[L] missing localization key: {key}");
        }
    }
}
