package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.engine.config.GameDataNativeBridge
import com.xianxia.sect.core.platform.AssetSource
import com.xianxia.sect.core.util.DomainLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 池内一个类别（角色类候选模板；物品类候选模板表 + 品阶上限）。 */
data class GachaCategorySpec(
    val kind: String,
    val weightPct: Int,
    val templateIds: List<String>,
    val itemSource: String,
    val maxRarity: Int,
) {
    /**
     * 角色类判据：命中即入碎片账本、不入仓库。
     *
     * 与 C++ `GachaCategory::isCharacter()`（`gamecore/data/gacha_pool_db.h`）同式——
     * 两侧都按 `character` 前缀判定，新增 `character_triple` 之类的类别无需改判定逻辑。
     */
    val isCharacter: Boolean
        get() = kind.startsWith("character")
}

/** 物品品阶权重（池级；全表 `weightPct` 和为 100）。 */
data class GachaRarityWeightSpec(val rarity: Int, val weightPct: Int)

/**
 * 保底配置。
 *
 * 语义是"第 [pullThreshold] 抽**本身**发 [fragmentCount] 片随机角色碎片"，
 * 不是在该抽之外额外赠送；[pickMode] 支持 `"random"`（池内全部角色）与
 * `"singleSpiritRoot"`（池内单灵根角色），其余取值按池不自洽拒绝。
 */
data class GachaPitySpec(
    val pullThreshold: Int,
    val fragmentCount: Int,
    val pickMode: String,
)

/**
 * 卡池规格（Kotlin 侧读面）。
 *
 * @property pricePerPull 单抽价（下品灵石）
 * @property categories 类别权重表——**声明序即加权累加序**，与 C++ 同序
 * @property itemRarityWeights 品阶权重表——同样声明序敏感
 * @property fragmentCountWeights 角色碎片数量权重表——**下标 i = i+1 片**，全表和为 100，
 *   声明序敏感（与 C++ `GachaPoolTemplate.fragmentCountWeights` 同构）
 * @property itemCountWeights 物品数量权重表——**下标 i = i+1 件**，全表和为 100
 *   （钟形近似正态分布），声明序敏感（与 C++ `GachaPoolTemplate.itemCountWeights` 同构）
 */
data class GachaPoolSpec(
    val poolId: String,
    val enabled: Boolean,
    val pricePerPull: Int,
    val categories: List<GachaCategorySpec>,
    val itemRarityWeights: List<GachaRarityWeightSpec>,
    val fragmentCountWeights: List<Int>,
    val itemCountWeights: List<Int>,
    val pity: GachaPitySpec,
)

/**
 * 卡池配置装载——寻访回退臂的概率口径来源。
 *
 * ## 为什么 Kotlin 也要读一遍
 * C++ 是 AUTHORITATIVE 真相源，但门控关闭 / native 桥不可用时寻访仍须能出货
 * （双实现并行契约，与 `GachaFragmentLedger` 同型）。概率表只有
 * `assets/data/game-data.json` 的 `db.gachaPools` 一份（`scripts/gen-game-data.mjs`
 * 单源产物），Kotlin 再抄一份字面量即构成第二真源 ⇒ 本类解析**与 C++ 注入同一份
 * 字节**的资产。
 *
 * ## 失败语义
 * 资产缺失 / 无 `db.gachaPools` 段 / 解析异常 ⇒ 空表 + WARN 日志，查询恒返回 null，
 * 由调用方按"查无此池"拒绝——不静默按另一套内置概率出货。
 */
@Singleton
class GachaPoolConfig @Inject constructor(
    private val assetSource: AssetSource,
) {
    private companion object {
        const val TAG = "GachaPoolConfig"
        val json = Json { ignoreUnknownKeys = true }
    }

    /** 解析结果缓存（`poolId → 规格`；null = 尚未解析） */
    @Volatile
    private var parsed: Map<String, GachaPoolSpec>? = null

    /** 按 id 取池规格；资产缺失或该池不存在返回 null。 */
    fun pool(poolId: String): GachaPoolSpec? = pools()[poolId]

    private fun pools(): Map<String, GachaPoolSpec> {
        parsed?.let { return it }
        return parse().also { parsed = it }
    }

    private fun parse(): Map<String, GachaPoolSpec> {
        val text = assetSource.open(GameDataNativeBridge.ASSET_PATH)
            ?.let { stream -> stream.reader(Charsets.UTF_8).use { it.readText() } }
        if (text.isNullOrEmpty()) {
            DomainLog.w(TAG, "数据文件缺失（${GameDataNativeBridge.ASSET_PATH}），寻访不可用")
            return emptyMap()
        }
        val rows = runCatching {
            ((json.parseToJsonElement(text) as? JsonObject)?.get("db") as? JsonObject)
                ?.get("gachaPools") as? JsonArray
        }.onFailure { error ->
            DomainLog.w(TAG, "卡池配置解析失败，寻访不可用: ${error.message}")
        }.getOrNull()
        if (rows == null) {
            DomainLog.w(TAG, "数据文件缺 db.gachaPools 段，寻访不可用")
            return emptyMap()
        }
        return rows.mapNotNull { it?.asPoolSpec() }.associateBy { it.poolId }
    }
}

private fun JsonElement?.asPoolSpec(): GachaPoolSpec? {
    val obj = (this as? JsonObject) ?: return null
    val poolId = obj.str("poolId") ?: return null
    return GachaPoolSpec(
        poolId = poolId,
        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        pricePerPull = obj.int("pricePerPull") ?: 0,
        categories = (obj["categories"] as? JsonArray)?.mapNotNull { it.asCategorySpec() }
            ?: emptyList(),
        itemRarityWeights = (obj["itemRarityWeights"] as? JsonArray)
            ?.mapNotNull { it.asRarityWeightSpec() } ?: emptyList(),
        fragmentCountWeights = obj.intList("fragmentCountWeights"),
        itemCountWeights = obj.intList("itemCountWeights"),
        pity = (obj["pity"] as? JsonObject).asPitySpec(),
    )
}

private fun JsonElement?.asCategorySpec(): GachaCategorySpec? {
    val obj = (this as? JsonObject) ?: return null
    val kind = obj.str("kind") ?: return null
    return GachaCategorySpec(
        kind = kind,
        weightPct = obj.int("weightPct") ?: 0,
        templateIds = (obj["templateIds"] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList(),
        itemSource = obj.str("itemSource") ?: "",
        maxRarity = obj.int("maxRarity") ?: 0,
    )
}

private fun JsonElement?.asRarityWeightSpec(): GachaRarityWeightSpec? {
    val obj = (this as? JsonObject) ?: return null
    return GachaRarityWeightSpec(rarity = obj.int("rarity") ?: 0, weightPct = obj.int("weightPct") ?: 0)
}

private fun JsonObject?.asPitySpec(): GachaPitySpec = GachaPitySpec(
    pullThreshold = this?.int("pullThreshold") ?: 0,
    fragmentCount = this?.int("fragmentCount") ?: 0,
    pickMode = this?.str("pickMode") ?: "",
)

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

/** 整数数组字段（缺键/非数组一律空表——池自洽校验会以「权重和 ≠ 100」显式拒绝） */
private fun JsonObject.intList(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
