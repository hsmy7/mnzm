package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.core.gameview.toJsonObject
import com.xianxia.sect.proto.gameview.CollectionChange
import com.xianxia.sect.proto.gameview.GameView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

/**
 * TypedEnvelopeSizeBenchTest — B18-P1 实施文档 §1.6 **勘察点②** 的实测台架。
 *
 * ## 勘察点原文
 * 「9 集合实体是否含超长嵌套（equipment/manual 族技能列表）——通用递归已覆盖，
 * 但若某集合 typed 后**体积/耗时劣化**（proto 重复 tag 开销 vs JSON 紧凑），
 * **实测对比登记**，必要时该集合暂留 JSON（登记残余，B20 一并收）」
 *
 * ## 口径（三档，诚实边界）
 * 1. **体积（硬门，精确）**：同一棵树两种**真实 wire 形态**逐集合对比——
 *    - 旧形态 = `CollectionChange{ name(1), upsertsJson(2)=JSON 文本 }`（B18-P1 前
 *      C++ 编码器形状，本台架以 javalite 原样重建）；
 *    - 新形态 = **桌面 C++ 真编码器**（`DiffRngBridge.nativeCoreEncodeGameView`）
 *      产出的 `upsertsTyped(4)` 信封。
 *    两形态共用同一信封壳（version + 单 collectionChange）⇒ 差值为载荷差。
 * 2. **Kotlin 消费侧耗时（软门）**：两形态**从 wire 字节到 JsonElement 树**的耗时
 *    （均含 protobuf 解析 + 形态解码：`parseToJsonElement(bytes)` vs
 *    `TypedRow.toJsonObject()` 递归重建）。同时断言两形态产物**逐字段相等**
 *    （真实样本上的等价复核，附带收益）。
 * 3. **C++ 编码侧耗时 = 不可测（诚实登记）**：旧编码器（`dump()` + bytes 分支）已随
 *    B18-P1 删除，**无法在同一实现上对拍**——任何"重实现一个旧编码器"测的都是 Kotlin
 *    侧而非被删的 C++ 路径。故本台架**不登记** C++ 侧耗时数字，只在完成报告说明
 *    "该轴不可测"。
 *
 * ## 断言（防灾难性膨胀，非绝对阈值门——沿本仓库 bench 惯例）
 * - 两形态解码产物**逐字段全等**（真实样本）；
 * - typed 体积 **≤ 3×** 旧形态（超限即红 ⇒ 强制走"该集合暂留 JSON"的登记流程）。
 *
 * 数字经 println/stderr 输出，供方案 §7.2 与完成报告引用。
 */
class TypedEnvelopeSizeBenchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `9 集合 typed 信封 vs 旧 JSON 原文信封 体积与消费侧耗时实测`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val rows = mutableListOf<Sample>()
        for (name in COLLECTIONS) {
            rows += Sample(name, JsonArray(rawSamples(name).map { json.parseToJsonElement(it) }))
        }

        val lines = mutableListOf<String>()
        for (s in rows) {
            val tree = """{"version":1,"changed":{"${s.name}":${s.entities}}}"""
            val typedBytes = DiffRngBridge.nativeCoreEncodeGameView(tree.encodeToByteArray())
            val legacyBytes = legacyEnvelope(s.name, s.entities)

            val legacyTree = legacyDecode(legacyBytes)
            val typedTree = typedDecode(typedBytes)
            assertEquals(
                "【${s.name}】两形态解码产物必须逐字段相等（真实样本等价复核）",
                legacyTree,
                typedTree,
            )

            val legacyMs = ms { legacyDecode(legacyBytes) }
            val typedMs = ms { typedDecode(typedBytes) }
            val ratio = typedBytes.size.toDouble() / legacyBytes.size
            lines += "[B18-P1 勘察点②] " + s.name.padEnd(20) +
                " 实体=" + s.entities.size.toString().padStart(2) +
                " 旧形态=" + legacyBytes.size.toString().padStart(6) + "B" +
                " 新形态=" + typedBytes.size.toString().padStart(6) + "B" +
                " 体积比=" + String.format(Locale.ROOT, "%.3f", ratio) +
                " | 消费侧 旧=" + String.format(Locale.ROOT, "%.3f", legacyMs) + "ms" +
                " 新=" + String.format(Locale.ROOT, "%.3f", typedMs) + "ms" +
                " 耗时比=" + String.format(Locale.ROOT, "%.3f", typedMs / legacyMs)
            assertTrue(
                "【${s.name}】typed 体积膨胀超 3×（${String.format(Locale.ROOT, "%.3f", ratio)}）——" +
                    "须按实施文档 §1.6 勘察点② 走「该集合暂留 JSON」的残余登记流程",
                ratio <= 3.0,
            )
        }
        for (l in lines) {
            println(l)
            System.err.println(l)
        }
    }

    // ── 两形态构造 / 解码 ────────────────────────────────────────

    /** 旧 wire 形态（B18-P1 前 C++ 编码器形状：name(1) + upsertsJson(2) 原文）。 */
    private fun legacyEnvelope(name: String, entities: JsonArray): ByteArray =
        GameView.newBuilder()
            .setVersion(1)
            .addCollectionChange(
                CollectionChange.newBuilder()
                    .setName(name)
                    .setUpsertsJson(ByteString.copyFromUtf8(entities.toString()))
            )
            .build()
            .toByteArray()

    private fun legacyDecode(bytes: ByteArray): JsonElement {
        val cc = GameViewMirrorCodec.parse(bytes).collectionChangeList[0]
        return json.parseToJsonElement(cc.upsertsJson.toStringUtf8())
    }

    private fun typedDecode(bytes: ByteArray): JsonElement {
        val cc = GameViewMirrorCodec.parse(bytes).collectionChangeList[0]
        return JsonArray(cc.upsertsTypedList.map { it.toJsonObject() })
    }

    private inline fun ms(block: () -> Unit): Double {
        repeat(WARMUP_ITERATIONS) { block() }   // JIT 预热（消首轮解释执行偏置）
        val t0 = System.nanoTime()
        repeat(TIMING_ITERATIONS) { block() }
        return (System.nanoTime() - t0) / 1e6 / TIMING_ITERATIONS
    }

    private data class Sample(val name: String, val entities: JsonArray)

    companion object {
        /** 计时迭代数（含 JIT 预热遍）——样本小、微秒级，数字作**量级参考**而非判决。 */
        private const val TIMING_ITERATIONS = 2000
        private const val WARMUP_ITERATIONS = 500

        private val COLLECTIONS = listOf(
            "equipmentStacks", "equipmentInstances", "manualStacks", "manualInstances",
            "pills", "materials", "herbs", "seeds", "storageBags",
        )

        /**
         * 逐集合样本（原始 JSON 文本）：键集 = C++ `models.h` 各结构体字段
         * （真实协议键），实体数 3、含浮点/布尔/嵌套对象/数组——**按最坏嵌套形态取**
         * （`storageBags` 的条目内嵌 `equipmentInstance` + `stackedData`，
         * 即勘察点所称"超长嵌套"）。
         */
        private fun rawSamples(name: String): List<String> = when (name) {
            "equipmentStacks" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"eq-$i","slotId":$i,"name":"青锋剑$i","rarity":${i + 1},
                   "description":"锋锐可断金石","slot":"WEAPON","physicalAttack":${12 + i},
                   "magicAttack":0,"physicalDefense":3,"magicDefense":1,"speed":2,"hp":10,
                   "mp":5,"critChance":0.05,"minRealm":3,"quantity":$i,"isLocked":false}"""
            }
            "equipmentInstances" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"ei-$i","slotId":$i,"name":"玄铁甲$i","rarity":3,"description":"重甲",
                   "slot":"ARMOR","physicalAttack":0,"magicAttack":0,"physicalDefense":${20 + i},
                   "magicDefense":8,"speed":-1,"hp":50,"mp":0,"critChance":0.0,
                   "nurtureLevel":${i + 2},"nurtureProgress":0.${i}5,"minRealm":4,
                   "ownerId":"10$i","isEquipped":true}"""
            }
            "manualStacks" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"ms-$i","slotId":$i,"name":"御剑术$i","rarity":2,"description":"剑道",
                   "type":"MIND","stats":{"comprehension":${i + 1},"intelligence":2},
                   "skillName":"剑芒","skillDescription":"斩击","skillType":"ATTACK",
                   "skillDamageType":"PHYSICAL","skillHits":2,"skillDamageMultiplier":1.${i}5,
                   "skillCooldown":3,"skillMpCost":12,"skillHealPercent":0.0,"skillHealFixed":0,
                   "skillHealType":"","skillBuffType":"BURN","skillBuffValue":0.1,
                   "skillBuffDuration":2,"skillBuffsJson":"[]","skillIsAoe":false,
                   "skillTargetScope":"SINGLE","skillShieldPercent":0.0,
                   "skillTurnAdvancePercent":0.0,"skillDamageSharePercent":0.0,
                   "skillDamageLinkPercent":0.0,"minRealm":2,"quantity":$i,"isLocked":false}"""
            }
            "manualInstances" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"mi-$i","slotId":$i,"name":"大衍诀$i","rarity":4,"description":"心法",
                   "type":"MIND","stats":{"comprehension":${i + 3}},"skillName":"衍化",
                   "skillDescription":"","skillType":"SUPPORT","skillDamageType":"MAGIC",
                   "skillHits":1,"skillDamageMultiplier":0.0,"skillCooldown":5,"skillMpCost":30,
                   "skillHealPercent":0.2,"skillHealFixed":10,"skillHealType":"PERCENT",
                   "skillBuffType":"","skillBuffValue":0.0,"skillBuffDuration":0,
                   "skillBuffsJson":"[]","skillIsAoe":true,"skillTargetScope":"ALL",
                   "skillShieldPercent":0.15,"skillTurnAdvancePercent":0.0,
                   "skillDamageSharePercent":0.0,"skillDamageLinkPercent":0.0,"minRealm":6,
                   "ownerId":"20$i","isLearned":true}"""
            }
            "pills" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"p-$i","slotId":$i,"name":"凝气丹$i","rarity":3,"description":"助修炼",
                   "category":"CULTIVATION","grade":"MEDIUM","pillType":"cultivation",
                   "effects":{"breakthroughChance":0.${i}5,"cultivationAdd":${100 * i},
                   "hpRestore":0,"mpRestore":0,"attackBonus":0,"duration":0},
                   "minRealm":2,"quantity":${i + 4},"isLocked":false}"""
            }
            "materials" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"m-$i","slotId":$i,"name":"兽皮$i","rarity":1,"description":"粗制",
                   "category":"BEAST_HIDE","quantity":${i * 3},"isLocked":false}"""
            }
            "herbs" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"h-$i","slotId":$i,"name":"灵芝$i","rarity":2,"description":"药性温",
                   "category":"SPIRIT_HERB","quantity":${i * 2},"isLocked":false}"""
            }
            "seeds" -> arrayOf(1, 2, 3).map { i ->
                """{"id":"sd-$i","slotId":$i,"name":"灵稻种$i","rarity":1,"description":"",
                   "growTime":${i + 2},"yield":${i + 1},"quantity":$i,"isLocked":false}"""
            }
            else -> arrayOf(1, 2, 3).map { i -> storageBag(i) }
        }

        /** 储物袋：袋内 2 条目，其中一条内嵌装备实例 + 功法实例（最坏嵌套档）。 */
        private fun storageBag(i: Int): String =
            """{"id":"bag-$i","slotId":$i,"name":"储物袋$i","rarity":2,"description":"",
               "capacity":${20 + i},"spiritStones":${100 * i},"isLocked":false,
               "items":[
                 {"itemId":"s-$i","itemType":"material","name":"兽皮","rarity":1,
                  "quantity":9,"obtainedYear":3,"obtainedMonth":2,
                  "stackedData":{"minRealm":2,"slot":"weapon","manualType":""}},
                 {"itemId":"sx-$i","itemType":"equipment","name":"青锋剑","rarity":3,
                  "quantity":1,"obtainedYear":4,"obtainedMonth":5,
                  "equipmentInstance":{"id":"eqx-$i","slotId":1,"name":"青锋剑","rarity":3,
                    "description":"","slot":"WEAPON","physicalAttack":15,"magicAttack":0,
                    "physicalDefense":2,"magicDefense":1,"speed":3,"hp":0,"mp":0,
                    "critChance":0.05,"nurtureLevel":4,"nurtureProgress":0.25,
                    "minRealm":3,"ownerId":"10$i","isEquipped":false}}
               ]}"""
    }
}
