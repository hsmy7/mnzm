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
        val releasedMemberIds: Set<String> = emptySet()
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
 * 事件流：遭遇妖兽事件 →（选择结算）→ 衔接事件（选择方向）→ 遭遇妖兽事件 → …
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
                beastCount = count
            )
        )
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
     */
    fun buildBeastPreGenStats(
        rng: DeterministicRng,
        realm: Int,
        beastTypeName: String,
        ambushSucceeded: Boolean
    ): BattleSystem.BeastPreGenStats {
        val config = GameConfig.Beast.TYPES.firstOrNull { it.name == beastTypeName }
            ?: GameConfig.Beast.TYPES[0]
        val stats = GameConfig.Beast.getRealmStats(realm)
        // 公式与 LevelGenerator.generateBeastLevel 完全对齐（maxHp/maxMp 共用 hpVariance、
        // maxMp = stats.mp × layerMult × (hpMod + variance)），避免平衡调整时分叉
        val layerMult = 1.0 + (rng.nextInt(GameConfig.SecretRealm.BEAST_LAYER_VARIANT_COUNT)) * 0.1
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
            realmLayer = 1
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
