package com.xianxia.sect.data

import android.util.Log
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton





@Singleton
class GameStateRepository @Inject constructor(
    private val gameDataDao: GameDataDao,
    private val discipleDaos: DiscipleDaos,
    private val itemDaos: ItemDaos,
    private val worldDaos: WorldDaos
) {
    companion object {
        private const val TAG = "GameStateRepository"
    }

    @Volatile
    private var currentSlotId: Int = 0

    fun setActiveSlot(slotId: Int) {
        currentSlotId = slotId
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
