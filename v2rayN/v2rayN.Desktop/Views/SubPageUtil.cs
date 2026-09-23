using Avalonia.VisualTree;

namespace v2rayN.Desktop.Views;

/// <summary>
/// Мелкие общие операции подэкранов настроек (лекало из <c>Assets/SubScreenStyles.axaml</c>).
/// Одиннадцать экранов делают ровно три одинаковые вещи, и все три легко сделать по-разному:
///   • переключить класс состояния на элементе (<see cref="SetClass"/>) — выбранное радио, галочка,
///     открытая каретка. Через <c>Classes.Add/Remove</c> напрямую это четыре строки на каждый вызов;
///   • понять, пришёл ли тап с интерактивного элемента ВНУТРИ строки (<see cref="OriginatedIn{T}"/>):
///     строка-тумблер переключает тумблер по тапу в любое место, КРОМЕ самого тумблера — иначе
///     он переключится дважды и вернётся на место («тап через раз»);
///   • класть текст в буфер обмена (<see cref="CopyAsync"/>) без ссылки на TopLevel в каждом файле.
/// </summary>
internal static class SubPageUtil
{
    /// <summary>Ставит/снимает класс состояния. Идемпотентно: повторный вызов ничего не меняет.</summary>
    public static void SetClass(StyledElement element, string className, bool on)
    {
        if (on)
        {
            if (!element.Classes.Contains(className))
            {
                element.Classes.Add(className);
            }
        }
        else
        {
            element.Classes.Remove(className);
        }
    }

    /// <summary>
    /// True, если событие пришло из <typeparamref name="T"/> или его потомка. Нужен именно обход
    /// ВИЗУАЛЬНОГО дерева: <c>e.Source</c> — это внутренняя часть шаблона контрола (Border/Ellipse
    /// тумблера), а не сам контрол, поэтому простая проверка типа источника не сработала бы.
    /// </summary>
    public static bool OriginatedIn<T>(object? source) where T : class
    {
        var visual = source as Visual;
        while (visual is not null)
        {
            if (visual is T)
            {
                return true;
            }
            visual = visual.GetVisualParent();
        }
        return false;
    }

    /// <summary>Кладёт текст в буфер обмена окна. Пустую строку не кладём — молча ничего не делаем.</summary>
    public static async Task CopyAsync(Visual owner, string? text)
    {
        if (text.IsNullOrEmpty())
        {
            return;
        }
        await Common.AvaUtils.SetClipboardData(owner, text!);
    }
}
