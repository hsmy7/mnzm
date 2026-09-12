package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.baseMagicDefense
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.basePhysicalDefense
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
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
import com.xianxia.sect.core.domain.disciple.computeMaxAge
import com.xianxia.sect.core.util.NameService
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
    /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
    internal val rng: DeterministicRng get() {
        /** 当前设备的电源管理配置 */
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
    internal const val PHASES_PER_MONTH = 3

    /** statusData 中"已尝试补全体质/词条/天赋"标记（防重复 roll 导致 RNG 漂移） */
    const val GEAR_ROLL_MARKER = "aiGearRolled"

    /** AI 宗门装备数量按宗门等级：小型 1 / 中型 2 / 大型 4 / 顶级 4 */
    internal val EQUIPMENT_COUNT_BY_SECT_LEVEL = mapOf(
        SectLevel.SMALL to 1,
        SectLevel.MEDIUM to 2,
        SectLevel.LARGE to 4,
        SectLevel.TOP to 4
    )

    /** AI 宗门功法数量按宗门等级：小型 1 / 中型 3 / 大型 6 / 顶级 6 */
    internal val MANUAL_COUNT_BY_SECT_LEVEL = mapOf(
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
     * 两名弟子装备同模板不同孕养时不得共享全局 map
     * （全局 map 以模板 id 为 key 会令后写者被跳过，孕养差异丢失）。
     */
    data class AIPreparedBattle(
        val disciples: List<Disciple>,
        val equipmentMapByDisciple: Map<String, Map<String, EquipmentInstance>>,
        val manualMap: Map<String, ManualInstance>,
        val proficiencies: Map<String, Map<String, ManualProficiencyData>>
    )

    // sectName 为语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费。
    // 全字段生成（弟子构造参数 20+，逐字段赋值不可再拆分）
    @Suppress("UnusedParameter", "LongMethod")
    fun generateRandomDisciple(sectName: String, existingNames: Set<String> = emptySet()): Disciple {
        val gender = if (rng.nextInt(2) == 0) "male" else "female"
        // 名字随机源收敛 AI 独立分区 RNG——
        // 原 NameService.generateName 未传 rng 用 JVM 全局 Random（非确定性、
        // 不入 rngStates，跨语言不可对拍，同存档 AI 演化名字不可复现）；
        // 传 rng.asKotlinRandom() 后名字序列存档可重放、C++ 对拍逐字符一致
        val nameResult = NameService.generateName(
            gender, NameService.NameStyle.XIANXIA, existingNames, rng.asKotlinRandom()
        )
        val spiritRoot = generateSpiritRoot()
        val spiritRootCount = spiritRoot.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> 80 + rng.nextInt(21)
            2 -> 60 + rng.nextInt(21)
            3 -> 40 + rng.nextInt(21)
            4 -> 20 + rng.nextInt(21)
            else -> 1 + rng.nextInt(20)
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
        // （见 generateQiRefiningDisciple）。
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

}
