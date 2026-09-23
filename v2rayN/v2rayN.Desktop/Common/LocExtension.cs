using Avalonia.Data;
using Avalonia.Markup.Xaml;

namespace v2rayN.Desktop.Common;

/// <summary>
/// The <c>{loc:T Key}</c> markup extension. Binds a control property to a per-key live translation
/// stream (<see cref="L.Observe"/>) so the value re-renders the instant the language switches.
///
/// HOW IT UPDATES LIVE (and why the previous indexer approach did not):
/// The extension returns a <see cref="Binding"/> that uses Avalonia's stream operator (<c>Value^</c>)
/// over an <see cref="IObservable{T}"/> of <see cref="string"/>. Avalonia subscribes to that observable
/// and pushes every emitted value straight onto the target property. <see cref="L.SetLanguage"/> emits
/// the new translation to every open binding, so all static <c>{loc:T}</c> labels refresh together.
/// The earlier design bound to <c>L.Instance[Key]</c> and relied on a <c>PropertyChanged("Item[]")</c>
/// notification — a WPF convention Avalonia 12 does not honour, which is why English switches used to
/// leave the XAML labels stuck in the startup language.
///
/// Because the binding has an explicit <see cref="Binding.Source"/>, it is independent of each view's
/// <c>x:DataType</c> / compiled bindings — no per-view DataContext plumbing is needed, and the
/// <c>{loc:T Key}</c> usage syntax is unchanged.
///
/// AXAML:
///   <c>xmlns:loc="clr-namespace:v2rayN.Desktop.Common"</c> on the view root, then e.g.
///   <c>Text="{loc:T Home_NotConnected}"</c>, <c>Header="{loc:T Nav_Home}"</c>,
///   <c>ToolTip.Tip="{loc:T Common_TestLatency}"</c>, <c>Watermark="{loc:T Servers_SearchPlaceholder}"</c>.
/// </summary>
public sealed class T : MarkupExtension
{
    public T()
    {
    }

    public T(string key)
    {
        Key = key;
    }

    /// <summary>The localization key (positional ctor arg in <c>{loc:T Key}</c>).</summary>
    public string Key { get; set; } = string.Empty;

    public override object ProvideValue(IServiceProvider serviceProvider)
        => new Binding(nameof(KeyStreamSource.Value) + "^")
        {
            // Source is a tiny holder exposing the per-key live translation stream. The trailing "^"
            // is Avalonia's stream-binding operator: it subscribes to that IObservable<string> and
            // feeds each emitted value to the bound property. One holder per binding; its lifetime is
            // tied to the binding/control, and L only ever weak-references the subscription behind it.
            Source = new KeyStreamSource(Key),
            Mode = BindingMode.OneWay,
        };

    /// <summary>
    /// Bridges a localization key to its live stream so the reflection <see cref="Binding"/> can reach it
    /// via the <c>Value^</c> path. (Avalonia's stream operator needs a property to stream; a bare source
    /// observable cannot be used as the binding path directly.)
    /// </summary>
    private sealed class KeyStreamSource
    {
        public KeyStreamSource(string key) => Value = L.Instance.Observe(key);

        /// <summary>The per-key <c>IObservable&lt;string&gt;</c> streamed by the <c>Value^</c> binding.</summary>
        public IObservable<string> Value { get; }
    }
}
