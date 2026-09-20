package com.xianxia.sect.core.gameview

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.nativebridge.DirtyApplyResult
import com.xianxia.sect.core.nativebridge.FakeGameStateStore
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * GameDataFieldPatchGuardTest —— R2.3 第二波「gameData 每旬级全量重建退场」等价守卫。
 *
 * ## 存在理由（验收门 5：投影完整性证据 · 逐值对照）
 * [GameDataFieldPatch] 把每旬镜像对 gameData 的应用从「整份 GameData JSON
 * encode → 覆盖变更键 → 整份 decode」换成「一次浅拷贝 + 变更字段逐个解码」。
 * 换的是**形状**，一个字段都不能换出**结果**：任何字段级解码与整份解码在
 * 同一 Json 实例 / 同一 serializer 下出现分歧，UI 看到的就是与迁移前不同的值。
 *
 * 对照面 = **测试侧 golden 转写**（[goldenRoundTrip]：被删生产臂的最终语义
 * 冻结快照，B18-臂2）。B18 前对照面是"生产两臂"（`NativeEngineFlag.gameViewProjection`
 * 开/关走 [StateSyncService.applyDirty] 的两条分支）；投影臂退役后回滚臂已从
 * 生产删除，但守卫不得失去对照面——故把旧全量往返臂的最终语义原样搬到测试侧
 * 冻结，[feed]（生产路径）继续与之逐值对照。golden 一旦被"顺手改绿"即失去
 * 对照意义，改动须回到 `GameDataFieldPatch.apply` 复审。
 *
 * ## 三条边界
 * 1. **双射**：写入器键集 == GameData 序列化面（kotlinx 描述符 elementNames）。
 *    新增 gameData 字段而未登记写入器 ⇒ 本用例红——正是
 *    [GameDataFieldPatch.apply] 运行期 fail-fast（"在册未登记即抛错，禁止静默
 *    丢镜像变更"）的静态对应面，两者合成"投影缺失字段 fail-fast"红线的闭环。
 * 2. **失败语义**：任一在册字段解码失败 ⇒ 整组丢弃、gameData 原实例不动
 *    （旧全量臂"解码异常 → 保留 Kotlin 现状"同语义）。
 * 3. **@Transient 副作用面**：旧形状"整份解码把运行态字段打回默认值"的缺陷已
 *    根治（b02 发现 11）——镜像三臂统一经 [GameDataTransientFace] 以事务前值
 *    承载 @Transient 面，且由反射枚举的防复发守卫逐字段锁定（新增字段自动纳管）。
 */
class GameDataFieldPatchGuardTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `写入器键集与 GameData 序列化面双射（漏登记即红）`() {
        val serialized = GameData.Companion.serializer().descriptor.elementNames.toSet()
        assertEquals(
            "gameData 字段级应用表与序列化面漂移 —— 仅表内有：" +
                (GameDataFieldPatch.coveredFields - serialized) +
                "；仅序列化面有（新增字段漏登记 = 镜像静默丢变更）：" +
                (serialized - GameDataFieldPatch.coveredFields),
            serialized,
            GameDataFieldPatch.coveredFields
        )
    }

    @Test
    fun `字段级应用与旧全量 JSON 往返逐值等价（逐类别 golden 对照）`() {
        val cases: List<Pair<String, List<Pair<String, JsonElement>>>> = listOf(
            "标量-String" to listOf(change("sectName", "等价宗")),
            "标量-Int" to listOf(change("gamePhase", 2)),
            "标量-Long" to listOf(change("spiritStones", 9_876_543_210L)),
            "标量-Double" to listOf(change("sectCultivation", 1234.5678)),
            "标量-Boolean" to listOf(change("playerProtectionEnabled", false)),
            "容器-ListString" to listOf(change("unlockedRecipes", listOf("recipe_a", "recipe_b"))),
            "容器-SetInt" to listOf(change("autoRecruitSpiritRootFilter", setOf(1, 3, 5))),
            "容器-MapIntInt" to listOf(change("yearlySalary", mapOf(9 to 100, 0 to 900))),
            "容器-MapIntBoolean" to listOf(change("yearlySalaryEnabled", mapOf(9 to false))),
            "容器-MapStringLong" to listOf(change("guideCounters", mapOf("kill" to 7L))),
            "嵌套-dataclass" to listOf(change("elderSlots", ElderSlots(viceSectMaster = "11"))),
            "嵌套-policy" to listOf(change("sectPolicies", SectPolicies(spiritMineBoost = true))),
            "实体列表-MerchantItem" to listOf(
                change("travelingMerchantItems", listOf(MerchantItem(name = "灵草", rarity = 2, price = 30L)))
            ),
            "实体列表-Disciple" to listOf(
                change("recruitList", listOf(Disciple(id = "77", name = "等价生", realm = 3)))
            ),
            "实体列表-WorldSect" to listOf(
                change("worldMapSects", listOf(WorldSect(id = "w1", name = "剑宗", level = 4)))
            ),
            "实体列表-GameEventRecord" to listOf(
                change(
                    "gameEventRecords",
                    listOf(GameEventRecord(timestamp = FIXED_EVENT_TS, eventType = "BREAKTHROUGH", summary = "突破"))
                )
            ),
            "混合-多字段同封" to listOf(
                change("gameYear", 7),
                change("gameMonth", 11),
                change("spiritStones", 4242L),
                change("watchedItemIds", listOf("pill:聚气丹")),
                change("mailRecords", listOf(MailClaimRecord(mailId = "mail-1", source = "builtin"))),
                change("bloodRefinements", mapOf("501" to listOf("mat-1", "mat-2")))
            ),
            "空变更集" to emptyList()
        )
        for ((label, changes) in cases) {
            val golden = goldenRoundTrip(changes)
            val patched = feed(changes)
            assertEquals("$label：golden 与生产臂 gameData 逐值分歧", golden, patched.gameDataValue)
            if (changes.isNotEmpty()) {
                assertNotSame(
                    "$label：新实例缺失（store 事务以引用变化为提交判据）",
                    richGameData(), patched.gameDataValue
                )
            }
        }
    }

    @Test
    fun `解码失败整组丢弃且保持原实例（旧全量臂同语义）`() {
        val changes = listOf(
            change("gameYear", 5),
            "gamePhase" to JsonPrimitive("旬位不是字符串")
        )
        val patched = feed(changes)
        assertEquals("失败语义：整组变更丢弃（原实例不动）", patched.initialGameData, patched.gameDataValue)
        assertEquals("gameYear 不得留下半套变更", RICH_GAME_YEAR, patched.gameDataValue.gameYear)
        assertSame(
            "丢弃后保留的必须是同一实例（引用不变 = 不触发 StateFlow 重发）",
            patched.initialGameData, patched.store.gameDataValue
        )
        assertEquals("计数为携带字段数（失败也计数，与旧语义一致）", 2, patched.applyResult?.changedFieldCount)
    }

    @Test
    fun `未知键宽松忽略且不中断其余在册字段（前向兼容面）`() {
        val changes = listOf("futureFieldFromNewerNative" to JsonPrimitive(123), change("gameMonth", 6))
        val patched = feed(changes)
        assertEquals("未知键不中断在册字段应用", 6, patched.gameDataValue.gameMonth)
        assertEquals("未知键在 gameData 序列化面之外", false, patched.gameDataValue.run {
            GameData.Companion.serializer().descriptor.elementNames.contains("futureFieldFromNewerNative")
        })
    }

    @Test
    fun `transient 运行态字段经镜像馈送同值（镜像不触碰 @Transient 面——b02 发现 11 根治）`() {
        val patched = feed(listOf(change("gameYear", 8)))
        val gd = patched.gameDataValue
        assertEquals("镜像保留 slotId 现值（不再打回默认 0）", 3, gd.slotId)
        assertEquals(
            "镜像保留 aiBeastEncounterTargets 现值（不再清空）",
            mapOf("beast1" to "aiSectA"), gd.aiBeastEncounterTargets
        )
        assertEquals("镜像保留 autoSaveIntervalMonths 现值", 9, gd.autoSaveIntervalMonths)
        assertEquals("镜像永不主动清空域：aiSectDisciples 保留现值", mapOf("s" to emptyList<Disciple>()), gd.aiSectDisciples)
        assertEquals("镜像永不主动清空域：lockedBeastIds 保留现值", setOf("b7"), gd.lockedBeastIds)
    }

    /**
     * 防复发守卫（结构性）：以 @Transient 注解为权威反射枚举全部运行态字段，
     * 断言镜像馈送前后逐字段值不变——**新增 @Transient 字段自动纳管**，不再依赖
     * 手抄清单（旧形状的教训：手抄 4 字段回填漏掉 5 个，三臂对照守卫因"三臂同错"
     * 而看不见该缺陷）。
     */
    @Test
    fun `防复发 - 全部 Transient 字段经镜像馈送后逐字段保留（新增字段自动纳管）`() {
        val before = richGameData()
        val after = feed(listOf(change("gameMonth", 6)))
        for (name in GameDataTransientFace.fieldNames) {
            val field = GameData::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertEquals(
                "镜像不得触碰 @Transient 字段 $name",
                field.get(before), field.get(after.gameDataValue)
            )
        }
    }

    // ── 对照面夹具 ──────────────────────────────────────────────

    private class Feed(
        val store: FakeGameStateStore,
        /** 馈送**前**的 store 现值实例（失败语义断言的"原实例"基准）。 */
        val initialGameData: GameData,
        val gameDataValue: GameData,
        val applyResult: DirtyApplyResult?
    )

    /** 生产路径馈送（B18 后单臂：恒 [GameDataFieldPatch] 字段级应用）。 */
    private fun feed(changes: List<Pair<String, JsonElement>>): Feed {
        val store = FakeGameStateStore().apply { gameDataValue = richGameData() }
        val initial = store.gameDataValue
        val result = StateSyncService(store).applyDirty(envelopeJson(changes))
        return Feed(store, initial, store.gameDataValue, result)
    }

    /**
     * golden 夹具：被删生产臂（整份 JSON 往返 + @Transient 承载）的最终语义转写。
     *
     * B18-臂2 把「关旗标走整份 GameData JSON 往返」的回滚臂从生产删除；本函数
     * 以测试侧实现冻结其语义，供 [feed] 逐值对照——守卫的对照面因此不因删臂丢失。
     */
    private fun goldenRoundTrip(changes: List<Pair<String, JsonElement>>): GameData {
        val before = richGameData()
        val currentJson = json.encodeToJsonElement(GameData.serializer(), before).jsonObject
        val merged = buildJsonObject {
            currentJson.forEach { (k, v) -> put(k, v) }
            changes.forEach { (name, value) -> put(name, value) }
        }
        val decoded = json.decodeFromJsonElement(GameData.serializer(), merged)
        GameDataTransientFace.carryOver(before, decoded)
        return decoded
    }

    private fun envelopeJson(changes: List<Pair<String, JsonElement>>): String =
        buildJsonObject {
            put("version", 1L)
            putJsonObject("changed") { changes.forEach { (name, value) -> put("gameData.$name", value) } }
            putJsonObject("removed") {}
        }.toString()

    private inline fun <reified T> change(name: String, value: T): Pair<String, JsonElement> {
        // 夹具字段名卡点（b02 发现 10）：不在册字段名会被应用器宽松忽略，
        // 等价断言随之"绿着空转"——在唯一入口拦下
        require(name in GameDataFieldPatch.coveredFields) { "测试字段 '$name' 不在 coveredFields" }
        return name to json.encodeToJsonElement(json.serializersModule.serializer<T>(), value)
    }

    /** 非默认值密集的 gameData——让两臂在每个 wire 类别上都有可观察差异面 */
    private fun richGameData(): GameData = GameData().apply {
        id = "gd-rich"
        slotId = 3
        sectName = "守一宗"
        currentSlot = 2
        gameYear = RICH_GAME_YEAR
        gameMonth = 5
        gamePhase = 1
        spiritStones = 12_345L
        midGradeSpiritStones = 6L
        highGradeSpiritStones = 1L
        spiritHerbs = 77
        sectCultivation = 3.25
        autoSaveIntervalMonths = 9
        yearlySalary = mapOf(9 to 240, 8 to 720)
        yearlySalaryEnabled = mapOf(9 to true)
        unlockedRecipes = listOf("r1")
        unlockedManuals = listOf("m1", "m2")
        autoRecruitSpiritRootFilter = setOf(1, 2)
        guideCounters = mapOf("c1" to 5L)
        elderSlots = ElderSlots(viceSectMaster = "11", herbGardenElder = "12")
        sectPolicies = SectPolicies(spiritMineBoost = true)
        recruitList = listOf(Disciple(id = "501", name = "甲", realm = 5))
        worldMapSects = listOf(WorldSect(id = "w1", name = "青云", level = 3))
        gameEventRecords = listOf(GameEventRecord(timestamp = FIXED_EVENT_TS, eventType = "DEATH", summary = "陨落"))
        aiSectDisciples = mapOf("s" to emptyList())
        aiBeastEncounterTargets = mapOf("beast1" to "aiSectA")
        lockedBeastIds = setOf("b7")
        mapSeed = 4242
        saveVersion = 27
    }

    companion object {
        private const val RICH_GAME_YEAR = 3
        private const val FIXED_EVENT_TS = 1_700_000_000_000L

        /** 未被任何用例覆盖的字段可在此登记（当前守卫覆盖 24 个字段 / 全部 wire 类别） */
        @Suppress("unused")
        private val UNCOVERED_NOTE: JsonObject = JsonObject(emptyMap())
    }
}
