package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.computeMaxAge
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlin.math.roundToInt

object AISectDiscipleManager {
    /**
     * AI RNG — 初始化时由 [initForSlot] 传入存档的系统种子进行确定性播种。
     * 未初始化时以固定 fallback 种子运行（各存档 AI 行为一致但不可与游戏主 PRNG 同步）。
     */
    @Volatile
    private var _rng: DeterministicRng? = null
    private val rng: DeterministicRng get() {
        val current = _rng
        if (current != null) return current
        // 兜底：引擎初始化前已调用时用 fallback 种子
        return DeterministicRng.fromSeed(0xA15EC7A15EC7L)
    }

    /**
     * 使用存档的 [systemSeed] 初始化 AI 分区 RNG。
     * 在 GameEngine 初始化世界/读档时调用，确保 AI 宗门行为在相同存档下可复现。
     *
     * 确定性范围说明：同一存档在相同结算路径下可复现（读档→结算→读档→结算结果一致）。
     * 热控分批（aiNonFocusedBatchMonths 跳月）为预存机制，跳过月份不消耗 RNG——
     * 跨设备/跨热状态的 AI 演化可能不同，属既定行为。
     */
    fun initForSlot(systemSeed: Long) {
        val aiSeed = systemSeed + RngPartition.AI_SECT.id.toLong() * 31337L
        _rng = DeterministicRng.fromSeed(aiSeed)
    }

    /** 每月真实秒数 = 3 旬 × MS_PER_PHASE_1X / 1000 = 6.0s */
    private val SECONDS_PER_MONTH = com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X * 3 / 1000.0

    /** 每月旬数（修炼速度与熟练度月度等效计算用，对齐 SECONDS_PER_MONTH 的 3 旬定义） */
    private const val PROFICIENCY_PHASES_PER_MONTH = 3

    /** MIND 功法选取概率分母（50% 概率带 1 本心法） */
    private const val MIND_MANUAL_ROLL_DENOMINATOR = 2

    /** 功法掌握等级熟练度阈值（对齐 ManualProficiencySystem.MasteryLevel 区间） */
    private const val MASTERY_NOVICE_MAX = 1000
    private const val MASTERY_SMALL_MAX = 10000
    private const val MASTERY_GREAT_MAX = 30000

    /** statusData 中"已尝试补全体质/词条/天赋"标记（防重复 roll 导致 RNG 漂移） */
    const val GEAR_ROLL_MARKER = "aiGearRolled"

    /** AI 宗门装备数量按宗门等级：小型 1 / 中型 2 / 大型 4 / 顶级 4 */
    private val EQUIPMENT_COUNT_BY_SECT_LEVEL = mapOf(
        SectLevel.SMALL to 1,
        SectLevel.MEDIUM to 2,
        SectLevel.LARGE to 4,
        SectLevel.TOP to 4
    )

    /** AI 宗门功法数量按宗门等级：小型 1 / 中型 3 / 大型 6 / 顶级 6 */
    private val MANUAL_COUNT_BY_SECT_LEVEL = mapOf(
        SectLevel.SMALL to 1,
        SectLevel.MEDIUM to 3,
        SectLevel.LARGE to 6,
        SectLevel.TOP to 6
    )

    /**
     * AI 弟子战前准备结果。
     * 包含修改后的弟子副本（带装备/功法 ID）和对应的实例映射。
     */
    data class AIPreparedBattle(
        val disciples: List<Disciple>,
        val equipmentMap: Map<String, EquipmentInstance>,
        val manualMap: Map<String, ManualInstance>,
        val proficiencies: Map<String, Map<String, ManualProficiencyData>>
    )

    fun generateRandomDisciple(sectName: String, maxRealm: Int = 9, existingNames: Set<String> = emptySet()): Disciple {
        val gender = if (rng.nextInt(2) == 0) "male" else "female"
        val nameResult = NameService.generateName(gender, NameService.NameStyle.XIANXIA, existingNames)
        val spiritRoot = generateSpiritRoot()
        val spiritRootCount = spiritRoot.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> 80 + rng.nextInt(21)
            2 -> 60 + rng.nextInt(41)
            3 -> 40 + rng.nextInt(61)
            4 -> 20 + rng.nextInt(81)
            else -> 1 + rng.nextInt(100)
        }
        val hpVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        val mpVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        val physicalAttackVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        val magicAttackVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        val physicalDefenseVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        val magicDefenseVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        val speedVariance = rng.nextGaussian(0.0, 16.667).roundToInt().coerceIn(-50, 50)
        // 天赋/体质/词条三类标签（与 DiscipleFactory.create 同构，走 AI 分区 RNG 保证确定性）
        val talents = TalentDatabase.generateTalentsForDisciple(rng.asKotlinRandom()).map { it.id }
        val physiqueIds = PhysiqueDatabase.generateForDisciple(rng.asKotlinRandom()).map { it.id }
        val affixIds = AffixDatabase.generateForDisciple(rng.asKotlinRandom()).map { it.id }

        // 寿命含天赋 + 词条加成（对齐 DiscipleFactory.create 的 lifespan 计算）
        val talentEffects = TalentDatabase.calculateTalentEffects(talents)
        val affixEffects = AffixDatabase.calculateAffixEffects(affixIds)
        val lifespanBonus =
            (talentEffects["lifespan"] ?: 0.0) + (affixEffects["lifespan"] ?: 0.0)
        val baseLifespan = GameConfig.Realm.get(9).maxAge
        val lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)

        return Disciple(
            id = java.util.UUID.randomUUID().toString(),
            name = nameResult.fullName,
            surname = nameResult.surname,
            gender = gender,
            portraitRes = PortraitPool.getRandomPortrait(gender) { rng.nextInt(it) },
            realm = 9,
            realmLayer = 1,
            cultivation = 0.0,
            spiritRootType = spiritRoot,
            age = 16 + rng.nextInt(14),
            lifespan = lifespan,
            isAlive = true,
            discipleType = "outer",
            talentIds = talents,
            physiqueIds = physiqueIds,
            affixIds = affixIds,
            manualIds = emptyList(),
            manualMasteries = emptyMap(),
            combat = CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            equipment = EquipmentSet(),
            skills = SkillStats(
                intelligence = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                charm = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                loyalty = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                comprehension = comprehension,
                morality = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                artifactRefining = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                pillRefining = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                spiritPlanting = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                mining = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100),
                teaching = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, 100)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed
        }
    }

    private fun generateSpiritRoot(): String = SpiritRootGenerator.generate(rng.asKotlinRandom())

    /**
     * 弟子装备/功法是否达到宗门等级标准数量（老档补全早退判定用）。
     *
     * @param disciple 目标弟子
     * @param sectLevel 宗门等级（0-3）
     * @return 装备数 ≥ 等级配置 且 功法数 ≥ 等级配置
     */
    fun isGearCompleteForLevel(disciple: Disciple, sectLevel: Int): Boolean =
        disciple.equipment.equippedItemIds.size >= (EQUIPMENT_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1) &&
            disciple.manualIds.size >= (MANUAL_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1)

    /**
     * 为缺失的体质/词条/天赋分类生成随机标签（0-3 个），并写入已尝试标记。
     *
     * 标记保证后续读档不再对空分类重复 roll——空是合法状态（0-3 随机可能为 0），
     * 若不标记，每次读档都会重新 roll 并消耗 AI 分区 RNG，导致同档演化序列漂移。
     */
    private fun rollMissingCategories(disciple: Disciple): Disciple {
        var working = disciple
        var rolled = false
        if (working.physiqueIds.isEmpty()) {
            working = working.copy(
                physiqueIds = PhysiqueDatabase.generateForDisciple(rng.asKotlinRandom()).map { it.id }
            )
            rolled = true
        }
        if (working.affixIds.isEmpty()) {
            working = working.copy(
                affixIds = AffixDatabase.generateForDisciple(rng.asKotlinRandom()).map { it.id }
            )
            rolled = true
        }
        if (working.talentIds.isEmpty()) {
            working = working.copy(
                talentIds = TalentDatabase.generateTalentsForDisciple(rng.asKotlinRandom()).map { it.id }
            )
            rolled = true
        }
        // 仅实际 roll 过才写标记（已齐备弟子保持原状，无状态变更）
        return if (rolled) {
            working.copy(
                statusData = (working.statusData ?: emptyMap()) + (GEAR_ROLL_MARKER to "1")
            )
        } else {
            working
        }
    }

    /**
     * 为 AI 弟子完整生成/刷新装备与功法（持久化）。
     *
     * 品阶恒为境界上限品阶（[GameConfig.Realm.getMaxRarity]），
     * 数量按宗门等级（[EQUIPMENT_COUNT_BY_SECT_LEVEL] / [MANUAL_COUNT_BY_SECT_LEVEL]）。
     * 用于新弟子生成与突破大境界后的刷新——保证永远是当前境界最高可用品阶。
     *
     * @param disciple 目标弟子（境界须已定型）
     * @param sectLevel 宗门等级（0-3）
     * @return 带装备/功法字段的弟子副本；registry 未初始化时原样返回
     */
    fun applyGearToDisciple(disciple: Disciple, sectLevel: Int): Disciple {
        if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized) return disciple
        val maxRarity = GameConfig.Realm.getMaxRarity(disciple.realm)
        val equipmentIds = generateEquipmentIds(maxRarity, EQUIPMENT_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1)
        val manuals = generateManuals(maxRarity, MANUAL_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1)
        return disciple.copy(
            manualIds = manuals.map { it.first },
            manualMasteries = manuals.toMap(),
            equipment = disciple.equipment.copy(
                weaponId = equipmentIds[EquipmentSlot.WEAPON].orEmpty(),
                armorId = equipmentIds[EquipmentSlot.ARMOR].orEmpty(),
                bootsId = equipmentIds[EquipmentSlot.BOOTS].orEmpty(),
                accessoryId = equipmentIds[EquipmentSlot.ACCESSORY].orEmpty(),
                weaponNurture = generateRandomNurture(equipmentIds[EquipmentSlot.WEAPON].orEmpty()),
                armorNurture = generateRandomNurture(equipmentIds[EquipmentSlot.ARMOR].orEmpty()),
                bootsNurture = generateRandomNurture(equipmentIds[EquipmentSlot.BOOTS].orEmpty()),
                accessoryNurture = generateRandomNurture(equipmentIds[EquipmentSlot.ACCESSORY].orEmpty())
            )
        )
    }

    /**
     * 只补缺不覆盖：体质/词条/天赋为空则生成，装备/功法不足则补至宗门等级数量。
     *
     * 用于旧档补全与宗门等级升级后的数量补齐，绝不重生成或删除已有项。
     * 体质/词条/天赋为 0-3 随机生成（可能 roll 出 0 个），补全后写入
     * [GEAR_ROLL_MARKER] 标记——防止下次读档对空分类重复 roll 造成
     * AI 分区 RNG 序列漂移（同档两次读档演化结果不一致）。
     *
     * @param disciple 目标弟子
     * @param sectLevel 宗门等级（0-3）
     * @return 补齐后的弟子副本
     */
    fun ensureDiscipleGear(disciple: Disciple, sectLevel: Int): Disciple {
        var working = disciple
        if (working.statusData?.get(GEAR_ROLL_MARKER) != "1") {
            working = rollMissingCategories(working)
        }
        if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized) return working

        val maxRarity = GameConfig.Realm.getMaxRarity(working.realm)
        val expectedEquip = EQUIPMENT_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1
        val expectedManuals = MANUAL_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1

        var equipment = working.equipment
        val currentEquip = equipment.equippedItemIds.size
        if (currentEquip < expectedEquip) {
            val emptySlots = EquipmentSlot.values()
                .filter { slot -> equipment.idFor(slot).isEmpty() }
                .shuffled(java.util.Random(rng.nextInt().toLong()))
            val toAdd = (expectedEquip - currentEquip).coerceAtMost(emptySlots.size)
            repeat(toAdd) { i ->
                val slot = emptySlots[i]
                val template = pickEquipmentTemplate(slot, maxRarity) ?: return@repeat
                equipment = equipment.withEquipped(slot, template.id, generateRandomNurture(template.id))
            }
        }
        working = working.copy(equipment = equipment)

        val currentManuals = working.manualIds.size
        if (currentManuals < expectedManuals) {
            val existing = working.manualIds.toSet()
            val newManuals = generateManuals(maxRarity, expectedManuals)
                .filter { it.first !in existing }
                .take(expectedManuals - currentManuals)
            working = working.copy(
                manualIds = working.manualIds + newManuals.map { it.first },
                manualMasteries = working.manualMasteries + newManuals.toMap()
            )
        }
        return working
    }

    /** 按境界上限品阶从槽位模板池选取装备（无该品阶时取槽位最高品阶兜底）。 */
    private fun pickEquipmentTemplate(
        slot: EquipmentSlot,
        maxRarity: Int
    ): EquipmentDatabase.EquipmentTemplate? {
        val allSlotTemplates = EquipmentDatabase.getBySlot(slot)
        return if (allSlotTemplates.isEmpty()) {
            null
        } else {
            val exact = allSlotTemplates.filter { it.rarity == maxRarity }
            if (exact.isNotEmpty()) {
                exact[rng.nextInt(exact.size)]
            } else {
                allSlotTemplates.maxByOrNull { it.rarity }
            }
        }
    }

    /** 随机选取 [count] 个装备槽位并生成境界上限品阶装备 id，返回 槽位 → 模板 id 映射。 */
    private fun generateEquipmentIds(maxRarity: Int, count: Int): Map<EquipmentSlot, String> {
        if (count <= 0) return emptyMap()
        val slots = EquipmentSlot.values().toList()
            .shuffled(java.util.Random(rng.nextInt().toLong()))
            .take(count)
        return slots.mapNotNull { slot ->
            pickEquipmentTemplate(slot, maxRarity)?.let { Pair(slot, it.id) }
        }.toMap()
    }

    /**
     * 生成 [count] 本功法（模板 id + 初始熟练度）。
     *
     * 沿用原战前随机选取逻辑：攻+防池、50% 概率带 1 本心法、其余补足；
     * 品阶过滤改为恒等于境界上限品阶。
     */
    private fun generateManuals(maxRarity: Int, count: Int): List<Pair<String, Int>> {
        val attackManuals = ManualDatabase.getByType(ManualType.ATTACK)
            .filter { it.rarity == maxRarity }
        val defenseManuals = ManualDatabase.getByType(ManualType.DEFENSE)
            .filter { it.rarity == maxRarity }
        val mindManuals = ManualDatabase.getByType(ManualType.MIND)
            .filter { it.rarity == maxRarity }

        val nonMindManuals = (attackManuals + defenseManuals)
            .shuffled(java.util.Random(rng.nextInt().toLong()))
        val selectedMind = if (mindManuals.isNotEmpty() && rng.nextInt(MIND_MANUAL_ROLL_DENOMINATOR) == 0) {
            listOf(mindManuals[rng.nextInt(mindManuals.size)])
        } else emptyList()

        val remainingCount = (count - selectedMind.size).coerceAtLeast(0)
        val selected = selectedMind + nonMindManuals.take(remainingCount)

        return selected.map { manual -> Pair(manual.id, randomMasteryProficiency()) }
    }

    /** 随机初始功法熟练度（NOVICE..PERFECTION 各段区间取值）。 */
    private fun randomMasteryProficiency(): Int {
        val maxMasteryLevel = ManualProficiencySystem.MasteryLevel.values().last().level
        val masteryLevel = ManualProficiencySystem.MasteryLevel.fromLevel(rng.nextInt(maxMasteryLevel + 1))
        return when (masteryLevel) {
            ManualProficiencySystem.MasteryLevel.NOVICE ->
                (rng.nextDouble() * MASTERY_NOVICE_MAX).toInt()
            ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS ->
                (MASTERY_NOVICE_MAX +
                    rng.nextDouble() * (MASTERY_SMALL_MAX - MASTERY_NOVICE_MAX)).toInt()
            ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS ->
                (MASTERY_SMALL_MAX +
                    rng.nextDouble() * (MASTERY_GREAT_MAX - MASTERY_SMALL_MAX)).toInt()
            ManualProficiencySystem.MasteryLevel.PERFECTION -> MASTERY_GREAT_MAX
        }
    }

    /** 读取指定槽位已装备的模板 id。 */
    private fun EquipmentSet.idFor(slot: EquipmentSlot): String = when (slot) {
        EquipmentSlot.WEAPON -> weaponId
        EquipmentSlot.ARMOR -> armorId
        EquipmentSlot.BOOTS -> bootsId
        EquipmentSlot.ACCESSORY -> accessoryId
    }

    /** 将装备写入指定槽位（含孕养数据）。 */
    private fun EquipmentSet.withEquipped(
        slot: EquipmentSlot,
        id: String,
        nurture: EquipmentNurtureData
    ): EquipmentSet = when (slot) {
        EquipmentSlot.WEAPON -> copy(weaponId = id, weaponNurture = nurture)
        EquipmentSlot.ARMOR -> copy(armorId = id, armorNurture = nurture)
        EquipmentSlot.BOOTS -> copy(bootsId = id, bootsNurture = nurture)
        EquipmentSlot.ACCESSORY -> copy(accessoryId = id, accessoryNurture = nurture)
    }

    /**
     * 为 AI 弟子列表准备战斗数据（读取持久化的装备/功法字段）。
     *
     * AI 弟子装备/功法已在生成与突破刷新时持久化（模板 id + 熟练度），
     * 本函数仅按模板构建临时实例映射供战斗使用，不修改原弟子。
     * 丹药/血炼不计入。
     *
     * @param disciples AI 弟子列表
     * @return 包含原弟子列表和装备/功法实例映射的 [AIPreparedBattle]
     */
    fun prepareDisciplesForBattle(disciples: List<Disciple>): AIPreparedBattle {
        if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized) {
            return AIPreparedBattle(disciples, emptyMap(), emptyMap(), emptyMap())
        }

        val equipmentMap = mutableMapOf<String, EquipmentInstance>()
        val manualMap = mutableMapOf<String, ManualInstance>()
        val proficiencies = mutableMapOf<String, Map<String, ManualProficiencyData>>()

        for (disciple in disciples) {
            equipmentMap.putAll(buildEquipmentMapForDisciple(disciple))
            val (discipleManuals, discipleProfs) = buildManualDataForDisciple(disciple)
            manualMap.putAll(discipleManuals)
            proficiencies[disciple.id] = discipleProfs
        }

        return AIPreparedBattle(disciples, equipmentMap, manualMap, proficiencies)
    }

    /**
     * 从持久化字段构建单个弟子的装备实例映射（模板 id → 临时实例 + 孕养覆盖）。
     *
     * 供 [prepareDisciplesForBattle] 与 AISectAttackManager 战斗组装复用。
     */
    fun buildEquipmentMapForDisciple(disciple: Disciple): Map<String, EquipmentInstance> {
        val equipmentMap = mutableMapOf<String, EquipmentInstance>()
        buildEquipmentEntry(equipmentMap, disciple.equipment.weaponId, disciple.equipment.weaponNurture)
        buildEquipmentEntry(equipmentMap, disciple.equipment.armorId, disciple.equipment.armorNurture)
        buildEquipmentEntry(equipmentMap, disciple.equipment.bootsId, disciple.equipment.bootsNurture)
        buildEquipmentEntry(equipmentMap, disciple.equipment.accessoryId, disciple.equipment.accessoryNurture)
        return equipmentMap
    }

    /**
     * 从持久化字段构建单个弟子的功法实例映射 + 熟练度数据。
     *
     * 功法实例以模板 id 为实例 id（AI 侧不落玩家实例表），
     * 熟练度从 [Disciple.manualMasteries] 转换（[ManualProficiencyData] 语义）。
     */
    fun buildManualDataForDisciple(
        disciple: Disciple
    ): Pair<Map<String, ManualInstance>, Map<String, ManualProficiencyData>> {
        val manualMap = mutableMapOf<String, ManualInstance>()
        for (mId in disciple.manualIds) {
            if (mId !in manualMap) {
                val template = ManualDatabase.getById(mId) ?: continue
                manualMap[mId] = ManualDatabase.createFromTemplate(template)
                    .toInstance(id = mId)
            }
        }
        return Pair(manualMap, buildProficiencyDataFromMasteries(disciple))
    }

    /**
     * 将 [Disciple.manualMasteries]（模板 id → 熟练度）转换为
     * 修炼/战斗可用的 [ManualProficiencyData] 映射（manualId → 数据）。
     */
    fun buildProficiencyDataFromMasteries(disciple: Disciple): Map<String, ManualProficiencyData> {
        return disciple.manualIds.associateWith { mId ->
            val mastery = disciple.manualMasteries[mId] ?: 0
            val manual = ManualDatabase.getById(mId)
            val masteryLevel = if (manual != null) {
                ManualProficiencySystem.MasteryLevel.fromProficiency(mastery.toDouble()).level
            } else 0
            val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
            ManualProficiencyData(
                manualId = mId,
                // 下界防护：损坏存档负熟练度归零（负值会放大 NOVICE 加成语义）
                proficiency = mastery.toDouble().coerceIn(0.0, maxProf.toDouble()),
                maxProficiency = maxProf,
                masteryLevel = masteryLevel
            )
        }
    }

    /** 向装备映射中添加单件装备条目（如已存在则跳过）。 */
    internal fun buildEquipmentEntry(
        equipmentMap: MutableMap<String, EquipmentInstance>,
        eqId: String,
        nurture: EquipmentNurtureData
    ) {
        if (eqId.isEmpty() || eqId in equipmentMap) return
        val template = EquipmentDatabase.getById(eqId) ?: return
        var instance = EquipmentDatabase.createFromTemplate(template).toInstance(id = eqId)
        if (nurture.equipmentId == eqId) {
            instance = instance.copy(
                nurtureLevel = nurture.nurtureLevel,
                nurtureProgress = nurture.nurtureProgress
            )
        }
        equipmentMap[eqId] = instance
    }

    private fun generateRandomNurture(equipmentId: String): EquipmentNurtureData {
        val template = EquipmentDatabase.getById(equipmentId) ?: return EquipmentNurtureData("", 0)
        val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(template.rarity)
        val nurtureLevel = rng.nextInt(maxLevel + 1)
        return EquipmentNurtureData(
            equipmentId = equipmentId,
            rarity = template.rarity,
            nurtureLevel = nurtureLevel,
            nurtureProgress = if (nurtureLevel >= maxLevel) 0.0 else rng.nextDouble() * EquipmentNurtureSystem.getExpRequiredForLevelUp(nurtureLevel, template.rarity)
        )
    }

    fun recruitYearlyDisciples(
        sectName: String,
        existingDisciples: List<Disciple>,
        sectLevel: Int = SectLevel.SMALL
    ): List<Disciple> {
        val newDisciples = generateYearlyRecruits(sectName, existingDisciples, sectLevel)
        return truncateToLimit(existingDisciples + newDisciples)
    }

    /**
     * 按战力降序截断至 [PlantSlotData.MAX_AI_DISCIPLES_PER_SECT]，供年度招募路径复用，
     * 防止 AI 宗门弟子池无界累积。
     */
    fun truncateToLimit(disciples: List<Disciple>): List<Disciple> =
        if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            disciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }
                .take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            disciples
        }

    /**
     * 仅生成年度新弟子列表（不合并现有弟子），供占领路由使用
     *
     * @param sectLevel 宗门等级（决定新弟子装备/功法数量）
     */
    fun generateYearlyRecruits(
        sectName: String,
        existingDisciples: List<Disciple>,
        sectLevel: Int = SectLevel.SMALL
    ): List<Disciple> {
        val newDisciples = mutableListOf<Disciple>()
        val usedNames = existingDisciples.map { it.name }.toMutableSet()
        repeat(rng.nextInt(7)) {
            val disciple = generateQiRefiningDisciple(sectName, usedNames, sectLevel)
            newDisciples.add(disciple)
            usedNames.add(disciple.name)
        }
        return newDisciples
    }

    private fun generateQiRefiningDisciple(
        sectName: String,
        existingNames: Set<String>,
        sectLevel: Int
    ): Disciple {
        return applyGearToDisciple(generateRandomDisciple(sectName, 9, existingNames), sectLevel)
    }

    fun processMonthlyCultivation(
        disciples: List<Disciple>,
        batchMonths: Int = 1,
        sectLevel: Int = SectLevel.SMALL
    ): List<Disciple> {
        // 与同文件其他函数一致：registry 未初始化时优雅降级（功法查询会抛异常）
        if (batchMonths <= 0 || disciples.isEmpty() || !ManualDatabase.isInitialized) {
            return disciples
        }

        return disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            var working = disciple
            repeat(batchMonths) {
                working = settleMonthlyCultivation(working, sectLevel)
                working = applyMonthlyProficiencyGain(working)
            }
            working
        }
    }

    /**
     * 单月修炼结算：修炼加速（吃功法/体质/词条加成）→ 突破判定（完整乘区）→
     * 大境界突破成功时按新境界刷新装备/功法（永远最高可用品阶）。
     */
    private fun settleMonthlyCultivation(disciple: Disciple, sectLevel: Int): Disciple {
        val cultivationSpeed = DiscipleStatCalculator.calculateCultivationPerPhase(
            disciple,
            manuals = emptyMap(),
            manualProficiencies = buildProficiencyDataFromMasteries(disciple),
            buildingBonus = 1.0,
            preachingElderBonus = 0.0,
            preachingMastersBonus = 0.0,
            cultivationSubsidyBonus = 0.0
        )
        // NaN/Infinity 防御：损坏存档修为异常时归零，避免永久卡死与存档污染
        val baseCultivation = disciple.cultivation.takeIf { it.isFinite() } ?: 0.0
        var working = disciple.copy(
            cultivation = baseCultivation + cultivationSpeed * SECONDS_PER_MONTH
        )

        while (working.cultivation >= working.maxCultivation && working.realm > 0) {
            val breakthroughChance = DiscipleStatCalculator.getBreakthroughChance(working)
            if (rng.nextDouble() >= breakthroughChance) {
                working = applyBreakthroughFailure(working)
                break
            }
            working = applyBreakthroughSuccess(working)
        }

        return if (working.realm != disciple.realm) {
            applyGearToDisciple(working, sectLevel)
        } else {
            working
        }
    }

    /** 突破成功：修为清零、层数+1 或大境界+1（对齐玩家 applyBreakthroughSuccess）。 */
    private fun applyBreakthroughSuccess(d: Disciple): Disciple {
        var working = d.copy(cultivation = 0.0)
        val oldRealm = working.realm
        working = if (working.realmLayer < GameConfig.Realm.get(working.realm).maxLayers) {
            working.copy(realmLayer = working.realmLayer + 1)
        } else {
            working.copy(realm = working.realm - 1, realmLayer = 1)
        }
        return if (working.realm != oldRealm) {
            working.copy(
                lifespan = working.lifespan +
                    DiscipleStatCalculator.calculateBreakthroughLifespanGain(
                        working.realm, working.talentIds
                    )
            )
        } else {
            working
        }
    }

    /**
     * 突破失败：修为清零 + HP/MP 打一折（对齐玩家 applyBreakthroughFailure）。
     *
     * AI 弟子无持续战斗资源状态（currentHp 恒为 -1 满血语义，战斗全恢复且不回写），
     * 此时跳过 HP/MP 惩罚，避免向存档写入 10% 血量的失真值并随俘虏流入玩家池。
     */
    private fun applyBreakthroughFailure(d: Disciple): Disciple {
        val hasRealHpState = d.combat.currentHp >= 0 || d.combat.currentMp >= 0
        if (!hasRealHpState) return d.copy(cultivation = 0.0)
        val curHp = if (d.combat.currentHp < 0) d.maxHp else d.combat.currentHp
        val curMp = if (d.combat.currentMp < 0) d.maxMp else d.combat.currentMp
        return d.copy(
            cultivation = 0.0,
            combat = d.combat.copy(
                currentHp = (curHp * DiscipleStatCalculator.BREAKTHROUGH_FAILURE_HP_MP_RATIO)
                    .toInt().coerceAtLeast(1),
                currentMp = (curMp * DiscipleStatCalculator.BREAKTHROUGH_FAILURE_HP_MP_RATIO)
                    .toInt().coerceAtLeast(1)
            )
        )
    }

    /**
     * 功法熟练度月度等效增长（对齐玩家每旬公式，1 月 = 3 旬）。
     * AI 无藏经阁建筑 → libraryBonus = 0；上限 MAX_PROFICIENCY；
     * 只保留 manualIds 中的键，清理残留/孤儿熟练度条目（防存档冗余累积）。
     */
    private fun applyMonthlyProficiencyGain(disciple: Disciple): Disciple {
        if (!ManualDatabase.isInitialized || disciple.manualIds.isEmpty()) return disciple
        val perMonthGain = ManualProficiencySystem.calculateProficiencyGainPerPhase(
            disciple.skills.comprehension,
            libraryBonus = 0.0
        ) * PROFICIENCY_PHASES_PER_MONTH
        val validIds = disciple.manualIds.toSet()
        val updated = disciple.manualMasteries
            .filterKeys { it in validIds }
            .mapValues { (mId, mastery) ->
                if (ManualDatabase.getById(mId) == null) {
                    mastery
                } else {
                    (mastery + perMonthGain).toInt()
                        .coerceAtMost(ManualProficiencySystem.MAX_PROFICIENCY.toInt())
                }
            }
        return disciple.copy(manualMasteries = updated)
    }

    fun processAging(disciples: List<Disciple>): List<Disciple> {
        return disciples.map { disciple ->
            val newAge = disciple.age + 1
            val maxAge = disciple.computeMaxAge()
            val isAlive = newAge <= maxAge

            disciple.copy(
                age = newAge,
                isAlive = isAlive
            )
        }.filter { it.isAlive }
    }

    fun initializeSectDisciples(sectName: String, sectLevel: Int): Pair<List<Disciple>, Int> {
        val config = SectLevelConfig.forLevel(sectLevel)

        val disciples = mutableListOf<Disciple>()
        val usedNames = mutableSetOf<String>()

        val normalCount = config.normalMin + rng.nextInt(config.normalMax - config.normalMin + 1)
        val realmDistribution = generateRealmDistribution(normalCount, config.normalMaxRealm)

        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sectName, config.normalMaxRealm, usedNames)
                val adjustedDisciple = applyGearToDisciple(adjustDiscipleRealm(disciple, realm), sectLevel)
                disciples.add(adjustedDisciple)
                usedNames.add(adjustedDisciple.name)
            }
        }

        repeat(config.eliteCount) {
            val disciple = generateRandomDisciple(sectName, config.eliteRealm, usedNames)
            val adjustedDisciple = applyGearToDisciple(adjustDiscipleRealm(disciple, config.eliteRealm), sectLevel)
            disciples.add(adjustedDisciple)
            usedNames.add(adjustedDisciple.name)
        }

        val trimmed = if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            disciples.sortedByDescending { it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
        } else {
            disciples
        }

        return Pair(trimmed, config.sectMaxRealm)
    }

    private data class SectLevelConfig(
        val normalMin: Int,
        val normalMax: Int,
        val normalMaxRealm: Int,
        val eliteCount: Int,
        val eliteRealm: Int,
        val sectMaxRealm: Int
    ) {
        companion object {
            fun forLevel(level: Int): SectLevelConfig {
                val maxRealm = SectLevel.maxRealmForLevel(level)
                return SectLevelConfig(
                    normalMin = 50, normalMax = 50,
                    normalMaxRealm = maxRealm,
                    eliteCount = 0, eliteRealm = maxRealm,
                    sectMaxRealm = maxRealm
                )
            }
        }
    }

    private fun generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()

        val realmRange = (maxRealm + 1)..9
        if (realmRange.isEmpty()) return distribution

        val weights = realmRange.associateWith { realm ->
            when (realm) {
                9 -> 3
                8 -> 2
                7 -> 2
                else -> 1
            }
        }
        val totalWeight = weights.values.sum()

        var assigned = 0
        for (realm in realmRange) {
            val weight = weights[realm] ?: 1
            val count = (total * weight / totalWeight)
            distribution[realm] = count
            assigned += count
        }

        var remaining = total - assigned
        if (remaining > 0) {
            val sortedRealms = realmRange.sortedByDescending { weights[it] ?: 1 }
            for (realm in sortedRealms) {
                if (remaining <= 0) break
                distribution[realm] = (distribution[realm] ?: 0) + 1
                remaining--
            }
        }

        return distribution
    }

    /**
     * 旧存档兼容：将 AI 宗门弟子补充至目标数量。
     * 新增弟子境界在宗门等级允许范围内随机分配。
     *
     * @param sectName 宗门名称
     * @param existingDisciples 现有弟子列表
     * @param targetCount 目标弟子总数（如 50）
     * @param sectLevel 宗门等级（用于境界上限）
     * @return 补满后的弟子列表
     */
    fun fillDisciplesToTarget(
        sectName: String,
        existingDisciples: List<Disciple>,
        targetCount: Int,
        sectLevel: Int
    ): List<Disciple> {
        // 已满员时仍补全存量弟子缺失的体质/词条/装备/功法（老档升级路径，只补缺不覆盖）
        if (existingDisciples.size >= targetCount) {
            return existingDisciples.map { ensureDiscipleGear(it, sectLevel) }
        }

        val maxRealm = SectLevel.maxRealmForLevel(sectLevel)
        val usedNames = existingDisciples.map { it.name }.toMutableSet()
        val newDisciples = mutableListOf<Disciple>()

        val fillCount = targetCount - existingDisciples.size
        val realmDistribution = generateRealmDistribution(fillCount, maxRealm)

        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sectName, maxRealm, usedNames)
                val adjusted = applyGearToDisciple(adjustDiscipleRealm(disciple, realm), sectLevel)
                newDisciples.add(adjusted)
                usedNames.add(adjusted.name)
            }
        }

        return existingDisciples.map { ensureDiscipleGear(it, sectLevel) } + newDisciples
    }

    private fun adjustDiscipleRealm(disciple: Disciple, targetRealm: Int): Disciple {
        if (targetRealm == 9) return disciple

        val baseLifespan = GameConfig.Realm.get(targetRealm).maxAge
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val lifespanBonus = talentEffects["lifespan"] ?: 0.0
        val newLifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
        val maxLayer = GameConfig.Realm.get(targetRealm).maxLayers

        return disciple.copy(
            realm = targetRealm,
            realmLayer = 1 + rng.nextInt(maxLayer),
            cultivation = rng.nextDouble() * 0.8 * GameConfig.Realm.get(targetRealm).cultivationBase,
            lifespan = newLifespan,
            // 高境界配合理年龄（防"38岁炼虚"类数据；炼气 realm=9 不调整）
            age = maxOf(disciple.age, GameConfig.Realm.minReasonableAge(targetRealm))
        )
    }
}
