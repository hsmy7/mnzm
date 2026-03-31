package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.SectPolicies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SectSystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "SectSystem"
        const val SYSTEM_NAME = "SectSystem"
        private const val MAX_SPIRIT_STONES = Long.MAX_VALUE
        private const val MAX_SPIRIT_HERBS = Int.MAX_VALUE
    }
    
    private val mutex = Mutex()
    
    private val _gameData = MutableStateFlow(GameData())
    val gameData: StateFlow<GameData> = _gameData.asStateFlow()
    internal val mutableGameData: MutableStateFlow<GameData> get() = _gameData
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "SectSystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "SectSystem released")
    }
    
    override fun clear() {
        _gameData.value = GameData()
    }
    
    suspend fun <T> withLock(block: suspend SectSystem.() -> T): T {
        return mutex.withLock { block() }
    }
    
    fun loadGameData(data: GameData) {
        _gameData.value = data
    }
    
    suspend fun updateGameDataSuspend(transform: (GameData) -> GameData) {
        mutex.withLock {
            _gameData.value = transform(_gameData.value)
        }
    }
    
    fun updateGameData(transform: (GameData) -> GameData) {
        _gameData.value = transform(_gameData.value)
    }
    
    fun getGameData(): GameData = _gameData.value
    
    val gameYear: Int get() = _gameData.value.gameYear
    val gameMonth: Int get() = _gameData.value.gameMonth
    val gameDay: Int get() = _gameData.value.gameDay
    val displayTime: String get() = _gameData.value.displayTime
    
    fun advanceTime(): TimeAdvanceResult {
        val data = _gameData.value
        var newDay = data.gameDay + 1
        var newMonth = data.gameMonth
        var newYear = data.gameYear
        var monthChanged = false
        
        if (newDay > 30) {
            newDay = 1
            newMonth++
            monthChanged = true
            if (newMonth > 12) {
                newMonth = 1
                newYear++
            }
        }
        
        _gameData.value = data.copy(
            gameYear = newYear,
            gameMonth = newMonth,
            gameDay = newDay
        )
        
        return TimeAdvanceResult(
            year = newYear,
            month = newMonth,
            day = newDay,
            monthChanged = monthChanged,
            yearChanged = newYear > data.gameYear
        )
    }
    
    data class TimeAdvanceResult(
        val year: Int,
        val month: Int,
        val day: Int,
        val monthChanged: Boolean,
        val yearChanged: Boolean
    )
    
    val sectName: String get() = _gameData.value.sectName
    val spiritStones: Long get() = _gameData.value.spiritStones
    val spiritHerbs: Int get() = _gameData.value.spiritHerbs
    
    fun addSpiritStones(amount: Long): Boolean {
        if (amount < 0) {
            Log.w(TAG, "Cannot add negative spirit stones: $amount")
            return false
        }
        val current = _gameData.value.spiritStones
        val newAmount = if (current > MAX_SPIRIT_STONES - amount) {
            Log.w(TAG, "Spirit stones overflow, capping at MAX")
            MAX_SPIRIT_STONES
        } else {
            current + amount
        }
        _gameData.value = _gameData.value.copy(spiritStones = newAmount)
        return true
    }
    
    fun addSpiritHerbs(amount: Int): Boolean {
        if (amount < 0) {
            Log.w(TAG, "Cannot add negative spirit herbs: $amount")
            return false
        }
        val current = _gameData.value.spiritHerbs
        val newAmount = if (current > MAX_SPIRIT_HERBS - amount) {
            Log.w(TAG, "Spirit herbs overflow, capping at MAX")
            MAX_SPIRIT_HERBS
        } else {
            current + amount
        }
        _gameData.value = _gameData.value.copy(spiritHerbs = newAmount)
        return true
    }
    
    fun spendSpiritStones(amount: Long): Boolean {
        if (amount < 0) {
            Log.w(TAG, "Cannot spend negative spirit stones: $amount")
            return false
        }
        if (_gameData.value.spiritStones < amount) {
            Log.w(TAG, "Not enough spirit stones: have ${_gameData.value.spiritStones}, need $amount")
            return false
        }
        _gameData.value = _gameData.value.copy(
            spiritStones = _gameData.value.spiritStones - amount
        )
        return true
    }
    
    fun spendSpiritHerbs(amount: Int): Boolean {
        if (amount < 0) {
            Log.w(TAG, "Cannot spend negative spirit herbs: $amount")
            return false
        }
        if (_gameData.value.spiritHerbs < amount) {
            Log.w(TAG, "Not enough spirit herbs: have ${_gameData.value.spiritHerbs}, need $amount")
            return false
        }
        _gameData.value = _gameData.value.copy(
            spiritHerbs = _gameData.value.spiritHerbs - amount
        )
        return true
    }
    
    fun hasSpiritStones(amount: Long): Boolean = _gameData.value.spiritStones >= amount
    fun hasSpiritHerbs(amount: Int): Boolean = _gameData.value.spiritHerbs >= amount
    
    val worldMapSects: List<WorldSect> get() = _gameData.value.worldMapSects
    
    fun updateWorldMapSects(sects: List<WorldSect>) {
        _gameData.value = _gameData.value.copy(worldMapSects = sects)
    }
    
    fun updateWorldSect(sectId: String, transform: (WorldSect) -> WorldSect): Boolean {
        var found = false
        _gameData.value = _gameData.value.copy(
            worldMapSects = _gameData.value.worldMapSects.map { 
                if (it.id == sectId) {
                    found = true
                    transform(it)
                } else it 
            }
        )
        return found
    }
    
    fun getWorldSectById(sectId: String): WorldSect? = 
        _gameData.value.worldMapSects.find { it.id == sectId }
    
    fun getPlayerSect(): WorldSect? = 
        _gameData.value.worldMapSects.find { it.isPlayerSect }
    
    val sectRelations: List<SectRelation> get() = _gameData.value.sectRelations
    
    fun updateSectRelations(relations: List<SectRelation>) {
        _gameData.value = _gameData.value.copy(sectRelations = relations)
    }
    
    fun getSectRelation(sectId1: String, sectId2: String): SectRelation? {
        return _gameData.value.sectRelations.find { 
            (it.sectId1 == sectId1 && it.sectId2 == sectId2) ||
            (it.sectId1 == sectId2 && it.sectId2 == sectId1)
        }
    }
    
    fun updateSectRelation(sectId1: String, sectId2: String, transform: (SectRelation) -> SectRelation): Boolean {
        val relations = _gameData.value.sectRelations.toMutableList()
        val index = relations.indexOfFirst { 
            (it.sectId1 == sectId1 && it.sectId2 == sectId2) ||
            (it.sectId1 == sectId2 && it.sectId2 == sectId1)
        }
        if (index >= 0) {
            relations[index] = transform(relations[index])
            _gameData.value = _gameData.value.copy(sectRelations = relations)
            return true
        }
        return false
    }
    
    val alliances: List<Alliance> get() = _gameData.value.alliances
    
    fun updateAlliances(alliances: List<Alliance>) {
        _gameData.value = _gameData.value.copy(alliances = alliances)
    }
    
    fun addAlliance(alliance: Alliance): Boolean {
        if (alliance.id.isBlank()) {
            Log.w(TAG, "Cannot add alliance with blank id")
            return false
        }
        if (_gameData.value.alliances.any { it.id == alliance.id }) {
            Log.w(TAG, "Alliance with id ${alliance.id} already exists")
            return false
        }
        _gameData.value = _gameData.value.copy(
            alliances = _gameData.value.alliances + alliance
        )
        return true
    }
    
    fun removeAlliance(allianceId: String): Boolean {
        val initialSize = _gameData.value.alliances.size
        _gameData.value = _gameData.value.copy(
            alliances = _gameData.value.alliances.filter { it.id != allianceId }
        )
        return _gameData.value.alliances.size < initialSize
    }
    
    fun getAllianceById(allianceId: String): Alliance? = 
        _gameData.value.alliances.find { it.id == allianceId }
    
    val sectPolicies: SectPolicies get() = _gameData.value.sectPolicies
    
    fun updateSectPolicies(policies: SectPolicies) {
        _gameData.value = _gameData.value.copy(sectPolicies = policies)
    }
    
    val isPlayerProtected: Boolean get() = _gameData.value.isPlayerProtected
    val playerProtectionRemainingYears: Int get() = _gameData.value.playerProtectionRemainingYears
    
    fun disablePlayerProtection() {
        _gameData.value = _gameData.value.copy(playerProtectionEnabled = false)
    }
    
    fun setPlayerAttackedAI() {
        _gameData.value = _gameData.value.copy(playerHasAttackedAI = true)
    }
    
    val monthlySalary: Map<Int, Int> get() = _gameData.value.monthlySalary
    val monthlySalaryEnabled: Map<Int, Boolean> get() = _gameData.value.monthlySalaryEnabled
    
    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean): Boolean {
        if (realm < 0 || realm > 9) {
            Log.w(TAG, "Invalid realm: $realm, must be in range [0, 9]")
            return false
        }
        val newEnabled = _gameData.value.monthlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        _gameData.value = _gameData.value.copy(monthlySalaryEnabled = newEnabled)
        return true
    }
    
    val lastSaveTime: Long get() = _gameData.value.lastSaveTime
    val currentSlot: Int get() = _gameData.value.currentSlot
    val usedRedeemCodes: List<String> get() = _gameData.value.usedRedeemCodes
    
    fun updateLastSaveTime() {
        _gameData.value = _gameData.value.copy(lastSaveTime = System.currentTimeMillis())
    }
    
    fun addUsedRedeemCode(code: String): Boolean {
        if (code.isBlank()) {
            Log.w(TAG, "Cannot add blank redeem code")
            return false
        }
        if (_gameData.value.usedRedeemCodes.contains(code)) {
            Log.w(TAG, "Redeem code already used: $code")
            return false
        }
        _gameData.value = _gameData.value.copy(
            usedRedeemCodes = _gameData.value.usedRedeemCodes + code
        )
        return true
    }
    
    val autoSaveIntervalMonths: Int get() = _gameData.value.autoSaveIntervalMonths
    
    fun setAutoSaveIntervalMonths(months: Int): Boolean {
        if (months < 1 || months > 12) {
            Log.w(TAG, "Invalid auto save interval: $months, must be in range [1, 12]")
            return false
        }
        _gameData.value = _gameData.value.copy(autoSaveIntervalMonths = months)
        return true
    }
}
