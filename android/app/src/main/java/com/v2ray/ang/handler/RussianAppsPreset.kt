package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * «РОССИЙСКИЕ ПРИЛОЖЕНИЯ» — a NAMED, REVERSIBLE preset for «Прокси по приложениям» that sends
 * Russian apps AROUND the tunnel.
 *
 * Why it exists: Госуслуги, «Мой налог», the banks, Mir Pay, СБП, ЮMoney, the operators' self-care
 * apps, the marketplaces and the Яндекс and VK families all refuse or degrade when they see a
 * foreign exit address — an approval that never arrives, a card that will not add, a self-care app
 * that says the account does not exist. Routing them direct is what makes the VPN usable in Russia
 * rather than something to switch off five times a day.
 *
 * So the set is deliberately WIDE. The owner's instruction was «все вот такие приложения»: not the
 * headline app of each company but its whole family — Яндекс down to Толока and Заправки, Ozon and
 * WB down to Seller and Job, Госуслуги down to Госключ and Карта болельщика — because the one the
 * user happens to have installed is the one that has to work. Breadth is free here: an absent
 * package matches nothing and costs nothing at connect time.
 *
 * THREE PROPERTIES ARE LOAD-BEARING, AND ALL THREE ARE THE OWNER'S:
 *
 * 1. **It is a preset, not a hidden list.** It has a name, it can be applied and un-applied from a
 *    switch, and every package it contains is visible on the screen it acts on — «Показать
 *    приложения набора» filters the list to exactly these, drawn with their real icons and names,
 *    and each one is still individually toggleable afterwards. A hard-coded list that silently
 *    edited the user's routing would be indistinguishable from a bug.
 * 2. **Un-applying gives back exactly what applying took.** [applyTo] records the packages it
 *    actually ADDED — not the whole preset — in [AppConfig.PREF_RU_BYPASS_PRESET_OWNED], and
 *    [removeFrom] removes only those. A подписка the user had ticked by hand before ever touching
 *    the preset survives un-applying it, because the preset never claimed it.
 * 3. **It never restarts the tunnel by itself.** Every write here goes through the view model's
 *    quiet path, which does NOT raise `SettingsChangeManager.makeRestartService()`. Applying a
 *    routing preset must not drop a live connection under the user — the desktop's equivalent does
 *    exactly that and it is a defect there. The change lands on the next connection, and the screen
 *    says so.
 *
 * ON THE PACKAGE NAMES. Every one below was verified against a store listing, not guessed: RuStore
 * (`rustore.ru/catalog/app/<package>` — the URL slug IS the application id, and RuStore is where
 * most of these live now that they are off Google Play) or Google Play where the app is still
 * listed there. A wrong package is silent — it matches nothing and simply does not route — so
 * guessing one is worse than omitting it. A package that is not installed costs nothing:
 * `CoreVpnService.configurePerAppProxy` skips it.
 */
object RussianAppsPreset {

    /**
     * The preset's contents, grouped by what they are for. Grouping is for the reader — the set is
     * flat at every call site.
     */
    val PACKAGES: List<String> = listOf(
        // Государство
        "ru.rostel",                                // Госуслуги
        "ru.gosuslugi.auto",                        // Госуслуги Авто
        "ru.sigma.gisgkh",                          // Госуслуги.Дом
        "ru.gosuslugi.culture",                     // Госуслуги Культура — Пушкинская карта
        "ru.gosuslugi.school",                      // Госуслуги Моя школа
        "ru.gosuslugi.pos",                         // Госуслуги Решаем вместе
        "ru.rtlabs.mobile.ebs.gosuslugi.android",   // Госуслуги Биометрия
        "ru.fanid",                                 // Госуслуги Карта болельщика
        "ru.gosuslugi.goskey",                      // Госключ — подпись документов
        "com.gnivts.selfemployed",                  // Мой налог — ФНС, самозанятые
        "ru.fns.lkfl",                              // Налоги ФЛ — ФНС
        "ru.sitesoft.fssp",                         // ФССП — судебные приставы
        "ru.gibdd_pay.app",                         // Штрафы ГИБДД
        "ru.mvd",                                   // МВД России
        "com.programmisty.emiasapp",                // ЕМИАС.ИНФО — запись к врачу

        // Москва: городские сервисы и транспорт
        "ru.mos.app",                               // Моя Москва — приложение mos.ru
        "ru.altarix.mos.pgu",                       // Госуслуги Москвы
        "ru.mosgorpass",                            // Московский транспорт
        "ru.mosmetro.metro",                        // Метро Москвы

        // Платёжные системы: НСПК, Mir Pay, СБП
        "ru.nspk.mirpay",                           // Mir Pay
        "ru.nspk.sbpay",                            // СБПэй
        "ru.nspk.mir.loyalty",                      // Привет! — акции СБП и «Мир»
        "ru.cardsmobile.mw3",                       // Кошелёк — карты, скидки, бонусы

        // Банки
        "ru.sberbankmobile",                        // СберБанк Онлайн
        "ru.sberbank_sbbol",                        // СберБизнес
        "ru.sberbank.spasibo",                      // СберСпасибо
        "ru.sberbank.sberkids",                     // СберKids
        "ru.sberbank.investor",                     // СберИнвестиции
        "com.idamob.tinkoff.android",               // Т-Банк
        "ru.tinkoff.sme",                           // Т-Бизнес
        "ru.tinkoff.investing",                     // Т-Инвестиции
        "ru.alfabank.mobile.android",               // Альфа-Банк
        "ru.alfabank.oavdo.amc",                    // Альфа-Бизнес
        "ru.alfadirect.app",                        // Альфа-Инвестиции
        "ru.vtb24.mobilebanking.android",           // ВТБ Онлайн
        "ru.vtb.smb",                               // Бизнес Платформа ВТБ
        "ru.gazprombank.android.mobilebank.app",    // Газпромбанк
        "ru.gazprompay.android",                    // Gazprom Pay
        "logo.com.mbanking",                        // ПСБ
        "ru.psbank.invest",                         // ПСБ Инвестиции
        "ru.mkb.mobile",                            // МКБ Онлайн
        "ru.rosbank.android.beta",                  // РОСБАНК Онлайн
        "ru.raiffeisennews",                        // Райффайзен Онлайн Банк Россия
        "com.openbank",                             // БМ-Банк (Открытие)
        "ru.sovcomcard.halva.v1",                   // Халва — Совкомбанк
        "ru.diftechsvc",                            // Совкомбанк Бизнес
        "ru.sovcombank.investor",                   // Совкомбанк Инвестиции
        "ru.letobank.Prometheus",                   // Почта Банк
        "ru.bankuralsib.mb.android",                // Уралсиб Онлайн
        "ru.uralsib.business",                      // Уралсиб Бизнес
        "ru.homecredit.mycredit",                   // Хоум Банк
        "ru.otpbank.mobile",                        // ОТП Банк Онлайн
        "cz.bsc.rc",                                // Ренессанс Банк
        "ru.lewis.dbo",                             // МТС Деньги — МТС Банк
        "ru.mts.pay",                               // МТС PAY
        "com.yandex.bank",                          // Яндекс Пэй — QR, NFC и Сплит
        "ru.yoo.money",                             // ЮMoney — кошелёк и карты
        "ru.ozon.fintech.finance",                  // Ozon Банк
        "ru.ozon.fintech.sme",                      // Ozon Банк для бизнеса
        "wildberries.business",                     // WB Банк Бизнес

        // Маркетплейсы и магазины
        "ru.ozon.app.android",                      // OZON
        "ru.ozon.select",                           // Ozon Селект
        "ru.ozon.seller_app",                       // Ozon Seller
        "ru.ozon.hire",                             // Ozon Job
        "com.wildberries.ru",                       // Wildberries
        "wb.partners",                              // WB Partners — продавцам
        "ru.wildberries.team",                      // WB Job
        "ru.megamarket.marketplace",                // Мегамаркет
        "com.kazanexpress.ke_app",                  // Магнит Маркет
        "com.avito.android",                        // Авито
        "com.lamoda.lite",                          // Lamoda
        "ru.sportmaster.app",                       // Спортмастер
        "ru.detmir.dmbonus",                        // Детский мир
        "ru.dns.shop.android",                      // DNS SHOP
        "ru.citilink",                              // Ситилинк
        "ru.filit.mvideo.b2c",                      // М.Видео
        "ru.mvm.eldo",                              // Эльдорадо
        "ru.leroymerlin.mobile",                    // Лемана ПРО (Леруа Мерлен)
        "ru.chitaigorod.mobile",                    // Читай-город

        // Продукты, еда и доставка
        "ru.sbcs.store",                            // Самокат
        "ru.vkusvill",                              // ВкусВилл
        "ru.pyaterochka.app.browser",               // Пятёрочка
        "ru.tander.magnit",                         // Магнит
        "ru.perekrestok.app",                       // Перекрёсток
        "com.icemobile.lenta.prod",                 // ЛЕНТА
        "ru.lenta.lentochka",                       // Лента Онлайн
        "ru.myauchan.droid",                        // Мой АШАН
        "ru.instamart",                             // Купер (бывший СберМаркет)
        "com.deliveryclub",                         // Деливери (Delivery Club)
        "ru.dodopizza.app",                         // Додо Пицца
        "ru.kfc.kfc_delivery",                      // Rostic's (KFC)
        "ru.burgerking",                            // Бургер Кинг
        "com.mobilemedia.tanuki",                   // Тануки
        "com.apegroup.mcdonaldsrussia",             // Вкусно — и точка

        // Логистика и посылки
        "com.octopod.russianpost.client.android",   // Почта России
        "com.logistic.sdek",                        // СДЭК
        "ru.boxberry.mobile",                       // Boxberry

        // Аптеки и здоровье
        "ru.apteka",                                // Аптека.ру
        "ru.zdravcity.app",                         // Здравсити
        "com.docdoc.docdoc",                        // СберЗдоровье

        // VK и Mail.ru
        "com.vkontakte.android",                    // ВКонтакте
        "com.vk.im",                                // VK Мессенджер
        "com.vk.vkvideo",                           // VK Видео
        "com.uma.musicvk",                          // VK Музыка
        "com.vk.clips",                             // VK Клипы
        "live.vkplay.app",                          // VK Видео Live
        "com.vk.mail",                              // VK Почта
        "ru.mail.mailapp",                          // Почта Mail
        "ru.mail.cloud",                            // Облако Mail
        "com.allgoritm.youla",                      // Юла
        "ru.ok.android",                            // Одноклассники
        "ru.zen.android",                           // Дзен
        "ru.oneme.app",                             // МАКС

        // Яндекс
        "ru.yandex.searchplugin",                   // Яндекс Старт
        "ru.yandex.searchplugin.beta",              // Яндекс Старт (бета)
        "com.yandex.searchapp",                     // Яндекс — с Алисой
        // БРАУЗЕРОВ В НАБОРЕ НЕТ, И ЭТО ПРАВИЛО, А НЕ ПРОПУСК.
        //
        // Здесь стояли «Яндекс Браузер», его Лайт и бета. Набор — это список «мимо туннеля»,
        // значит браузер в нём означает, что весь веб идёт напрямую, то есть мимо смысла VPN:
        // «браузеры из списка приложений надо убрать, вообще все, чтобы приложения работали через
        // впн». Остальные приложения набора ходят каждое в свой банк или к своему госсервису,
        // которым нужен российский адрес; браузер ходит куда угодно, и решать за него нельзя.
        //
        // НЕ ИСКАТЬ БРАУЗЕРЫ ГРЕПОМ ПО СЛОВУ «browser»: в наборе есть
        // "ru.pyaterochka.app.browser" — это Пятёрочка, у неё историческое имя пакета, и она к
        // вебу отношения не имеет. Убирать по смыслу приложения, а не по строке.
        "com.yandex.aliceapp",                      // Алиса AI
        "com.yandex.iot",                           // Дом с Алисой — Станция
        "ru.yandex.taxi",                           // Яндекс Go
        "ru.yandex.taximeter",                      // Яндекс Про — Таксометр
        "ru.yandex.yandexmaps",                     // Яндекс Карты и Навигатор
        "ru.yandex.yandexnavi",                     // Яндекс Навигатор
        "ru.yandex.metro",                          // Яндекс Метро
        "ru.yandex.rasp",                           // Яндекс Электрички
        "ru.yandex.mobile.gasstations",             // Яндекс Заправки
        "com.yandex.mobile.drive",                  // Яндекс Драйв
        "ru.beru.android",                          // Яндекс Маркет
        "ru.yandex.market.partner",                 // Яндекс Маркет для продавцов
        "ru.foodfox.client",                        // Яндекс Еда
        "com.yandex.lavka",                         // Яндекс Лавка
        "ru.yandex.music",                          // Яндекс Музыка
        "ru.plus.bookmate",                         // Яндекс Книги
        "ru.yandex.mobile.fmradio",                 // Яндекс Радио
        "ru.kinopoisk",                             // Кинопоиск
        "ru.yandex.mail",                           // Яндекс Почта
        "ru.yandex.disk",                           // Яндекс Диск
        "ru.yandex.telemost",                       // Яндекс Телемост
        "ru.yandex.key",                            // Яндекс Ключ — Яндекс ID
        "ru.yandex.weatherplugin",                  // Яндекс Погода
        "ru.yandex.travel",                         // Яндекс Путешествия
        "ru.yandex.mobile.afisha",                  // Яндекс Афиша
        "com.yandex.mobile.realty",                 // Яндекс Недвижимость
        "ru.yandex.mobile.arenda",                  // Яндекс Аренда
        "ru.yandex.androidkeyboard",                // Яндекс Клавиатура
        "ru.yandex.direct",                         // Яндекс Директ
        "com.yandex.toloka.androidapp",             // Толока
        "com.yandex.tasks.androidapp",              // Яндекс Задания

        // Операторы связи
        "ru.mts.mymts",                             // Мой МТС
        "ru.mts.mtstv",                             // KION
        "ru.mts.music.android",                     // МТС Музыка — KION Музыка
        "ru.mts.books.droid",                       // KION Строки
        "ru.megafon.mlk",                           // МегаФон
        "ru.beeline.services",                      // билайн
        "ru.beeline.cloud",                         // Облако билайн
        "ru.tele2.mytele2",                         // t2 (Теле2)
        "ru.yota.android",                          // Yota
        "com.dartit.RTcabinet",                     // Мой Ростелеком
        "ru.rt.video.app.mobile",                   // Wink
        "ru.wink.music",                            // Wink Музыка
        "ru.sber.telecom",                          // СберМобайл
        "ru.tinkoff.mvno",                          // Т-Мобайл

        // Транспорт и путешествия
        "ru.rzd.pass",                              // РЖД Пассажирам
        "ru.aeroflot",                              // Аэрофлот
        "aero.pobeda.twa",                          // Победа
        "ru.s7.android",                            // S7 Airlines
        "ru.aviasales",                             // Авиасейлс
        "ru.tutu.tutu_emp",                         // Туту
        "ru.tutu.etrains",                          // Туту Электрички
        "ru.ostrovok.android",                      // Островок
        "com.punicapp.whoosh",                      // Whoosh
        "ru.urentbike.app",                         // Юрент
        "youdrive.today",                           // Ситидрайв
        "com.carshering",                           // Делимобиль
        "com.taxsee.taxsee",                        // maxim — заказ такси

        // Медиа и подписки
        "ru.rutube.app",                            // RUTUBE
        "ru.ivi.client",                            // Иви
        "ru.more.play",                             // Okko
        "gpm.tnt_premier",                          // PREMIER
        "ru.start.androidmobile",                   // START
        "com.zvooq.openplay",                       // Звук (СберЗвук)
        "ru.litres.android",                        // Литрес
        "ru.mybook",                                // MyBook
        "ru.kassir",                                // Кассир.ру

        // Карты, классифайды, недвижимость, работа
        "ru.dublgis.dgismobile",                    // 2ГИС
        "ru.cian.main",                             // Циан
        "ru.domclick.mortgage",                     // Домклик
        "ru.auto.ara",                              // Авто.ру
        "ru.farpost.dromfilter",                    // Дром Авто
        "ru.drom.baza.android.app",                 // Дром База
        "ru.hh.android",                            // hh — поиск работы
    )

    private val packageSet: Set<String> = PACKAGES.toSet()

    /** MMKV stores string sets as MutableSet, so every write below hands it a fresh mutable copy. */
    private fun Set<String>.forStore(): MutableSet<String> = HashSet(this)

    fun contains(packageName: String): Boolean = packageSet.contains(packageName)

    /** True when the preset is currently applied to the bypass selection. */
    fun isApplied(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_RU_BYPASS_PRESET_ON, false)

    /**
     * Adds every preset package that is not already selected, and remembers exactly which ones it
     * added so [removeFrom] can hand them back and touch nothing else.
     *
     * @param current the selection as it stands
     * @return the packages to ADD (may be empty when everything was already selected)
     */
    fun applyTo(current: Set<String>): Set<String> {
        val added = packageSet - current
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_OWNED, added.forStore())
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_ON, true)
        return added
    }

    /**
     * The packages to REMOVE when the preset is switched off: only what [applyTo] added.
     *
     * A package the user had chosen before applying the preset is not in the owned set, so it stays
     * — un-applying a preset must never take away a decision the user made themselves.
     */
    fun removeFrom(): Set<String> {
        val owned = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_RU_BYPASS_PRESET_OWNED)
            ?.toSet()
            .orEmpty()
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_OWNED, HashSet<String>())
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_ON, false)
        return owned
    }

    /**
     * DEFAULT-ON, ONCE, AND ONLY ON A CONFIGURATION NOBODY HAS TOUCHED.
     *
     * The owner asked for this to be on out of the box. "Out of the box" is the whole of the
     * condition: it seeds only when per-app proxy is off AND the selection is empty — an untouched
     * install. Anyone who has already chosen a mode or ticked an app has made a decision, and
     * flipping their routing to «Кроме выбранных» behind their back would destroy it. They get the
     * switch on the screen instead, off, waiting to be pressed.
     *
     * Runs at most once either way, so a user who turns the preset off does not find it back on at
     * the next launch.
     */
    fun seedOnFirstRun() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_RU_BYPASS_PRESET_SEEDED, false)) return
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_SEEDED, true)

        val perApp = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
        val selection = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (perApp || !selection.isNullOrEmpty()) return

        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, packageSet.forStore())
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        // «Кроме выбранных»: the selection is what goes AROUND the tunnel, which is the whole point.
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_OWNED, packageSet.forStore())
        MmkvManager.encodeSettings(AppConfig.PREF_RU_BYPASS_PRESET_ON, true)
    }
}
