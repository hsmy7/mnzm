package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventParams
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmOption
import com.xianxia.sect.core.model.SecretRealmRewardItem
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.DeterministicRng

/** 远古秘境探索结束原因 */
enum class SecretRealmEndReason {
    /** 玩家主动结束 */
    EXPLORER_END,
    /** 体力耗尽 */
    EXHAUSTED,
    /** 队伍全灭 */
    WIPEOUT
}

/**
 * 秘境选择结果（sealed：错误与成功分离，规则 1.3）。
 * 成功时携带战斗播放数据；错误时仅携带消息。
 * 放 domain/exploration 包（跨 Service/GameEngine 扩展/ViewModel/UI 共享的类型）。
 */
sealed interface SecretRealmChoiceResult {
    /** 选择失败（无状态变更） */
    data class Error(val message: String) : SecretRealmChoiceResult

    /** 选择成功（状态已结算） */
    data class Success(
        val message: String,
        /** 体力耗尽/全灭自动结束（会话已清空） */
        val sessionEnded: Boolean = false,
        /** 本次选择是否进入战斗（UI 触发战斗播放） */
        val enteredCombat: Boolean = false,
        val combatLog: BattleLogData? = null,
        val victory: Boolean = false,
        /** 本场永久死亡弟子 ID（调用方事务外触发哀伤） */
        val deadIds: Set<String> = emptySet(),
        /** 自动结束时需要释放 gate 占用的成员 ID */
        val releasedMemberIds: Set<String> = emptySet(),
        /** 偷袭选项是否成功（UI 用于区分"偷袭成功/偷袭失败"标题） */
        val ambushSucceeded: Boolean = false
    ) : SecretRealmChoiceResult
}

/** 妖兽事件选择结算结果（chooseOption 分支内部载体） */
internal data class SecretRealmBeastChoiceResolution(
    val resultText: String,
    val enteredCombat: Boolean = false,
    val combatLog: BattleLogData? = null,
    val victory: Boolean = false,
    val backpack: SecretRealmBackpack = SecretRealmBackpack(),
    val members: List<SecretRealmMemberState> = emptyList(),
    val deadIds: Set<String> = emptySet(),
    val params: SecretRealmEventParams = SecretRealmEventParams(),
    val nextEvent: SecretRealmEventRecord
)

/** 秘境战斗执行结果（事务内战斗结算的内部载体） */
internal data class SecretRealmBattleOutcome(
    val victory: Boolean,
    val log: BattleLogData?,
    val backpack: SecretRealmBackpack,
    val members: List<SecretRealmMemberState>,
    val deadIds: Set<String>,
    val params: SecretRealmEventParams,
    val resultText: String
)

/**
 * 远古秘境事件生成器——纯函数（注入 SECRET_REALM 分区 PRNG）。
 *
 * 事件流：遭遇妖兽事件 →（选择结算）→ 衔接事件（选择方向）→
 * 30% 概率空地事件 / 20% 概率发现遗迹事件 / 妖兽事件 →（选择结算）→ 衔接事件 → …
 */
object SecretRealmEventGenerator {

    /**
     * 秘境妖兽境界：玩家队伍平均境界附近随机 [avg-1, avg+2]，clamp 0..9。
     * 与 LevelGenerator.selectBeastRealm 的 clamp 逻辑一致，但使用 SECRET_REALM 分区 RNG。
     */
    fun rollBeastRealm(rng: DeterministicRng, playerAvgRealm: Int): Int {
        val minRealm = (playerAvgRealm - 1)
            .coerceIn(GameConfig.SecretRealm.REALM_MIN, GameConfig.SecretRealm.REALM_MAX)
        val maxRealm = (playerAvgRealm + 2)
            .coerceIn(GameConfig.SecretRealm.REALM_MIN, GameConfig.SecretRealm.REALM_MAX)
        return minRealm + rng.nextInt(maxRealm - minRealm + 1)
    }

    // ── 妖兽事件 ──────────────────────────────────────────────────────

    /**
     * 生成"遭遇妖兽"事件。
     *
     * @param rng SECRET_REALM 分区 PRNG
     * @param playerAvgRealm 玩家队伍平均境界（数值越小境界越高）
     * @return 妖兽事件记录（含妖兽名/数量/境界参数）
     */
    fun generateBeastEvent(rng: DeterministicRng, playerAvgRealm: Int): SecretRealmEventRecord {
        val config = GameConfig.Beast.TYPES[rng.nextInt(GameConfig.Beast.TYPES.size)]
        val realm = rollBeastRealm(rng, playerAvgRealm)
        val layer = 1 + rng.nextInt(GameConfig.SecretRealm.BEAST_LAYER_VARIANT_COUNT)
        val count = GameConfig.SecretRealm.BEAST_COUNT_MIN +
            rng.nextInt(GameConfig.SecretRealm.BEAST_COUNT_MAX - GameConfig.SecretRealm.BEAST_COUNT_MIN + 1)
        val beastName = "${config.prefix}${config.name}"
        val realmName = GameConfig.Realm.getName(realm)

        return SecretRealmEventRecord(
            eventType = SecretRealmEventType.BEAST_ENCOUNTER.name,
            title = "遭遇妖兽",
            description = "途中遭遇妖兽！$beastName × $count，境界：$realmName",
            options = listOf(
                SecretRealmOption("远离妖兽", "小心避让，有一定概率被妖兽察觉"),
                SecretRealmOption("发起战斗", "与妖兽正面交锋"),
                SecretRealmOption("尝试偷袭", "伺机偷袭，成功则妖兽血量削减一成")
            ),
            params = SecretRealmEventParams(
                beastTypeName = config.name,
                beastRealm = realm,
                beastLayer = layer,
                beastCount = count
            )
        )
    }

    // ── 空地事件 ─────────────────────────────────────────────────────

    /**
     * 生成"平坦空地"事件（内容无随机性，无需 rng）。
     *
     * 选项 0：原地休整（所有弟子恢复 40% 最大生命，含重伤濒死）；
     * 选项 1：继续前进。两项均进入衔接事件。
     */
    fun generateRestAreaEvent(): SecretRealmEventRecord = SecretRealmEventRecord(
        eventType = SecretRealmEventType.REST_AREA.name,
        title = "发现空地",
        description = "发现一处平坦空地",
        options = listOf(
            SecretRealmOption("原地休整", "所有弟子恢复40%状态"),
            SecretRealmOption("继续前进", "不做停留，继续探索")
        )
    )

    /**
     * 衔接方向选择后的下一事件分派：一次 nextDouble() 分段判定——
     * < REST_AREA_CHANCE 空地事件；[REST_AREA_CHANCE, REST_AREA_CHANCE + RUINS_CHANCE) 发现遗迹；
     * 其余妖兽事件。仍只消费一次 nextDouble()（RNG 消费次数与旧版一致，读档确定性不变）。
     *
     * @param rng SECRET_REALM 分区 PRNG
     * @param playerAvgRealm 玩家队伍平均境界（数值越小境界越高）
     * @return 空地事件 / 发现遗迹事件 / 妖兽事件记录
     */
    fun rollNextEvent(rng: DeterministicRng, playerAvgRealm: Int): SecretRealmEventRecord {
        val roll = rng.nextDouble()
        return when {
            roll < GameConfig.SecretRealm.REST_AREA_CHANCE -> generateRestAreaEvent()
            roll < GameConfig.SecretRealm.REST_AREA_CHANCE +
                GameConfig.SecretRealm.RUINS_CHANCE -> generateRuinsEvent()
            else -> generateBeastEvent(rng, playerAvgRealm)
        }
    }

    // ── 发现遗迹事件 ─────────────────────────────────────────────────

    /**
     * 生成"发现遗迹"事件（内容无随机性，无需 rng）。
     *
     * 选项 0：直接离开（扣 1 体力）；选项 1：简单搜寻（扣 1 体力）；
     * 选项 2：仔细搜寻（扣 [GameConfig.SecretRealm.CAREFUL_SEARCH_STAMINA_COST] 体力）。
     * 搜寻结果（空无一物 / 发现秘宝）为独立的 [SecretRealmEventType.RUIN_RESULT] 子事件。
     */
    fun generateRuinsEvent(): SecretRealmEventRecord = SecretRealmEventRecord(
        eventType = SecretRealmEventType.RUIN_EXPLORE.name,
        title = "发现遗迹",
        description = "发现未知遗迹可能存在未知宝物",
        options = listOf(
            SecretRealmOption("直接离开", "不进入遗迹，继续探索"),
            SecretRealmOption("简单搜寻", "简单搜寻一番，可能有所发现"),
            SecretRealmOption(
                "仔细搜寻",
                "仔细搜寻一番，消耗更多体力但收获更丰",
                staminaCost = GameConfig.SecretRealm.CAREFUL_SEARCH_STAMINA_COST
            )
        )
    )

    /**
     * 生成遗迹搜寻结果子事件（空无一物 / 发现秘宝共用 RUIN_RESULT，title/description 区分）。
     *
     * @param title 子事件标题（"空无一物" / "发现秘宝"）
     * @param description 子事件描述（发现秘宝时列出所获物品）
     * @param itemRewards 秘宝奖励描述符（发现秘宝时非空；空无一物时为空）
     * @return 结果子事件记录（唯一选项"继续前进"进入衔接事件）
     */
    fun generateRuinsResultEvent(
        title: String,
        description: String,
        itemRewards: List<SecretRealmRewardItem> = emptyList()
    ): SecretRealmEventRecord = SecretRealmEventRecord(
        eventType = SecretRealmEventType.RUIN_RESULT.name,
        title = title,
        description = description,
        options = listOf(SecretRealmOption("继续前进", "")),
        params = SecretRealmEventParams(itemRewards = itemRewards)
    )

    /**
     * 秘宝可出物品类型（装备/功法/丹药/材料/草药/种子六类；索引式选取，RNG 消费确定）。
     */
    private val RUIN_ITEM_TYPES = listOf("equipment", "manual", "pill", "material", "herb", "seed")

    /**
     * 秘宝奖励生成（描述符，模板 ID 入 params；实例化在 Service 结算时）。
     *
     * RNG 消费顺序（读档确定性关键，顺序固定不可调换）：
     * 1×nextInt(数量) → 每件物品：1×nextInt(类型) + 1×nextInt(品阶) + 1×nextInt(模板选取)。
     * 全部经 SECRET_REALM 分区 PRNG，禁用 kotlin.random 默认随机（否则读档随机序列漂移）。
     * 数据空洞（该品阶无可用模板）时重试补生成，保证实际件数达到配置数量。
     *
     * @param rng SECRET_REALM 分区 PRNG
     * @param minCount / maxCount 物品数量范围
     * @param minRarity / maxRarity 品阶范围（1..6）
     * @return 奖励描述符列表（每件 quantity = 1）
     */
    fun generateRuinsTreasure(
        rng: DeterministicRng,
        minCount: Int,
        maxCount: Int,
        minRarity: Int,
        maxRarity: Int
    ): List<SecretRealmRewardItem> {
        val count = minCount + rng.nextInt(maxCount - minCount + 1)
        val rewards = mutableListOf<SecretRealmRewardItem>()
        var attempts = 0
        // 补生成：跳过数据空洞件后继续尝试，直到凑满 count 件（上限防极端数据死循环）
        while (rewards.size < count && attempts < count * RUIN_PICK_MAX_ATTEMPTS) {
            attempts++
            val type = RUIN_ITEM_TYPES[rng.nextInt(RUIN_ITEM_TYPES.size)]
            val rarity = minRarity + rng.nextInt(maxRarity - minRarity + 1)
            val template = pickRuinsTemplate(type, rarity, rng) ?: continue
            rewards.add(
                SecretRealmRewardItem(
                    type = type,
                    itemId = template.first,
                    name = template.second,
                    rarity = rarity,
                    quantity = 1
                )
            )
        }
        return rewards
    }

    /** 单件秘宝的模板选取最大尝试次数（数据空洞补生成上限） */
    private const val RUIN_PICK_MAX_ATTEMPTS = 5

    /**
     * 按类型 + 品阶选取模板（仅取模板 ID 与名称）。数据空洞（该品阶无模板）或
     * ManualDatabase 未初始化时返回 null（调用方跳过该件，不抛异常）。
     */
    private fun pickRuinsTemplate(
        type: String,
        rarity: Int,
        rng: DeterministicRng
    ): Pair<String, String>? = when (type) {
        "equipment" -> pickTemplate(EquipmentDatabase.getByRarity(rarity).map { it.id to it.name }, rng)
        "manual" -> {
            if (!ManualDatabase.isInitialized) null
            else pickTemplate(ManualDatabase.getByRarity(rarity).map { it.id to it.name }, rng)
        }
        "pill" -> pickTemplate(ItemDatabase.getPillsByRarity(rarity).map { it.id to it.name }, rng)
        "material" -> pickTemplate(
            ItemDatabase.allMaterials.values.filter { it.rarity == rarity }.map { it.id to it.name }, rng
        )
        "herb" -> pickTemplate(HerbDatabase.getByRarity(rarity).map { it.id to it.name }, rng)
        "seed" -> pickTemplate(HerbDatabase.getSeedsByRarity(rarity).map { it.id to it.name }, rng)
        else -> null
    }

    // ── 衔接事件 ──────────────────────────────────────────────────────

    /**
     * 生成"衔接事件"（上个事件结果描述 + 请选择探索方向）。
     *
     * @param resultText 上个事件的结果描述（成为衔接事件描述前缀）
     */
    fun generateBridgeEvent(resultText: String): SecretRealmEventRecord = SecretRealmEventRecord(
        eventType = SecretRealmEventType.BRIDGE.name,
        title = "探索方向",
        description = "$resultText，请选择探索方向",
        options = listOf(
            SecretRealmOption("走左路", ""),
            SecretRealmOption("直线前进", ""),
            SecretRealmOption("走右路", "")
        )
    )

    // ── 妖兽属性预生成 ────────────────────────────────────────────────

    /**
     * 预生成秘境妖兽最终属性（含随机方差，参考 LevelGenerator.generateBeastLevel）。
     *
     * @param rng SECRET_REALM 分区 PRNG
     * @param realm 妖兽境界 0~9
     * @param beastTypeName 妖兽类型名（GameConfig.Beast.TYPES 中的 name）
     * @param ambushSucceeded 偷袭成功：所有妖兽初始血量 -10%
     * @param beastLayer 妖兽层数 1..9（与事件显示层数一致，派生战斗倍率）
     */
    fun buildBeastPreGenStats(
        rng: DeterministicRng,
        realm: Int,
        beastTypeName: String,
        ambushSucceeded: Boolean,
        beastLayer: Int = 1
    ): BattleSystem.BeastPreGenStats {
        val config = GameConfig.Beast.TYPES.firstOrNull { it.name == beastTypeName }
            ?: GameConfig.Beast.TYPES[0]
        val stats = GameConfig.Beast.getRealmStats(realm)
        // 层数倍率与显示层数一致（LevelGenerator.generateBeastLevel 同公式：
        // layerMult = 1.0 + (realmLayer-1)*0.1），防止"显示九层实际最弱"脱钩
        val clampedLayer = beastLayer.coerceIn(
            1, GameConfig.SecretRealm.BEAST_LAYER_VARIANT_COUNT
        )
        val layerMult = 1.0 + (clampedLayer - 1) * 0.1
        val hpVariance = -0.2 + rng.nextDouble() * 0.4
        val atkVariance = -0.2 + rng.nextDouble() * 0.4
        val defVariance = -0.2 + rng.nextDouble() * 0.4
        val speedVariance = -0.2 + rng.nextDouble() * 0.4

        var maxHp = (stats.hp * layerMult * (config.hpMod + hpVariance)).toInt().coerceAtLeast(1)
        if (ambushSucceeded) {
            maxHp = (maxHp * (1.0 - GameConfig.SecretRealm.AMBUSH_BEAST_HP_REDUCTION)).toInt()
                .coerceAtLeast(1)
        }
        val maxMp = (stats.mp * layerMult * (config.hpMod + hpVariance)).toInt().coerceAtLeast(1)
        val atk = (stats.attack * layerMult * (config.atkMod + atkVariance)).toInt().coerceAtLeast(1)
        val def = (stats.defense * layerMult * (config.defMod + defVariance)).toInt().coerceAtLeast(1)
        val speed = (stats.speed * layerMult * (config.speedMod + speedVariance)).toInt().coerceAtLeast(1)

        return BattleSystem.BeastPreGenStats(
            maxHp = maxHp,
            maxMp = maxMp,
            physicalAttack = atk,
            magicAttack = atk,
            physicalDefense = def,
            magicDefense = def,
            speed = speed,
            realmLayer = clampedLayer
        )
    }

    // ── 掉落生成 ──────────────────────────────────────────────────────

    /**
     * 妖兽战斗胜利掉落：每只妖兽固定 2 个该妖兽材料（复用妖兽战掉落机制）。
     */
    fun rollBeastLoot(
        rng: DeterministicRng,
        beastTypeName: String,
        beastRealm: Int,
        beastCount: Int
    ): List<SecretRealmRewardItem> {
        val tier = GameConfig.Realm.getMaxRarity(beastRealm)
        val candidates = BeastMaterialDatabase.getMaterialsByBeastType(beastTypeName)
            .filter { it.tier == tier }
        if (candidates.isEmpty()) return emptyList()
        val totalWeight = candidates.sumOf { it.dropWeight }
        val rewards = mutableListOf<SecretRealmRewardItem>()
        repeat(beastCount * 2) {
            var roll = rng.nextDouble() * totalWeight
            val selected = candidates.firstOrNull {
                roll -= it.dropWeight
                roll <= 0.0
            } ?: candidates.first()
            rewards.add(
                SecretRealmRewardItem(
                    type = "material",
                    itemId = selected.id,
                    name = selected.name,
                    rarity = selected.rarity,
                    quantity = 1
                )
            )
        }
        return rewards
    }
}

/** 从模板 ID/名称列表中按 rng 索引选取（空列表返回 null）——文件级私有，不计入 object 函数数 */
private fun pickTemplate(
    templates: List<Pair<String, String>>,
    rng: DeterministicRng
): Pair<String, String>? =
    if (templates.isEmpty()) null else templates[rng.nextInt(templates.size)]
