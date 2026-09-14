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
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlin.math.roundToInt



/** AI 宗门周期性招募每周期人数下限（含） */
private const val SECT_RECRUIT_MIN_COUNT = 1
/** AI 宗门周期性招募每周期人数上限（含） */
private const val SECT_RECRUIT_MAX_COUNT = 5

/**
 * AI 流种子步长：`aiSeed = seed + AI_SECT.id(6) × 31337`。
 *
 * **与 C++ `GameCore::aiRng_` 的播种式同源**（`game_core.cpp` 的 initialize /
 * rngInitSystemSeed / importStateInternal 三处均用同一常量）——改此值会让两侧
 * 分叉，必须同步改 C++（C++ 侧为字面量 `static_cast<int64_t>(6) * 31337LL`）。
 */
private const val AI_SECT_SEED_STRIDE = 31337L

@Suppress("LargeClass") // AI 弟子域聚合（生成/装备/修炼/突破/招募/养成，先例 GameData.kt）
object AISectDiscipleManager {
    /**
     * AI 随机源解析器（**非自持流**——R5 禁止自建随机源，本字段只做"接入真源"）。
     *
     * 注入后 [rng] 按当前 RNG 模式解析：
     * - 委托模式（AUTHORITATIVE）→ [RngPartition.AI_SECT_MIRROR]：其
     *   `NativeBackedRng(9)` 直达 C++ `GameCore::aiRng_` 本体 ⇒ Kotlin AI 抽取与
     *   C++ 月结/年结消费的 AI 流**同源同序**（读档续接一致）
     * - 非委托（OFF/SHADOW 回退）→ [RngPartition.AI_SECT]（本地 PCG，种子
     *   `systemSeed + 6`，与 C++ `aiRng_` 的 `seed + 6×31337` 同为确定性流）
     *
     * 注入时机：生产经 [GameEngine] 构造时 [initialize]；测试可注入固定种子实例。
     */
    @Volatile
    private var rngManager: GameRngManager? = null

    /**
     * 无管理器时的兜底流（**仅为兼容旧测试 API**，生产恒走 [initialize] 注入）。
     *
     * 由 [initForSlot] 播种；未播种时用固定 fallback 种子——保证"同输入同输出"，
     * 不引入任何非确定性来源（旧实现在此处的缺陷是**每次访问都新建同种子实例**，
     * 导致同一调用点恒返回同一个值，即"初始弟子全员克隆"）。
     */
    @Volatile
    private var fallbackRng: DeterministicRng? = null

    /**
     * 注入 RNG 管理器（幂等；生产经 [GameEngine] 构造，测试注入固定种子实例）。
     *
     * @param manager 引擎单例 [GameRngManager]
     */
    fun initialize(manager: GameRngManager) {
        rngManager = manager
    }

    /**
     * AI 随机流（**解析式**，非自持）。
     *
     * 优先级：注入的管理器 → 兜底流（[initForSlot] 播种）。
     *
     * @return 委托模式下为 `aiRng_` 的镜像分区实例；否则为 `AI_SECT` 分区实例
     *         （无管理器时回落 [fallbackRng]，仍在值域与确定性约束内）
     */
    internal val rng: DeterministicRng get() {
        val manager = rngManager
        if (manager != null) {
            return manager.getRng(
                if (manager.isDelegatingToNative()) RngPartition.AI_SECT_MIRROR
                else RngPartition.AI_SECT
            )
        }
        return fallbackRng ?: DeterministicRng.fromSeed(LEGACY_FALLBACK_SEED).also { fallbackRng = it }
    }

    /**
     * 以世界种子对齐 AI 流（新档/读档/重启时调用；**幂等**）。
     *
     * 语义（两段）：
     * 1. **兜底流播种**：重置为 `systemSeed + 6×31337`——与 C++ `GameCore::aiRng_`
     *    的播种式同源。引擎尚未注入管理器时（引擎早期调用、无管理器夹具）用它即
     *    得到与原 `initForSlot` 逐位一致的序列。
     * 2. **已注入管理器时**：把 [aiSeed] 的**混种态**恢复到解析出的分区
     *    （非委托模式 = [RngPartition.AI_SECT]，委托模式 = [RngPartition.AI_SECT_MIRROR]）——
     *    保证"同 seed ⇒ 同序列"对**管理器路径同样成立**（原实现的核心契约；
     *    缺失该段会让 [RngPartition.AI_SECT] 停留在 `initSystemSeed` 的
     *    `seed + 6` 上，与 `aiRng_` 的 `seed + 6×31337` 分叉）。
     *
     *    **必须用混种态（`fromSeed` 的 snapshot）而非裸种子**：`snapshot()` 是
     *    **状态**不是种子——C++ `aiRng_`（`game_core.cpp` initialize /
     *    rngInitSystemSeed / importStateInternal 三处）与旧影子流都经
     *    `fromSeed(aiSeed)` 走过一轮混种（`state = (seed shl 1) or 1` 后丢弃一次
     *    `nextLong()`）。若此处 `restore(aiSeed)` 写入裸种子，同一 `aiSeed` 会
     *    得到**另一条序列**（首次抽取即分叉——AI 突破判定/孕养升级行为漂移，
     *    实测 4 处测试红即此缺陷）。
     *
     * **不动分区 9**：`AI_SECT_MIRROR` 是通道型分区（`inSnapshot = false`），其状态
     * 真源 = C++ `aiRng_`，由 native 侧在 `rngInitSystemSeed` / `importStateInternal`
     * 两处按同一公式播种或按存档 9 号键续接（Kotlin 侧覆盖会与宿主侧序列分叉）。
     *
     * @param systemSeed 存档的世界种子（`GameData.mapSeed`）
     */
    fun initForSlot(systemSeed: Long) {
        val aiSeed = systemSeed + RngPartition.AI_SECT.id.toLong() * AI_SECT_SEED_STRIDE
        val mixed = DeterministicRng.fromSeed(aiSeed)
        fallbackRng = mixed
        // 分区必须落"混种态"：快照是**状态**（见 KDoc 第 2 段），裸种子会得到另一条序列
        rngManager?.let { it.getRng(RngPartition.AI_SECT).restore(mixed.snapshot()) }
    }

    /**
     * 摘除注入的管理器（**仅供测试夹具隔离**）。
     *
     * 本对象是进程级 `object`，[initialize] 写入的引用会跨测试类存活——若夹具
     * 注入了自己构造的 [GameRngManager] 而后续用例未重新注入，[rng] 会解析到
     * 上一个夹具的实例（跨测试污染）。夹具的 `@After` 调用本方法即可回到
     * [fallbackRng] 的确定性接缝。
     */
    internal fun resetManagerForTest() {
        rngManager = null
        fallbackRng = null
    }

    /**
     * 旧测试路径的固定兜底种子（不参与生产路径；生产恒经 [initialize] 注入管理器）。
     */
    private const val LEGACY_FALLBACK_SEED = 0xA15EC7A15EC7L

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
