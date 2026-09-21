package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.core.gameview.GameViewDiscipleRows
import com.xianxia.sect.core.gameview.toJsonObject
import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.proto.gameview.DiscipleRow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * MirrorTypedDiscipleBagGuardTest — B18-P1-A2「`DiscipleRow.storageBagItems` 由
 * 75 号 JSON 原文换轨 110 号 typed 行」的等价守卫（实施文档 §1.5 第 2 层，
 * 与 [MirrorTypedEnvelopeEquivalenceTest] 同族：彼管信封级两字段，本管行级列）。
 *
 * ## 三臂
 * - **旧 75 号 bytes 臂**（恒跑）：直设 JSON 原文 —— B18-P1-A2 前的生产形状，
 *   同时证明 fallback 未被删断言（对照面保留）；
 * - **typed 行臂**（恒跑）：生产 [GameViewDiscipleRows.toRow] 直出 —— 无桥环境
 *   的等价证明（含嵌套 `BagStackedData` 递归结构）；
 * - **C++ 真编码臂**（`DiffRngBridge` 可用时跑，组合门内恒激活）：同一棵树经真
 *   `encodeGameView` 编码后解码，断言 110 号在位 + **75 号停写** + 列携带位在位。
 *
 * ## 为什么需要「列携带位」（本笔最值钱的判据）
 * 75 号是**标量**：`bytes` 空与非空天然区分「未携带」与「携带空袋」。
 * 换轨到 repeated `storageBagItemsTyped` 后，**零条目同时表示两种相反语义** ——
 * 「列缺省（保留 store 基线）」与「列脏且清空（袋被扣空/清袋，须整体替换为空）」。
 * 列级合并（[GameViewDiscipleRows.mergeToDisciple]）按补丁 presence 决定是否
 * 清空基线，故缺判别位会让"清空袋"静默丢失。本守卫的第二测即锁该语义（双向）。
 */
class MirrorTypedDiscipleBagGuardTest {

    private val json = MirrorProtoFeedFixture.json

    // ── 臂 1 vs 臂 2：JVM 恒跑的双路等价 ──────────────────────────

    @Test
    fun `弟子行 storageBagItems typed 与旧 75 号 bytes 原文解码逐字段相等`() {
        val d = bagDisciple()
        val typedRow = GameViewDiscipleRows.toRow(d, json)
        val legacyRow = typedRow.toBuilder()
            .clearStorageBagItemsTyped()
            .clearStorageBagItemsPresent()
            .setStorageBagItemsJson(legacyBagBytes(d))
            .build()

        // 先钉住两臂形状本身（防"两臂同错"）
        assertEquals("typed 臂：条目数", 2, typedRow.storageBagItemsTypedCount)
        assertTrue("typed 臂：列携带位", typedRow.storageBagItemsPresent)
        assertTrue("typed 臂：75 号已停写", typedRow.storageBagItemsJson.isEmpty)
        assertEquals("legacy 臂：无 typed 条目", 0, legacyRow.storageBagItemsTypedCount)
        assertTrue("legacy 臂：无列携带位", !legacyRow.storageBagItemsPresent)
        assertTrue("legacy 臂：75 号在位", !legacyRow.storageBagItemsJson.isEmpty)

        val fromTyped = GameViewDiscipleRows.toDisciple(typedRow, json)
        val fromLegacy = GameViewDiscipleRows.toDisciple(legacyRow, json)
        assertEquals(
            "储物袋条目（含嵌套 BagStackedData）逐字段相等",
            fromLegacy.equipment.storageBagItems,
            fromTyped.equipment.storageBagItems,
        )
        assertEquals("整行投影逐字段相等", fromLegacy, fromTyped)
    }

    // ── 列携带位语义：清空 vs 未携带（双向）─────────────────────────

    @Test
    fun `空袋补丁按列携带位清空基线（repeated 列整体替换语义）`() {
        val d = bagDisciple()
        val base = GameViewDiscipleRows.toDisciple(GameViewDiscipleRows.toRow(d, json), json)
        assertTrue("前提：基线袋非空", base.equipment.storageBagItems.isNotEmpty())

        // ① 列脏且清空 = 零 TypedRow + 列携带位（C++ 扣空袋后的真实形态）
        val emptyPatch = DiscipleRow.newBuilder()
            .setId(d.id)
            .setStorageBagItemsPresent(true)
            .build()
        val cleared = GameViewDiscipleRows.mergeToDisciple(
            base,
            GameViewDiscipleRows.DiscipleRowPatch(emptyPatch),
            json,
        )
        assertTrue(
            "列携带位必须在位 ⇒ 整列替换为空（缺该位即静默保留旧袋 = 数据丢失）",
            cleared.equipment.storageBagItems.isEmpty(),
        )

        // ② 列未携带 = 零 TypedRow 且无位 ⇒ 保留 store 基线
        val absentPatch = DiscipleRow.newBuilder().setId(d.id).build()
        val kept = GameViewDiscipleRows.mergeToDisciple(
            base,
            GameViewDiscipleRows.DiscipleRowPatch(absentPatch),
            json,
        )
        assertEquals(
            "列未携带必须保留基线袋（与 ① 构成双向断言：位存在与否语义相反）",
            base.equipment.storageBagItems,
            kept.equipment.storageBagItems,
        )
    }

    // ── 臂 3：桌面 C++ 真编码（组合门内激活；无 .so 时跳过非本守卫失败）──

    @Test
    fun `C++ 真编码弟子行走 typed 且 75 号停写（列携带位在位）`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val bagJson = """[{"id":"s1","count":9}]"""
        val tree = """{"version":11,"changed":{"disciples":[""" +
            """{"id":"7","name":"玄真","storageBagItems":$bagJson}]},"removed":{}}"""
        val gv = GameViewMirrorCodec.parse(
            DiffRngBridge.nativeCoreEncodeGameView(tree.encodeToByteArray())
        )
        val row = gv.discipleListDelta.upsertsList[0]

        assertEquals("typed 行承载", 1, row.storageBagItemsTypedCount)
        assertTrue("75 号 bytes 停写（号冻结）", row.storageBagItemsJson.isEmpty)
        assertTrue("列携带位在位", row.storageBagItemsPresent)
        assertEquals(
            "typed 行重建与 JSON 原文逐字段相等",
            json.parseToJsonElement(bagJson).jsonArray[0],
            row.storageBagItemsTypedList[0].toJsonObject(),
        )
    }

    // ── 夹具 ────────────────────────────────────────────────────

    /** 富弟子 + 一个带嵌套 `BagStackedData` 的袋条目（覆盖递归承载形状）。 */
    private fun bagDisciple(): Disciple {
        val base = MirrorProtoFeedFixture.richDisciple()
        return base.copy(
            equipment = base.equipment.copy(
                storageBagItems = base.equipment.storageBagItems + StorageBagItem(
                    itemId = "s2",
                    itemType = "equipment",
                    name = "青锋剑",
                    rarity = 3,
                    quantity = 1,
                    obtainedYear = 4,
                    obtainedMonth = 5,
                    stackedData = BagStackedData(minRealm = 2, slot = "weapon", manualType = ""),
                ),
            ),
        )
    }

    /** 旧 75 号形状：整列表的 JSON 原文（与 B18-P1-A2 前生产编码同源）。 */
    private fun legacyBagBytes(d: Disciple): ByteString = ByteString.copyFromUtf8(
        json.encodeToString(ListSerializer(StorageBagItem.serializer()), d.equipment.storageBagItems)
    )
}
