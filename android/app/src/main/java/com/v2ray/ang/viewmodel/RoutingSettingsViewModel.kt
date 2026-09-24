package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

class RoutingSettingsViewModel : ViewModel() {
    private val rulesets: MutableList<RulesetItem> = mutableListOf()

    fun getAll(): List<RulesetItem> = rulesets.toList()

    fun reload() {
        rulesets.clear()
        rulesets.addAll(MmkvManager.decodeRoutingRulesets() ?: mutableListOf())
    }

    fun update(position: Int, item: RulesetItem) {
        if (position in rulesets.indices) {
            rulesets[position] = item
            SettingsManager.saveRoutingRuleset(position, item)
        }
    }

    /**
     * Turns one rule on or off from the list, without opening it.
     *
     * The rule row carried this switch at 5e8cd54 and a rebuild moved it inside the editor, which
     * turned a one-tap change into three. It goes through [update], so the list's copy of the rule
     * and the stored one cannot disagree — and so the core-config restart the editor's own toggle
     * triggers is triggered here too, by the same call.
     */
    fun setEnabled(position: Int, enabled: Boolean) {
        val item = rulesets.getOrNull(position) ?: return
        if (item.enabled == enabled) return
        update(position, item.copy(enabled = enabled))
    }

    fun swap(fromPosition: Int, toPosition: Int) {
        if (fromPosition in rulesets.indices && toPosition in rulesets.indices) {
            SettingsManager.swapRoutingRuleset(fromPosition, toPosition)
        }
    }
}

