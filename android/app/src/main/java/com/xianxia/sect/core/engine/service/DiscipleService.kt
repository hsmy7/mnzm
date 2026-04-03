package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import java.util.UUID
import kotlin.random.Random

/**
 * 弟子服务 - 负责弟子管理和状态机
 *
 * 职责域：
 * - 弟子 CRUD 操作
 * - 弟子状态机管理（培养/闭关/出战/探索等）
 * - disciples StateFlow 管理
 * - 弟子招募和培养
 * - 职位分配
 * - 道侣系统
 */
class DiscipleService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "DiscipleService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get disciples StateFlow
     */
    fun getDisciples(): StateFlow<List<Disciple>> = _disciples

    // ==================== 弟子 CRUD ====================

    /**
     * Add new disciple
     */
    fun addDisciple(disciple: Disciple) {
        _disciples.value = _disciples.value + disciple
    }

    /**
     * Remove disciple by ID
     */
    fun removeDisciple(discipleId: String): Boolean {
        val current = _disciples.value
        val filtered = current.filter { it.id != discipleId }
        if (filtered.size < current.size) {
            _disciples.value = filtered
            return true
        }
        return false
    }

    /**
     * Get disciple by ID
     */
    fun getDiscipleById(discipleId: String): Disciple? {
        return _disciples.value.find { it.id == discipleId }
    }

    /**
     * Update disciple
     */
    fun updateDisciple(disciple: Disciple) {
        _disciples.value = _disciples.value.map {
            if (it.id == disciple.id) disciple else it
        }
    }

    // ==================== 弟子状态管理 ====================

    /**
     * Get disciple status based on current assignments
     */
    fun getDiscipleStatus(discipleId: String): DiscipleStatus {
        val data = _gameData.value
        val disciple = _disciples.value.find { it.id == discipleId } ?: return DiscipleStatus.IDLE

        if (!disciple.isAlive) return DiscipleStatus.DEAD
        if (disciple.status == DiscipleStatus.REFLECTING) return DiscipleStatus.REFLECTING

        // Check battle team
        val battleTeam = data.battleTeam
        if (battleTeam != null && battleTeam.status != "idle") {
            if (battleTeam.slots.any { it.discipleId == discipleId }) {
                return DiscipleStatus.BATTLE
            }
        }

        // Check exploration teams
        if (_isInExploration(discipleId)) return DiscipleStatus.EXPLORING

        // Check cave exploration teams
        if (_isInCaveExploration(discipleId)) return DiscipleStatus.EXPLORING

        // Check elder slots
        val elderSlots = data.elderSlots
        if (elderSlots.lawEnforcementElder == discipleId ||
            elderSlots.lawEnforcementDisciples.any { it.discipleId == discipleId } ||
            elderSlots.lawEnforcementReserveDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.LAW_ENFORCING
        }
        if (elderSlots.preachingElder == discipleId ||
            elderSlots.preachingMasters.any { it.discipleId == discipleId }) {
            return DiscipleStatus.PREACHING
        }
        if (elderSlots.qingyunPreachingElder == discipleId ||
            elderSlots.qingyunPreachingMasters.any { it.discipleId == discipleId }) {
            return DiscipleStatus.PREACHING
        }

        // Check management positions
        if (elderSlots.viceSectMaster == discipleId ||
            elderSlots.outerElder == discipleId ||
            elderSlots.innerElder == discipleId ||
            elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MANAGING
        }

        // Check work positions
        if (elderSlots.forgeElder == discipleId) return DiscipleStatus.FORGING
        if (elderSlots.alchemyElder == discipleId) return DiscipleStatus.ALCHEMY
        if (elderSlots.herbGardenElder == discipleId) return DiscipleStatus.FARMING

        // Check library slots
        if (data.librarySlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.STUDYING
        }

        // Check spirit mine slots
        if (data.spiritMineSlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MINING
        }

        // Growing children
        if (disciple.realmLayer == 0) return DiscipleStatus.GROWING

        return DiscipleStatus.IDLE
    }

    /**
     * Sync all disciples' status based on their assignments
     */
    fun syncAllDiscipleStatuses() {
        val data = _gameData.value
        val elderSlots = data.elderSlots

        // Build sets of assigned disciple IDs for each status
        val lawEnforcerIds = mutableSetOf<String>()
        elderSlots.lawEnforcementElder?.let { lawEnforcerIds.add(it) }
        elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }.forEach { lawEnforcerIds.add(it) }

        val preachingIds = mutableSetOf<String>()
        elderSlots.preachingElder?.let { preachingIds.add(it) }
        elderSlots.qingyunPreachingElder?.let { preachingIds.add(it) }
        elderSlots.preachingMasters.mapNotNull { it.discipleId }.forEach { preachingIds.add(it) }
        elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }.forEach { preachingIds.add(it) }

        val managingIds = mutableSetOf<String>()
        elderSlots.viceSectMaster?.let { managingIds.add(it) }
        elderSlots.outerElder?.let { managingIds.add(it) }
        elderSlots.innerElder?.let { managingIds.add(it) }
        elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }.forEach { managingIds.add(it) }

        val forgingIds = mutableSetOf<String>()
        elderSlots.forgeElder?.let { forgingIds.add(it) }

        val alchemyIds = mutableSetOf<String>()
        elderSlots.alchemyElder?.let { alchemyIds.add(it) }

        val farmingIds = mutableSetOf<String>()
        elderSlots.herbGardenElder?.let { farmingIds.add(it) }

        val studyingIds = data.librarySlots.mapNotNull { it.discipleId }.toMutableSet()

        val miningIds = data.spiritMineSlots.mapNotNull { it.discipleId }.toMutableSet()

        // Battle team
        val battleIds = mutableSetOf<String>()
        val battleTeam = data.battleTeam
        if (battleTeam != null && battleTeam.status != "idle") {
            battleTeam.slots.mapNotNull { it.discipleId }.forEach { battleIds.add(it) }
        }

        // Exploration teams
        val exploringIds = mutableSetOf<String>()
        // This would be populated from exploration service

        _disciples.value = _disciples.value.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            if (disciple.status == DiscipleStatus.REFLECTING) return@map disciple

            val newStatus = when {
                battleIds.contains(disciple.id) -> DiscipleStatus.BATTLE
                exploringIds.contains(disciple.id) -> DiscipleStatus.EXPLORING
                lawEnforcerIds.contains(disciple.id) -> DiscipleStatus.LAW_ENFORCING
                preachingIds.contains(disciple.id) -> DiscipleStatus.PREACHING
                managingIds.contains(disciple.id) -> DiscipleStatus.MANAGING
                forgingIds.contains(disciple.id) -> DiscipleStatus.FORGING
                alchemyIds.contains(disciple.id) -> DiscipleStatus.ALCHEMY
                farmingIds.contains(disciple.id) -> DiscipleStatus.FARMING
                studyingIds.contains(disciple.id) -> DiscipleStatus.STUDYING
                miningIds.contains(disciple.id) -> DiscipleStatus.MINING
                disciple.realmLayer == 0 -> DiscipleStatus.GROWING
                else -> DiscipleStatus.IDLE
            }

            if (disciple.status != newStatus) {
                disciple.copy(status = newStatus)
            } else {
                disciple
            }
        }
    }

    /**
     * Reset all disciples to IDLE status
     * Used when resetting game state or disbanding all teams
     */
    fun resetAllDisciplesStatus() {
        val data = _gameData.value
        val discipleIdsToReset = mutableSetOf<String>()

        // Collect disciple IDs from various sources
        // 1. Building slots
        data.forgeSlots.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }

        // 2. Spirit mine slots
        data.spiritMineSlots.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }

        // 3. Library slots
        data.librarySlots.forEach { slot ->
            slot.discipleId?.let { discipleIdsToReset.add(it) }
        }

        // 4. Elder slots
        val elderSlots = data.elderSlots
        elderSlots.viceSectMaster?.let { discipleIdsToReset.add(it) }
        elderSlots.herbGardenElder?.let { discipleIdsToReset.add(it) }
        elderSlots.alchemyElder?.let { discipleIdsToReset.add(it) }
        elderSlots.forgeElder?.let { discipleIdsToReset.add(it) }
        elderSlots.outerElder?.let { discipleIdsToReset.add(it) }
        elderSlots.preachingElder?.let { discipleIdsToReset.add(it) }
        elderSlots.lawEnforcementElder?.let { discipleIdsToReset.add(it) }
        elderSlots.innerElder?.let { discipleIdsToReset.add(it) }
        elderSlots.qingyunPreachingElder?.let { discipleIdsToReset.add(it) }

        // Collect from position slots
        elderSlots.preachingMasters.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.lawEnforcementDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.lawEnforcementReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.qingyunPreachingMasters.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.herbGardenDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.alchemyDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.forgeDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.herbGardenReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.alchemyReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.forgeReserveDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }
        elderSlots.spiritMineDeaconDisciples.forEach { slot -> slot.discipleId?.let { discipleIdsToReset.add(it) } }

        // Update disciple statuses (exclude reflecting disciples)
        _disciples.value = _disciples.value.map { disciple ->
            if (discipleIdsToReset.contains(disciple.id) && disciple.status != DiscipleStatus.REFLECTING) {
                disciple.copy(status = DiscipleStatus.IDLE)
            } else {
                disciple
            }
        }
    }

    // ==================== 弟子培养 ====================

    /**
     * Recruit new disciple
     */
    fun recruitDisciple(): Disciple {
        val id = UUID.randomUUID().toString()
        val gender = if (Random.nextBoolean()) "male" else "female"

        val surnames = listOf("李", "张", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴")
        val maleNames = listOf("逍遥", "无忌", "长生", "问道", "清风", "明月", "玄真", "道尘")
        val femaleNames = listOf("月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾")

        val surname = surnames.random()
        val name = if (gender == "male") maleNames.random() else femaleNames.random()

        val spiritRootType = listOf("metal", "wood", "water", "fire", "earth").random()

        val hpVariance = Random.nextInt(-50, 51)
        val mpVariance = Random.nextInt(-50, 51)
        val physicalAttackVariance = Random.nextInt(-50, 51)
        val magicAttackVariance = Random.nextInt(-50, 51)
        val physicalDefenseVariance = Random.nextInt(-50, 51)
        val magicDefenseVariance = Random.nextInt(-50, 51)
        val speedVariance = Random.nextInt(-50, 51)

        val disciple = Disciple(
            id = id,
            name = "$surname$name",
            gender = gender,
            age = Random.nextInt(16, 30),
            realm = 9, // Start at Qi Refining
            realmLayer = Random.nextInt(1, 10),
            spiritRootType = spiritRootType,
            status = DiscipleStatus.IDLE,
            combat = com.xianxia.sect.core.model.CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            social = com.xianxia.sect.core.model.SocialData(),
            skills = com.xianxia.sect.core.model.SkillStats(
                intelligence = Random.nextInt(30, 81),
                charm = Random.nextInt(30, 81),
                loyalty = Random.nextInt(70, 101),
                morality = Random.nextInt(30, 80)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            baseHp = baseStats.baseHp
            baseMp = baseStats.baseMp
            basePhysicalAttack = baseStats.basePhysicalAttack
            baseMagicAttack = baseStats.baseMagicAttack
            basePhysicalDefense = baseStats.basePhysicalDefense
            baseMagicDefense = baseStats.baseMagicDefense
            baseSpeed = baseStats.baseSpeed
        }

        // Set recruitment time
        val data = _gameData.value
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        disciple.recruitedMonth = currentMonthValue

        addDisciple(disciple)
        addEvent("新弟子 ${disciple.name} 加入宗门", EventType.SUCCESS)

        return disciple
    }

    /**
     * Expel disciple from sect
     */
    fun expelDisciple(discipleId: String): Boolean {
        val disciple = getDiscipleById(discipleId) ?: return false

        // Cannot expel if not idle
        if (!disciple.isAlive) {
            addEvent("${disciple.name} 已死亡，无法逐出", EventType.WARNING)
            return false
        }

        // Clear from all assignments
        clearDiscipleFromAllSlots(discipleId)

        // Remove equipment
        disciple.weaponId?.let { unequipEquipment(discipleId, it) }
        disciple.armorId?.let { unequipEquipment(discipleId, it) }
        disciple.bootsId?.let { unequipEquipment(discipleId, it) }
        disciple.accessoryId?.let { unequipEquipment(discipleId, it) }

        // Remove disciple
        removeDisciple(discipleId)

        addEvent("已将 ${disciple.name} 逐出宗门", EventType.INFO)
        return true
    }

    fun sendToReflection(discipleId: String): Boolean {
        val disciple = getDiscipleById(discipleId) ?: return false

        if (!disciple.isAlive) {
            addEvent("${disciple.name} 已死亡，无法关押", EventType.WARNING)
            return false
        }

        if (disciple.status == DiscipleStatus.REFLECTING) {
            addEvent("${disciple.name} 已在思过崖中", EventType.WARNING)
            return false
        }

        clearDiscipleFromAllSlots(discipleId)

        val currentYear = _gameData.value.gameYear
        val endYear = currentYear + 10

        _disciples.value = _disciples.value.map {
            if (it.id == discipleId) it.copy(
                status = DiscipleStatus.REFLECTING,
                statusData = it.statusData + mapOf(
                    "reflectionStartYear" to currentYear.toString(),
                    "reflectionEndYear" to endYear.toString()
                )
            ) else it
        }

        addEvent("执法堂已将 ${disciple.name} 关入思过崖，${endYear - currentYear}年后释放", EventType.INFO)
        return true
    }

    // ==================== 装备管理 ====================

    /**
     * Equip equipment to disciple
     */
    fun equipEquipment(discipleId: String, equipmentId: String): Boolean {
        val discipleIndex = _disciples.value.indexOfFirst { it.id == discipleId }
        if (discipleIndex < 0) return false

        val equipment = _equipment.value.find { it.id == equipmentId } ?: return false
        val disciple = _disciples.value[discipleIndex]

        // Check realm requirement
        if (disciple.realm > equipment.minRealm) {
            addEvent("${disciple.name} 境界不足，无法装备 ${equipment.name}", EventType.WARNING)
            return false
        }

        // Unequip existing equipment in same slot
        val updatedDisciple = when (equipment.slot) {
            EquipmentSlot.WEAPON -> {
                disciple.weaponId?.let { unequipEquipment(discipleId, it) }
                disciple.copyWith(weaponId = equipmentId)
            }
            EquipmentSlot.ARMOR -> {
                disciple.armorId?.let { unequipEquipment(discipleId, it) }
                disciple.copyWith(armorId = equipmentId)
            }
            EquipmentSlot.BOOTS -> {
                disciple.bootsId?.let { unequipEquipment(discipleId, it) }
                disciple.copyWith(bootsId = equipmentId)
            }
            EquipmentSlot.ACCESSORY -> {
                disciple.accessoryId?.let { unequipEquipment(discipleId, it) }
                disciple.copyWith(accessoryId = equipmentId)
            }
            else -> disciple
        }

        _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = updatedDisciple }

        // Mark equipment as equipped
        _equipment.value = _equipment.value.map {
            if (it.id == equipmentId) it.copy(isEquipped = true) else it
        }

        addEvent("${disciple.name} 装备了 ${equipment.name}", EventType.INFO)
        return true
    }

    /**
     * Unequip equipment from disciple
     */
    fun unequipEquipment(discipleId: String, equipmentId: String): Boolean {
        val discipleIndex = _disciples.value.indexOfFirst { it.id == discipleId }
        if (discipleIndex < 0) return false

        val disciple = _disciples.value[discipleIndex]
        val updatedDisciple = when {
            disciple.weaponId == equipmentId -> disciple.copyWith(weaponId = null)
            disciple.armorId == equipmentId -> disciple.copyWith(armorId = null)
            disciple.bootsId == equipmentId -> disciple.copyWith(bootsId = null)
            disciple.accessoryId == equipmentId -> disciple.copyWith(accessoryId = null)
            else -> disciple
        }

        if (updatedDisciple != disciple) {
            _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = updatedDisciple }

            // Mark equipment as unequipped
            _equipment.value = _equipment.value.map {
                if (it.id == equipmentId) it.copy(isEquipped = false) else it
            }

            return true
        }
        return false
    }

    // ==================== 辅助方法 ====================

    /**
     * Clear disciple from all slots and assignments
     */
    fun clearDiscipleFromAllSlots(discipleId: String) {
        synchronized(transactionMutex) {
            val data = _gameData.value

            // Clear from forge slots
            val updatedForgeSlots = data.forgeSlots.filter { it.discipleId != discipleId }

            // Clear from spirit mine slots
            val updatedSpiritMineSlots = data.spiritMineSlots.map {
                if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "") else it
            }

            // Clear from library slots
            val updatedLibrarySlots = data.librarySlots.map {
                if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "") else it
            }

            // Clear from elder slots
            val updatedElderSlots = clearDiscipleFromElderSlots(data.elderSlots, discipleId)

            _gameData.value = data.copy(
                forgeSlots = updatedForgeSlots,
                spiritMineSlots = updatedSpiritMineSlots,
                librarySlots = updatedLibrarySlots,
                elderSlots = updatedElderSlots
            )

            // 触发执法堂自动补位（弟子离任后从储备池补位）
            autoFillLawEnforcementSlots()
        }
    }

    /**
     * Clear disciple from elder slots
     */
    private fun clearDiscipleFromElderSlots(slots: ElderSlots, discipleId: String): ElderSlots {
        var updated = slots

        if (updated.viceSectMaster == discipleId) updated = updated.copy(viceSectMaster = null)
        if (updated.herbGardenElder == discipleId) updated = updated.copy(herbGardenElder = null)
        if (updated.alchemyElder == discipleId) updated = updated.copy(alchemyElder = null)
        if (updated.forgeElder == discipleId) updated = updated.copy(forgeElder = null)
        if (updated.outerElder == discipleId) updated = updated.copy(outerElder = null)
        if (updated.preachingElder == discipleId) updated = updated.copy(preachingElder = null)
        if (updated.lawEnforcementElder == discipleId) updated = updated.copy(lawEnforcementElder = null)
        if (updated.innerElder == discipleId) updated = updated.copy(innerElder = null)
        if (updated.qingyunPreachingElder == discipleId) updated = updated.copy(qingyunPreachingElder = null)

        // Clear from position slots
        updated = updated.copy(
            preachingMasters = updated.preachingMasters.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            herbGardenDisciples = updated.herbGardenDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            }
        )

        return updated
    }

    /**
     * 执法堂自动补位
     *
     * 当执法弟子槽位出现空缺时，从储备弟子池中按优先级自动选取候选人补位。
     *
     * 补位规则：
     * - 仅对 lawEnforcementDisciples 的 8 个槽位（index 0-7）进行补位，不自动填补执法长老
     * - 候选人排序优先级：智力降序 → 境界升序
     * - 按槽位 index 从小到大依次填补
     * - 一个储备弟子只能填补一个槽位
     * - 仅选取存活且存在于 disciples 列表中的储备弟子
     *
     * @return 成功补位的数量
     */
    fun autoFillLawEnforcementSlots(): Int {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        val activeSlots = elderSlots.lawEnforcementDisciples
        val reserveSlots = elderSlots.lawEnforcementReserveDisciples

        // 收集空缺槽位（仅处理前 8 个槽位）
        val emptySlotIndices = (0 until 8).filter { index ->
            index >= activeSlots.size || activeSlots[index].discipleId == null
        }

        if (emptySlotIndices.isEmpty()) return 0

        // 从储备池中筛选有效候选人：存活且存在于 disciples 列表中
        val candidates = reserveSlots
            .mapNotNull { slot ->
                val discipleId = slot.discipleId ?: return@mapNotNull null
                val disciple = _disciples.value.find { it.id == discipleId }
                if (disciple != null && disciple.isAlive) {
                    Triple(discipleId, slot.discipleName, disciple)
                } else null
            }
            // 排序：智力降序 → 境界升序（realm 越小境界越高）
            .sortedWith(compareByDescending<Triple<String, String, Disciple>> { it.third.intelligence }
                .thenBy { it.third.realm })

        if (candidates.isEmpty()) return 0

        var fillCount = 0
        var updatedActiveSlots = activeSlots.toMutableList()
        var updatedReserveSlots = reserveSlots.toMutableList()
        val usedReserveIds = mutableSetOf<String>()

        // 确保活跃槽位列表至少有 8 个元素
        while (updatedActiveSlots.size < 8) {
            updatedActiveSlots.add(DirectDiscipleSlot(index = updatedActiveSlots.size))
        }

        for (slotIndex in emptySlotIndices) {
            if (fillCount >= candidates.size) break

            // 找到第一个未被使用的最优候选人
            val candidate = candidates.find { (discipleId, _, _) -> discipleId !in usedReserveIds } ?: break
            val (discipleId, discipleName, disciple) = candidate
            usedReserveIds.add(discipleId)

            // 构建新的活跃槽位
            updatedActiveSlots[slotIndex] = DirectDiscipleSlot(
                index = slotIndex,
                discipleId = discipleId,
                discipleName = discipleName,
                discipleRealm = disciple.realmName,
                discipleSpiritRootColor = disciple.spiritRoot.countColor
            )

            // 从储备池中移除该弟子
            updatedReserveSlots = updatedReserveSlots.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            }.toMutableList()

            addEvent("执法堂自动补位：$discipleName 接任第${slotIndex + 1}号执法弟子", EventType.INFO)
            fillCount++
        }

        if (fillCount > 0) {
            _gameData.value = data.copy(
                elderSlots = elderSlots.copy(
                    lawEnforcementDisciples = updatedActiveSlots.toList(),
                    lawEnforcementReserveDisciples = updatedReserveSlots.toList()
                )
            )

            // 同步补位弟子的状态为 LAW_ENFORCING
            syncAllDiscipleStatuses()
        }

        return fillCount
    }

    /**
     * Check if disciple is in exploration team
     */
    private fun _isInExploration(discipleId: String): Boolean {
        // Would check with exploration service
        return false
    }

    /**
     * Check if disciple is in cave exploration team
     */
    private fun _isInCaveExploration(discipleId: String): Boolean {
        val data = _gameData.value
        return data.caveExplorationTeams.any { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == CaveExplorationStatus.TRAVELING || team.status == CaveExplorationStatus.EXPLORING)
        }
    }

    /**
     * Check if disciple is assigned to spirit mine
     */
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean {
        val data = _gameData.value
        val inMinerSlots = data.spiritMineSlots.any { it.discipleId == discipleId }
        val inDeaconSlots = data.elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }
        return inMinerSlots || inDeaconSlots
    }

    /**
     * Get alive disciples count
     */
    fun getAliveDisciplesCount(): Int {
        return _disciples.value.count { it.isAlive }
    }

    /**
     * Get disciples by status
     */
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> {
        return _disciples.value.filter { it.status == status && it.isAlive }
    }

    /**
     * Get idle disciples
     */
    fun getIdleDisciples(): List<Disciple> {
        return getDisciplesByStatus(DiscipleStatus.IDLE)
    }

    // ==================== DiscipleAggregate 查询接口（渐进式迁移支持）====================

    /**
     * 获取单个弟子的聚合数据
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 单表实体转换而来。
     *
     * @param discipleId 弟子 ID
     * @return 完整的 DiscipleAggregate 实例，如果弟子不存在则返回 null
     */
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? {
        val disciple = getDiscipleById(discipleId) ?: return null
        return disciple.toAggregate()
    }

    /**
     * 获取所有弟子的聚合数据列表
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 列表批量转换而来。
     *
     * @return 所有弟子的 DiscipleAggregate 列表
     */
    fun getAllDiscipleAggregates(): List<DiscipleAggregate> {
        return _disciples.value.map { it.toAggregate() }
    }

    /**
     * Update monthly salary enabled/disabled for a realm
     */
    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        val data = _gameData.value
        val newEnabled = data.monthlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        _gameData.value = data.copy(monthlySalaryEnabled = newEnabled)
    }
}
