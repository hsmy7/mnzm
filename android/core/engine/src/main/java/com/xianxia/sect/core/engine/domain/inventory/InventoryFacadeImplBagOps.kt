package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.engine.domain.inventory.InventoryFacadeImpl.StorageBagRewardBatch
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.util.randomWith
import java.util.UUID

/**
 * 储物袋奖励生成（抽签描述符 → 物品物化）。
 *
 * ## 随机源契约（ADR 随机源治理 阶段 1①）
 * 抽签**必须**取自调用方传入的 [DeterministicRng]（AUTHORITATIVE 下 =
 * `EXPLORATION` 分区，经 native 委托到 C++ 真相源）——本文件内**不得出现任何
 * 默认随机源**（历史上 `EquipmentDatabase.generateRandom(...)` /
 * `templates.random()` / `ItemDatabase.generateRandomPill(...)` 的**默认实参**
 * 回落 `kotlin.random.Random.Default`，不入存档、不随档走 ⇒ 同一存档两次开袋
 * 产出不同，不可复现）。
 *
 * ## 两臂同序（native / 回退）
 * 抽签序 = **先 `nextInt(16)` 取件数偏移，再逐件 `nextInt(7)` 取种类**——
 * 与 C++ `storage_bag_tx.h::openStorageBagTx` 的消费序逐位一致：
 * - native 臂：C++ 消费 `EXPLORATION` 产出 `draws`，Kotlin 据此物化
 * - 回退臂：Kotlin 用同一分区 RNG 走 [rollRewardDraws]，产出同序
 * ⇒ 双臂消费同一序列、同一起点，抽取序不因走哪条臂而分叉。
 */

/** 抽签件数下界（与 C++ `kMinRewardCount` 同源语义） */
internal const val MIN_REWARD_COUNT = 5

/** 抽签件数上界（含；与 C++ `kMaxRewardCount` 同值） */
internal const val MAX_REWARD_COUNT = 20

/** 件数抽取上界：`nextInt(REWARD_COUNT_SPAN)` ∈ [0,16) ⇒ 件数 [5,20] */
internal const val REWARD_COUNT_SPAN = MAX_REWARD_COUNT - MIN_REWARD_COUNT + 1

/**
 * 奖励种类数：`nextInt(REWARD_KIND_COUNT)` 的返回值即 [StorageBagRewardBatch]
 * 的分派下标（0=装备 / 1=功法 / 2=丹药 / 3=草药 / 4=种子 / 5=材料 / 6=灵石）——
 * **顺序即语义**，与 C++ `kRewardKindCount` 及 Kotlin `when` 分支同序。
 */
internal const val REWARD_KIND_COUNT = 7

/**
 * 抽签（回退臂与 native 回执共用同一映射表）：返回长度 ∈ [5,20] 的种类下标序列。
 *
 * @param rng `EXPLORATION` 分区 PRNG（AUTHORITATIVE 下即 C++ 真相源）
 * @return 种类下标序列（值域 `[0, REWARD_KIND_COUNT)`）
 */
internal fun rollRewardDraws(rng: DeterministicRng): List<Int> {
    val count = MIN_REWARD_COUNT + rng.nextInt(REWARD_COUNT_SPAN)
    return List(count) { rng.nextInt(REWARD_KIND_COUNT) }
}

/**
 * 按抽签下标序列物化奖励批次（不变更任何游戏状态——仅构造待入仓的物品实例，
 * 入仓由 [InventoryFacadeImpl.consumeStorageBagAndGrant] 在单事务内统一完成）。
 *
 * @param draws 种类下标序列（native 回执或 [rollRewardDraws] 产出）
 * @param rarity 袋品阶（决定奖励品阶）
 * @param rng `EXPLORATION` 分区 PRNG——**模板选择必须显式消费它**，不得回落默认随机源
 */
internal fun generateStorageBagRewardsFromDraws(
    draws: List<Int>,
    rarity: Int,
    rng: DeterministicRng
): StorageBagRewardBatch {
    val batch = StorageBagRewardBatch()
    for (kind in draws) {
        when (kind) {
            0 -> batch.generateEquipmentReward(rarity = rarity, rng = rng)
            1 -> batch.generateManualReward(rarity = rarity, rng = rng)
            2 -> batch.generatePillReward(rarity = rarity, rng = rng)
            3 -> batch.generateHerbReward(rarity = rarity, rng = rng)
            4 -> batch.generateSeedReward(rarity = rarity, rng = rng)
            5 -> batch.generateMaterialReward(rarity = rarity, rng = rng)
            6 -> batch.generateSpiritStoneReward(rarity = rarity)
        }
    }
    return batch
}

/** 装备条目（模板抽取显式消费分区 rng——原默认实参回落 Random.Default 已消除） */
internal fun StorageBagRewardBatch.generateEquipmentReward(rarity: Int, rng: DeterministicRng) {
    val stack: EquipmentStack = EquipmentDatabase.generateRandom(
        minRarity = rarity, maxRarity = rarity, random = rng.asKotlinRandom()
    )
    equipment.add(stack)
    rewards.add(
        BattleRewardItem(
            itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity,
            type = "equipment"
        )
    )
}

/** 功法条目（`templates.random()` → `randomWith(rng)`） */
internal fun StorageBagRewardBatch.generateManualReward(rarity: Int, rng: DeterministicRng) {
    if (!ManualDatabase.isInitialized) return
    val templates = ManualDatabase.getByRarity(rarity)
    val template = templates.randomWith(rng.asKotlinRandom()) ?: return
    val stack: ManualStack = ManualDatabase.createFromTemplate(template)
    manuals.add(stack)
    rewards.add(
        BattleRewardItem(
            itemId = stack.id, name = stack.name, quantity = 1, rarity = stack.rarity,
            type = "manual"
        )
    )
}

/** 丹药条目 */
internal fun StorageBagRewardBatch.generatePillReward(rarity: Int, rng: DeterministicRng) {
    val pill: Pill = ItemDatabase.generateRandomPill(
        minRarity = rarity, maxRarity = rarity, random = rng.asKotlinRandom()
    )
    pills.add(pill)
    rewards.add(
        BattleRewardItem(
            itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity,
            type = "pill"
        )
    )
}

/** 草药条目（`templates.random()` → `randomWith(rng)`） */
internal fun StorageBagRewardBatch.generateHerbReward(rarity: Int, rng: DeterministicRng) {
    val templates = HerbDatabase.getHerbsByTier(rarity)
    val h = templates.randomWith(rng.asKotlinRandom()) ?: return
    val herb = Herb(
        id = UUID.randomUUID().toString(), name = h.name, rarity = h.rarity,
        description = h.description, category = h.category, quantity = 1
    )
    herbs.add(herb)
    rewards.add(
        BattleRewardItem(
            itemId = herb.id, name = herb.name, quantity = 1, rarity = herb.rarity,
            type = "herb"
        )
    )
}

/** 种子条目（`templates.random()` → `randomWith(rng)`） */
internal fun StorageBagRewardBatch.generateSeedReward(rarity: Int, rng: DeterministicRng) {
    val templates = HerbDatabase.getAllSeeds().filter { it.rarity == rarity }
    val s = templates.randomWith(rng.asKotlinRandom()) ?: return
    val seed = Seed(
        id = UUID.randomUUID().toString(), name = s.name, rarity = s.rarity,
        description = s.description, growTime = s.growTime, yield = s.yield, quantity = 1
    )
    seeds.add(seed)
    rewards.add(
        BattleRewardItem(
            itemId = seed.id, name = seed.name, quantity = 1, rarity = seed.rarity,
            type = "seed"
        )
    )
}

/** 材料条目 */
internal fun StorageBagRewardBatch.generateMaterialReward(rarity: Int, rng: DeterministicRng) {
    val mat: Material = ItemDatabase.generateRandomMaterial(
        minRarity = rarity, maxRarity = rarity, random = rng.asKotlinRandom()
    )
    materials.add(mat)
    rewards.add(
        BattleRewardItem(
            itemId = mat.id, name = mat.name, quantity = 1, rarity = mat.rarity,
            type = "material"
        )
    )
}

/** 灵石条目：合并进 spiritStones 奖励卡片（零抽取——按品阶查表） */
internal fun StorageBagRewardBatch.generateSpiritStoneReward(rarity: Int) {
    val amount = StorageBag.SPIRIT_STONE_AMOUNTS.getOrElse(rarity - 1) { DEFAULT_SPIRIT_STONE_FALLBACK }
    spiritStones += amount
    val existing = rewards.find { it.type == "spiritStones" }
    if (existing != null) {
        rewards[rewards.indexOf(existing)] = existing.copy(quantity = existing.quantity + amount.toInt())
    } else {
        rewards.add(
            BattleRewardItem(
                name = ItemNames.SPIRIT_STONE, quantity = amount.toInt(), rarity = 1,
                type = "spiritStones"
            )
        )
    }
}

/** 品阶越界时的灵石兜底值（与 [StorageBag.SPIRIT_STONE_AMOUNTS] 表域外的既有语义一致） */
private const val DEFAULT_SPIRIT_STONE_FALLBACK = 500L
