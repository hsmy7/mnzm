package com.xianxia.sect.data

import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAttributes
import com.xianxia.sect.core.model.DiscipleCombatStats
import com.xianxia.sect.core.model.DiscipleCompact
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleEquipment
import com.xianxia.sect.core.model.DiscipleExtended
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.data.local.GameDataDao
import com.xianxia.sect.data.local.GameDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton





@Singleton
class GameStateRepository @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao,
    private val discipleDaos: DiscipleDaos,
    private val itemDaos: ItemDaos,
    private val worldDaos: WorldDaos
) {
    companion object {
        private const val TAG = "GameStateRepository"
        private const val WRITE_BATCH_DEBOUNCE_MS = 500L
    }

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
        val battleLogs: Boolean = false
    )

    private val dirty = AtomicReference(DirtySet())

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
        battleLogs: Boolean = false
    ) {
        dirty.updateAndGet { current ->
            current.copy(
                gameData = current.gameData || gameData,
                disciples = current.disciples || disciples,
                equipmentStacks = current.equipmentStacks || equipmentStacks,
                equipmentInstances = current.equipmentInstances || equipmentInstances,
                manualStacks = current.manualStacks || manualStacks,
                manualInstances = current.manualInstances || manualInstances,
                pills = current.pills || pills,
                materials = current.materials || materials,
                herbs = current.herbs || herbs,
                seeds = current.seeds || seeds,
                storageBags = current.storageBags || storageBags,
                battleLogs = current.battleLogs || battleLogs
            )
        }
    }

    fun markAllDirty() {
        dirty.updateAndGet {
            DirtySet(
                gameData = it.gameData || true, disciples = it.disciples || true,
                equipmentStacks = it.equipmentStacks || true, equipmentInstances = it.equipmentInstances || true,
                manualStacks = it.manualStacks || true, manualInstances = it.manualInstances || true,
                pills = it.pills || true, materials = it.materials || true,
                herbs = it.herbs || true, seeds = it.seeds || true,
                storageBags = it.storageBags || true,
                battleLogs = it.battleLogs || true
            )
        }
    }

    fun clearDirty() {
        dirty.set(DirtySet())
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
        battleLogs: List<BattleLog>
    ) {
        val snapshot = dirty.getAndSet(DirtySet())
        if (snapshot == DirtySet()) return

        val slotId = currentSlotId
        try {
            // 单事务写入：所有表在同一个 withTransaction 中串行写入，
            // 消除 13 个并发 withTransaction 之间的 WAL 竞争（之前导致 #5037 SIGSEGV）。
            // 参考 Room KMP ConnectionPool: WAL 模式使用 1 writer + N readers
            database.withTransaction {
                if (snapshot.gameData) {
                    gameDataDao.insert(gameData.copy(id = "game_data_$slotId", slotId = slotId))
                }
                if (snapshot.disciples) {
                    // 先清后写：防止已移除弟子的行残留在 DB 中
                    discipleDaos.discipleDao.deleteAll(slotId)
                    discipleDaos.discipleCoreDao.deleteAll(slotId)
                    discipleDaos.discipleCombatStatsDao.deleteAll(slotId)
                    discipleDaos.discipleEquipmentDao.deleteAll(slotId)
                    discipleDaos.discipleExtendedDao.deleteAll(slotId)
                    discipleDaos.discipleAttributesDao.deleteAll(slotId)
                    database.discipleCompactDao().deleteAll(slotId)

                    val batch = disciples.map { it.copy(slotId = slotId) }
                    discipleDaos.discipleDao.upsertAll(batch)
                    discipleDaos.discipleCoreDao.upsertAll(
                        batch.map { DiscipleCore.fromDisciple(it).copy(slotId = slotId) }
                    )
                    discipleDaos.discipleCombatStatsDao.upsertAll(
                        batch.map { DiscipleCombatStats.fromDisciple(it).copy(slotId = slotId) }
                    )
                    discipleDaos.discipleEquipmentDao.upsertAll(
                        batch.map { DiscipleEquipment.fromDisciple(it).copy(slotId = slotId) }
                    )
                    discipleDaos.discipleExtendedDao.upsertAll(
                        batch.map { DiscipleExtended.fromDisciple(it).copy(slotId = slotId) }
                    )
                    discipleDaos.discipleAttributesDao.upsertAll(
                        batch.map { DiscipleAttributes.fromDisciple(it).copy(slotId = slotId) }
                    )
                    database.discipleCompactDao().insertAll(batch.map {
                        DiscipleCompact.fromDisciple(it, gameData.bloodRefinementPctTotals)
                    })
                }
                if (snapshot.equipmentStacks) {
                    itemDaos.equipmentStackDao.upsertAll(equipmentStacks.map { it.copy(slotId = slotId) })
                }
                if (snapshot.equipmentInstances) {
                    itemDaos.equipmentInstanceDao.upsertAll(equipmentInstances.map { it.copy(slotId = slotId) })
                }
                if (snapshot.manualStacks) {
                    itemDaos.manualStackDao.upsertAll(manualStacks.map { it.copy(slotId = slotId) })
                }
                if (snapshot.manualInstances) {
                    itemDaos.manualInstanceDao.upsertAll(manualInstances.map { it.copy(slotId = slotId) })
                }
                if (snapshot.pills) {
                    itemDaos.pillDao.upsertAll(pills.map { it.copy(slotId = slotId) })
                }
                if (snapshot.materials) {
                    itemDaos.materialDao.upsertAll(materials.map { it.copy(slotId = slotId) })
                }
                if (snapshot.herbs) {
                    itemDaos.herbDao.upsertAll(herbs.map { it.copy(slotId = slotId) })
                }
                if (snapshot.seeds) {
                    itemDaos.seedDao.upsertAll(seeds.map { it.copy(slotId = slotId) })
                }
                if (snapshot.storageBags) {
                    itemDaos.storageBagDao.upsertAll(storageBags.map { it.copy(slotId = slotId) })
                }
                if (snapshot.battleLogs) {
                    worldDaos.battleLogDao.upsertAll(battleLogs.map { it.copy(slotId = slotId) })
                }
            }
            // dirty 已在 getAndSet 中原子清空，事务成功则无需再清理
            Log.d(TAG, "Flushed dirty state for slot $slotId (single transaction)")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            // 事务失败：恢复被 getAndSet 清空的脏标记，防止数据永久丢失
            dirty.updateAndGet { current ->
                DirtySet(
                    gameData = current.gameData || snapshot.gameData,
                    disciples = current.disciples || snapshot.disciples,
                    equipmentStacks = current.equipmentStacks || snapshot.equipmentStacks,
                    equipmentInstances = current.equipmentInstances || snapshot.equipmentInstances,
                    manualStacks = current.manualStacks || snapshot.manualStacks,
                    manualInstances = current.manualInstances || snapshot.manualInstances,
                    pills = current.pills || snapshot.pills,
                    materials = current.materials || snapshot.materials,
                    herbs = current.herbs || snapshot.herbs,
                    seeds = current.seeds || snapshot.seeds,
                    storageBags = current.storageBags || snapshot.storageBags,
                    battleLogs = current.battleLogs || snapshot.battleLogs
                )
            }
            Log.e(TAG, "Failed to flush dirty state, restored marks", e)
        }
    }

    suspend fun loadFullState(slotId: Int): FullGameState? {
        return try {
            val gameData = gameDataDao.getGameDataSync(slotId) ?: return null
            val disciples = discipleDaos.discipleDao.getAllSync(slotId)
            val equipmentStacks = itemDaos.equipmentStackDao.getAllSync(slotId)
            val equipmentInstances = itemDaos.equipmentInstanceDao.getAllSync(slotId)
            val manualStacks = itemDaos.manualStackDao.getAllSync(slotId)
            val manualInstances = itemDaos.manualInstanceDao.getAllSync(slotId)
            val pills = itemDaos.pillDao.getAllSync(slotId)
            val materials = itemDaos.materialDao.getAllSync(slotId)
            val herbs = itemDaos.herbDao.getAllSync(slotId)
            val seeds = itemDaos.seedDao.getAllSync(slotId)
            val storageBags = itemDaos.storageBagDao.getAllSync(slotId)
            val battleLogs = worldDaos.battleLogDao.getAllSync(slotId)
            currentSlotId = slotId
            dirty.set(DirtySet())
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
        val battleLogs: List<BattleLog>
    )
}
