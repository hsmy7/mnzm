package com.xianxia.sect.core.engine.domain

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.registry.EquipmentDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 品阶境界门槛守卫（0.2-5 拍板 / S17，方案 §3.8/§6.1）。
 *
 * 全部产出链的唯一入口是 [EquipmentFactory]——品阶境界约束单点实现：
 * 产出品阶不得高于角色当前境界可穿上限（`create` 钳制 + `pickRarity`
 * 分层抽取钳制）。低境界玩家（如 Lv1/凡人）经锻造/掉落/商店拿不到 T6。
 */
class EquipmentRarityGateTest {

    /** 定值 Random（只喂 nextDouble；pickRarity 不消费其他通道） */
    private class FixedDoubleRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    // ── 可穿上限 ─────────────────────────────────────────────

    @Test
    fun `可穿最高品阶随境界单调`() {
        // realm 值越小境界越高；rarity 1..6 的门槛表 [9,7,6,5,4,2]
        assertEquals(1, EquipmentFactory.maxWearableRarity(discipleRealm = 9))
        assertEquals(2, EquipmentFactory.maxWearableRarity(discipleRealm = 7))
        assertEquals(3, EquipmentFactory.maxWearableRarity(discipleRealm = 6))
        assertEquals(4, EquipmentFactory.maxWearableRarity(discipleRealm = 5))
        assertEquals(5, EquipmentFactory.maxWearableRarity(discipleRealm = 4))
        assertEquals(6, EquipmentFactory.maxWearableRarity(discipleRealm = 2))
        assertEquals("顶境可穿全部品阶", 6, EquipmentFactory.maxWearableRarity(discipleRealm = 1))
        assertEquals(
            "「不设限」哨兵=REALM_UNRESTRICTED(1)，Int.MAX_VALUE 是最低境界（禁用，B3 实证）",
            6, EquipmentFactory.maxWearableRarity(discipleRealm = EquipmentFactory.REALM_UNRESTRICTED)
        )
    }

    @Test
    fun `门槛表与静态表快照一致`() {
        (1..6).forEach { rarity ->
            // 经公开 entries 面比对（RARITY_MIN_REALMS internal，模块外只读展开条目）
            val entryMinRealm = requireNotNull(EquipmentDatabase.getByRarity(rarity).first().minRealm)
            assertEquals(
                "品阶 $rarity 穿戴门槛与 EquipmentDatabase 快照漂移",
                entryMinRealm,
                GameConfig.Realm.getMinRealmForRarity(rarity)
            )
        }
    }

    // ── 产出钳制（S17 单点保证） ─────────────────────────────

    @Test
    fun `凡人请求T6被钳制到T1`() {
        val clamped = EquipmentFactory.create(
            setId = "lietian", part = EquipmentSlot.WEAPON,
            rarity = 6, rng = Random(1), discipleRealm = 9
        )
        assertEquals("产出品阶钳到可穿上限", 1, clamped.rarity)
        assertEquals("门槛元数据随实际品阶", GameConfig.Realm.getMinRealmForRarity(1), clamped.minRealm)
        // 数值面必须按 T1 生成（与同种子直接 T1 生成逐位一致）
        val direct = EquipmentFactory.create(
            setId = "lietian", part = EquipmentSlot.WEAPON,
            rarity = 1, rng = Random(1), discipleRealm = Int.MAX_VALUE
        )
        assertEquals(direct.growth.affix.mainStat, clamped.growth.affix.mainStat)
        assertEquals(direct.growth.affix.subStats, clamped.growth.affix.subStats)
    }

    @Test
    fun `同种子产出逐位确定`() {
        val a = EquipmentFactory.create(
            setId = "lihuo", part = EquipmentSlot.HEAD, rarity = 3, rng = Random(20260930)
        )
        val b = EquipmentFactory.create(
            setId = "lihuo", part = EquipmentSlot.HEAD, rarity = 3, rng = Random(20260930)
        )
        assertEquals(a.growth, b.growth)
        assertEquals(a.meta, b.meta)
        assertEquals(a.part, b.part)
        assertEquals("离火焚天·灵冠", a.name)
    }

    @Test
    fun `产出实例形态完整`() {
        val created = EquipmentFactory.create(
            setId = "lietian", part = EquipmentSlot.FEET, rarity = 4, rng = Random(7)
        )
        assertEquals("lietian", created.setId)
        assertEquals(EquipmentSlot.FEET, created.part)
        assertEquals("副词条恒 3 条", 3, created.growth.affix.subStats.size)
        assertEquals("副词条互不重复", 3, created.growth.affix.subStats.map { it.stat }.distinct().size)
        assertEquals("强化次数初始恒 1", listOf(1, 1, 1), created.growth.affix.subRolls)
        assertEquals(1, created.level)
        assertEquals(0, created.exp)
    }

    // ── pickRarity 分层抽取 ──────────────────────────────────

    @Test
    fun `分层阈值与方案拍板一致`() {
        // 0.5/0.75/0.9/0.97/0.99 五段偏移 +min
        val top = EquipmentFactory.REALM_UNRESTRICTED
        assertEquals(1, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.49)))
        assertEquals(2, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.50)))
        assertEquals(2, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.74)))
        assertEquals(3, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.75)))
        assertEquals(4, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.90)))
        assertEquals(5, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.97)))
        assertEquals(6, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.99)))
        assertEquals(6, EquipmentFactory.pickRarity(1, discipleRealm = top, rng = FixedDoubleRandom(0.999)))
    }

    @Test
    fun `pickRarity同样受境界钳制`() {
        // 凡人 realmMax=1：任何 roll 都只能产 T1（锻造/掉落/商店共用本入口）
        (0..100).forEach { i ->
            assertEquals(
                1,
                EquipmentFactory.pickRarity(1, discipleRealm = 9, rng = FixedDoubleRandom(i / 101.0))
            )
        }
        // minRarity 高于 realmMax 时收敛到 realmMax
        assertEquals(
            1,
            EquipmentFactory.pickRarity(6, discipleRealm = 9, rng = FixedDoubleRandom(0.999))
        )
    }

    @Test
    fun `minRarity下限不越界`() {
        assertEquals(
            1,
            EquipmentFactory.pickRarity(
                0, discipleRealm = EquipmentFactory.REALM_UNRESTRICTED, rng = FixedDoubleRandom(0.1)
            )
        )
        assertEquals(
            6,
            EquipmentFactory.pickRarity(
                8, discipleRealm = EquipmentFactory.REALM_UNRESTRICTED, rng = FixedDoubleRandom(0.1)
            )
        )
    }

    // ── pickPart ─────────────────────────────────────────────

    @Test
    fun `随机部件恒在套装部位集合内且确定`() {
        val sequence = List(64) { EquipmentFactory.pickPart("lietian", Random(it)) }
        sequence.forEach { part ->
            assertTrue("部件 $part 不在套装内", EquipmentDatabase.setPieces.any { it.setId == "lietian" && it.part == part })
        }
        assertEquals(sequence, List(64) { EquipmentFactory.pickPart("lietian", Random(it)) })
    }

    // ── 产出链单点路由守卫（S17 全链保证，EQ-B4 补面） ────────

    /**
     * 三条具名产出链（锻造/掉落/商店，S17 验收口径）必须路由到
     * [EquipmentFactory.create]（境界钳制唯一入口）；链上文件缺失路由符号即红。
     * 自动购买经 [com.xianxia.sect.core.engine.system.MerchantItemConverter] 间接入厂，
     * 故其路由符号是转换器调用本身。
     */
    @Test
    fun `具名产出链必须经工厂唯一入口`() {
        val namedChains = mapOf(
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/ProductionProcessor处理Ops1.kt"
                to ("锻造" to "EquipmentFactory.create"),
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/ProductionProcessorBatcOps4.kt"
                to ("锻造结算" to "EquipmentFactory.create"),
            "core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineWorldBattleOps.kt"
                to ("战斗掉落" to "EquipmentFactory.create"),
            "core/engine/src/main/java/com/xianxia/sect/core/engine/domain/battle/EnemyGenerator.kt"
                to ("妖兽生成" to "EquipmentFactory.create"),
            "core/engine/src/main/java/com/xianxia/sect/core/engine/system/MerchantItemConverter.kt"
                to ("商店" to "EquipmentFactory.create"),
            "core/engine/src/main/java/com/xianxia/sect/core/engine/service/AutoBuyService.kt"
                to ("自动购买" to "toEquipment(")
        )
        namedChains.forEach { (path, pair) ->
            val (label, symbol) = pair
            val file = File(androidRoot, path)
            assertTrue("产出链文件缺失：$label（$path）", file.isFile)
            assertTrue(
                "$label 链不再路由到工厂入口（缺 $symbol）——S17 境界钳制单点被绕过，" +
                    "请恢复工厂路由或在 EquipmentRarityGateTest 重新登记",
                stripComments(file.readText()).contains(symbol)
            )
        }
    }

    /**
     * 引擎主源直构 [EquipmentInstance] 守卫：产出实例只能出自工厂（钳制 + 词条 roll
     * 单点）。白名单 = 登记的**非产出**占位物化面（AI 轻量实例 I5 / 俘虏装备落库），
     * 新增白名单条目必须在此登记理由。
     */
    @Test
    fun `引擎主源装备实例直构仅限登记面`() {
        val constructorCall = Regex("""(?<![A-Za-z0-9_])EquipmentInstance\(""")
        val engineMain = File(androidRoot, "core/engine/src/main/java")
        val whitelist = mapOf(
            "com/xianxia/sect/core/engine/domain/EquipmentFactory.kt"
                to "工厂本体（唯一产出入口）",
            "com/xianxia/sect/core/engine/domain/diplomacy/Gear.kt"
                to "AI 轻量实例（占位空词条，I5——非产出链，无 roll）",
            "com/xianxia/sect/core/state/CaptiveGearUtils.kt"
                to "俘虏/旧档 AI 载荷物化（占位空词条——非产出链，无 roll）"
        )
        val offenders = mutableListOf<String>()
        engineMain.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            if (constructorCall.containsMatchIn(stripComments(file.readText()))) {
                val relative = file.relativeTo(engineMain).invariantSeparatorsPath
                if (relative !in whitelist) {
                    offenders.add("$relative（发现直构 EquipmentInstance）")
                }
            }
        }
        assertTrue(
            "引擎主源出现未登记的 EquipmentInstance 直构——产出/词条 roll 绕过工厂单点：\n" +
                offenders.joinToString("\n") + "\n处置：改走 EquipmentFactory.create，或在白名单登记非产出理由",
            offenders.isEmpty()
        )
    }

    /** android/ 根（Gradle 测试工作目录 = android/core/engine） */
    private val androidRoot: File = File("..", "..")

    /** 剥 /* */ 与 // 注释（守卫口径宁严勿漏） */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
}
