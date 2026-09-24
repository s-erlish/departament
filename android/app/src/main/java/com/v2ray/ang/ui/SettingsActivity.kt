package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.View
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MmkvPreferenceDataStore
import com.v2ray.ang.util.Utils

/**
 * Экран «Дополнительно» - всё, что реально влияет на работу приложения, но не
 * должно висеть на вкладке настроек.
 *
 * Информационная архитектура взята из `docs/design2026/12-settings.md`: группы
 * «Ядро» и «Туннель» - раздел 5.9, «DNS» - 5.4, «Параметры фрагментации» - 5.5,
 * «Проверка задержки» - 5.6. Порядок ключей, в котором они лежали в
 * `res/xml/pref_settings.xml`, роли не играет.
 *
 * Экран умеет открываться одной группой: [EXTRA_SECTION] со значением
 * [SECTION_DNS], [SECTION_FRAGMENT] или [SECTION_LATENCY] показывает только её и
 * ставит её название в шапку. Так вкладка настроек ведёт на маршруты
 * `settings/dns`, `settings/fragment` и `settings/latency` из 12-settings.md 5.0,
 * не заводя три отдельные Activity. Без экстры открывается весь экран.
 */
class SettingsActivity : BaseActivity() {

    companion object {
        /** Какую группу показать. Значение - одна из констант SECTION_*. */
        const val EXTRA_SECTION = "extra_settings_section"

        /** Весь экран. Значение по умолчанию, если экстры нет. */
        const val SECTION_ADVANCED = "advanced"

        /** Только «DNS» (12-settings.md 5.4). */
        const val SECTION_DNS = "dns"

        /** Только «Параметры фрагментации» (12-settings.md 5.5). */
        const val SECTION_FRAGMENT = "fragment"

        /** Только «Проверка задержки» (12-settings.md 5.6). */
        const val SECTION_LATENCY = "latency"

        /** Ключ группы в pref_settings.xml для запрошенного раздела, или null. */
        internal fun categoryKeyOf(section: String?): String? = when (section) {
            SECTION_DNS -> "adv_section_dns"
            SECTION_FRAGMENT -> "adv_section_fragment"
            SECTION_LATENCY -> "adv_section_latency"
            else -> null
        }

        @StringRes
        internal fun titleOf(section: String?): Int = when (section) {
            SECTION_DNS -> R.string.adv_title_dns
            SECTION_FRAGMENT -> R.string.adv_title_fragment
            SECTION_LATENCY -> R.string.adv_title_latency
            else -> R.string.adv_title
        }

        /**
         * Готовый intent на экран целиком или на одну его группу. Вкладка настроек
         * зовёт его так: `startActivity(SettingsActivity.newIntent(this))`.
         */
        @JvmStatic
        fun newIntent(context: Context, section: String = SECTION_ADVANCED): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_SECTION, section)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = intent?.getStringExtra(EXTRA_SECTION)
        setContentViewWithToolbar(
            R.layout.activity_settings,
            showHomeAsUp = true,
            title = getString(titleOf(section))
        )
        applySeamlessToolbar()
    }

    /**
     * Шапка вложенного экрана по 00-rules.md 4.8: фон страницы, без тени и
     * разделителя, заголовок в Title 16/700 (Golos), отступ - единый жёлоб 16.
     *
     * activity_base.xml выдаёт тулбар уже прозрачным и без elevation, но с
     * `ToolbarBrandTitle`: это словесный знак в Space Grotesk 20sp, где нет ни
     * одной кириллической глифы, поэтому русский заголовок подменяется системным
     * шрифтом. Пока activity_base.xml не перешёл на `Widget.Departament.Toolbar`,
     * чиним это здесь. Правка локальная и повторяет то, что делает сам стиль, так
     * что после перехода она станет безвредным дублем.
     */
    private fun applySeamlessToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar) ?: return
        toolbar.setTitleTextAppearance(this, R.style.TextAppearance_App_Title)
        val gutter = resources.getDimensionPixelSize(R.dimen.screen_gutter)
        toolbar.contentInsetStartWithNavigation = gutter
        toolbar.setContentInsetsRelative(gutter, gutter)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // Ядро
        private val logLevel by lazy { findPreference<ListPreference>(AppConfig.PREF_LOGLEVEL) }
        private val sniffing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_SNIFFING_ENABLED) }
        private val routeOnly by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ROUTE_ONLY_ENABLED) }
        private val allowInsecure by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ALLOW_INSECURE) }

        // Туннель
        private val vpnMtu by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_MTU) }
        private val vpnInterfaceAddress by lazy {
            findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX)
        }

        // DNS
        private val localDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_LOCAL_DNS_ENABLED) }
        private val fakeDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FAKE_DNS_ENABLED) }
        private val domesticDns by lazy { findPreference<EditTextPreference>(AppConfig.PREF_DOMESTIC_DNS) }
        private val dnsHosts by lazy { findPreference<EditTextPreference>(AppConfig.PREF_DNS_HOSTS) }
        private val resolveMethod by lazy {
            findPreference<ListPreference>(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD)
        }

        // Параметры фрагментации
        private val fragmentLength by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH) }
        private val fragmentInterval by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL) }
        private val fragmentPackets by lazy { findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS) }
        private val fragmentNote by lazy { findPreference<Preference>(KEY_FRAGMENT_NOTE) }

        // Проверка задержки
        private val delayTestUrl by lazy { findPreference<EditTextPreference>(AppConfig.PREF_DELAY_TEST_URL) }
        private val pingConcurrency by lazy { findPreference<ListPreference>(AppConfig.PREF_REAL_PING_CONCURRENCY) }

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            // MMKV - единственное хранилище настроек. Без этого Preference писал бы
            // в SharedPreferences, ядро читало бы MMKV, и экран врал бы молча.
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            addPreferencesFromResource(R.xml.pref_settings)

            applyRequestedSection()
            bindListRows()
            bindFieldRows()
            bindToggleRows()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // Разделителей во всю ширину в языке экранов нет: строки разделены
            // заголовками групп (00-rules.md 4.6).
            setDivider(null)
            setDividerHeight(0)
        }

        override fun onStart() {
            super.onStart()
            // Зависимые строки пересобираем и при возврате: значения могли
            // измениться на вкладке настроек, пока экран был в фоне.
            updateSniffingDependants(
                MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true)
            )
            updateLocalDnsDependants(
                MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
            )
            updateFragmentDependants(
                MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
            )
        }

        // region Разделы

        /** Показывает одну группу, если Activity открыли с EXTRA_SECTION. */
        private fun applyRequestedSection() {
            val wanted = SettingsActivity.categoryKeyOf(
                activity?.intent?.getStringExtra(EXTRA_SECTION)
            ) ?: return
            val screen = preferenceScreen ?: return
            for (i in 0 until screen.preferenceCount) {
                val group = screen.getPreference(i)
                group.isVisible = group.key == wanted
            }
        }

        // endregion

        // region Строки со списком значений

        /**
         * Сводка строки - выбранное значение. Если в хранилище лежит что-то вне
         * набора, показываем его как есть, а не пустоту.
         */
        private fun bindListRows() {
            listOf(logLevel, vpnInterfaceAddress, resolveMethod, fragmentPackets, pingConcurrency)
                .forEach { pref -> pref?.let { bindListRow(it) } }

            // 12-settings.md 5.9: «Отладка» стоит батареи, и сказать об этом надо
            // в момент выбора, а не в справке.
            logLevel?.setOnPreferenceChangeListener { pref, newValue ->
                val value = newValue as? String
                showListValue(pref as ListPreference, value)
                if (value == LOG_LEVEL_DEBUG) {
                    notice(R.string.adv_loglevel_debug_hint)
                } else {
                    noticeCoreChange()
                }
                true
            }
        }

        private fun bindListRow(pref: ListPreference) {
            showListValue(pref, pref.value)
            pref.setOnPreferenceChangeListener { p, newValue ->
                showListValue(p as ListPreference, newValue as? String)
                noticeCoreChange()
                true
            }
        }

        private fun showListValue(pref: ListPreference, value: String?) {
            val index = pref.findIndexOfValue(value)
            pref.summary = if (index >= 0) pref.entries[index].toString() else value.orEmpty()
        }

        // endregion

        // region Поля ввода

        /**
         * Поля с границами. Ни одно не сохранит значение, которое читающий код
         * молча выбросит: тип клавиатуры сужен, фильтр не даёт набрать заведомо
         * неверное, проверка на сохранении отклоняет остальное и говорит, что
         * именно ввести.
         */
        private fun bindFieldRows() {
            // MTU уходит в VpnService.Builder.setMtu() через SettingsManager.getVpnMtu().
            // 576 - минимальный IPv4-датаграм по RFC 791, 9000 - обычный джамбо-кадр.
            bindNumberField(vpnMtu, MTU_MIN, MTU_MAX, R.string.adv_mtu_error)

            // SettingsManager.getDomesticDnsServers() оставляет только чистые IP и
            // адреса https/tcp/quic, остальное там тихо отбрасывается. Значит,
            // отбрасываем на вводе и объясняем почему.
            bindTextField(
                pref = domesticDns,
                errorRes = R.string.adv_domestic_dns_error,
                emptySummaryRes = 0,
                configureEditor = { it.inputType = uriInputType },
                normalize = ::normalizeDnsList,
            )

            // dns.hosts: пары «домен - адрес». CoreConfigManager режет запись по
            // двоеточию, поэтому адрес обязан быть IPv4.
            bindTextField(
                pref = dnsHosts,
                errorRes = R.string.adv_dns_hosts_error,
                emptySummaryRes = R.string.adv_dns_hosts_empty,
                configureEditor = { editor ->
                    editor.inputType = multilineInputType
                    editor.setText(hostsToLines(editor.text?.toString()))
                },
                normalize = ::normalizeHosts,
                display = ::hostsToSummary,
            )

            bindRangeField(fragmentLength)
            bindRangeField(fragmentInterval)

            bindTextField(
                pref = delayTestUrl,
                errorRes = R.string.adv_delay_url_error,
                emptySummaryRes = 0,
                configureEditor = { it.inputType = uriInputType },
                normalize = ::normalizeProbeUrl,
            )
        }

        /** Целое число в границах [min]..[max]. Больше [max] набрать нельзя. */
        private fun bindNumberField(
            pref: EditTextPreference?,
            min: Int,
            max: Int,
            @StringRes errorRes: Int,
        ) {
            pref ?: return
            pref.setOnBindEditTextListener { editor ->
                editor.inputType = InputType.TYPE_CLASS_NUMBER
                editor.filters = arrayOf(
                    UpperBoundFilter(max),
                    InputFilter.LengthFilter(max.toString().length),
                )
                editor.setSelection(editor.text?.length ?: 0)
            }
            showFieldValue(pref, pref.text, 0) { it }
            pref.setOnPreferenceChangeListener { p, newValue ->
                val raw = (newValue as? String).orEmpty().trim()
                val value = raw.toIntOrNull()
                if (value == null || value < min || value > max) {
                    rejectValue(p, errorRes)
                    return@setOnPreferenceChangeListener false
                }
                showFieldValue(p as EditTextPreference, raw, 0) { it }
                noticeCoreChange()
                true
            }
        }

        /** Диапазон вида «50-100»: только цифры и дефис, оба числа 1..10000. */
        private fun bindRangeField(pref: EditTextPreference?) {
            bindTextField(
                pref = pref,
                errorRes = R.string.adv_fragment_range_error,
                emptySummaryRes = 0,
                configureEditor = { editor ->
                    editor.inputType = InputType.TYPE_CLASS_PHONE
                    editor.filters = arrayOf(
                        CharsetFilter(RANGE_CHARS),
                        InputFilter.LengthFilter(RANGE_MAX_LENGTH),
                    )
                },
                normalize = ::normalizeRange,
            )
        }

        /**
         * Текстовое поле с нормализацией. [normalize] возвращает то, что надо
         * сохранить, или null, если значение неверное. [display] переводит
         * сохранённое значение в текст сводки.
         */
        private fun bindTextField(
            pref: EditTextPreference?,
            @StringRes errorRes: Int,
            @StringRes emptySummaryRes: Int,
            configureEditor: (EditText) -> Unit,
            normalize: (String) -> String?,
            display: (String) -> String = { it },
        ) {
            pref ?: return
            pref.setOnBindEditTextListener { editor ->
                configureEditor(editor)
                editor.setSelection(editor.text?.length ?: 0)
            }
            showFieldValue(pref, pref.text, emptySummaryRes, display)
            pref.setOnPreferenceChangeListener { p, newValue ->
                val raw = (newValue as? String).orEmpty()
                val normalized = normalize(raw)
                if (normalized == null) {
                    rejectValue(p, errorRes)
                    return@setOnPreferenceChangeListener false
                }
                val field = p as EditTextPreference
                noticeCoreChange()
                if (normalized == raw) {
                    showFieldValue(field, normalized, emptySummaryRes, display)
                    return@setOnPreferenceChangeListener true
                }
                // Нормализованное значение сохраняем сами: setText() пишет в
                // хранилище и НЕ дёргает этот же слушатель, рекурсии не будет.
                field.text = normalized
                showFieldValue(field, normalized, emptySummaryRes, display)
                false
            }
        }

        private fun showFieldValue(
            pref: EditTextPreference,
            value: String?,
            @StringRes emptySummaryRes: Int,
            display: (String) -> String,
        ) {
            val stored = value.orEmpty().trim()
            pref.summary = when {
                stored.isNotEmpty() -> display(stored)
                emptySummaryRes != 0 -> getString(emptySummaryRes)
                else -> ""
            }
        }

        // endregion

        // region Проверка значений

        /** Список DNS-адресов через запятую - ровно то, что читает SettingsManager. */
        private fun normalizeDnsList(raw: String): String? {
            val items = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) return null
            if (items.any { !Utils.isPureIpAddress(it) && !Utils.isCoreDNSAddress(it) }) return null
            return items.joinToString(",")
        }

        /**
         * Записи hosts. На вводе - строка на запись, «домен адрес»; в хранилище -
         * «домен:адрес» через запятую, как ждёт CoreConfigManager. Адрес IPv6
         * отпадает сам: он содержит двоеточия и распадается больше чем на два
         * куска, а читающий код всё равно взял бы из него только первую группу.
         */
        private fun normalizeHosts(raw: String): String? {
            val lines = raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return ""
            val entries = ArrayList<String>(lines.size)
            for (line in lines) {
                val parts = line.split(' ', '\t', ':').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size != 2) return null
                val host = parts[0]
                val address = parts[1]
                if (host.isEmpty() || !Utils.isPureIpAddress(address)) return null
                entries.add("$host:$address")
            }
            return entries.joinToString(",")
        }

        /** «50-100» -> «50-100»; всё, что ядро не поймёт, отклоняется. */
        private fun normalizeRange(raw: String): String? {
            val parts = raw.trim().split('-')
            if (parts.size != 2) return null
            val from = parts[0].trim().toIntOrNull() ?: return null
            val to = parts[1].trim().toIntOrNull() ?: return null
            if (from < RANGE_MIN || to > RANGE_MAX || from > to) return null
            return "$from-$to"
        }

        /** Адрес пробы задержки: только http и https, только разбираемая ссылка. */
        private fun normalizeProbeUrl(raw: String): String? {
            val value = raw.trim()
            if (!value.startsWith("http://") && !value.startsWith("https://")) return null
            return if (Utils.isValidUrl(value)) value else null
        }

        private fun hostsToLines(stored: String?): String =
            splitHosts(stored).joinToString("\n")

        private fun hostsToSummary(stored: String?): String =
            splitHosts(stored).joinToString(", ")

        private fun splitHosts(stored: String?): List<String> =
            stored.orEmpty().split(",", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.replaceFirst(":", " ") }

        // endregion

        // region Зависимые строки

        private fun bindToggleRows() {
            sniffing?.setOnPreferenceChangeListener { _, newValue ->
                updateSniffingDependants(newValue as? Boolean ?: true)
                noticeCoreChange()
                true
            }
            localDns?.setOnPreferenceChangeListener { _, newValue ->
                updateLocalDnsDependants(newValue as? Boolean ?: false)
                noticeCoreChange()
                true
            }
            listOf(routeOnly, fakeDns, allowInsecure).forEach { pref ->
                pref?.setOnPreferenceChangeListener { _, _ ->
                    noticeCoreChange()
                    true
                }
            }
        }

        /**
         * «Только для маршрутизации» существует лишь как режим определения домена,
         * поэтому без него строки нет (12-settings.md 5.9: строка d видна, пока
         * включена строка c).
         */
        private fun updateSniffingDependants(enabled: Boolean) {
            routeOnly?.isVisible = enabled
        }

        /**
         * FakeIP работает только вместе с локальным резолвером: CoreConfigManager
         * поднимает fakedns, лишь когда включены оба. Выключенная строка называет
         * причину прямо в сводке (12-settings.md 10, «Disabled / gated»).
         */
        private fun updateLocalDnsDependants(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
            fakeDns?.setSummary(
                if (enabled) R.string.adv_fake_dns_summary else R.string.adv_fake_dns_summary_locked
            )
        }

        /**
         * Пока сама фрагментация выключена на вкладке настроек, эти три значения
         * ни на что не влияют. Показывать работающие с виду поля - врать, поэтому
         * они выключены, а пояснение под ними называет причину.
         */
        private fun updateFragmentDependants(enabled: Boolean) {
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
            fragmentPackets?.isEnabled = enabled
            fragmentNote?.setSummary(
                if (enabled) R.string.adv_fragment_note else R.string.adv_fragment_note_off
            )
        }

        // endregion

        // region Сообщения

        /**
         * 12-settings.md 10.1: настройки шлют сообщение, только когда туннель уже
         * поднят и его придётся перезапустить. В отключённом состоянии - молча.
         */
        private fun noticeCoreChange() {
            // `isTunnelUp`, НЕ `isRunning`. Настройки живут в UI-процессе, а ядро — в
            // `:RunSoLibV2RayDaemon`, и его контроллер там свой: `isRunning` отвечал «нет» всегда,
            // сколько бы туннель ни был поднят. Условие не выполнялось никогда, и сообщение,
            // которого требует 12-settings.md 10.1, не показывалось ни разу.
            // @see CoreServiceManager.isTunnelUp
            val running = runCatching { CoreServiceManager.isTunnelUp() }.getOrDefault(false)
            if (running) notice(R.string.adv_notice_reconnecting)
        }

        private fun notice(@StringRes messageRes: Int) {
            val root = view ?: return
            Snackbar.make(root, getString(messageRes), Snackbar.LENGTH_SHORT).show()
        }

        /** Значение не сохранено: называем причину и даём вернуться в поле. */
        private fun rejectValue(pref: Preference, @StringRes errorRes: Int) {
            val root = view ?: return
            Snackbar.make(root, getString(errorRes), Snackbar.LENGTH_LONG)
                .setAction(R.string.adv_action_retry) { pref.performClick() }
                .show()
        }

        // endregion

        private companion object {
            const val KEY_FRAGMENT_NOTE = "adv_fragment_note"
            const val LOG_LEVEL_DEBUG = "debug"

            const val MTU_MIN = 576
            const val MTU_MAX = 9000

            const val RANGE_MIN = 1
            const val RANGE_MAX = 10000
            const val RANGE_CHARS = "0123456789-"
            const val RANGE_MAX_LENGTH = 11

            val uriInputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            val multilineInputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }
}

/**
 * Не даёт набрать число больше [max]. Значения меньше нижней границы остаются
 * набираемыми: «5» - это шаг к «576», обрывать ввод на нём нельзя. Нижнюю границу
 * проверяет сохранение.
 */
private class UpperBoundFilter(private val max: Int) : InputFilter {
    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
        val candidate = StringBuilder(dest)
            .replace(dstart, dend, source.subSequence(start, end).toString())
            .toString()
        if (candidate.isEmpty()) return null
        if (candidate.any { !it.isDigit() }) return ""
        val value = candidate.toLongOrNull() ?: return ""
        return if (value > max) "" else null
    }
}

/** Пропускает только перечисленные символы. */
private class CharsetFilter(private val allowed: String) : InputFilter {
    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
        for (i in start until end) {
            if (!allowed.contains(source[i])) return ""
        }
        return null
    }
}
