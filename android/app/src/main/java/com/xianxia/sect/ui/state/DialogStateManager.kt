package com.xianxia.sect.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DialogStateManager @Inject constructor() {
    
    sealed class DialogType {
        object Save : DialogType()
        object Alchemy : DialogType()
        object Forge : DialogType()
        object HerbGarden : DialogType()
        object SpiritMine : DialogType()
        object Library : DialogType()
        object WenDaoPeak : DialogType()
        object Recruit : DialogType()
        object Diplomacy : DialogType()
        object Inventory : DialogType()
        object Merchant : DialogType()
        object EventLog : DialogType()
        object SalaryConfig : DialogType()
        object SectManagement : DialogType()
        object WorldMap : DialogType()
        object SecretRealm : DialogType()
        object BattleLog : DialogType()
        object TianshuHall : DialogType()
        object LawEnforcementHall : DialogType()
        object MissionHall : DialogType()
        object ReflectionCliff : DialogType()
        object QingyunPeak : DialogType()
        object BattleTeam : DialogType()
        object SectTrade : DialogType()
        object Gift : DialogType()
        object Scout : DialogType()
        object Alliance : DialogType()
        object EnvoyDiscipleSelect : DialogType()
        object RequestSupport : DialogType()
        object RedeemCode : DialogType()
        object MonthlySalary : DialogType()
        object BuildingDetail : DialogType()
        data class Custom(val id: String) : DialogType()
    }
    
    data class DialogState(
        val type: DialogType,
        val params: Map<String, Any?> = emptyMap()
    )
    
    private val _currentDialog = MutableStateFlow<DialogState?>(null)
    val currentDialog: StateFlow<DialogState?> = _currentDialog.asStateFlow()
    
    private val _dialogParams = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val dialogParams: StateFlow<Map<String, Any?>> = _dialogParams.asStateFlow()
    
    fun openDialog(type: DialogType, params: Map<String, Any?> = emptyMap()) {
        _currentDialog.value = DialogState(type, params)
        _dialogParams.value = params
    }
    
    fun closeDialog() {
        _currentDialog.value = null
        _dialogParams.value = emptyMap()
    }
    
    fun isOpen(type: DialogType): Boolean {
        return _currentDialog.value?.type == type
    }
    
    fun isAnyOpen(): Boolean {
        return _currentDialog.value != null
    }
    
    fun <T> getParam(key: String, default: T): T {
        @Suppress("UNCHECKED_CAST")
        return _dialogParams.value[key] as? T ?: default
    }
    
    fun getStringParam(key: String, default: String = ""): String {
        return _dialogParams.value[key] as? String ?: default
    }
    
    fun getIntParam(key: String, default: Int = 0): Int {
        return (_dialogParams.value[key] as? Number)?.toInt() ?: default
    }
    
    fun getLongParam(key: String, default: Long = 0L): Long {
        return (_dialogParams.value[key] as? Number)?.toLong() ?: default
    }
    
    fun getBooleanParam(key: String, default: Boolean = false): Boolean {
        return _dialogParams.value[key] as? Boolean ?: default
    }
    
    fun updateParams(newParams: Map<String, Any?>) {
        _dialogParams.value = _dialogParams.value + newParams
        _currentDialog.value?.let { current ->
            _currentDialog.value = current.copy(params = _dialogParams.value)
        }
    }
}
