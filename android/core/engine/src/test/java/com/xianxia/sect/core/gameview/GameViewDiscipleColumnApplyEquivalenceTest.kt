package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.nativebridge.MirrorProtoFeedFixture
import com.xianxia.sect.proto.gameview.DiscipleRow
import com.xianxia.sect.proto.gameview.EquipmentNurtureDataView
import com.xianxia.sect.proto.gameview.StringIntEntry
import com.xianxia.sect.proto.gameview.StringStringEntry
import com.google.protobuf.ByteString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GameViewDiscipleColumnApplyEquivalenceTest — B20a「列级补丁 presence 列直写」
 * （[GameViewDiscipleRows.applyPatchInPlace]）对全行合并臂（`mergeToDisciple` +
 * `upsertMirrorRow`，B20a 前 [com.xianxia.sect.core.nativebridge.StateSyncService]
 * applyDisciplePatches 的唯一形态）的**逐列等价守卫**。
 *
 * 每场景 = 同一基线表两份 → 老臂/新臂各应用同一补丁 → 断言：
 * ① 组装弟子逐字段全等（覆盖 ~百列经域模型的读取面）；
 * ② 协议外/稀疏列净效果全等（lifeEvents 瞬态清空、slotIds 恒 0 回写、
 *    deathYears/lastTheftJudgementYears 稀疏列零触碰——assemble 覆盖不到的面）；
 * ③ changedIdTracker 净效果全等（增量组装基建依赖）。
 *
 * 场景覆盖：热路径标量、全行 emit-always、repeated 整列替换、映射列、社交
 * 线路哨兵（""/0/-1 → null）、孕养嵌套消息 overlay（含部分子字段）、储物袋
 * 三表达（typed 携带 / present 清空 / 75-only 的 typed 优先怪语义）、usage
 * 集合与布尔、空补丁、新行/幽灵行回退契约。
 */
@RunWith(RobolectricTestRunner::class)
class GameViewDiscipleColumnApplyEquivalenceTest {

    private val json: Json = MirrorProtoFeedFixture.json

    @Before
    fun setUp() {
        DiscipleTables.consistencyCheckEnabled = true
    }

    // ── 场景等价断言 ────────────────────────────────────────────

    @Test
    fun `修炼热路径补丁直写与全行合并逐字段全等`() {
        assertEquivalence("热路径三列") {
            cultivation = 999.5
            currentHp = 1234
            currentMp = 567
        }
    }

    @Test
    fun `全行 emit-always 补丁直写与全行合并逐字段全等`() {
        val fullRow = GameViewDiscipleRows.toRow(baseDisciple(), json)
        assertEquivalenceRaw("全行补丁", fullRow)
    }

    @Test
    fun `字符串与标量列直写等价`() {
        assertEquivalence("标量列") {
            name = "改名的玄真"
            portraitRes = "d999"
            discipleType = "elder"
            soulPower = 42
            cultivationSpeedBonus = 2.5
            cultivationSpeedDuration = 12
            realm = 4
            realmLayer = 2
            age = 22
            lifespan = 180
            aptitude = 88
        }
    }

    @Test
    fun `repeated 与映射列整列替换直写等价`() {
        assertEquivalence("repeated/映射列") {
            addAllManualIds(listOf("m9", "m8", "m7"))
            addAllTalentIds(emptyList())  // 零条目 = 列未携带 ⇒ 基线保留
            addManualMasteries(StringIntEntry.newBuilder().setKey("m9").setValue(5))
            addStatusData(StringStringEntry.newBuilder().setKey("k").setValue("v"))
            addAllActivePillTypes(listOf("attack", "speed"))
        }
    }

    @Test
    fun `社交线路哨兵直写等价（空串与零与负一归 null）`() {
        assertEquivalence("社交哨兵") {
            partnerId = ""          // null 化（基线为 "p1"）
            partnerSectId = "sect9" // 赋值
            parentId1 = ""
            griefEndYear = -1       // null 哨兵透传
            childBirthMonth = 0     // null 化
            masterId = ""
            lastChildYear = 7
        }
    }

    @Test
    fun `孕养嵌套消息 overlay 直写等价（全字段与部分子字段）`() {
        val base = baseDisciple().copy(
            equipment = baseDisciple().equipment.copy(
                weaponNurture = EquipmentNurtureData("w1", 3, 4, 55.0),
            ),
        )
        // 部分子字段消息：全行臂对消息列是整值替换（clearer 先清 + mergeFrom
        // 整值写入），absent 子字段按域默认——直写同净效果
        assertEquivalence("孕养部分子字段", base) {
            weaponNurture = EquipmentNurtureDataView.newBuilder()
                .setNurtureLevel(9)
                .build()
        }
        // 全字段消息：整值覆盖
        assertEquivalence("孕养全字段", base) {
            armorNurture = EquipmentNurtureDataView.newBuilder()
                .setEquipmentId("a2")
                .setRarity(5)
                .setNurtureLevel(6)
                .setNurtureProgress(77.5)
                .build()
        }
    }

    @Test
    fun `储物袋三表达直写等价（typed 携带与 present 清空与 75 兼容）`() {
        val bagged = bagDisciple()
        // ① typed 携带：整列替换
        val typedRow = GameViewDiscipleRows.toRow(bagged, json)
        assertEquivalenceRaw("袋 typed 携带", typedRow)
        // ② present 零条目：清袋（列携带位语义）
        assertEquivalence("袋 present 清空", bagged) {
            storageBagItemsPresent = true
        }
        // ③ 75-only 且基线袋非空：老语义 typed 优先吞掉 75 ⇒ 基线保留
        assertEquivalence("袋 75-only 基线非空", bagged) {
            storageBagItemsJson = legacyBagBytes(bagged)
        }
        // ④ 75-only 且基线袋为空：merged 行 typed 计数 0 ⇒ 75 解码可见
        val unbagged = baseDisciple().copy(
            equipment = baseDisciple().equipment.copy(storageBagItems = emptyList()),
        )
        assertTrue("前提：基线袋为空", unbagged.equipment.storageBagItems.isEmpty())
        assertEquivalence("袋 75-only 基线空", unbagged) {
            storageBagItemsJson = legacyBagBytes(bagged)
        }
    }

    @Test
    fun `usage 集合与布尔列直写等价`() {
        assertEquivalence("usage 列") {
            addAllUsedFunctionalPillTypes(listOf("hp", "mp"))
            addAllUsedExtendLifePillIds(listOf("pill-9"))
            addUsedPermanentPillKeys("k1")
            addUsedPermanentPillKeys("k2")
            addUsedExtendLifePillTypes("extend")
            recruitedMonth = 13
            hasReviveEffect = true
            hasClearAllEffect = false
        }
    }

    @Test
    fun `状态与存活列直写等价`() {
        assertEquivalence("status/isAlive") {
            status = "IDLE"
            isAlive = false
            deathYear = 33  // 协议随行字段：两臂同语义丢弃（域模型无对应列）
        }
    }

    @Test
    fun `空补丁净效果等价——协议外瞬态列清空其余列零触碰`() {
        assertEquivalence("空补丁") {
            // 仅 id（setUp 的 newBuilder 已设）——无任何携带列
        }
    }

    // ── 回退契约：新行 / 幽灵行不走直写 ──────────────────────────

    @Test
    fun `新行返回 false 由调用方回退全行臂（稀疏新增照旧抛错）`() {
        val tables = seededTables()
        val sparse = DiscipleRow.newBuilder().setId("999").setCultivation(1.0).build()
        assertFalse(
            "不存在行必须返回 false（回退全行臂，C++ append 恒整行标脏）",
            GameViewDiscipleRows.applyPatchInPlace(
                tables,
                GameViewDiscipleRows.DiscipleRowPatch(sparse),
                json,
            ),
        )
        // 调用方回退后的老臂契约保持：稀疏新增 = 协议漂移，fail-fast 抛错
        assertThrows(IllegalArgumentException::class.java) {
            val merged = GameViewDiscipleRows.mergeToDisciple(
                null,
                GameViewDiscipleRows.DiscipleRowPatch(sparse),
                json,
            )
            tables.upsertMirrorRow(merged)
        }
        assertTrue("回退抛错前不得污染表", !tables.isAlive.contains(999))
    }

    @Test
    fun `幽灵行返回 false 回退全行臂（与 upsertMirrorRow 前语义一致）`() {
        val tables = seededTables()
        tables.isAlive.remove(101)  // 构造幽灵：_ids 在、isAlive 缺键
        val sparse = DiscipleRow.newBuilder().setId("101").setCultivation(1.0).build()
        assertFalse(
            "幽灵行（isAlive 缺键）必须返回 false 回退全行臂",
            GameViewDiscipleRows.applyPatchInPlace(
                tables,
                GameViewDiscipleRows.DiscipleRowPatch(sparse),
                json,
            ),
        )
    }

    // ── 等价断言机体 ────────────────────────────────────────────

    private fun assertEquivalence(
        label: String,
        base: Disciple = baseDisciple(),
        patch: DiscipleRow.Builder.() -> Unit,
    ) {
        val row = DiscipleRow.newBuilder().setId(base.id).apply(patch).build()
        assertEquivalenceRaw(label, row, base)
    }

    private fun assertEquivalenceRaw(label: String, row: DiscipleRow, base: Disciple = baseDisciple()) {
        val fullRowArm = seededTables(base)
        val inPlaceArm = seededTables(base)

        // 老臂 = B20a 前 applyDisciplePatches 的逐行原文
        val merged = GameViewDiscipleRows.mergeToDisciple(
            fullRowArm.assemble(BASE_ID),
            GameViewDiscipleRows.DiscipleRowPatch(row),
            json,
        )
        fullRowArm.upsertMirrorRow(merged)

        // 新臂 = presence 列直写
        val applied = GameViewDiscipleRows.applyPatchInPlace(
            inPlaceArm,
            GameViewDiscipleRows.DiscipleRowPatch(row),
            json,
        )
        assertTrue("$label: 存在行必须走直写臂", applied)

        assertEquals("$label: 组装弟子逐字段全等", fullRowArm.assembleAll(), inPlaceArm.assembleAll())
        assertEquals(
            "$label: lifeEvents 瞬态列净效果（全行臂恒清空）",
            fullRowArm.lifeEvents[BASE_ID],
            inPlaceArm.lifeEvents[BASE_ID],
        )
        assertEquals(
            "$label: slotIds 列净效果（全行臂恒 0 回写）",
            fullRowArm.slotIds[BASE_ID],
            inPlaceArm.slotIds[BASE_ID],
        )
        assertEquals(
            "$label: 稀疏列 deathYears 零触碰",
            fullRowArm.deathYears[BASE_ID],
            inPlaceArm.deathYears[BASE_ID],
        )
        assertEquals(
            "$label: 稀疏列 lastTheftJudgementYears 零触碰",
            fullRowArm.lastTheftJudgementYears[BASE_ID],
            inPlaceArm.lastTheftJudgementYears[BASE_ID],
        )
        assertEquals(
            "$label: changedIdTracker 净效果",
            fullRowArm.changedIdTracker.consumeChangedIds(),
            inPlaceArm.changedIdTracker.consumeChangedIds(),
        )
    }

    // ── 夹具 ────────────────────────────────────────────────────

    private fun seededTables(base: Disciple = baseDisciple()): DiscipleTables = DiscipleTables().also {
        it.writeAllowed = true
        it.insert(base)
        // 预置协议外/稀疏列，钉住两臂净效果（清空与否、触碰与否都要可比）
        it.lifeEvents[BASE_ID] = listOf("21岁：加入宗门", "22岁：试炼")
        it.slotIds[BASE_ID] = 7
        it.deathYears[BASE_ID] = 0
        it.lastTheftJudgementYears[BASE_ID] = 33
    }

    private fun baseDisciple(): Disciple = MirrorProtoFeedFixture.richDisciple()

    /** 富弟子 + 含嵌套 `BagStackedData` 的袋条目（与 MirrorTypedDiscipleBagGuardTest 同源）。 */
    private fun bagDisciple(): Disciple {
        val base = baseDisciple()
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

    /** 旧 75 号形状：整列表的 JSON 原文（B18-P1-A2 前生产编码同源）。 */
    private fun legacyBagBytes(d: Disciple): ByteString = ByteString.copyFromUtf8(
        json.encodeToString(ListSerializer(StorageBagItem.serializer()), d.equipment.storageBagItems)
    )

    private companion object {
        const val BASE_ID = 101  // MirrorProtoFeedFixture.richDisciple 的 id
    }
}
