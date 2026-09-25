package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaFragmentLedger
import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffGachaFragmentTest — 角色碎片入账的跨语言对拍（G08 c340-2，决策 D-7 / D-18 / D-19 / D-20）。
 *
 * ## 对拍双方
 * - **C++ 权威臂**：`gamecore/system/gacha_fragment.h::addFragment`，经**既有**动作执行通道
 *   `DiffRngBridge.nativeCoreExecute(ActionIds.GACHA_FRAGMENT_GRANT_TX = 1870, params)` 驱动
 *   （`execute_dispatch.cpp` 端口链 → `dispatch_gacha.cpp` → 账本写者），账本回读走
 *   `nativeCoreExportState()` 的整张 map；
 * - **Kotlin 回退臂**：`GachaFragmentLedger.grant`（门面 `GachaFacade.grantFragments` 的降级臂）。
 *
 * 🔴 **零新增 `external fun`**（D-18 铁律 3 + `check-jni-count` 恒 86/86）：本类只用
 * `DiffRngBridge` 已在册的 `nativeDestroy / nativeCoreInit / nativeCoreImportState /
 * nativeCoreExecute / nativeCoreExportState` 五个导出。既有夹具**足以**驱动 1870 分派
 * ⇒ D-18 的退化路径（「两侧各跑一遍同名向量」）**未触发**，本片交付的是真对拍。
 *
 * ## 双守护的落地形态
 * 1. 五条 Diff 用例（入账向量 / 拒绝臂 / 可加性 / 零 RNG / native 参数盲取）——同一组向量喂两条臂，
 *    并与 [VECTORS] 黄金表**三方比对**（Kotlin ↔ 表、C++ ↔ 表 ⇒ Kotlin ↔ C++）；
 * 2. [Kotlin 臂结果与 C++ GTest 同名向量一致 - 无 JNI 时仍判绿的真守护] ——
 *    **不依赖桌面 JNI、永不跳过**的兜底守护：表内每条期望值都与
 *    `gamecore/test/gacha_fragment_test.cpp` 的 GTest 断言值同源，故未注入
 *    `-Dgamecore.jni.path`（第 1 组按 Diff* 惯例跳过）时「两侧齐备」依然成立、不会静默失真。
 *
 * ## 向量覆盖（[GrantVector.name] 与 C++ GTest 用例一一对照）
 * 未达门槛只累加（30 / 50）· 恰好满 100 升 1 星且进度归零 · 一次入账跨两星（250 ⇒ 2 星 50）·
 * 已满 5 星只累加不进位不降级（5 星 50 + 100 ⇒ 5 星 150，不截断不折算）·
 * 已有进度但未跨门槛（1 星 50 + 40 ⇒ 1 星 90）· 事务入口跨星（1 星 80 + 130 ⇒ 3 星 10，
 * 与 GTest `GrantTx_参数齐备_入账并回星级` 同值）· 可加性（+60 两次 == +120 一次 ⇒ 1 星 20）·
 * 拒绝臂（空 templateId / count ≤ 0 ⇒ 账本零改动且**不建空键**）·
 * native 入口参数缺失与类型不符（C++ 盲取语义；Kotlin 侧无此语义 ⇒ 单臂断言，已在该用例 KDoc 标注）。
 *
 * ## 稀疏账本判据（D-19）
 * 期望值写成**整张 map 字面量**（不是 `map[tid]` 取值）：`star == 0` 的成功臂期望
 * `gachaStarMap` **不含该键**、`gachaFragmentCounts` 含该键；拒绝臂期望两张表与预置逐键相同。
 * 故「多建空键」与「少建键」都会红——两侧任一侧回退到「star 0 也写键」即判红。
 * 🔴 按 D-19 的钉子，表内**不使用空白 id（`" "`）试探**：两侧拒绝判据都是
 * `empty()`/`isEmpty()`，空白 id 在 C++ 侧是合法非空键，拿它试探会把「两侧语义本就不同」
 * 误判成分歧（`GachaFacadeImpl.grantFragments` 另有一道 Kotlin-only 的 `isBlank()` 预闸，
 * 该不对称已按公约 12 报主线程并写进 report-G08）。
 *
 * ## 判别力自证（退回旧状态判红；主线程抽验）
 * 表内「无需 JNI」那一行是**任何环境都会跑**的兜底，其余行需桌面 `.so`（`-Dgamecore.jni.path`）。
 * | 测试 | 构造反例（只改一处） | 期望失败消息片段 |
 * |---|---|---|
 * | Kotlin 臂 vs 黄金表（无需 JNI） | `GachaFragmentLedger` 升星条件 `>=` 改成 `>` | `Kotlin 臂结果与黄金表不符` |
 * | 同上（无需 JNI） | `starMap = if (star > 0) …` 改成无条件写键（回退 D-19） | 同上（stars 由 `{}` 变 `{zhouming=0}`） |
 * | 同上（无需 JNI） | 拒绝判据去掉 `templateId.isEmpty()` | 同上（`空模板id 拒绝` 变非 null） |
 * | 同上（无需 JNI） | 把 `GameConfig.Gacha.FRAGMENTS_PER_STAR` 改 99 | `黄金表的门槛字面量与 Kotlin 常量不同值` |
 * | 入账向量双臂逐位一致 | C++ `addFragment` 的 `while` 改成 `if` | `C++ 权威臂与黄金表不符` |
 * | 同上 | C++ 满星后把进度截断为 `kFragmentsPerStar - 1` | 同上（`已满星只累加不进位不降级` 红） |
 * | 同上 | 删掉 C++ 的 `if (star > 0)` 稀疏守卫 | `C++ 两张账本与黄金表不符` |
 * | 拒绝臂双臂零改动 | C++ 拒绝臂改成「仍写 0 进度键」 | 同上（`零数量 拒绝且不建空键` 红） |
 * | 入账零 RNG 消费 | 让 `addFragment` 取一次分区随机数 | `SYSTEM 分区快照必须原样导出` |
 */
class DiffGachaFragmentTest {

    // ignoreUnknownKeys：C++ 导出的 GameData 含 Kotlin 模型未声明的键（镜像通道宽松合并同口径）
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ── 双臂驱动 ────────────────────────────────────────────────────

    /** 重建 C++ 单例并导入本向量的账本预置（含 SYSTEM 分区快照，供零 RNG 审计）。 */
    private fun loadScene(vector: GrantVector) {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        val state = NativeGameState(
            gameData = GameData().apply {
                gachaFragmentCounts = vector.preset.fragments
                gachaStarMap = vector.preset.stars
                rngStates = mapOf(RNG_PARTITION to RNG_SEED_STATE)
            }
        )
        assertTrue(
            "[${vector.name}] C++ 导入账本预置失败（对拍前提不成立即判红，禁止静默跳过）",
            DiffRngBridge.nativeCoreImportState(
                json.encodeToString(NativeGameState.serializer(), state).encodeToByteArray()
            ),
        )
    }

    private fun exportGameData(): GameData = json.decodeFromString(
        NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
    ).gameData

    /** 执行 native 事务并回读一次完整状态（失败信封同样读账本——旁路写入照样判红）。 */
    private fun nativeGrant(params: JsonObject): NativeGrant {
        val envelope = json.parseToJsonElement(
            DiffRngBridge.nativeCoreExecute(
                ActionIds.GACHA_FRAGMENT_GRANT_TX,
                json.encodeToString(JsonObject.serializer(), params).encodeToByteArray(),
            ).decodeToString()
        ).jsonObject
        val status = envelope["status"]?.jsonPrimitive?.content ?: "«信封无 status」"
        val data = envelope["data"]?.jsonObject
        val gameData = exportGameData()
        val ledger = Ledger(gameData.gachaFragmentCounts, gameData.gachaStarMap)
        val outcome = if (status == "success" && data != null) {
            GrantSnapshot(
                starBefore = data.getValue("starBefore").jsonPrimitive.content.toInt(),
                starAfter = data.getValue("starAfter").jsonPrimitive.content.toInt(),
                progressAfter = data.getValue("fragmentsAfter").jsonPrimitive.content.toInt(),
                ledger = ledger,
            )
        } else {
            null
        }
        return NativeGrant(
            outcome = outcome,
            status = status,
            code = envelope["code"]?.jsonPrimitive?.content,
            ledger = ledger,
            rngState = gameData.rngStates[RNG_PARTITION],
        )
    }

    private fun kotlinGrant(vector: GrantVector): GrantSnapshot? {
        val outcome = GachaFragmentLedger.grant(
            fragmentCounts = vector.preset.fragments,
            starMap = vector.preset.stars,
            templateId = vector.templateId,
            count = vector.count,
        ) ?: return null
        return GrantSnapshot(
            starBefore = outcome.starBefore,
            starAfter = outcome.starAfter,
            progressAfter = outcome.fragmentsAfter,
            ledger = Ledger(outcome.fragmentCounts, outcome.starMap),
        )
    }

    private fun grantParams(templateId: String, count: Int): JsonObject = buildJsonObject {
        put("templateId", templateId)
        put("count", count)
    }

    /** 单条向量的双臂对拍：三方比对（Kotlin ↔ 黄金表、C++ ↔ 黄金表）+ 双臂账本逐键全等。 */
    private fun assertVectorBothArms(vector: GrantVector) {
        val tag = "[${vector.name}] tid=\"${vector.templateId}\" count=${vector.count}"
        val expected = vector.expectedOutcome()
        val kotlin = kotlinGrant(vector)
        assertEquals("$tag Kotlin 臂结果与黄金表不符", expected, kotlin)

        loadScene(vector)
        val native = nativeGrant(grantParams(vector.templateId, vector.count))
        assertEquals("$tag C++ 权威臂与黄金表不符", expected, native.outcome)
        assertEquals("$tag C++ 两张账本与黄金表不符", vector.expected, native.ledger)
        assertEquals("$tag 双臂碎片+星级账本必须逐键一致", kotlin?.ledger, native.outcome?.ledger)
        if (expected == null) {
            assertEquals("$tag 无效授予必须落失败信封", "failure", native.status)
            assertEquals("$tag 无效授予的错误码", ERROR_INVALID_GRANT, native.code)
        } else {
            assertEquals("$tag 有效授予必须落成功信封", "success", native.status)
        }
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    fun `入账向量双臂逐位一致 - 累加 满门槛 跨多星 满星溢出`() {
        assumeTrue(DiffRngBridge.isAvailable())
        VECTORS.filter { it.granted }.forEach { vector -> assertVectorBothArms(vector) }
    }

    @Test
    fun `拒绝臂双臂零改动且不为被读键建空条目 - 空 id 与非正数量`() {
        assumeTrue(DiffRngBridge.isAvailable())
        VECTORS.filterNot { it.granted }.forEach { vector -> assertVectorBothArms(vector) }
    }

    @Test
    fun `可加性 双臂等价 - 连续两次入账与一次合并入账同结果`() {
        assumeTrue(DiffRngBridge.isAvailable())
        assertEquals(
            "可加性基数漂移：合并入账数必须恰等于两步之和", STEP * 2, MERGED_COUNT,
        )
        // Kotlin 臂：空账本连投两次 STEP，与一次合并投 MERGED_COUNT 必须同结果、同两本账
        val first = GachaFragmentLedger.grant(emptyMap(), emptyMap(), TEMPLATE_ID, STEP)
        val stepwiseKotlin = first?.let {
            GachaFragmentLedger.grant(it.fragmentCounts, it.starMap, TEMPLATE_ID, STEP)
        }
        val mergedKotlin = GachaFragmentLedger.grant(emptyMap(), emptyMap(), TEMPLATE_ID, MERGED_COUNT)
        assertTrue("Kotlin 臂连投两次未落账（first=$first）", stepwiseKotlin != null)
        assertEquals("Kotlin 臂可加性：连投两次与合并入账必须同结果", stepwiseKotlin, mergedKotlin)
        assertEquals(
            "Kotlin 臂可加性：两张账本必须落在黄金表期望值上（D-19 稀疏性同判据）",
            ADDITIVITY_EXPECTED,
            Ledger(stepwiseKotlin?.fragmentCounts ?: emptyMap(), stepwiseKotlin?.starMap ?: emptyMap()),
        )

        // C++ 臂：同一场景走 native 事务，回执/账本都必须与表内期望一致
        loadScene(EMPTY_PRESET)
        nativeGrant(grantParams(TEMPLATE_ID, STEP))
        val stepwiseNative = nativeGrant(grantParams(TEMPLATE_ID, STEP))
        loadScene(EMPTY_PRESET)
        val mergedNative = nativeGrant(grantParams(TEMPLATE_ID, MERGED_COUNT))
        assertEquals(
            "C++ 臂可加性：连投两次与合并入账的回执+账本必须全等",
            mergedNative.outcome, stepwiseNative.outcome,
        )
        assertEquals(
            "C++ 臂可加性：两张账本必须落在黄金表期望值上（与 Kotlin 臂同一期望值 ⇒ 双臂等价）",
            ADDITIVITY_EXPECTED, stepwiseNative.outcome?.ledger,
        )
    }

    @Test
    fun `入账零RNG消费 - 事务不触碰分区状态`() {
        assumeTrue(DiffRngBridge.isAvailable())
        VECTORS.forEach { vector ->
            loadScene(vector)
            val result = nativeGrant(grantParams(vector.templateId, vector.count))
            assertEquals(
                "[${vector.name}] 零 RNG 审计：入账路径不得消费随机数" +
                    "（SYSTEM 分区快照必须原样导出，否则抽卡/兑换码共用链路会平移 RNG 消费序）",
                RNG_SEED_STATE, result.rngState,
            )
        }
    }

    @Test
    fun `native事务入口 参数缺失或类型不符一律走拒绝臂 - C++ 盲取语义`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 与 gacha_fragment_test.cpp 的 GrantTx_参数缺失或类型不符_走拒绝臂零改动 同名同集：
        // 缺 templateId / 缺 count / 类型不符 ⇒ C++ 按空串与 0 处理落拒绝臂。Kotlin 臂的入参是
        // 非空声明（grantFragments(templateId, count)），不存在「参数缺键」语义 ⇒ 本例只断言
        // C++ 单臂（诚实登记：这不算对拍向量，算 C++ 侧盲写语义的锁定）。
        val cases = listOf(
            "空参数对象" to buildJsonObject { },
            "缺 count" to buildJsonObject { put("templateId", TEMPLATE_ID) },
            "缺 templateId" to buildJsonObject { put("count", DEFAULT_COUNT) },
            "templateId 类型不符" to buildJsonObject {
                put("templateId", 123); put("count", DEFAULT_COUNT)
            },
            "count 类型不符" to buildJsonObject {
                put("templateId", TEMPLATE_ID); put("count", "50")
            },
            "count 为负" to buildJsonObject {
                put("templateId", TEMPLATE_ID); put("count", -DEFAULT_COUNT)
            },
        )
        cases.forEach { (label, params) ->
            loadScene(BLANK_ID_REJECTION)
            val result = nativeGrant(params)
            assertEquals("$label：必须落失败信封", "failure", result.status)
            assertEquals("$label：错误码", ERROR_INVALID_GRANT, result.code)
            assertEquals("$label：两张账本必须零改动", BLANK_ID_REJECTION.expected, result.ledger)
        }
    }

    @Test
    fun `Kotlin 臂结果与 C++ GTest 同名向量一致 - 无 JNI 时仍判绿的真守护`() {
        // 不依赖桌面 JNI（不 assumeTrue）：期望值与 gacha_fragment_test.cpp 的 GTest 断言同源，
        // 故 .so 缺失（五条 Diff 用例按惯例跳过）时 D-18 的「两侧齐备」依然成立。
        assertEquals(
            "黄金表的门槛字面量与 Kotlin 常量不同值——改 GameConfig.Gacha.FRAGMENTS_PER_STAR 必须" +
                "同步重对本表与 gacha_fragment_test.cpp 的期望值（三向一致性由 " +
                "CharacterTemplateGuardTest 看护，此处只锁本表不脱锚）",
            GameConfig.Gacha.FRAGMENTS_PER_STAR, TABLE_FRAGMENTS_PER_STAR,
        )
        VECTORS.forEach { vector ->
            val tag = "[${vector.name}] tid=\"${vector.templateId}\" count=${vector.count}"
            assertEquals("$tag Kotlin 臂结果与黄金表不符", vector.expectedOutcome(), kotlinGrant(vector))
        }
    }

    // ── 数据模型 ────────────────────────────────────────────────────

    /** 两张账本的一次快照（碎片星内进度 + 星级）；整 map 全等即锁死 D-19 的稀疏性。 */
    private data class Ledger(
        val fragments: Map<String, Int> = emptyMap(),
        val stars: Map<String, Int> = emptyMap(),
    )

    /** 期望回执（入账后的星级与星内进度）。 */
    private data class Receipt(val starAfter: Int = 0, val progressAfter: Int = 0)

    /** 一次入账的可观测结果：回执三元组 + 入账后的两张账本。 */
    private data class GrantSnapshot(
        val starBefore: Int,
        val starAfter: Int,
        val progressAfter: Int,
        val ledger: Ledger,
    )

    private data class NativeGrant(
        val outcome: GrantSnapshot?,
        val status: String,
        val code: String?,
        val ledger: Ledger,
        val rngState: Long?,
    )

    /**
     * 黄金表条目（期望值一律写成字面量，见类注释「稀疏账本判据」）。
     *
     * @property granted true = 期望两条臂都落账；false = 期望都拒绝（此时 [expected] 必须等于 [preset]）
     */
    private data class GrantVector(
        val name: String,
        val templateId: String,
        val count: Int,
        val preset: Ledger,
        val granted: Boolean,
        val receipt: Receipt = Receipt(),
        val expected: Ledger = Ledger(),
    ) {
        /** 黄金表期望的回执+账本快照；拒绝臂期望 null（两侧都必须拒绝）。 */
        fun expectedOutcome(): GrantSnapshot? = if (!granted) {
            null
        } else {
            GrantSnapshot(
                starBefore = preset.stars[templateId] ?: 0,
                starAfter = receipt.starAfter,
                progressAfter = receipt.progressAfter,
                ledger = expected,
            )
        }
    }

    private companion object {
        /** 对拍用模板 id（开局模板，与 C++ GTest 的 `kTemplate` 同值） */
        const val TEMPLATE_ID = "zhouming"

        /** 黄金表使用的每星门槛（与 C++ `kFragmentsPerStar` 同值，由无 JNI 的那条用例断言对齐） */
        const val TABLE_FRAGMENTS_PER_STAR = 100

        /** 拒绝臂错误码（C++ `GrantResult::errorType` 与 Kotlin 侧 AppError 分型同名口径） */
        const val ERROR_INVALID_GRANT = "InvalidGrant"

        /** SYSTEM 分区 id（零 RNG 审计的观测点，与 `DiffDiplomacyTxTest` 同分区） */
        const val RNG_PARTITION = 3

        /** 预置进快照的分区状态值（任意稳定值即可——审计只要求它不变） */
        const val RNG_SEED_STATE = 20260925L

        /** 可加性：单步入账数；两步合计 [MERGED_COUNT] 恰好跨一次门槛 ⇒ 1 星 20 */
        const val STEP = 60
        const val MERGED_COUNT = 120

        /** 满星向量的星级上限（与 C++ `kMaxStar` 同值；三向一致性由模板守卫看护） */
        const val FULL_STAR = 5

        /** 盲取用例的参数基数（缺键/类型不符/取负都以此为基准写死，避免魔法数散落） */
        const val DEFAULT_COUNT = 100

        /** 拒绝臂/盲取用例的账本预置值（2 星、星内进度 40；与 C++ GTest 的 seededLedger(2,40) 同值） */
        const val PRESET_STAR = 2
        const val PRESET_PROGRESS = 40

        /** 已预置账本（2 星、星内进度 40）——拒绝臂与盲取用例共用同一预置 */
        val PRESET_SEEDED = Ledger(
            fragments = mapOf(TEMPLATE_ID to PRESET_PROGRESS),
            stars = mapOf(TEMPLATE_ID to PRESET_STAR),
        )

        /** 空模板 id 的拒绝臂向量（表内条目与盲取用例的预置同一个实例） */
        val BLANK_ID_REJECTION = GrantVector(
            name = "空模板id 拒绝", templateId = "", count = DEFAULT_COUNT,
            preset = PRESET_SEEDED, granted = false, expected = PRESET_SEEDED,
        )

        /** 空账本预置（可加性用例用；count 只影响预置读取，不影响 loadScene） */
        val EMPTY_PRESET = GrantVector(
            name = "空账本预置", templateId = TEMPLATE_ID, count = STEP,
            preset = Ledger(), granted = false,
        )

        /** 可加性的期望账本：1 星、星内进度 20（两条臂都必须落在这里） */
        val ADDITIVITY_EXPECTED = Ledger(fragments = mapOf(TEMPLATE_ID to 20), stars = mapOf(TEMPLATE_ID to 1))

        /** 黄金表：两条臂共用同一组向量（期望值即本表） */
        val VECTORS = listOf(
            GrantVector(
                name = "未达门槛只累加(30)", templateId = TEMPLATE_ID, count = 30,
                preset = Ledger(), granted = true, receipt = Receipt(starAfter = 0, progressAfter = 30),
                expected = Ledger(fragments = mapOf(TEMPLATE_ID to 30)),
            ),
            GrantVector(
                name = "未达门槛只累加(50)", templateId = TEMPLATE_ID, count = 50,
                preset = Ledger(), granted = true, receipt = Receipt(starAfter = 0, progressAfter = 50),
                expected = Ledger(fragments = mapOf(TEMPLATE_ID to 50)),
            ),
            GrantVector(
                name = "恰好满门槛升一星且进度归零", templateId = TEMPLATE_ID,
                count = TABLE_FRAGMENTS_PER_STAR, preset = Ledger(), granted = true,
                receipt = Receipt(starAfter = 1, progressAfter = 0),
                expected = Ledger(fragments = mapOf(TEMPLATE_ID to 0), stars = mapOf(TEMPLATE_ID to 1)),
            ),
            GrantVector(
                name = "一次入账跨两星", templateId = TEMPLATE_ID, count = 250,
                preset = Ledger(), granted = true, receipt = Receipt(starAfter = 2, progressAfter = 50),
                expected = Ledger(fragments = mapOf(TEMPLATE_ID to 50), stars = mapOf(TEMPLATE_ID to 2)),
            ),
            GrantVector(
                name = "已满星只累加不进位不降级", templateId = TEMPLATE_ID, count = DEFAULT_COUNT,
                preset = Ledger(fragments = mapOf(TEMPLATE_ID to 50), stars = mapOf(TEMPLATE_ID to FULL_STAR)),
                granted = true, receipt = Receipt(starAfter = FULL_STAR, progressAfter = 150),
                expected = Ledger(
                    fragments = mapOf(TEMPLATE_ID to 150), stars = mapOf(TEMPLATE_ID to FULL_STAR),
                ),
            ),
            GrantVector(
                name = "已有进度但未跨门槛", templateId = TEMPLATE_ID, count = 40,
                preset = Ledger(fragments = mapOf(TEMPLATE_ID to 50), stars = mapOf(TEMPLATE_ID to 1)),
                granted = true, receipt = Receipt(starAfter = 1, progressAfter = 90),
                expected = Ledger(fragments = mapOf(TEMPLATE_ID to 90), stars = mapOf(TEMPLATE_ID to 1)),
            ),
            GrantVector(
                name = "事务入口跨星(GTest GrantTx 同名)", templateId = TEMPLATE_ID, count = 130,
                preset = Ledger(fragments = mapOf(TEMPLATE_ID to 80), stars = mapOf(TEMPLATE_ID to 1)),
                granted = true, receipt = Receipt(starAfter = 3, progressAfter = 10),
                expected = Ledger(fragments = mapOf(TEMPLATE_ID to 10), stars = mapOf(TEMPLATE_ID to 3)),
            ),
            BLANK_ID_REJECTION,
            GrantVector(
                name = "零数量 拒绝且不建空键", templateId = TEMPLATE_ID, count = 0,
                preset = Ledger(), granted = false, expected = Ledger(),
            ),
            GrantVector(
                name = "负数量 拒绝且不建空键", templateId = TEMPLATE_ID, count = -DEFAULT_COUNT,
                preset = Ledger(), granted = false, expected = Ledger(),
            ),
        )
    }
}
