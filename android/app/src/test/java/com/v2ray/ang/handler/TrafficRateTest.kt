package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * СКОРОСТЬ ПОСЛЕ ВЫКЛЮЧЕННОГО ЭКРАНА - [TrafficRate].
 *
 * На ПК после трея щит на секунду показывал сотни мегабайт в секунду: первый замер после паузы
 * накрывал всё время в трее. На Android опрос счётчиков стоит, пока выключен экран, и первый опрос
 * после включения получал всё, что прошло через туннель за это время.
 */
class TrafficRateTest {

    private val interval = 3_000L

    /** Обычный тик: байты за окно, делённые на окно. */
    @Test
    fun anOrdinaryTickIsBytesOverTheWindow() {
        assertEquals(1_000_000L, TrafficRate.perSecond(3_000_000L, 3_000L, interval))
        assertEquals(750_000L, TrafficRate.perSecond(3_000_000L, 4_000L, interval))
    }

    /** Полчаса с выключенным экраном - это не скорость, а сброс счётчиков: на экран уходит ноль. */
    @Test
    fun theFirstTickAfterTheScreenWasOffIsZero() {
        assertEquals(0L, TrafficRate.perSecond(600L * 1024 * 1024, 30 * 60_000L, interval))
    }

    /** Граница - два интервала: чуть запоздавший тик ещё скорость, дальше - догоняющий опрос. */
    @Test
    fun theCatchUpBoundaryIsTwoIntervals() {
        assertEquals(1_000L, TrafficRate.perSecond(6_000L, 6_000L, interval))
        assertEquals(0L, TrafficRate.perSecond(6_001L, 6_001L, interval))
        assertEquals(0L, TrafficRate.perSecond(1_000L, 0L, interval))
    }
}
