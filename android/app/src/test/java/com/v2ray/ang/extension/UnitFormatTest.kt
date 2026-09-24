package com.v2ray.ang.extension

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * СКОРОСТЬ И ОБЪЁМ ПО-РУССКИ - [toSpeedString] и [toTrafficString].
 *
 * Главная и шторка писали «1,2 MB/s» латиницей, а разделитель брали из языка телефона, так что на
 * английском телефоне выходило «1.2 MB/s» посреди русского интерфейса; объём подписки на Главной
 * был «12.4 GB», а на вкладке «Аккаунт» те же байты - «12,4 ГБ». Правило теперь одно: русские
 * единицы, запятая, неразрывный пробел перед единицей.
 */
class UnitFormatTest {

    private val nbsp = '\u00A0'

    private fun speed(bytesPerSecond: Long) = bytesPerSecond.toSpeedString().replace(nbsp, ' ')
    private fun traffic(bytes: Long) = bytes.toTrafficString().replace(nbsp, ' ')

    /** Байты в секунду: от КБ/с, один знак до 100, без него дальше, к МБ/с - с тысячи. */
    @Test
    fun speedsAreWrittenInRussianUnits() {
        assertEquals("0 КБ/с", speed(0))
        assertEquals("0 КБ/с", speed(-5))
        assertEquals("0 КБ/с", speed(50))
        assertEquals("0,1 КБ/с", speed(100))
        assertEquals("1,0 КБ/с", speed(1024))
        assertEquals("99,9 КБ/с", speed(102_297))
        assertEquals("100 КБ/с", speed(102_350))
        assertEquals("240 КБ/с", speed(245_760))
        assertEquals("1,0 МБ/с", speed(1_023_488))
        assertEquals("1,2 МБ/с", speed(1_258_291))
    }

    /** Объём - как у кольца на «Аккаунте»: байты целыми, дальше по 1024 с одним знаком. */
    @Test
    fun volumesAreWrittenInRussianUnits() {
        assertEquals("0 Б", traffic(0))
        assertEquals("512 Б", traffic(512))
        assertEquals("1023 Б", traffic(1023))
        assertEquals("1,0 КБ", traffic(1024))
        assertEquals("12,4 ГБ", traffic(13_314_398_618))
    }

    /** Язык телефона не меняет ни разделитель, ни цифры. */
    @Test
    fun theDeviceLocaleDoesNotLeakIn() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1,2 МБ/с", speed(1_258_291))
            assertEquals("12,4 ГБ", traffic(13_314_398_618))
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** Единица не отрывается от числа при переносе строки. */
    @Test
    fun theUnitIsBoundToTheFigure() {
        assertEquals("1,0${nbsp}КБ/с", 1024L.toSpeedString())
        assertEquals("1,0${nbsp}КБ", 1024L.toTrafficString())
    }

    /** Ноль на Главной в покое (@string/home_speed_zero) - тот же ноль, что пишет форматтер. */
    @Test
    fun theRestingZeroIsTheFormattersZero() {
        val file = listOf(File("src/main/res/values/strings_home.xml"), File("app/src/main/res/values/strings_home.xml"))
            .first { it.exists() }
        val value = Regex("""<string name="home_speed_zero">(.*?)</string>""").find(file.readText())!!.groupValues[1]
        assertEquals(0L.toSpeedString(), value.replace("\\u00A0", "\u00A0"))
    }
}
