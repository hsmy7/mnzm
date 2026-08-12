package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.accessoryNurture
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.armorNurture
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.baseMagicDefense
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.basePhysicalDefense
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.bootsNurture
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.hpVariance
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.magicAttackVariance
import com.xianxia.sect.core.model.magicDefenseVariance
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.mpVariance
import com.xianxia.sect.core.model.physicalAttackVariance
import com.xianxia.sect.core.model.physicalDefenseVariance
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.speedVariance
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.model.weaponNurture
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.domain.disciple.computeMaxAge
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlin.math.roundToInt



/** AI 宗门周期性招募每周期人数下限（含） */
private const val SECT_RECRUIT_MIN_COUNT = 1
/** AI 宗门周期性招募每周期人数上限（含） */
private const val SECT_RECRUIT_MAX_COUNT = 5

@Suppress("LargeClass") // AI 弟子域聚合（生成/装备/修炼/突破/招募/养成，先例 GameData.kt）
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

    /**
     * 每月旬数 = 3（玩家修炼每旬结算一次速率，AI 月度结算按 3 旬等效对齐，
     * 保证同一公式下 AI 与玩家的单位时间修为增速一致）。
     */
    private const val PHASES_PER_MONTH = 3

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
     *
     * [equipmentMapByDisciple] 按弟子 id 索引各自装备实例（模板 id → 实例）——
     * 两名弟子装备同模板不同孕养时不得共享全局 map（2026-08-06 途中发现修复：
     * 原全局 map 以模板 id 为 key，后写者被跳过，孕养差异丢失）。
     */
    data class AIPreparedBattle(
        val disciples: List<Disciple>,
        val equipmentMapByDisciple: Map<String, Map<String, EquipmentInstance>>,
        val manualMap: Map<String, ManualInstance>,
        val proficiencies: Map<String, Map<String, ManualProficiencyData>>
    )

    @Suppress("LongMethod") // 全字段生成（弟子构造参数 20+，逐字段赋值不可再拆分）
    fun generateRandomDisciple(sectName: String, existingNames: Set<String> = emptySet()): Disciple {
        val gender = if (rng.nextInt(2) == 0) "male" else "female"
        val nameResult = NameService.generateName(gender, NameService.NameStyle.XIANXIA, existingNames)
        val spiritRoot = generateSpiritRoot()
        val spiritRootCount = spiritRoot.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> 80 + rng.nextInt(121)
            2 -> 60 + rng.nextInt(141)
            3 -> 40 + rng.nextInt(161)
            4 -> 20 + rng.nextInt(181)
            else -> 1 + rng.nextInt(200)
        }
        // 资质：与悟性一致的按灵根阶梯生成（上界统一 200，防 AI 对抗不对称；避开哨兵 50）
        val aptitude = rollAptitudeByRootCount(spiritRootCount)
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
                intelligence = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                charm = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                loyalty = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.MAX_LOYALTY),
                comprehension = comprehension,
                morality = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                artifactRefining = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                pillRefining = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                spiritPlanting = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                mining = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                teaching = rng.nextGaussian(50.5, 16.5).roundToInt().coerceIn(1, GameConfig.Disciple.SKILL_MAX),
                aptitude = aptitude
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

    /** 资质按灵根阶梯生成（与悟性阶梯一致，上界 200），并避开哨兵值 50 防自愈误判 */
    private fun rollAptitudeByRootCount(spiritRootCount: Int): Int =
        avoidSentinel50(
            when (spiritRootCount) {
                1 -> 80 + rng.nextInt(121)
                2 -> 60 + rng.nextInt(141)
                3 -> 40 + rng.nextInt(161)
                4 -> 20 + rng.nextInt(181)
                else -> 1 + rng.nextInt(200)
            }
        )

    /** 资质生成避开哨兵值 50（==50 强制 +1）：与 [DiscipleTables.healDefaultAptitudes] 收敛逻辑一致，防自愈误判 */
    private fun avoidSentinel50(roll: Int): Int =
        if (roll == DiscipleTables.DEFAULT_APTITUDE) DiscipleTables.DEFAULT_APTITUDE + 1 else roll

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
                weaponNurture = generateInitialNurture(equipmentIds[EquipmentSlot.WEAPON].orEmpty()),
                armorNurture = generateInitialNurture(equipmentIds[EquipmentSlot.ARMOR].orEmpty()),
                bootsNurture = generateInitialNurture(equipmentIds[EquipmentSlot.BOOTS].orEmpty()),
                accessoryNurture = generateInitialNurture(equipmentIds[EquipmentSlot.ACCESSORY].orEmpty())
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
                equipment = equipment.withEquipped(slot, template.id, generateInitialNurture(template.id))
            }
        }
        working = working.copy(equipment = equipment)

        val currentManuals = working.manualIds.size
        if (currentManuals < expectedManuals) {
            val existing = working.manualIds.toSet()
            // 已有心法时补全不再生成心法（避免同弟子多本心法堆叠）；无则补 1 本
            val hasMindManual = existing.any { ManualDatabase.getById(it)?.type == ManualType.MIND }
            val newManuals = generateManuals(maxRarity, expectedManuals, includeMind = !hasMindManual)
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
     * 攻+防池随机选取；[includeMind] 为 true 时恒带 1 本心法（其余攻防补足），
     * 品阶过滤恒等于境界上限品阶。初始熟练度恒为 0（与玩家"刚学功法"NOVICE 一致），
     * 由月度增长（[applyMonthlyProficiencyGain]）随时间提升。
     *
     * @param includeMind 是否强制包含 1 本心法（新弟子必带；补全路径传 false 避免已有心法重复）
     */
    private fun generateManuals(maxRarity: Int, count: Int, includeMind: Boolean = true): List<Pair<String, Int>> {
        val attackManuals = ManualDatabase.getByType(ManualType.ATTACK)
            .filter { it.rarity == maxRarity }
        val defenseManuals = ManualDatabase.getByType(ManualType.DEFENSE)
            .filter { it.rarity == maxRarity }
        val mindManuals = ManualDatabase.getByType(ManualType.MIND)
            .filter { it.rarity == maxRarity }

        val nonMindManuals = (attackManuals + defenseManuals)
            .shuffled(java.util.Random(rng.nextInt().toLong()))
        val selectedMind = if (includeMind && mindManuals.isNotEmpty()) {
            listOf(mindManuals[rng.nextInt(mindManuals.size)])
        } else emptyList()

        val remainingCount = (count - selectedMind.size).coerceAtLeast(0)
        val selected = selectedMind + nonMindManuals.take(remainingCount)

        return selected.map { manual -> Pair(manual.id, 0) }
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

        val equipmentMapByDisciple = mutableMapOf<String, Map<String, EquipmentInstance>>()
        val manualMap = mutableMapOf<String, ManualInstance>()
        val proficiencies = mutableMapOf<String, Map<String, ManualProficiencyData>>()

        for (disciple in disciples) {
            // 每弟子独立装备 map（含各自孕养等级），同模板不同弟子不共享实例
            equipmentMapByDisciple[disciple.id] = buildEquipmentMapForDisciple(disciple)
            val (discipleManuals, discipleProfs) = buildManualDataForDisciple(disciple)
            manualMap.putAll(discipleManuals)
            proficiencies[disciple.id] = discipleProfs
        }

        return AIPreparedBattle(disciples, equipmentMapByDisciple, manualMap, proficiencies)
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

    /** 初始装备孕养数据（2026-08-06 需求：AI 装备从 0 级 0 进度起步，由月度增长温养）。 */
    private fun generateInitialNurture(equipmentId: String): EquipmentNurtureData {
        val template = EquipmentDatabase.getById(equipmentId) ?: return EquipmentNurtureData("", 0)
        return EquipmentNurtureData(
            equipmentId = equipmentId,
            rarity = template.rarity,
            nurtureLevel = 0,
            nurtureProgress = 0.0
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

    // 注：recruitYearlyDisciples 当前无调用方（预留）。周期性招募由年变事件经
    // runSectRecruitmentIfDue 差值判据每 3 年触发一次，本函数自动继承同一数量范围。

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
     * 仅生成周期性招募新弟子列表（不合并现有弟子），供占领路由使用。
     * 由年变事件每 3 年（AI_SECT_RECRUIT_INTERVAL_YEARS，差值判据）触发一次，
     * 每批 [SECT_RECRUIT_MIN_COUNT]~[SECT_RECRUIT_MAX_COUNT] 名炼气弟子。
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
        repeat(SECT_RECRUIT_MIN_COUNT + rng.nextInt(SECT_RECRUIT_MAX_COUNT)) {
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
        return applyGearToDisciple(generateRandomDisciple(sectName, existingNames), sectLevel)
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
                working = applyMonthlyNurtureGain(working)
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
        // NaN/Infinity/负数防御：损坏存档修为异常时归零，避免永久卡死与存档污染
        val baseCultivation = disciple.cultivation
            .takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        // 速率结果防御：DB 配置损坏（功法 cultivationSpeedPercent 为 NaN 等）时归零
        val safeSpeed = cultivationSpeed.takeIf { it.isFinite() } ?: 0.0
        var working = disciple.copy(
            cultivation = baseCultivation + safeSpeed * PHASES_PER_MONTH
        )

        while (working.cultivation >= working.maxCultivation && working.realm > 0) {
            val breakthroughChance = DiscipleStatCalculator.getBreakthroughChance(working)
            if (rng.nextDouble() >= breakthroughChance) {
                working = applyBreakthroughFailure(working)
                break
            }
            working = applyBreakthroughSuccess(working)
        }

        // 大境界变化且品阶上限提升时才刷新装备/功法——炼气→筑基等品阶不变的
        // 突破若重刷会清空已有孕养/熟练度积累（对抗性审查发现），无收益只有损失
        return if (working.realm != disciple.realm &&
            GameConfig.Realm.getMaxRarity(working.realm) >
            GameConfig.Realm.getMaxRarity(disciple.realm)
        ) {
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
                        working.realm, working.talentIds, working.affixIds
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
            libraryBonus = 0.0
        ) * PHASES_PER_MONTH
        val validIds = disciple.manualIds.toSet()
        val updated = disciple.manualMasteries
            .filterKeys { it in validIds }
            .mapValues { (mId, mastery) ->
                if (ManualDatabase.getById(mId) == null) {
                    mastery
                } else {
                    // 防御篡改：负数熟练度钳 0（负值会驻留存档并长期污染），上界不变
                    (mastery + perMonthGain).toInt()
                        .coerceIn(0, ManualProficiencySystem.MAX_PROFICIENCY.toInt())
                }
            }
        return disciple.copy(manualMasteries = updated)
    }

    /**
     * 装备孕养月度增长（2026-08-06 需求：AI 装备孕养度正常增长）。
     *
     * 速率与玩家"每旬自动温养"一致：每月 exp = [EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE] × 3 旬；
     * 升级曲线/上限走 [EquipmentNurtureSystem.getExpRequiredForLevelUp]/[EquipmentNurtureSystem.getMaxNurtureLevel]，
     * 与玩家共用同一套数值（品阶越高升级越慢）。满级后不再增长；空槽位跳过。
     *
     * 老档兼容（对抗性审查修复）：存量弟子 [EquipmentNurtureData] 序列化默认
     * equipmentId=""，此处对"槽位有装备但 nurture 记录为空"的做一次性回填
     * （0 级 0 进度，由模板 id + rarity 初始化），此后正常增长。
     */
    private fun applyMonthlyNurtureGain(disciple: Disciple): Disciple {
        if (!EquipmentDatabase.isInitialized) return disciple
        val monthlyGain = EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE * PHASES_PER_MONTH

        fun growNurture(slotEquipmentId: String, nurture: EquipmentNurtureData): EquipmentNurtureData {
            // 老档回填：槽位有装备但记录为空 → 0 级起步
            val normalized = if (slotEquipmentId.isNotEmpty() && nurture.equipmentId.isEmpty()) {
                generateInitialNurture(slotEquipmentId)
            } else {
                nurture
            }
            // 防御篡改：负数等级钳 0、NaN 进度归零（NaN 比较恒 false 会永久卡死不升级）
            val safeLevel = normalized.nurtureLevel.coerceAtLeast(0)
            val safeProgress = normalized.nurtureProgress.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
            val maxLevel = EquipmentNurtureSystem.getMaxNurtureLevel(normalized.rarity)
            val canGrow = normalized.equipmentId.isNotEmpty() && safeLevel < maxLevel
            if (!canGrow) return normalized
            val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(safeLevel, normalized.rarity)
            val newProgress = safeProgress + monthlyGain
            val newLevel = safeLevel + 1
            return if (newProgress >= expRequired) {
                EquipmentNurtureData(
                    equipmentId = normalized.equipmentId,
                    rarity = normalized.rarity,
                    nurtureLevel = newLevel,
                    nurtureProgress = if (newLevel >= maxLevel) 0.0 else newProgress - expRequired
                )
            } else {
                normalized.copy(nurtureProgress = newProgress)
            }
        }

        val current = disciple.equipment
        val updated = current.copy(
            weaponNurture = growNurture(current.weaponId, current.weaponNurture),
            armorNurture = growNurture(current.armorId, current.armorNurture),
            bootsNurture = growNurture(current.bootsId, current.bootsNurture),
            accessoryNurture = growNurture(current.accessoryId, current.accessoryNurture)
        )
        return if (updated != current) disciple.copy(equipment = updated) else disciple
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
        // 初始 50 名弟子按宗门等级分布境界（小型最高元婴/中型化神/大型合体/顶级仙人，
        // 权重 炼气3/筑基2/金丹2/其余1）；后续周期性招募的新弟子固定炼气一层
        // （2026-08-06 需求，见 generateQiRefiningDisciple）。
        val config = SectLevelConfig.forLevel(sectLevel)

        val disciples = mutableListOf<Disciple>()
        val usedNames = mutableSetOf<String>()

        val normalCount = config.normalMin + rng.nextInt(config.normalMax - config.normalMin + 1)
        val realmDistribution = generateRealmDistribution(normalCount, config.normalMaxRealm)

        realmDistribution.forEach { (realm, count) ->
            repeat(count) {
                val disciple = generateRandomDisciple(sectName, usedNames)
                val adjustedDisciple = applyGearToDisciple(adjustDiscipleRealm(disciple, realm), sectLevel)
                disciples.add(adjustedDisciple)
                usedNames.add(adjustedDisciple.name)
            }
        }

        repeat(config.eliteCount) {
            val disciple = generateRandomDisciple(sectName, usedNames)
            val adjustedDisciple = applyGearToDisciple(adjustDiscipleRealm(disciple, config.eliteRealm), sectLevel)
            disciples.add(adjustedDisciple)
            usedNames.add(adjustedDisciple.name)
        }

        val trimmed = if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
            disciples.sortedByDescending {
                it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp
            }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
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

    /** 按权重分配境界分布（炼气3/筑基2/金丹2/其余1），余数从高权重境界逐个补足。 */
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
     * 新增弟子境界在宗门等级允许范围内按权重随机分配（与初始分布同规则）；
     * 周期性招募的新弟子固定炼气一层（generateQiRefiningDisciple）。
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
                val disciple = generateRandomDisciple(sectName, usedNames)
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
