namespace v2rayN.Desktop.Common;

/// <summary>
/// Единственное место, где деньги превращаются в текст.
/// <para/>
/// Формат из пакета (tokens.md, screens.md): «2 600 ₽» — НЕРАЗРЫВНЫЙ пробел в разрядах и запятая в
/// дробной части. Раньше три экрана печатали деньги по-своему («2600», «187.50») тремя копиями
/// одного и того же кода: баланс на «Аккаунте», цены в «Купить подписку» и суммы в «Истории
/// платежей». Разошлись бы они не сразу, а на первой же правке одного из трёх — поэтому склейка
/// живёт здесь, а не в каждом ViewModel.
/// <para/>
/// Пробел именно НЕРАЗРЫВНЫЙ (U+00A0): обычный позволил бы переносу разорвать «14 555» пополам
/// в узкой колонке.
/// </summary>
public static class Money
{
    //  Своя культура вместо CurrentUICulture: приложение печатает рубли одинаково независимо от
    //  языка системы, иначе на английской локали появились бы «2,600.00».
    private static readonly NumberFormatInfo _ru = new()
    {
        NumberGroupSeparator = " ",
        NumberDecimalSeparator = ",",
        NumberGroupSizes = [3],
    };

    /// <summary>Голая сумма без валюты: целое печатается без копеек, дробное — с двумя знаками.</summary>
    public static string Amount(double amount) => amount % 1.0 == 0.0
        ? ((long)amount).ToString("#,0", _ru)
        : amount.ToString("#,0.00", _ru);

    /// <summary>Сумма со знаком валюты: «2 600 ₽».</summary>
    public static string WithCurrency(double amount, string currency)
    {
        var n = Amount(amount);
        return currency.IsNullOrEmpty() ? n : $"{n} {Symbol(currency)}";
    }

    /// <summary>
    /// Знак валюты. Продукт рублёвый, поэтому пустая и незнакомая валюта — рубль: показать «USD»
    /// там, где платят рублями, хуже, чем показать рубль.
    /// </summary>
    public static string Symbol(string currency) => currency.Trim().ToUpperInvariant() switch
    {
        "USD" => "$",
        "EUR" => "€",
        "KZT" => "₸",
        "UAH" => "₴",
        _ => "₽",
    };
}
