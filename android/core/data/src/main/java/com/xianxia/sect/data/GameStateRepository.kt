package com.xianxia.sect.data

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.*
import com.xianxia.sect.data.incremental.ChangeLogDao
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameStateRepository @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao,
    private val discipleDao: DiscipleDao,
    private val discipleCoreDao: DiscipleCoreDao,
    private val discipleCombatStatsDao: DiscipleCombatStatsDao,
    private val discipleEquipmentDao: DiscipleEquipmentDao,
    private val discipleExtendedDao: DiscipleExtendedDao,
    private val discipleAttributesDao: DiscipleAttributesDao,
    private val equipmentStackDao: EquipmentStackDao,
    private val equipmentInstanceDao: EquipmentInstanceDao,
    private val manualStackDao: ManualStackDao,
    private val manualInstanceDao: ManualInstanceDao,
    private val pillDao: PillDao,
    private val materialDao: MaterialDao,
    private val seedDao: SeedDao,
    private val herbDao: HerbDao,
    private val storageBagDao: StorageBagDao,
    private val explorationTeamDao: ExplorationTeamDao,
    private val buildingSlotDao: BuildingSlotDao,
    private val recipeDao: RecipeDao,
    private val battleLogDao: BattleLogDao,
    private val forgeSlotDao: ForgeSlotDao,
    private val alchemySlotDao: AlchemySlotDao,
    private val productionSlotDao: ProductionSlotDao,
    private val changeLogDao: ChangeLogDao,
    private val scopeProvider: CoroutineScopeProvider
) {
    companion object {
        private const val TAG = "GameStateRepository"
        private const val WRITE_BATCH_DEBOUNCE_MS = 500L
    }

    private val scope = scopeProvider.scope

    private val _pendingWrites = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private data class DirtySet(
        val gameData: Boolean = false,
        val disciples: Boolean = false,
        val equipmentStacks: Boolean = false,
        val equipmentInstances: Boolean = false,
        val manualStacks: Boolean = false,
        val manualInstances: Boolean = false,
        val pills: Boolean = false,
        val materials: Boolean = false,
        val herbs: Boolean = false,
        val seeds: Boolean = false,
        val storageBags: Boolean = false,
        val teams: Boolean = false,
        val battleLogs: Boolean = false
    )

    @Volatile
    private var dirty = DirtySet()

    @Volatile
    private var currentSlotId: Int = 0

    fun setActiveSlot(slotId: Int) {
        currentSlotId = slotId
    }

    fun markDirty(
        gameData: Boolean = false,
        disciples: Boolean = false,
        equipmentStacks: Boolean = false,
        equipmentInstances: Boolean = false,
        manualStacks: Boolean = false,
        manualInstances: Boolean = false,
        pills: Boolean = false,
        materials: Boolean = false,
        herbs: Boolean = false,
        seeds: Boolean = false,
        storageBags: Boolean = false,
        teams: Boolean = false,
        battleLogs: Boolean = false
    ) {
        dirty = dirty.copy(
            gameData = dirty.gameData || gameData,
            disciples = dirty.disciples || disciples,
            equipmentStacks = dirty.equipmentStacks || equipmentStacks,
            equipmentInstances = dirty.equipmentInstances || equipmentInstances,
            manualStacks = dirty.manualStacks || manualStacks,
            manualInstances = dirty.manualInstances || manualInstances,
            pills = dirty.pills || pills,
            materials = dirty.materials || materials,
            herbs = dirty.herbs || herbs,
            seeds = dirty.seeds || seeds,
            storageBags = dirty.storageBags || storageBags,
            teams = dirty.teams || teams,
            battleLogs = dirty.battleLogs || battleLogs
        )
        _pendingWrites.tryEmit(Unit)
    }

    fun markAllDirty() {
        dirty = DirtySet(
            gameData = true, disciples = true, equipmentStacks = true,
            equipmentInstances = true, manualStacks = true, manualInstances = true,
            pills = true, materials = true, herbs = true, seeds = true,
            storageBags = true, teams = true, battleLogs = true
        )
        _pendingWrites.tryEmit(Unit)
    }

    suspend fun flushDirtyState(
        gameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        storageBags: List<StorageBag>,
        teams: List<ExplorationTeam>,
        battleLogs: List<BattleLog>
    ) {
        val snapshot = dirty
        if (snapshot == DirtySet()) return

        val slotId = currentSlotId
        try {
            coroutineScope {
                if (snapshot.gameData) launch(Dispatchers.IO) {
                    database.withTransaction {
                        gameDataDao.insert(gameData.copy(id = "game_data_$slotId", slotId = slotId))
                    }
                }
                if (snapshot.disciples) launch(Dispatchers.IO) {
                    database.withTransaction {
                        discipleDao.upsertAll(disciples.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.equipmentStacks) launch(Dispatchers.IO) {
                    database.withTransaction {
                        equipmentStackDao.upsertAll(equipmentStacks.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.equipmentInstances) launch(Dispatchers.IO) {
                    database.withTransaction {
                        equipmentInstanceDao.upsertAll(equipmentInstances.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.manualStacks) launch(Dispatchers.IO) {
                    database.withTransaction {
                        manualStackDao.upsertAll(manualStacks.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.manualInstances) launch(Dispatchers.IO) {
                    database.withTransaction {
                        manualInstanceDao.upsertAll(manualInstances.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.pills) launch(Dispatchers.IO) {
                    database.withTransaction {
                        pillDao.upsertAll(pills.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.materials) launch(Dispatchers.IO) {
                    database.withTransaction {
                        materialDao.upsertAll(materials.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.herbs) launch(Dispatchers.IO) {
                    database.withTransaction {
                        herbDao.upsertAll(herbs.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.seeds) launch(Dispatchers.IO) {
                    database.withTransaction {
                        seedDao.upsertAll(seeds.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.storageBags) launch(Dispatchers.IO) {
                    database.withTransaction {
                        storageBagDao.upsertAll(storageBags.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.teams) launch(Dispatchers.IO) {
                    database.withTransaction {
                        explorationTeamDao.upsertAll(teams.map { it.copy(slotId = slotId) })
                    }
                }
                if (snapshot.battleLogs) launch(Dispatchers.IO) {
                    database.withTransaction {
                        battleLogDao.upsertAll(battleLogs.map { it.copy(slotId = slotId) })
                    }
                }
            }
            dirty = DirtySet()
            Log.d(TAG, "Flushed dirty state for slot $slotId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush dirty state", e)
        }
    }

    suspend fun loadFullState(slotId: Int): FullGameState? {
        return try {
            val gameData = gameDataDao.getGameDataSync(slotId) ?: return null
            val disciples = discipleDao.getAllSync(slotId)
            val equipmentStacks = equipmentStackDao.getAllSync(slotId)
            val equipmentInstances = equipmentInstanceDao.getAllSync(slotId)
            val manualStacks = manualStackDao.getAllSync(slotId)
            val manualInstances = manualInstanceDao.getAllSync(slotId)
            val pills = pillDao.getAllSync(slotId)
            val materials = materialDao.getAllSync(slotId)
            val herbs = herbDao.getAllSync(slotId)
            val seeds = seedDao.getAllSync(slotId)
            val storageBags = storageBagDao.getAllSync(slotId)
            val teams = explorationTeamDao.getAllSync(slotId)
            val battleLogs = battleLogDao.getAllSync(slotId)
            currentSlotId = slotId
            dirty = DirtySet()
            FullGameState(
                gameData = gameData,
                disciples = disciples,
                equipmentStacks = equipmentStacks,
                equipmentInstances = equipmentInstances,
                manualStacks = manualStacks,
                manualInstances = manualInstances,
                pills = pills,
                materials = materials,
                herbs = herbs,
                seeds = seeds,
                storageBags = storageBags,
                teams = teams,
                battleLogs = battleLogs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load full state for slot $slotId", e)
            null
        }
    }

    data class FullGameState(
        val gameData: GameData,
        val disciples: List<Disciple>,
        val equipmentStacks: List<EquipmentStack>,
        val equipmentInstances: List<EquipmentInstance>,
        val manualStacks: List<ManualStack>,
        val manualInstances: List<ManualInstance>,
        val pills: List<Pill>,
        val materials: List<Material>,
        val herbs: List<Herb>,
        val seeds: List<Seed>,
        val storageBags: List<StorageBag>,
        val teams: List<ExplorationTeam>,
        val battleLogs: List<BattleLog>
    )
}
