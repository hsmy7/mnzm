package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.domain.disciple.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.EventBusPort
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("DiscipleLifecycleProcessor")
class DiscipleLifecycleProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val scopeProvider: CoroutineScopeProvider,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventBus: EventBusPort,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    private val lawEnforcementProcessor: javax.inject.Provider<LawEnforcementProcessor>,
    private val discipleStatusService: DiscipleStatusService
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "DiscipleLifecycle"
        private const val CULL_DEAD_AFTER_YEARS = 1
        private const val REFLECTION_RELEASE_MORALITY_BONUS = 5
        private const val REFLECTION_RELEASE_LOYALTY_BONUS = 5
    }

    // ── 弟子老化/死亡 ──────────────────────────────────────────────────

    fun processGriefExpiry(currentYear: Int) {
        stateStore.update {
            val updated = discipleTables.assembleAll().map { disciple ->
                val griefEnd = disciple.social.griefEndYear
                if (griefEnd != null && currentYear >= griefEnd) {
                    disciple.copy(social = disciple.social.copy(griefEndYear = null))
                } else {
                    disciple
                }
            }
            discipleTables.replaceAll(updated)
        }
    }

    fun processDiscipleAging(currentYear: Int) {
        // 从组件表读取，不依赖 Flow（防止 Flow 缺失数据被 replaceAll 永久覆盖）
        val currentList = stateStore.discipleTables.assembleAll()
        val deadThisYear = mutableSetOf<Int>()
        val deadDiscipleData = mutableMapOf<Int, Disciple>() // id → aged disciple snapshot

        // 第一阶段：判断生死、标记回生
        for (disciple in currentList) {
            if (!disciple.isAlive) continue

            var agedDisciple = disciple.copy(age = disciple.age + 1)

            if (agedDisciple.age == 5 && agedDisciple.realmLayer == 0) {
                agedDisciple = agedDisciple.copy(realmLayer = 1, status = DiscipleStatus.IDLE)
            }

            val talentEffects = TalentDatabase.calculateTalentEffects(agedDisciple.talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val realmMaxAge = GameConfig.Realm.get(agedDisciple.realm).maxAge
            val talentLifespan = (realmMaxAge * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
            val maxAge = maxOf(agedDisciple.lifespan, realmMaxAge, talentLifespan)
            if (agedDisciple.age >= maxAge) {
                val intId = disciple.id.toIntOrNull() ?: continue
                deadThisYear.add(intId)
                deadDiscipleData[intId] = agedDisciple
            }
        }

        if (deadThisYear.isEmpty()) {
            // 无死亡，仅更新活弟子字段（单事务）
            stateStore.update {
                for (disciple in currentList) {
                    val id = disciple.id.toIntOrNull() ?: continue
                    if (!disciple.isAlive) continue
                    val agedAge = disciple.age + 1
                    discipleTables.ages[id] = agedAge
                    if (agedAge == 5 && disciple.realmLayer == 0) {
                        discipleTables.realmLayers[id] = 1
                    }
                }
            }
            discipleStatusService.syncAllDiscipleStatuses()
            return
        }

        // 第二阶段+第三阶段合并：单事务内完成死亡处理+活弟子字段更新
        // 消除原两阶段间的 TOCTOU 窗口（handleDiscipleDeath 的事务与 Phase3 事务之间）
        stateStore.update {
            for ((id, agedDisciple) in deadDiscipleData) {
                // ── 槽位清理（事务内版本，替换 handleDiscipleDeath 的独立 update）──
                gameData = discipleSlotCleanup.clearAllSlots(gameData, agedDisciple.id, includeResidence = true)

                // ── 哀悼期传播 + 伴侣/师徒解绑 ──
                val griefUpdated = propagateGriefToRelatives(currentList, agedDisciple, currentYear)
                unbindPartnerRelationship(griefUpdated, agedDisciple)
                unbindMasterRelationships(griefUpdated, agedDisciple)

                val lifeEventsToWrite = computeBereavementLifeEvents(
                    griefUpdated, currentList, agedDisciple, discipleTables
                )

                discipleTables.replaceAll(griefUpdated)
                discipleTables.deathYears[id] = currentYear

                lifeEventsToWrite.forEach { (grievingId, event) ->
                    val prevEvents = discipleTables.lifeEvents.getOrDefault(grievingId, emptyList())
                    discipleTables.lifeEvents[grievingId] = prevEvents + event
                }

                // ── 血炼清理 ──
                gameData = gameData.copy(
                    bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals - agedDisciple.id,
                    bloodRefinements = gameData.bloodRefinements - agedDisciple.id
                )

                // ── 装备/功法清除 ──
                val deleteEquipIds = mutableSetOf<String>()
                agedDisciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
                agedDisciple.equipment.armorId?.let { deleteEquipIds.add(it) }
                agedDisciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
                agedDisciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
                val deleteManualIds = agedDisciple.manualIds.toSet()

                equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
                manualInstances = manualInstances.filter { it.id !in deleteManualIds }

                // ── 死亡记录 ──
                discipleTables.addDeathRecord(DeathRecord(
                    id = id, name = agedDisciple.name, surname = agedDisciple.surname,
                    realm = agedDisciple.realm, realmLayer = agedDisciple.realmLayer,
                    deathAge = agedDisciple.age, deathYear = currentYear, cause = "age"
                ))
                discipleTables.remove(id)
                // remove 会清空 deathYears，重新写入以保留记录
                discipleTables.deathYears[id] = currentYear

                recordGameEvent(
                    GameEventCategory.SECT, GameEventType.DEATH,
                    "${agedDisciple.name}陨落（寿元耗尽）",
                    agedDisciple.id, agedDisciple.name
                )
                gameData = gameData.copy(
                    annualDeceasedDisciples = gameData.annualDeceasedDisciples + 1
                )
            }

            // ── 活弟子字段更新 ──
            for (disciple in currentList) {
                val id = disciple.id.toIntOrNull() ?: continue
                if (id in deadThisYear || !disciple.isAlive) continue
                val agedAge = disciple.age + 1
                discipleTables.ages[id] = agedAge
                if (agedAge == 5 && disciple.realmLayer == 0) {
                    discipleTables.realmLayers[id] = 1
                }
            }
        }

        discipleStatusService.syncAllDiscipleStatuses()

        // ── 事务外操作：forge 槽位清理 + 事件分发（非原子操作，不影响游戏状态一致性）──
        for ((id, agedDisciple) in deadDiscipleData) {
            clearForgeSlotsIfNeeded(agedDisciple.id)
            eventBus.emitSync(DeathEvent(
                discipleId = agedDisciple.id,
                discipleName = agedDisciple.name,
                cause = "age",
                deathYear = currentYear
            ))
        }
    }

    fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        clearDiscipleFromAllSlots(disciple.id)

        // 从组件表读取，不依赖 Flow（同 processDiscipleAging 修复模式，防止 Flow 缺失数据被 replaceAll 永久覆盖）
        val originalList = stateStore.discipleTables.assembleAll()
        val currentYear = stateStore.gameData.value.gameYear

        val griefUpdated = propagateGriefToRelatives(originalList, disciple, currentYear)
        unbindPartnerRelationship(griefUpdated, disciple)
        unbindMasterRelationships(griefUpdated, disciple)
        val tables = stateStore.discipleTables
        val lifeEventsToWrite = computeBereavementLifeEvents(griefUpdated, originalList, disciple, tables)

        // 收集要删除的装备/功法 ID（不论内外都是直接删除）
        val deleteEquipIds = mutableSetOf<String>()
        disciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
        disciple.equipment.armorId?.let { deleteEquipIds.add(it) }
        disciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
        disciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
        val deleteManualIds = disciple.manualIds.toSet()

        // 单事务写入：弟子表 + 血炼清理 + 装备/功法清除
        stateStore.update {
            discipleTables.replaceAll(griefUpdated)
            val idInt = disciple.id.toInt()
            discipleTables.deathYears[idInt] = currentYear
            lifeEventsToWrite.forEach { (grievingId, event) ->
                val prevEvents = discipleTables.lifeEvents.getOrDefault(grievingId, emptyList())
                discipleTables.lifeEvents[grievingId] = prevEvents + event
            }
            gameData = gameData.copy(
                bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals - disciple.id,
                bloodRefinements = gameData.bloodRefinements - disciple.id
            )
            equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
            manualInstances = manualInstances.filter { it.id !in deleteManualIds }
            recordGameEvent(
                GameEventCategory.SECT, GameEventType.DEATH,
                "${disciple.name}陨落（${if (isOutsideSect) "战斗" else "寿元耗尽"}）",
                disciple.id, disciple.name
            )
            gameData = gameData.copy(
                annualDeceasedDisciples = gameData.annualDeceasedDisciples + 1
            )
        }

        if (isOutsideSect) removeProficiencies(disciple.id)

        eventBus.emitSync(DeathEvent(
            discipleId = disciple.id,
            discipleName = disciple.name,
            cause = if (isOutsideSect) "combat" else "age",
            deathYear = currentYear
        ))
    }

    // ── 以下为 handleDiscipleDeath 的拆分子函数 ────────────────────────────

    private fun propagateGriefToRelatives(
        currentList: List<Disciple>, disciple: Disciple, year: Int
    ): MutableList<Disciple> = DiscipleStatCalculator.applyGriefToRelatives(
        currentList, listOf(disciple), year
    ).toMutableList()

    private fun unbindPartnerRelationship(griefUpdated: MutableList<Disciple>, disciple: Disciple) {
        val partnerId = disciple.social.partnerId ?: return
        val partnerIndex = griefUpdated.indexOfFirst { it.id == partnerId }
        if (partnerIndex >= 0) {
            griefUpdated[partnerIndex] = griefUpdated[partnerIndex].copy(
                social = griefUpdated[partnerIndex].social.copy(partnerId = null)
            )
        }
    }

    private fun unbindMasterRelationships(griefUpdated: MutableList<Disciple>, disciple: Disciple) {
        val deadId = disciple.id
        griefUpdated.indices.forEach { i ->
            if (griefUpdated[i].social.masterId == deadId) {
                griefUpdated[i] = griefUpdated[i].copy(
                    social = griefUpdated[i].social.copy(masterId = null)
                )
            }
        }
    }

    private fun computeBereavementLifeEvents(
        griefUpdated: List<Disciple>,
        originalList: List<Disciple>,
        deceased: Disciple,
        tables: DiscipleTables
    ): Map<Int, String> {
        val events = mutableMapOf<Int, String>()
        val deadId = deceased.id
        for (grievingD in griefUpdated) {
            val originalD = originalList.find { it.id == grievingD.id } ?: continue
            val wasGrieving = originalD.social.griefEndYear != null
            val isNowGrieving = grievingD.social.griefEndYear != null
            if (wasGrieving || !isNowGrieving) continue

            val relationship = deduceRelationship(originalD, deadId)
            val grievingId = grievingD.id.toIntOrNull() ?: continue
            val grievingAge = tables.ages[grievingId]
            events[grievingId] = "${grievingAge}岁：因${relationship}${deceased.name}离世陷入悲痛，修炼速度降低50%"
        }
        return events
    }

    private fun deduceRelationship(disciple: Disciple, deadId: String): String = when {
        disciple.social.partnerId == deadId -> "道侣"
        deadId == disciple.social.partnerId -> "道侣"
        disciple.social.parentId1 == deadId || disciple.social.parentId2 == deadId -> "父/母"
        deadId == disciple.social.parentId1 || deadId == disciple.social.parentId2 -> "子女"
        else -> "亲属"
    }

    private fun removeProficiencies(discipleId: String) {
        val data = stateStore.gameData.value
        val updated = data.manualProficiencies.toMutableMap()
        updated.remove(discipleId)
        if (updated != data.manualProficiencies) {
            stateStore.update {
                gameData = gameData.copy(manualProficiencies = updated)
            }
        }
    }

    fun processYearlyAging(currentYear: Int) {
        val cullThreshold = currentYear - CULL_DEAD_AFTER_YEARS
        stateStore.update { discipleTables.cullDeadDisciples(cullThreshold) }
    }

    fun processReflectionRelease(year: Int) {
        stateStore.update {
            val currentList = discipleTables.assembleAll()
            val reflectingDisciples = currentList.filter { it.status == DiscipleStatus.REFLECTING && it.isAlive }
            if (reflectingDisciples.isEmpty()) return@update

            val updatedDisciples = currentList.map { disciple ->
                if (disciple.status != DiscipleStatus.REFLECTING || !disciple.isAlive) return@map disciple

                val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: return@map disciple
                if (year < endYear) return@map disciple

                disciple.copy(
                    status = DiscipleStatus.IDLE,
                    statusData = disciple.statusData - "reflectionStartYear" - "reflectionEndYear",
                    skills = disciple.skills.copy(
                        morality = disciple.skills.morality + REFLECTION_RELEASE_MORALITY_BONUS,
                        loyalty = disciple.skills.loyalty + REFLECTION_RELEASE_LOYALTY_BONUS
                    )
                )
            }
            // ★ 字段级更新替代 clear+insert：GameStateStoreImpl 事务中
            // _discipleTables 引用不变，clear+insert 后 !（引用不等）检测不触发，
            // mutationVersion 虽变化但 batchEmission/reentrantBuffer 路径可能跳过。
            // 直接写字段确保值落盘且 mutationVersion 正确递增。
            for (d in updatedDisciples) {
                val id = d.id.toIntOrNull() ?: continue
                discipleTables.statuses[id] = d.status
                discipleTables.statusData[id] = d.statusData
                discipleTables.moralities[id] = d.skills.morality
                discipleTables.loyalties[id] = d.skills.loyalty
                // 道德降低后即时触发偷盗判定（事务内版本）
                if (d.skills.morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
                    lawEnforcementProcessor.get().processSingleDiscipleTheft(id, this)
                }
            }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    fun clearDiscipleFromAllSlots(discipleId: String) {
        // 在 update 锁内完成清理，避免 TOCTOU（锁外读取 gameData 再用整块覆写会丢其他并发写入）
        stateStore.update {
            gameData = discipleSlotCleanup.clearAllSlots(gameData, discipleId, includeResidence = true)
        }

        // 清理生产槽位（DAO 操作不能放入 stateStore.update，异常时无法回滚内存已写入的清理）
        // 此处异常不会导致数据不一致：内存中 residence/slots 已清理，DB 残留引用由下次 validate 修复
        clearForgeSlotsIfNeeded(discipleId)
    }

    private fun clearForgeSlotsIfNeeded(discipleId: String) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BUILDING_FORGE)
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId) {
                // 同步阻塞执行 DAO 写，消除 scope.launch 跨线程竞态
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    productionSlotRepository.updateSlotByBuildingId(BUILDING_FORGE, slot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    }
                }
            }
        }
    }

    fun returnEquipmentToWarehouse(equipmentId: String) {
        val currentInstances = stateStore.equipmentInstances.value
        val eq = currentInstances.find { it.id == equipmentId } ?: return
        val stack = eq.toStack()
        val currentStacks = stateStore.equipmentStacks.value
        val existingStack = currentStacks.find {
            it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot
        }
        stateStore.update {
            if (existingStack != null) {
                val maxQty = inventoryConfig.getMaxStackSize(ITEM_TYPE_EQUIPMENT_STACK)
                val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxQty)
                equipmentStacks = equipmentStacks.map { s ->
                    if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                }
            } else {
                equipmentStacks = equipmentStacks + stack
            }
            equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
        }
    }

    fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        stateStore.update {
            equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
        }
    }
}

/** 弟子死亡事件 */
data class DeathEvent(
    val discipleId: String,
    val discipleName: String,
    val cause: String,
    val deathYear: Int,
    override val type: String = "disciple.death"
) : DomainEvent
