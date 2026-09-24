package com.v2ray.ang.handler

/**
 * Скорость по счётчикам ядра: байты, накопленные с прошлого опроса, на время с прошлого опроса.
 *
 * ОПРОС ПОСЛЕ ПАУЗЫ - НЕ СКОРОСТЬ. Счётчики ядра обнуляются только опросом, а опрос стоит, пока
 * выключен экран (`CoreServiceManager`: ACTION_SCREEN_OFF -> `stopSpeedNotification`). Первый опрос
 * после включения получает всё, что прошло через туннель за это время, и делит на всё это время -
 * выходит средняя за паузу, а показывается она как скорость «сейчас»: телефон в кармане полчаса
 * докачивал файл, и Главная, едва её открыли, пишет мегабайты в секунду над простаивающим
 * туннелем. На ПК то же самое случалось после трея и исправлено там же
 * (`MainWindowViewModel.StatsCatchUpGapMs`); здесь то же правило: разрыв дольше двух интервалов -
 * опрос догоняющий, счётчики им только сбрасываются, а показывается ноль. Со следующего опроса
 * скорость снова настоящая.
 */
internal object TrafficRate {

    /** Разрыв дольше двух интервалов значит, что опрос стоял. */
    fun isCatchUp(elapsedMillis: Long, intervalMillis: Long): Boolean = elapsedMillis > intervalMillis * 2

    /** Байт в секунду за окно [elapsedMillis], или 0 для догоняющего опроса. */
    fun perSecond(bytes: Long, elapsedMillis: Long, intervalMillis: Long): Long =
        if (elapsedMillis <= 0L || isCatchUp(elapsedMillis, intervalMillis)) 0L else bytes * 1000L / elapsedMillis
}
