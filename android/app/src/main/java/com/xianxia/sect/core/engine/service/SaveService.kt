@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*

/**
 * 存档服务内部使用的轻量级状态快照
 * （与 GameEngine.GameStateSnapshot 不同，此版本用于统计和 UI 展示）
 */
data class GameStateSnapshot(
    val gameYear: Int = 1,
    val gameMonth: Int = 1,
    val gameDay: Int = 1,
    val isGameStarted: Boolean = false,
    val gameSpeed: Int = 1,
    val sectName: String = "",
    val spiritStones: Long = 0,
    val sectCultivation: Double = 0.0,
    val discipleCount: Int = 0,
    val equipmentCount: Int = 0,
    val manualCount: Int = 0,
    val pillCount: Int = 0,
    val materialCount: Int = 0,
    val herbCount: Int = 0,
    val seedCount: Int = 0,
    val explorationTeamCount: Int = 0,
    val caveExplorationTeamCount: Int = 0,
    val forgeSlotCount: Int = 0,
    val alchemySlotCount: Int = 0,
    val worldMapSectCount: Int = 0,
    val allianceCount: Int = 0,
    val eventCount: Int = 0,
    val battleLogCount: Int = 0,
    val caveCount: Int = 0,
    val activeCaveExplorationCount: Int = 0,
    val lastUpdated: Long = 0L
)

/**
 * 存档服务 - 负责游戏状态快照和存档管理
 *
 * 职责域：
 * - getStateSnapshot / getStateSnapshotSync
 * - 存档加载协调
 * - 与 SaveLoadCoordinator 对接
 * - 状态序列化和反序列化
 */
class SaveService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _manuals: MutableStateFlow<List<Manual>>,
    private val _pills: MutableStateFlow<List<Pill>>,
    private val _materials: MutableStateFlow<List<Material>>,
    private val _herbs: MutableStateFlow<List<Herb>>,
    private val _seeds: MutableStateFlow<List<Seed>>,
    private val _events: MutableStateFlow<List<GameEvent>>,
    private val _battleLogs: MutableStateFlow<List<BattleLog>>,
    private val _teams: MutableStateFlow<List<ExplorationTeam>>
) {
    companion object {
        private const val TAG = "SaveService"
    }

    // ==================== 状态快照 ====================

    /**
     * Get complete game state snapshot (synchronous version for UI thread)
     *
     * This is a simplified snapshot that captures the current state without deep copying all data.
     * For full save functionality, use async version with SaveLoadCoordinator.
     */
    fun getStateSnapshotSync(): GameStateSnapshot {
        val data = _gameData.value

        return GameStateSnapshot(
            // Basic game info
            gameYear = data.gameYear,
            gameMonth = data.gameMonth,
            gameDay = data.gameDay,
            isGameStarted = data.isGameStarted,
            gameSpeed = data.gameSpeed,
            sectName = data.sectName,

            // Resources
            spiritStones = data.spiritStones,
            sectCultivation = data.sectCultivation,

            // Counts (for quick UI updates)
            discipleCount = _disciples.value.size,
            equipmentCount = _equipment.value.size,
            manualCount = _manuals.value.size,
            pillCount = _pills.value.size,
            materialCount = _materials.value.size,
            herbCount = _herbs.value.size,
            seedCount = _seeds.value.size,

            // Team counts
            explorationTeamCount = _teams.value.size,
            caveExplorationTeamCount = data.caveExplorationTeams.size,

            // Building slot counts
            forgeSlotCount = data.forgeSlots.count { it.status == SlotStatus.WORKING },
            alchemySlotCount = data.alchemySlots.count { it.status == AlchemySlotStatus.WORKING },

            // World map info
            worldMapSectCount = data.worldMapSects.size,
            allianceCount = data.alliances.size,

            // Event log sizes
            eventCount = _events.value.size,
            battleLogCount = _battleLogs.value.size,

            // Cave info
            caveCount = data.cultivatorCaves.count { it.status == CaveStatus.AVAILABLE },
            activeCaveExplorationCount = data.caveExplorationTeams.count {
                it.status == CaveExplorationStatus.EXPLORING ||
                it.status == CaveExplorationStatus.TRAVELING
            },

            // Timestamp
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Get complete game state snapshot (async version)
     *
     * This creates a full deep copy of all game state suitable for saving.
     * Heavy operation - should be called on background thread.
     */
    suspend fun getStateSnapshot(): GameStateSnapshot {
        // For now, use synchronous version
        // In full implementation, this would coordinate with SaveLoadCoordinator
        return getStateSnapshotSync()
    }

    // ==================== 存档加载协调 ====================

    /**
     * Prepare state for loading from save
     * Clears current state and prepares to receive loaded data
     */
    fun prepareForLoad() {
        // Reset core GameData to default values first (atomic reset)
        _gameData.value = GameData()

        // Clear all mutable state to prepare for loading
        _disciples.value = emptyList()
        _equipment.value = emptyList()
        _manuals.value = emptyList()
        _pills.value = emptyList()
        _materials.value = emptyList()
        _herbs.value = emptyList()
        _seeds.value = emptyList()
        _events.value = emptyList()
        _battleLogs.value = emptyList()
        _teams.value = emptyList()

        android.util.Log.d(TAG, "Prepared state for loading from save")
    }

    /**
     * Restore state from loaded GameData
     * Called after SaveLoadCoordinator has loaded the data
     */
    fun restoreFromLoad(loadedGameData: GameData) {
        _gameData.value = loadedGameData

        android.util.Log.d(TAG, "Restored state from save: year=${loadedGameData.gameYear}, disciples count would be set separately")
    }

    /**
     * Restore collections from loaded data
     * Called after restoreFromLoad to populate collection StateFlows
     */
    fun restoreCollections(
        disciples: List<Disciple>,
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        events: List<GameEvent>,
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    ) {
        _disciples.value = disciples
        _equipment.value = equipment
        _manuals.value = manuals
        _pills.value = pills
        _materials.value = materials
        _herbs.value = herbs
        _seeds.value = seeds
        _events.value = events
        _battleLogs.value = battleLogs
        _teams.value = teams

        android.util.Log.d(TAG, "Restored collections: ${disciples.size} disciples, ${equipment.size} equipment")
    }

    // ==================== 数据验证 ====================

    /**
     * Validate current game state consistency
     * Returns list of validation errors, empty if valid
     */
    fun validateState(): List<String> {
        val errors = mutableListOf<String>()
        val data = _gameData.value

        // Check basic invariants
        if (data.gameYear < 0) {
            errors.add("Invalid game year: ${data.gameYear}")
        }
        if (data.gameMonth < 1 || data.gameMonth > 12) {
            errors.add("Invalid game month: ${data.gameMonth}")
        }
        if (data.gameDay < 1 || data.gameDay > 30) {
            errors.add("Invalid game day: ${data.gameDay}")
        }

        // Check disciple references
        val discipleIds = _disciples.value.map { it.id }.toSet()
        data.forgeSlots.forEach { slot ->
            slot.discipleId?.let { discipleId ->
                if (!discipleIds.contains(discipleId)) {
                    errors.add("Forge slot references non-existent disciple: $discipleId")
                }
            }
        }

        // Check equipment uniqueness
        val equipmentIds = _equipment.value.map { it.id }
        val duplicateEquipmentIds = equipmentIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicateEquipmentIds.isNotEmpty()) {
            errors.add("Duplicate equipment IDs found: $duplicateEquipmentIds")
        }

        return errors
    }

    /**
     * Get state statistics for debugging/monitoring
     */
    fun getStateStatistics(): Map<String, Any> {
        val data = _gameData.value

        return mapOf(
            "gameYear" to data.gameYear,
            "gameMonth" to data.gameMonth,
            "spiritStones" to data.spiritStones,
            "sectCultivation" to data.sectCultivation,
            "discipleCount" to _disciples.value.size,
            "aliveDisciples" to _disciples.value.count { it.isAlive },
            "equipmentCount" to _equipment.value.size,
            "equippedEquipment" to _equipment.value.count { it.isEquipped },
            "manualCount" to _manuals.value.size,
            "pillCount" to _pills.value.size,
            "materialCount" to _materials.value.size,
            "herbCount" to _herbs.value.size,
            "seedCount" to _seeds.value.size,
            "eventCount" to _events.value.size,
            "battleLogCount" to _battleLogs.value.size,
            "explorationTeams" to _teams.value.size,
            "caveExplorations" to data.caveExplorationTeams.size,
            "activeForgingSlots" to data.forgeSlots.count { it.status == SlotStatus.WORKING },
            "activeAlchemySlots" to data.alchemySlots.count { it.status == AlchemySlotStatus.WORKING },
            "worldSects" to data.worldMapSects.size,
            "alliances" to data.alliances.size,
            "cultivatorCaves" to data.cultivatorCaves.size
        )
    }

    /**
     * Check if game has been started
     */
    fun isGameStarted(): Boolean {
        return _gameData.value.isGameStarted
    }

    /**
     * Get current game time as formatted string
     */
    fun getFormattedGameTime(): String {
        val data = _gameData.value
        return "${data.gameYear}年${data.gameMonth}月${data.gameDay}日"
    }
}
