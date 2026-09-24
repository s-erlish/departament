package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ЧТО НАЗЫВАЕТ ПОДПИСКУ - один ответ на все экраны.
 *
 * Владелец: «когда подписку с буфера добавляешь, вместо ника подписки пишет мои серверы». На ПК это
 * оказалось чтением ТОЛЬКО пометки, а вставка из буфера штампует служебную заглушку. На Android
 * ранжирование давно живёт здесь - но два экрана мимо него ходили: список «Подписки» печатал сырую
 * пометку, а карточка на вкладке «Аккаунт» брала общий ярлык сервиса. Тест закрепляет порядок,
 * которому оба теперь подчиняются.
 *
 * [SubscriptionNaming.nameOf] проверяется, а не [SubscriptionNaming.titleOf]: последнему нужен
 * Context ради последнего слова «Подписка», а весь порядок - в первом.
 */
class SubscriptionNamingTest {

    private fun sub(remarks: String = "", profileTitle: String = "") =
        SubscriptionItem(remarks = remarks, profileTitle = profileTitle)

    /** Заголовок провайдера - настоящее имя подписки, и он выше пометки. */
    @Test
    fun providerTitleOutranksTheStoredRemark() {
        assertEquals("erlish", SubscriptionNaming.nameOf(sub(remarks = "старое имя", profileTitle = "erlish")))
    }

    /**
     * Установка, поднятая со старой сборки, хранит в пометке заглушку. Показывать её нельзя, а
     * настоящее имя лежит рядом - в той же записи.
     */
    @Test
    fun aStoredPlaceholderNeverWins() {
        for (placeholder in SubscriptionNaming.PLACEHOLDERS) {
            assertEquals(
                "заглушка «$placeholder» не должна побеждать",
                "erlish",
                SubscriptionNaming.nameOf(sub(remarks = placeholder, profileTitle = "erlish")),
            )
            assertNull(
                "заглушка «$placeholder» сама по себе не имя",
                SubscriptionNaming.nameOf(sub(remarks = placeholder)),
            )
        }
    }

    /** Регистр и пробелы вокруг заглушки её заглушкой быть не перестают. */
    @Test
    fun placeholdersAreMatchedTrimmedAndCaseInsensitively() {
        assertNull(SubscriptionNaming.nameOf(sub(remarks = "  Import Sub  ")))
        assertNull(SubscriptionNaming.nameOf(sub(remarks = "DEPARTAMENT VPN")))
    }

    /**
     * Подписка, вставленная из буфера, хранится с ПУСТОЙ пометкой нарочно: имя придёт заголовком на
     * первой же загрузке. До неё имени нет - и это null, а не пустая строка, чтобы вызывающий
     * поставил «Подписка», а не нарисовал строку без заголовка.
     */
    @Test
    fun aFreshlyPastedSubscriptionHasNoNameYet() {
        assertNull(SubscriptionNaming.nameOf(sub()))
    }

    /** Ник из кабинета выбрал человек - он выше всего, что придумали машины. */
    @Test
    fun theCabinetNicknameOutranksEverything() {
        assertEquals(
            "мой впн",
            SubscriptionNaming.nameOf(
                sub(remarks = "пометка", profileTitle = "erlish"),
                accountDisplayName = "мой впн",
                accountDefaultLabel = "Подписка #2",
            ),
        )
    }

    /**
     * «Подписка #2» - настоящий ярлык этой подписки, но сгенерированный, поэтому он ниже и заголовка
     * провайдера, и пометки.
     */
    @Test
    fun theGeneratedLabelSitsBelowAnythingChosen() {
        assertEquals(
            "erlish",
            SubscriptionNaming.nameOf(sub(profileTitle = "erlish"), accountDefaultLabel = "Подписка #2"),
        )
        assertEquals(
            "Подписка #2",
            SubscriptionNaming.nameOf(sub(), accountDefaultLabel = "Подписка #2"),
        )
    }
}
