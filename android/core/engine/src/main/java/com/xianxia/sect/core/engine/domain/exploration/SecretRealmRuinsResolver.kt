package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmRewardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.DeterministicRng
import java.util.UUID

/**
 * 远古秘境"发现遗迹"事件结算器——独立对象（避免 SecretRealmService/EventGenerator 超
 * detekt LargeClass / TooManyFunctions 阈值，God Method 拆分方向）。
 *
 * 纯函数：入参 session/rng/事件，产出结算载体，不直接触碰状态。
 */
internal object SecretRealmRuinsResolver {

    /** 遗迹探索选项结算：0=直接离开（进入下一事件）；>=1 视为搜寻（简单/仔细，防篡改档多选项） */
    fun resolveRuinsExplore(
        optionIndex: Int,
        session: SecretRealmExplorationSession,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        if (optionIndex == 0) {
            return bridgeResolution("你方决定离开遗迹，继续探索", session, rng)
        }
        return resolveRuinsSearch(optionIndex, session, rng)
    }

    /**
     * 遗迹搜寻结算：RUINS_TREASURE_CHANCE 概率发现秘宝（描述符生成 → 实例化入背包），
     * 否则空无一物。结果以 RUIN_RESULT 子事件呈现（选项"继续前进"进入衔接事件）。
     *
     * RNG 消费顺序（确定性关键，顺序固定）：1×nextDouble(秘宝判定) → 秘宝路径
     * 1×nextInt(数量) → 每件 3×nextInt（类型/品阶/模板选取）。
     *
     * @Suppress 说明：2 个提前 return 为判空防御（未发现秘宝 / 极端数据空洞降级），
     * 拆分辅助函数反而割裂读档确定性消费顺序的可读性。
     */
    @Suppress("ReturnCount")
    fun resolveRuinsSearch(
        optionIndex: Int,
        session: SecretRealmExplorationSession,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        val careful = optionIndex >= 2
        val treasure = rng.nextDouble() < GameConfig.SecretRealm.RUINS_TREASURE_CHANCE
        if (!treasure) {
            return emptyRuinsResolution(session)
        }
        val minCount = if (careful) GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MIN
        else GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MIN
        val maxCount = if (careful) GameConfig.SecretRealm.CAREFUL_SEARCH_COUNT_MAX
        else GameConfig.SecretRealm.SIMPLE_SEARCH_COUNT_MAX
        val minRarity = if (careful) GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MIN
        else GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MIN
        val maxRarity = if (careful) GameConfig.SecretRealm.CAREFUL_SEARCH_RARITY_MAX
        else GameConfig.SecretRealm.SIMPLE_SEARCH_RARITY_MAX
        val rewards = SecretRealmEventGenerator.generateRuinsTreasure(
            rng, minCount, maxCount, minRarity, maxRarity
        )
        // 防御：极端数据空洞（补生成耗尽仍无可用模板）时降级为空无一物，
        // 避免"发现物品："空描述（RNG 消费顺序不变，确定性保持）
        if (rewards.isEmpty()) return emptyRuinsResolution(session)
        val newBackpack = instantiateRuinsRewards(rewards, session.backpack)
        val itemText = rewards.joinToString("、") {
            it.name + if (it.quantity > 1) "×${it.quantity}" else ""
        }
        val nextEvent = SecretRealmEventGenerator.generateRuinsResultEvent(
            title = "发现秘宝",
            description = "发现物品：$itemText",
            itemRewards = rewards
        )
        return SecretRealmBeastChoiceResolution(
            resultText = "你方在遗迹中发现了宝物！",
            members = session.members,
            backpack = newBackpack,
            params = nextEvent.params,
            nextEvent = nextEvent
        )
    }

    /** 空无一物结算载体（未发现秘宝 / 数据空洞降级共用；携带会话背包防清空） */
    private fun emptyRuinsResolution(
        session: SecretRealmExplorationSession
    ): SecretRealmBeastChoiceResolution = SecretRealmBeastChoiceResolution(
        resultText = "你方在遗迹中搜寻一番，空无一物",
        members = session.members,
        backpack = session.backpack,
        nextEvent = SecretRealmEventGenerator.generateRuinsResultEvent(
            title = "空无一物",
            description = "这里什么都没有"
        )
    )

    /** 遗迹结果子事件：唯一选项"继续前进" → 下一事件（按有无奖励区分文案，防篡改档 title 不一致） */
    fun resolveRuinsResult(
        session: SecretRealmExplorationSession,
        event: SecretRealmEventRecord,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        val resultText = if (event.params.itemRewards.isEmpty()) {
            "遗迹中空无一物，你方继续前行"
        } else {
            "你方携秘宝离开遗迹，继续前行"
        }
        // 保留 event.params（itemRewards 秘宝描述符）——chooseOption 写入 eventHistory 时
        // 用 resolution.params 覆盖 markedEvent.params，若不携带则历史中秘宝明细丢失
        // （与 BEAST_ENCOUNTER 历史保留掉落描述符行为一致，对抗性审查发现）
        return bridgeResolution(resultText, session, rng).copy(params = event.params)
    }

    /**
     * 秘宝描述符 → 背包实例（模板 ID 查表；篡改档未知 ID 静默跳过；Herb/Seed 复制新 UUID
     * 实例防共享库内模板跨会话污染，照抄 openStorageBag 做法）。
     */
    fun instantiateRuinsRewards(
        rewards: List<SecretRealmRewardItem>,
        backpack: SecretRealmBackpack
    ): SecretRealmBackpack {
        var newBackpack = backpack
        for (item in rewards) {
            newBackpack = instantiateItem(item.type, item.itemId, item.quantity, newBackpack)
        }
        return newBackpack
    }

    /** 单件描述符实例化分派（未知类型原样返回，不抛异常） */
    private fun instantiateItem(
        type: String,
        itemId: String,
        quantity: Int,
        backpack: SecretRealmBackpack
    ): SecretRealmBackpack = when (type) {
        "equipment" -> instantiateFromTemplate(
            EquipmentDatabase.getById(itemId),
            { b, t -> b.copy(equipment = b.equipment + EquipmentDatabase.createFromTemplate(t)) },
            backpack
        )
        "manual" -> instantiateFromTemplate(
            ManualDatabase.getById(itemId),
            { b, t -> b.copy(manuals = b.manuals + ManualDatabase.createFromTemplate(t)) },
            backpack
        )
        "pill" -> instantiateFromTemplate(
            ItemDatabase.getPillById(itemId),
            { b, t -> b.copy(pills = b.pills + ItemDatabase.createPillFromTemplate(t)) },
            backpack
        )
        "material" -> instantiateFromTemplate(
            ItemDatabase.getMaterialById(itemId),
            { b, t -> b.copy(materials = b.materials + ItemDatabase.createMaterialFromTemplate(t)) },
            backpack
        )
        "herb" -> instantiateHerb(itemId, quantity, backpack)
        "seed" -> instantiateSeed(itemId, quantity, backpack)
        else -> backpack
    }

    /** 模板实例化并入对应背包列表（模板查不到原样返回，不抛异常） */
    private fun <T> instantiateFromTemplate(
        template: T?,
        addToList: (SecretRealmBackpack, T) -> SecretRealmBackpack,
        backpack: SecretRealmBackpack
    ): SecretRealmBackpack = template?.let { addToList(backpack, it) } ?: backpack

    private fun instantiateHerb(
        itemId: String,
        quantity: Int,
        backpack: SecretRealmBackpack
    ): SecretRealmBackpack = HerbDatabase.getHerbById(itemId)?.let { template ->
        // HerbDatabase.Herb 是库内模板（无 quantity）；手动构造 model.Herb 新 UUID 实例
        backpack.copy(herbs = backpack.herbs + Herb(
            id = UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            category = template.category,
            quantity = quantity
        ))
    } ?: backpack

    private fun instantiateSeed(
        itemId: String,
        quantity: Int,
        backpack: SecretRealmBackpack
    ): SecretRealmBackpack = HerbDatabase.getSeedById(itemId)?.let { template ->
        backpack.copy(seeds = backpack.seeds + Seed(
            id = UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            growTime = template.growTime,
            yield = template.yield,
            quantity = quantity
        ))
    } ?: backpack
}

/** 无战斗分支结算载体（成员不变，携带会话背包防 chooseOption 空覆盖；结算后直接进入下一事件） */
private fun bridgeResolution(
    resultText: String,
    session: SecretRealmExplorationSession,
    rng: DeterministicRng
): SecretRealmBeastChoiceResolution = SecretRealmBeastChoiceResolution(
    resultText = resultText,
    members = session.members,
    backpack = session.backpack,
    nextEvent = SecretRealmEventGenerator.rollNextEvent(
        rng, SecretRealmEventGenerator.playerAvgRealm(session.members)
    )
)
