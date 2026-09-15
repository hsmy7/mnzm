package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.state.ReverseChannelPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReverseChannelVolumeProfileTest — 反向信封**体积构成与关闭效果**实测（batch-21 验收面）。
 *
 * 测量口径：单元级 harness（`FakeGameStateStore` + 注入记录桩发送器），
 * 不依赖真机——给出稳态窗口内各通道的字节占比，以及关闭某单元后的字节下降，
 * 作为"逐域关闭可观测验收"（batch-21 §7）的实测证据。
 *
 * 结论口径（与 `docs/ui-read-surface.md` §5 真机基准同源）：gameData 段在多数字段
 * 被关闭后趋近于零；**体积主因是弟子段与实体集合段的全实体 JSON**——这两条通道
 * 的关闭前置是弟子/库存域稳态 Kotlin 写者归零（本次审计证明尚未达成）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ReverseChannelVolumeProfileTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        ReverseChannelPolicy.resetSwitches()
    }

    @Before
    fun restoreTransportForMachineryTest() {
        // 弟子通道已随 w3-13 关闭（handover §2.76 续批）；体积口径守护经覆盖钩子
        // 恢复传输前提（信封机械语义不变）。
        ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.DISCIPLE)
    }

    private fun disciple(id: Int) = Disciple(
        id = id.toString(),
        name = "弟子$id",
        realm = 9,
        realmLayer = 1,
        cultivation = 10.0 + id,
        spiritRootType = "metal",
    )

    /** 构造一个"稳态窗口"：gameData 变更 + 1 名弟子变更 + 1 个集合变更。 */
    private fun measureWindowBytes(): Pair<Int, Map<String, Int>> {
        val store = FakeGameStateStore()
        store.disciplesValue = (1..40).map { disciple(it) }
        store.pillsValue = (1..20).map { Pill(id = "p$it", name = "丹$it", quantity = it) }
        val sent = mutableListOf<String>()
        val sync = StateSyncService(store) { sent += it.decodeToString(); true }

        // 预热窗口：建立反向锚点与各段缓存（生产由引擎启动全量导入完成）
        store.update { gameData = gameData.copy(spiritStones = 1) }
        assertTrue(sync.applyDirtyToNative())
        sent.clear()

        store.update {
            gameData = gameData.copy(
                spiritStones = 12345,
                jadeSymbols = 7,
                lockedBeastIds = setOf("beast-1", "beast-2"),
            )
            discipleTables.cultivations[3] = 99.0
            pills.add(Pill(id = "p21", name = "新丹", quantity = 1))
        }
        assertTrue(sync.applyDirtyToNative())

        val envelope = json.parseToJsonElement(sent.last()).jsonObject
        val changed = envelope["changed"]!!.jsonObject
        val sectionBytes = changed.mapValues { (_, v) -> v.toString().length }
        return sent.last().length to sectionBytes
    }

    @Test
    fun `steady window composition - gameData segment is small, entity segments dominate`() {
        val (total, sections) = measureWindowBytes()
        val gameDataBytes = sections["gameData"] ?: 0
        val disciplesBytes = sections["disciples"] ?: 0
        val collectionBytes = (sections["pills"] ?: 0)
        println(
            "反向信封稳态窗口构成：合计=${total}B；" +
                "gameData=${gameDataBytes}B（${sections["gameData"]?.let { "已含字段" } ?: "无"}）；" +
                "disciples=${disciplesBytes}B；pills=${collectionBytes}B"
        )
        assertTrue("gameData 段应远小于实体段（字段级 dirty 集）", gameDataBytes < disciplesBytes)
        assertTrue("实体集合段应随实体规模增长（全实体 upsert）", collectionBytes > 0)
    }

    @Test
    fun `closing the lockedBeastIds section removes its bytes from the envelope`() {
        val (withSection, sectionsClosed) = measureWindowBytes()
        assertTrue(
            "关闭段（lockedBeastIds）不应出现在信封里：" +
                "changed=${sectionsClosed.keys}",
            !sectionsClosed.containsKey("lockedBeastIds")
        )
        // 对照：临时恢复该域传输 → 段回到信封并带来额外字节
        ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.BATTLE)
        val (withSectionOpen, sectionsOpen) = measureWindowBytes()
        ReverseChannelPolicy.resetSwitches()
        println(
            "lockedBeastIds 段关闭效果：关闭=${withSection}B；恢复传输=${withSectionOpen}B" +
                "（段=${sectionsOpen["lockedBeastIds"] ?: 0}B）"
        )
        assertTrue("恢复传输后该段必须回到信封", sectionsOpen.containsKey("lockedBeastIds"))
        assertTrue("关闭该段必须减少信封字节", withSection < withSectionOpen)
    }

    @Test
    fun `gameData patch carries only changed fields`() {
        val (_, sections) = measureWindowBytes()
        val gameData = sections["gameData"] ?: 0
        assertTrue("gameData 段应为字段级 dirty 集（非全量 134 键）", gameData in 1..600)
    }

    private fun Map<String, Int>.sectionNames(): Set<String> = keys

    @Test
    fun `envelope sections are drawn from the protocol name set`() {
        val (_, sections) = measureWindowBytes()
        val allowed = ReverseChannelPolicy.COLLECTION_NAMES + setOf(
            "gameData",
            "disciples",
            ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES,
            ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS,
        )
        val unknown = sections.sectionNames() - allowed
        assertTrue("信封出现协议外段名：$unknown", unknown.isEmpty())
    }
}
