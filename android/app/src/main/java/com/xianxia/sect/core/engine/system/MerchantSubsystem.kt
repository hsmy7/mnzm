package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "MerchantSubsystem"
        const val SYSTEM_NAME = "MerchantSubsystem"
    }
    
    private val _merchantItems = MutableStateFlow<List<MerchantItem>>(emptyList())
    val merchantItems: StateFlow<List<MerchantItem>> = _merchantItems.asStateFlow()
    
    private val _playerListedItems = MutableStateFlow<List<MerchantItem>>(emptyList())
    val playerListedItems: StateFlow<List<MerchantItem>> = _playerListedItems.asStateFlow()
    
    private val _lastMerchantRefreshYear = MutableStateFlow(0)
    val lastMerchantRefreshYear: StateFlow<Int> = _lastMerchantRefreshYear.asStateFlow()
    
    private val _merchantAutoTradingEnabled = MutableStateFlow(false)
    val merchantAutoTradingEnabled: StateFlow<Boolean> = _merchantAutoTradingEnabled.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "MerchantSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "MerchantSubsystem released")
    }
    
    override fun clear() {
        StateFlowListUtils.clearList(_merchantItems)
        StateFlowListUtils.clearList(_playerListedItems)
        _lastMerchantRefreshYear.value = 0
        _merchantAutoTradingEnabled.value = false
    }
    
    fun loadMerchantData(
        items: List<MerchantItem>,
        playerListed: List<MerchantItem>,
        lastRefreshYear: Int,
        autoTradingEnabled: Boolean
    ) {
        StateFlowListUtils.setList(_merchantItems, items)
        StateFlowListUtils.setList(_playerListedItems, playerListed)
        _lastMerchantRefreshYear.value = lastRefreshYear
        _merchantAutoTradingEnabled.value = autoTradingEnabled
    }
    
    fun getMerchantItems(): List<MerchantItem> = _merchantItems.value
    
    fun getPlayerListedItems(): List<MerchantItem> = _playerListedItems.value
    
    fun getLastRefreshYear(): Int = _lastMerchantRefreshYear.value
    
    fun updateMerchantItems(items: List<MerchantItem>) = StateFlowListUtils.setList(_merchantItems, items)
    
    fun updatePlayerListedItems(items: List<MerchantItem>) = StateFlowListUtils.setList(_playerListedItems, items)
    
    fun setLastRefreshYear(year: Int) { _lastMerchantRefreshYear.value = year }
    
    fun setAutoTradingEnabled(enabled: Boolean) { _merchantAutoTradingEnabled.value = enabled }
    
    fun addPlayerListedItem(item: MerchantItem) = StateFlowListUtils.addItem(_playerListedItems, item)
    
    fun removePlayerListedItem(itemId: String): Boolean = 
        StateFlowListUtils.removeItemById(_playerListedItems, itemId, getId = { it.id })
    
    fun updatePlayerListedItem(itemId: String, transform: (MerchantItem) -> MerchantItem): Boolean =
        StateFlowListUtils.updateItemById(_playerListedItems, itemId, { it.id }, transform)
    
    fun getMerchantItemById(itemId: String): MerchantItem? = 
        StateFlowListUtils.findItemById(_merchantItems, itemId) { it.id }
    
    fun removeMerchantItem(itemId: String): Boolean = 
        StateFlowListUtils.removeItemById(_merchantItems, itemId, getId = { it.id })
    
    fun updateMerchantItem(itemId: String, transform: (MerchantItem) -> MerchantItem): Boolean =
        StateFlowListUtils.updateItemById(_merchantItems, itemId, { it.id }, transform)
}
