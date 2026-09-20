package com.xianxia.sect.core.nativebridge

import com.google.protobuf.ByteString
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.proto.gameview.CollectionChange
import com.xianxia.sect.proto.gameview.DiscipleListDelta
import com.xianxia.sect.proto.gameview.GameView
import com.xianxia.sect.proto.gameview.JsonFieldChange
import com.xianxia.sect.proto.gameview.ResourcesHeader
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.xianxia.sect.core.nativebridge.MirrorDiscipleRowFixture as rowFixture
import com.xianxia.sect.core.nativebridge.MirrorProtoFeedFixture as fixture

/**
 * MirrorProtoFeedEquivalenceTest — R2.3 第一波验收门 4「全链路守卫」：
 * **proto 信封 → 解码 → GameStateStore 馈送**逐字段与旧 JSON 镜像路径同形同值。
 *
 * ## 与 B06 守卫的分工（为什么还需要本类）
 * `DiffDirtyEnvelopeEquivalenceTest`（R2.2）锁的是**变更集树层**等价（C++ 编码器
 * 产出 ↔ [GameViewMirrorCodec] 还原 ↔ JSON 树 deep-equal）；本类往下再锁一段：
 * 解码产物经 [StateSyncService] 的 applier 落到 GameStateStore 之后，**UI 可见的
 * 数据形状与值**仍与旧路径逐字段一致（弟子行级列存储、实体集合 upsert/remove、
 * gameData 标量与容器、单事务原子性、计数契约）——即"只换传输、UI 无感"这条
 * 红线的运行期证明。
 *
 * ## 为什么不经 native
 * 弟子表底层为 `android.util.SparseArray`（普通 JVM stub 静默 no-op）——行级馈送
 * 断言必须在 Robolectric 环境跑，而桌面 JNI 桥与 Robolectric 沙箱 ClassLoader
 * 冲突（既有约束，同 [DiffDirtyDisciplesTest]）。故本类按 `game_view.proto` 契约
 * 以 javalite builder 手工产出信封，两臂输入由 [MirrorProtoFeedFixture] 的同一个
 * Disciple 实例派生（旧臂 = DiscipleSerializer 平铺协议文本，新臂 = typed 行，
 * 线路口径与 C++ to_json 一致）。"C++ 实际产出 == 手工信封"由 R2.2 编码面对拍
 * 守卫负责，两段合起来覆盖 `native 编码 → 传输 → 解码 → GameStateStore 馈送` 全链。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class MirrorProtoFeedEquivalenceTest {

    private val json = fixture.json

    @Test
    fun `proto envelope feeds store identically to legacy json mirror`() {
        val changed = fixture.richDisciple()
        val viaJson = FakeGameStateStore().also { seed(it) }
        val viaProto = FakeGameStateStore().also { seed(it) }

        val jsonResult = StateSyncService(viaJson).applyDirty(legacyJsonDirty(changed))
        val protoResult = StateSyncService(viaProto).applyDirtyProto(protoDirty(changed))

        // ── 应用契约同值（版本号 / gameData 字段数 / upsert / removed 计数） ──
        assertEquals("DirtyApplyResult 逐字段等价", jsonResult, protoResult)
        assertEquals(DirtyApplyResult(fixture.VERSION, 3, 3, 3), jsonResult)
        assertEquals("两臂同为单事务原子", 1, viaJson.updateCallCount)
        assertEquals(1, viaProto.updateCallCount)

        // ── UI 可见馈送面逐块同形同值 ──
        assertEquals("gameData 全字段一致", viaJson.gameDataValue, viaProto.gameDataValue)
        assertEquals("弟子行集一致", viaJson.disciplesValue, viaProto.disciplesValue)
        assertEquals("pills 一致", viaJson.pillsValue, viaProto.pillsValue)
        assertEquals("herbs 一致", viaJson.herbsValue, viaProto.herbsValue)

        // ── 防"两臂同错"：断言镜像确实落到期望值（非仅彼此相等） ──
        assertEquals(expectedGameData(), viaProto.gameDataValue)
        assertEquals(listOf("101", fixture.NEW_DISCIPLE_ID), viaProto.disciplesValue.map { it.id })
        assertRichDiscipleFed(viaProto.disciplesValue.first { it.id == changed.id })
        assertEquals(listOf("p-keep", "p-new"), viaProto.pillsValue.map { it.id }.sorted())
        assertTrue("herbs 整删", viaProto.herbsValue.isEmpty())
    }

    // ── 两臂共同的初态 ──────────────────────────────────────────

    /** 初态：弟子 101（将被整行覆盖）+ 102（将被删）、pills 两枚、herbs 一枚。 */
    private fun seed(store: FakeGameStateStore) {
        store.gameDataValue = GameData().apply {
            spiritStones = 111
            gameYear = 1
            gameMonth = 5
            gamePhase = 2
            sectName = "镜像宗"
            unlockedManuals = emptyList()
        }
        store.disciplesValue = listOf(
            Disciple().apply { id = "101"; name = "更新前"; realm = 1 },
            Disciple().apply { id = fixture.REMOVED_DISCIPLE_ID; name = "待删" },
        )
        store.pillsValue = listOf(
            Pill(id = "p-old", name = "旧丹", rarity = 1, quantity = 1),
            Pill(id = "p-keep", name = "保留丹", rarity = 2, quantity = 2),
        )
        store.herbsValue = listOf(
            Herb(id = "h-1", name = "灵草", rarity = 1, quantity = 2, category = "普通"),
        )
    }

    private fun expectedGameData(): GameData = GameData().apply {
        spiritStones = fixture.SPIRIT_STONES
        gameYear = fixture.GAME_YEAR
        gameMonth = 5
        gamePhase = 2
        sectName = "镜像宗"
        unlockedManuals = listOf("man-a", "man-b")
    }

    /** 旧 JSON 镜像协议文本（R2.2 换轨前镜像通道的唯一编码形态）。 */
    private fun legacyJsonDirty(changed: Disciple): String = buildJsonObject {
        put("version", fixture.VERSION)
        put(
            "changed", buildJsonObject {
                put("gameData.${fixture.mirrorGameDataField("spiritStones")}", fixture.SPIRIT_STONES)
                put("gameData.${fixture.mirrorGameDataField("gameYear")}", fixture.GAME_YEAR)
                put(
                    "gameData.${fixture.mirrorGameDataField("unlockedManuals")}",
                    element(fixture.UNLOCKED_MANUALS_JSON)
                )
                put("disciples", element(fixture.discipleUpsertsJson(changed)))
                put("pills", element(fixture.PILLS_UPSERT_JSON))
            }
        )
        put(
            "removed", buildJsonObject {
                put("disciples", element("""["${fixture.REMOVED_DISCIPLE_ID}"]"""))
                put("pills", element("""["p-old"]"""))
                put("herbs", element("""["h-1"]"""))
            }
        )
    }.toString()

    /**
     * GameView protobuf 信封（同一变更集的二进制传输形态）——块划分即
     * `game_view.proto` 的生产者分发规则：resourcesHeader（spiritStones 直拷）
     * + gameDataChange（其余 gameData 字段）+ discipleListDelta（typed 行 +
     * removed）+ collectionChange（pills 增删、herbs 删）。
     */
    private fun protoDirty(changed: Disciple): ByteArray =
        GameView.newBuilder()
            .setVersion(fixture.VERSION)
            .setResourcesHeader(ResourcesHeader.newBuilder().setSpiritStones(fixture.SPIRIT_STONES))
            .addGameDataChange(
                JsonFieldChange.newBuilder().setName(fixture.mirrorGameDataField("gameYear"))
                    .setValueJson(ByteString.copyFromUtf8(fixture.GAME_YEAR.toString()))
            )
            .addGameDataChange(
                JsonFieldChange.newBuilder().setName(fixture.mirrorGameDataField("unlockedManuals"))
                    .setValueJson(ByteString.copyFromUtf8(fixture.UNLOCKED_MANUALS_JSON))
            )
            .setDiscipleListDelta(
                DiscipleListDelta.newBuilder()
                    .addUpserts(rowFixture.toGameViewRow(changed))
                    .addUpserts(rowFixture.newDiscipleRow())
                    .addRemovedIds(fixture.REMOVED_DISCIPLE_ID)
            )
            .addCollectionChange(
                CollectionChange.newBuilder().setName("pills")
                    .setUpsertsJson(ByteString.copyFromUtf8(fixture.PILLS_UPSERT_JSON))
                    .addRemovedIds("p-old")
            )
            .addCollectionChange(
                CollectionChange.newBuilder().setName("herbs").addRemovedIds("h-1")
            )
            .build()
            .toByteArray()

    private fun element(text: String) = json.parseToJsonElement(text).jsonArray

    /** 馈送后弟子行的逐字段核对（覆盖每个 wire 类别，证明"同形"而非"同为空"）。 */
    private fun assertRichDiscipleFed(actual: Disciple) {
        assertEquals("玄真", actual.name)
        assertEquals("李", actual.surname)
        assertEquals(5, actual.realm)
        assertEquals(128.5, actual.cultivation, 0.0)
        assertEquals(99L, actual.cultivationCheckpoint.toLong())
        assertEquals(DiscipleStatus.ALCHEMY, actual.status)
        assertEquals(mapOf("m1" to 3, "m2" to 7), actual.manualMasteries)
        assertEquals(mapOf("task" to "alchemy", "slot" to "42"), actual.statusData)
        assertEquals(listOf("m1", "m2"), actual.manualIds)
        assertEquals(setOf("dan1"), actual.pillEffects.activePillTypes)
        assertEquals(500, actual.combat.baseHp)
        assertEquals(9999999L, actual.combat.totalCultivation)
        assertEquals(-1, actual.combat.currentHp)
        assertEquals("w1", actual.equipment.weaponId)
        assertEquals(4, actual.equipment.weaponNurture.nurtureLevel)
        assertEquals(0.25, actual.equipment.weaponNurture.nurtureProgress, 0.0)
        assertEquals(1, actual.equipment.storageBagItems.size)
        assertEquals("s1", actual.equipment.storageBagItems.first().itemId)
        assertEquals(55L, actual.equipment.storageBagSpiritStones)
        assertEquals("p2", actual.social.partnerId)
        assertEquals(20, actual.social.griefEndYear)
        assertNull(actual.social.parentId1)
        assertEquals(setOf("3#hpAdd"), actual.usage.usedPermanentPillKeys)
        assertTrue(actual.usage.hasClearAllEffect)
    }

    /**
     * proto3 present 语义纪律守卫（b02 发现 7）：空信封零变更可解；wire 层
     * "空集合"与"字段缺省"不可区分（缺省消息序列化为零字节）——消费侧一律
     * 回落域模型默认值，present 不得作业务判据（纪律全文见 game_view.proto 头）。
     * 本测试同时是启动期预热（ensureAuthoritativeNative 解空信封）的同形状证明。
     */
    @Test
    fun `空信封零变更可解且空集合与缺省线路同形（present 不承载业务语义）`() {
        val decoded = GameViewMirrorCodec.decode(GameView.getDefaultInstance().toByteArray())
        assertEquals("缺省信封 version=0", 0L, decoded.version)
        assertTrue("缺省信封不得产出变更键", decoded.changed.isEmpty())
        assertTrue("缺省信封不得产出删除键", decoded.removed.isEmpty())
        assertTrue("缺省信封不携带弟子投影", decoded.discipleProjections.isEmpty())
        // wire 不可区分性的直接证明：全缺省消息序列化为零字节——proto3 没有
        // "空集合"的编码位，任何按 present 分叉业务语义的消费点都在制造假语义
        assertTrue(
            "缺省 GameView 应序列化为零字节",
            GameView.getDefaultInstance().toByteArray().isEmpty()
        )
        assertTrue(
            "缺省 DiscipleListDelta 应序列化为零字节",
            DiscipleListDelta.getDefaultInstance().toByteArray().isEmpty()
        )
    }
}
