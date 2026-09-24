package com.xianxia.sect.core.engine.domain.battle

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.InventoryNativeForward
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.battleWritebackMaxHpMp
import com.xianxia.sect.core.event.DeathEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton



@Singleton
class CombatService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventBus: EventBusPort,
    // 溢出邮件投递载体（InventoryNativeForward.deliverDraft 消费）
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
    // Native 臂（1780 伤亡残差事务）经 Provider<GameEngineCore>? 注入——
    // Dagger 破环（GameEngineCore 构造链持有本服务）；null = 既有测试直构
    // ⇒ 恒走回退臂（MerchantAndRecruitService 同款注入形态）
    private val gameEngineCoreProvider: Provider<GameEngineCore>? = null
) {

    // ==================== StateFlow 暴露 ====================

    /**
     * Get battle logs StateFlow
     */
    fun getBattleLogs(): StateFlow<List<BattleLog>> = stateStore.battleLogs

    // ==================== 战斗结果处理 ====================

    /**
     * Process battle casualties - update disciples status and handle deaths.
     *
     * ① 重伤写：玩家侧败北 → HP=1 存活；② native 尝试：非战斗路径伤亡残差事务；
     * ③ 幸存者回血：native 未接管时的 HP/MP 兜底回写。
     * 所有状态写入（弟子标记、HP/MP）在单次 [stateStore.update] 事务中完成，
     * 避免中途失败导致数据不一致。
     */
    suspend fun processBattleCasualties(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int> = emptyMap(),
        isOutsideSect: Boolean = true
    ) {
        // ① 重伤（HP=1 存活）：不清槽/不解绑/不清装/不物化行囊。
        if (isOutsideSect && deadMemberIds.isNotEmpty()) {
            stateStore.update {
                applyBattleInjuries(this, deadMemberIds, survivorHpMap, survivorMpMap)
            }
            return
        }
        // ② native 尝试（非战斗路径伤亡残差事务）
        if (deadMemberIds.isNotEmpty() &&
            tryNativeCasualtySettle(deadMemberIds, survivorHpMap, survivorMpMap, isOutsideSect)
        ) {
            clearDeadFromProductionRepository(deadMemberIds)
            return
        }
        // ③ 幸存者回血兜底（native 未接管时）
        stateStore.update {
            applySurvivorHpMpUpdates(
                state = this,
                liveSurvivorUpdates = computeSurvivorUpdates(
                    state = this, survivorHpMap = survivorHpMap,
                    survivorMpMap = survivorMpMap, deadMemberIds = deadMemberIds
                )
            )
        }
    }

    /**
     * G07 玩家侧败北 → **重伤**：气血钳到
     * [com.xianxia.sect.core.GameConfig.Disciple.INJURED_HP] 且保持存活；幸存者照常回写
     * HP/MP。不清槽、不解绑、不清装、不物化行囊、不计年报死亡。回血走既有每旬回血机制。
     */
    private fun applyBattleInjuries(
        state: MutableGameState,
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int>
    ) {
        val injuredIds = deadMemberIds.mapNotNull { it.toIntOrNull() }
            .filter { state.discipleTables.ids.contains(it) }
        for (id in injuredIds) {
            state.discipleTables.markDead(id, currentYear = state.gameData.gameYear, cause = "battle")
        }
        val survivorUpdates = computeSurvivorUpdates(
            state = state, survivorHpMap = survivorHpMap,
            survivorMpMap = survivorMpMap, deadMemberIds = deadMemberIds
        )
        applySurvivorHpMpUpdates(state = state, liveSurvivorUpdates = survivorUpdates)
    }

    /**
     * Native 臂（1780）：C++ 执行伤亡残差状态段（battle_residual_tx.h ①），
     * Kotlin 补平台面：生命日志草稿回写（lifeEvents 类体属性列）+ 死亡事件
     * 广播 + 溢出邮件投递。接管成功返回 true；降级/失败信封返回 false。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tryNativeCasualtySettle(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int>,
        isOutsideSect: Boolean
    ): Boolean {
        if (!NativeEngineFlag.authoritative) return false
        val core = gameEngineCoreProvider?.get() ?: return false
        // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null——
        // 先赋可空局部再判空（handover findings 13，W4-B 转发器同守卫）
        val sync: StateSyncService? = core.stateSyncServiceRef
        if (sync == null) return false
        val reply = GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = ActionIds.BATTLE_CASUALTY_SETTLE_TX,
            paramsJson = params {
                put("deadIds", JsonArray(deadMemberIds.map { JsonPrimitive(it) }))
                put("survivorHp", JsonObject(survivorHpMap.mapValues { (k, v) -> JsonPrimitive(v) }))
                put("survivorMp", JsonObject(survivorMpMap.mapValues { (k, v) -> JsonPrimitive(v) }))
                put("isOutsideSect", isOutsideSect)
            }
        ) as? JsonObject ?: return false

        // ① 生命日志草稿回写（lifeEvents 为 Kotlin 类体属性，C++ 无该列——
        //    disciple_lifecycle_tx logLine 机制同族；本事务信封无填充点时为空）
        applyNativeLifeEventDrafts(reply)
        // ② 死亡事件广播（平台面）
        if (isOutsideSect) emitNativeDeathEvents(deadMemberIds)
        // ③ 袋物化溢出邮件投递（W2-a/S6 同通道）
        deliverNativeOverflowDrafts(reply)
        return true
    }

    /** 信封 lifeEventDrafts → lifeEvents 瞬态列回写（battle_residual_tx 草稿契约）。 */
    private fun applyNativeLifeEventDrafts(reply: JsonObject) {
        val drafts = reply["lifeEventDrafts"] as? JsonArray ?: return
        if (drafts.isEmpty()) return
        stateStore.update {
            for (element in drafts) {
                val obj = element as? JsonObject
                val id = obj?.get("id")?.jsonPrimitive?.intOrNull
                val line = obj?.get("line")?.jsonPrimitive?.contentOrNull
                if (id == null || line == null) continue
                discipleTables.lifeEvents[id] =
                    discipleTables.lifeEvents.getOrDefault(id, emptyList()) + line
            }
        }
    }

    /** 死亡事件广播（DeathEvent：memberId + 姓名 + 死因）。 */
    private fun emitNativeDeathEvents(deadMemberIds: Set<String>) {
        for (memberId in deadMemberIds) {
            val id = memberId.toIntOrNull()
            val name = if (id != null) stateStore.discipleTables.names.getOrDefault(id, "") else ""
            eventBus.emitSync(DeathEvent(memberId, name, "战斗阵亡"))
        }
    }

    /** 信封 overflowDrafts → 溢出邮件投递（InventoryNativeForward 同通道）。 */
    private fun deliverNativeOverflowDrafts(reply: JsonObject) {
        val overflow = reply["overflowDrafts"] as? JsonArray ?: return
        for (element in overflow) {
            val obj = element as? JsonObject ?: continue
            InventoryNativeForward.deliverDraft(inventorySystem, obj)
        }
    }

    /** 幸存者 HP/MP 更新计算（processBattleCasualties 拆分，锁内读取最新状态） */
    private fun computeSurvivorUpdates(
        state: MutableGameState,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int>,
        deadMemberIds: Set<String>
    ): List<SurvivorUpdate> {
        return survivorHpMap.mapNotNull { (memberId, hp) ->
            val id = memberId.toIntOrNull() ?: return@mapNotNull null
            if (!state.discipleTables.ids.contains(id) || memberId in deadMemberIds) return@mapNotNull null
            // clamp 上限用含血炼口径，防削血
            val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(
                state, state.discipleTables.assemble(id)
            )
            val mp = survivorMpMap[memberId] ?: state.discipleTables.currentMps[id]
            val currentStatus = state.discipleTables.statuses[id]
            val updatedStatus = if (currentStatus in setOf(DiscipleStatus.IN_TEAM,
                DiscipleStatus.GARRISONING)) DiscipleStatus.IDLE else currentStatus
            SurvivorUpdate(id, hp.coerceIn(0, finalMaxHp), mp.coerceIn(0, finalMaxMp), updatedStatus)
        }
    }

    /** 幸存者 HP/MP 回写 */
    private fun applySurvivorHpMpUpdates(
        state: MutableGameState,
        liveSurvivorUpdates: List<SurvivorUpdate>
    ) {
        // E. 幸存者HP/MP
        for (su in liveSurvivorUpdates) {
            if (su.id in state.discipleTables.ids) {
                state.discipleTables.currentHps[su.id] = su.hp
                state.discipleTables.currentMps[su.id] = su.mp
            }
        }
    }

    /** 阶段 3 — 跨 Repository 清理：全建筑生产槽移除阵亡弟子 */
    private suspend fun clearDeadFromProductionRepository(deadMemberIds: Set<String>) {
        // 全建筑清理（含进行中工作槽）：弟子已阵亡，生产中断；
        // 只清单一槽位会让炼丹/灵田槽残留（死弟子继续显示在生产界面）。
        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId in deadMemberIds) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    // 幸存者HP/MP更新数据
    private data class SurvivorUpdate(val id: Int, val hp: Int, val mp: Int, val newStatus: DiscipleStatus)

    // ==================== 统计查询 ====================

    /**
     * Get total battles fought
     */
    fun getTotalBattlesCount(): Int {
        return stateStore.battleLogs.value.size
    }

    /**
     * Get recent battle results (last N)
     */
    fun getRecentBattles(count: Int = 10): List<BattleLog> {
        return stateStore.battleLogs.value.take(count)
    }

    /**
     * Get win rate for last N battles
     */
    fun getWinRate(lastNBattles: Int = 50): Double {
        val recentBattles = stateStore.battleLogs.value.take(lastNBattles)
        if (recentBattles.isEmpty()) return 0.0

        val wins = recentBattles.count { it.result == BattleResult.WIN }
        return wins.toDouble() / recentBattles.size
    }
}

