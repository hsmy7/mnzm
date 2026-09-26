package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.config.GameDataNativeBridge
import com.xianxia.sect.core.engine.domain.gacha.GachaCategorySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPitySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullLedger
import com.xianxia.sect.core.engine.domain.gacha.GachaPullOutcome
import com.xianxia.sect.core.engine.domain.gacha.GachaPullRow
import com.xianxia.sect.core.engine.domain.gacha.GachaRarityWeightSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaService
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.platform.AssetSource
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 回退臂夹具的无界 scope（GachaService 的 StateFlow 派生用；生产由 Dagger 提供）。 */
private object PullTestScopeProvider : CoroutineScopeProvider {
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val ioScope = scope
}

/**
 * DiffGachaPullTest — 寻访抽卡的跨语言双臂对拍（G09，决策 D-2 / D-3 / D-11 / D-15）。
 *
 * ## 对拍双方
 * - **C++ 权威臂**：`gamecore/system/gacha_tx.h::pullTx`，经**既有**动作执行通道
 *   `DiffRngBridge.nativeCoreExecute(ActionIds.GACHA_PULL_ONCE = 1871 /
 *   GACHA_PULL_TEN = 1872, params)` 驱动（`execute_dispatch.cpp` 端口链 →
 *   `dispatch_gacha.cpp` → 事务），事后回读 `nativeCoreExportState()` 取两张碎片账本、
 *   保底计数、寻访历史环与 12 号分区状态；
 * - **Kotlin 回退臂**：[GachaService.pullLocally]（内含 [GachaPullLedger] 的逐字同式
 *   掷点 + 真钱包扣费 + 真历史环），卡池规格由 [GachaPoolConfig] 解析
 *   `assets/data/game-data.json`——**与 C++ 注入的是同一份字节**，不是第二套概率表。
 *
 * 🔴 **生产 JNI 面零新增**（G08 D-18 铁律 3 + `check-jni-count` 恒 86/86：该门禁只扫
 * `src/main` 的两个在册生产桥，测试源集不在面上）：本类用 `DiffRngBridge` 的
 * `nativeDestroy / nativeCoreInit / nativeCoreImportState / nativeCoreExecute /
 * nativeCoreExportState` 五个既有导出，外加桌面专用注入端口
 * [DiffRngBridge.nativeCoreSetGameData]（生产对应物 `GameCoreBridge.nativeSetGameData`
 * 早在册，桌面 .so 不编生产桥文件，故只补测试桥侧同语义端口）。
 *
 * ## 双臂同起点（本批 RNG 分区决策的跨语言锁）
 * 两臂的起点**不是**「各自按种子重播」，而是**逐位同一个 PCG 状态**：
 * 1. Kotlin 臂用 `GameRngManager().initSystemSeed(seed)` ⇒ GACHA 分区 =
 *    `DeterministicRng.fromSeed(seed + 12)`（与 C++ `rng_manager.h` 的 `seed + 12` 同式）；
 * 2. 取该实例的 `snapshot()` 作为 `rngStates[12]` 预置进导入 C++ 的快照；
 * 3. 两臂各跑完同一向量后断言事后 `rngStates[12]` **全等** ⇒ 消费次数与消费顺序被逐位
 *    钉死：少掷一次、多掷一次、换序都会红（rows 相同但消费序不同 ⇒ 后续抽必分叉，
 *    这条断言照样落网）。分区 id 取 [RngPartition.GACHA]（D-3 拍板的独立抽卡流）。
 *
 * ## 双守护的落地形态
 * 1. 双臂用例（出货向量 / 失败臂 / 零消费）——同一组向量喂两条臂，并与 [VECTORS]
 *    黄金表**三方比对**（Kotlin ↔ 表、C++ ↔ 表 ⇒ Kotlin ↔ C++）；
 * 2. [Kotlin 臂与黄金表一致 - 无 JNI 时仍判绿的真守护] 与
 *    [十连 rows 顺序即抽取序 - 双臂与 DTO 契约同向] —— **不依赖桌面 JNI、永不跳过**
 *    的兜底守护：表内每条期望值都与 `gamecore/test/gacha_pull_test.cpp` 的 GTest 断言
 *    同源（保底语义 / 十连 pity 下标 / 池校验拒绝臂逐条同名），故未注入
 *    `-Dgamecore.jni.path` 时「两侧齐备」依然成立、不会静默失真。
 *
 * ## 🔴 桌面 JNI 的 native 臂前置（缺即由前置用例点名判红，勿误读成双臂分叉）
 * C++ 卡池表**没有内联兜底**（`gacha_pool_db.h` 头注释：概率表再抄一份字面量即构成
 * 第二真源），只由 `data_inject.h` 在引擎初始化期注入。生产的注入通道是
 * `app/src/main/cpp/GameCoreBridge.cpp` 的 `nativeSetGameData`（由
 * `GameDataNativeBridge.injectFrom` 喂 assets 产物），而**桌面 .so 不编该文件** ⇒
 * 桌面侧由 `jni/GameCoreJni.cpp` 的同语义端口 [DiffRngBridge.nativeCoreSetGameData]
 * 补齐，本类在首次进 native 臂前把 [FileAssetSource] 定位到的**同一份产物文件**注入
 * （`data_store` 状态机保证「仅初始化期一次」，重复注入按语义返回 false）。
 * [C++ 权威臂可读的卡池表必须已注入] 一条单独判红基础设施缺口并指名修复路径，
 * **不用 assumeTrue 静默跳过**（X-2 #6 假绿源头）。
 *
 * ## 向量覆盖（与 C++ GTest 用例一一对照）
 * 保底抽（`gachaPityCounters` 预置 9 ⇒ 第 10 抽**本身**给 5 片随机角色、计数归零；
 * 同名 `第十抽本身是随机角色碎片且计数归零`）· 第九抽不触发保底（同名
 * `第九抽不触发保底_计数逐位推进`）· 角色抽 +1 片 · 物品抽三张表各自命中（灵草 /
 * 种子 / 兽材；`itemId` 恒落在「按 id 升序」的候选表上）· 十连（10 行、恰一条 pity
 * 在下标 2、一次性扣费 10×5000、保底跨十连连续 ⇒ 同名
 * `保底跨十连连续_恰一条保底行且位置正确`）· 首次跨 1 星才给解锁描述符（同名
 * `首次跨一星才给解锁描述符_已解锁再抽不重复给`）· 余额不足（单抽差 1 片 / 十连只够
 * 九抽 ⇒ 整体失败而非差额抽，同名 `十连余额只够九抽_整体失败而非差额抽`）·
 * 池不存在 `PoolNotFound`。
 *
 * ## 判别力自证（把实现改回旧口径 ⇒ 哪条断言变红）
 * 标「无需 JNI」的两行是**任何环境都会跑**的兜底，其余行需桌面 `.so`。
 * | 构造反例（只改一处） | 变红的用例 / 消息片段 |
 * |---|---|
 * | 保底判定 `pity >= threshold` 改成 `>`（第 10 抽不触发） | 无需 JNI 的 `Kotlin 臂结果与黄金表不符`（保底抽向量 pityAfter 0→10） |
 * | 第 10 抽之外**额外**再送一次碎片（「保底是赠送」旧口径） | 同上（保底抽 frags 由 `{linxuetang=5}` 变 `{linxuetang=10}`） |
 * | 物品候选去掉 `sortedBy(itemId)`（回退到表迭代序） | 同上（三条物品向量的 itemId 变）；双臂另红 `双臂 rows` |
 * | 十连改成逐抽扣费（中途可失败、不做整体前置） | `十连 rows 顺序即抽取序…`（差额场景）+ 双臂 `spiritStonesAfter` 分叉 |
 * | 回退臂 `rows` 保持历史序（新在前，即本批 A-09b 实测形态） | 无需 JNI 的 `十连 rows 顺序即抽取序`（第 0 格是第 10 抽 ⇒ 消息直接点名） |
 * | 抽卡改取 `RngPartition.SYSTEM`（D-3 否决的方案） | `双臂 12 号抽卡分区的事后状态`（native 12 号键不动 ⇒ 与 Kotlin 分叉） |
 * | 失败臂先掷点再校验 | `失败臂零消费 - 抽卡分区状态两臂都不许前移` |
 * | 解锁判据去掉 `starBefore < 1` | 无需 JNI 的 `Kotlin 臂结果与黄金表不符`（解锁向量 unlocked 由 1 条变 6 条） |
 * | 保底计数跨十连不连续（每抽重置） | `十连…`（pityAfter 由 7 变 0）|
 * | 把 `GameConfig.Gacha.PRICE_PER_PULL` 改 5001 | `黄金表的单抽价与 Kotlin 常量不同值` |
 * | 改中性源概率不重对本表 | `Kotlin 回退臂解析出的 standard 池与黄金表假定值不符` |
 */
class DiffGachaPullTest {

    // ignoreUnknownKeys：C++ 导出的 GameData 含 Kotlin 模型未声明的键（镜像通道宽松合并同口径）
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 与 C++ 注入同一份数据文件的卡池读面（回退臂的概率口径来源）。 */
    private val poolConfig = GachaPoolConfig(FileAssetSource())

    // ── 双臂驱动 ────────────────────────────────────────────────────

    /** 重建 C++ 单例并导入本向量的账本预置（含与回退臂逐位同一起点的 12 号分区）。 */
    private fun loadScene(vector: PullVector, rngState: Long) {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        ensureStaticDataInjected()
        val state = NativeGameState(
            gameData = GameData().apply {
                spiritStones = vector.stones
                gachaFragmentCounts = vector.fragments
                gachaStarMap = vector.stars
                gachaPityCounters = pityPreset(vector.poolId, vector.pityBefore)
                gameYear = PRESET_YEAR
                gameMonth = PRESET_MONTH
                rngStates = mapOf(RngPartition.GACHA.id to rngState)
            }
        )
        assertTrue(
            "[${vector.name}] C++ 导入账本预置失败（对拍前提不成立即判红，禁止静默跳过）",
            DiffRngBridge.nativeCoreImportState(
                json.encodeToString(NativeGameState.serializer(), state).encodeToByteArray()
            ),
        )
    }

    /** 保底计数预置：0 不落键（「无键即 0」与 C++ `detail::pityBefore` 同语义）。 */
    private fun pityPreset(poolId: String, pityBefore: Int): Map<String, Int> =
        if (pityBefore > 0) mapOf(poolId to pityBefore) else emptyMap()

    /**
     * 首次进 native 臂前，把与生产同一份数据文件注入 C++ 的静态表存储。
     *
     * `db.gachaPools` / `db.characterTemplates` 按 D-1 刻意**不做内联兜底**（空表 ⇒
     * 真实池一律 `PoolNotFound`），而 `data_store` 的「仅初始化期一次」是进程级状态机
     * （[DiffRngBridge.nativeDestroy] 只销毁引擎实例，不清模板表）⇒ 本类按进程注一次。
     */
    private fun ensureStaticDataInjected() {
        if (staticDataInjected) return
        val accepted = DiffRngBridge.nativeCoreSetGameData(gameDataFile().readText().encodeToByteArray())
        assertTrue(
            "静态表注入被 C++ 拒绝（false = 解析失败或结构与注入器不符，落点 " +
                "android/app/src/main/cpp/gamecore/include/gamecore/data/data_inject.h 的" +
                "「段缺失⇒跳过、段在而非数组/空⇒整体失败」纪律）。" +
                "修复：在仓库根跑 node scripts/gen-game-data.mjs 重生成产物",
            accepted,
        )
        staticDataInjected = true
    }

    private fun exportGameData(): GameData = json.decodeFromString(
        NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
    ).gameData

    /** 本向量的抽卡分区起点状态（两臂共用同一个值，见类注释「双臂同起点」）。 */
    private fun startState(vector: PullVector): Long =
        DeterministicRng.fromSeed(vector.seed + RngPartition.GACHA.id).snapshot()

    /** C++ 权威臂：执行抽卡事务并回读完整状态（失败信封同样读账本——旁路写入照样判红）。 */
    private fun nativeArm(vector: PullVector): ArmOutcome {
        val before = startState(vector)
        loadScene(vector, before)
        val actionId = if (vector.count == 1) ActionIds.GACHA_PULL_ONCE else ActionIds.GACHA_PULL_TEN
        val params = buildJsonObject { put("poolId", vector.poolId) }
        val envelope = json.parseToJsonElement(
            DiffRngBridge.nativeCoreExecute(
                actionId,
                json.encodeToString(JsonObject.serializer(), params).encodeToByteArray(),
            ).decodeToString()
        ).jsonObject
        val status = envelope["status"]?.jsonPrimitive?.content ?: "«信封无 status»"
        val data = envelope["data"]?.jsonObject
        val gameData = exportGameData()
        val snapshot = if (status == SUCCESS && data != null) {
            PullSnapshot(
                code = SUCCESS,
                pricePaid = data.longOf("pricePaid"),
                spiritStonesAfter = data.longOf("spiritStonesAfter"),
                pityAfter = data.longOf("pityAfter").toInt(),
                rows = data.arrayField("rows").map { it.toNativeRow() },
                unlockedTemplateIds = data.arrayField("unlockedTemplateIds")
                    .map { it.jsonPrimitive.content },
                fragments = gameData.gachaFragmentCounts,
                stars = gameData.gachaStarMap,
                history = gameData.gachaHistory,
            )
        } else {
            PullSnapshot(
                code = envelope["code"]?.jsonPrimitive?.content ?: "«失败信封无 code»",
                pricePaid = 0L,
                spiritStonesAfter = gameData.spiritStones,
                pityAfter = gameData.gachaPityCounters[vector.poolId] ?: 0,
                rows = emptyList(),
                unlockedTemplateIds = emptyList(),
                fragments = gameData.gachaFragmentCounts,
                stars = gameData.gachaStarMap,
                history = gameData.gachaHistory,
            )
        }
        return ArmOutcome(
            snapshot = snapshot,
            rngStateBefore = before,
            rngStateAfter = gameData.rngStates[RngPartition.GACHA.id] ?: NO_RNG_STATE,
        )
    }

    /**
     * Kotlin 回退臂：真服务 + 真钱包 + 真分区 RNG 跑 [GachaService.pullLocally]
     * （门面 `GachaFacadeImpl.pull` 在 native 未转发时走的正是这一条）。
     */
    private fun kotlinArm(vector: PullVector): ArmOutcome {
        val pool = poolConfig.pool(vector.poolId)
        val reject = GachaPullLedger.poolError(pool)
        val manager = GameRngManager().apply { initSystemSeed(vector.seed) }
        val before = manager.getRng(RngPartition.GACHA).snapshot()
        val store = newStore(vector, before)
        // 校验未过 ⇒ 不得进入事务（扣费/掷点/写账本全在事务内），与 C++ 的失败臂同构
        val local = if (reject == null) {
            gachaService(store, manager).pullLocally(requireNotNull(pool), vector.poolId, vector.count)
        } else {
            null
        }
        val data = store.gameDataValue
        val snapshot = when {
            local != null -> PullSnapshot(
                code = SUCCESS,
                pricePaid = local.pricePaid,
                spiritStonesAfter = local.spiritStonesAfter,
                pityAfter = local.pityAfter,
                rows = local.rows.map { row ->
                    GachaPullRow(row.category, row.templateId, row.itemId, row.rarity, row.count, row.isPity)
                },
                unlockedTemplateIds = local.unlockedTemplateIds,
                fragments = data.gachaFragmentCounts,
                stars = data.gachaStarMap,
                history = data.gachaHistory,
            )
            else -> PullSnapshot(
                // 校验未过（池缺失/停用/不自洽）或余额不足：两臂都只出结果码，零写入
                code = reject ?: GachaPullOutcome.CODE_INSUFFICIENT,
                pricePaid = 0L,
                spiritStonesAfter = data.spiritStones,
                pityAfter = data.gachaPityCounters[vector.poolId] ?: 0,
                rows = emptyList(),
                unlockedTemplateIds = emptyList(),
                fragments = data.gachaFragmentCounts,
                stars = data.gachaStarMap,
                history = data.gachaHistory,
            )
        }
        return ArmOutcome(snapshot, before, manager.getRng(RngPartition.GACHA).snapshot())
    }

    /** 回退臂夹具的状态预置（与 [loadScene] 喂给 C++ 的字段逐项同值）。 */
    private fun newStore(vector: PullVector, rngState: Long): FakeGameStateStore =
        FakeGameStateStore().apply {
            gameDataValue = GameData().apply {
                spiritStones = vector.stones
                gachaFragmentCounts = vector.fragments
                gachaStarMap = vector.stars
                gachaPityCounters = pityPreset(vector.poolId, vector.pityBefore)
                gameYear = PRESET_YEAR
                gameMonth = PRESET_MONTH
                rngStates = mapOf(RngPartition.GACHA.id to rngState)
            }
        }

    /** 回退臂的服务夹具（真钱包 + 真分区 RNG；入库口按仓库惯例 mockSmart）。 */
    private fun gachaService(store: FakeGameStateStore, manager: GameRngManager): GachaService {
        val inventorySystem = mockSmart(InventorySystem::class.java)
        // withTrackingSource 透传 block（RedeemCodeServiceTest 同款 doAnswer 模式）：
        // 让物品入库描述符的构造路径真实执行，而不是被 mock 吞掉
        Mockito.doAnswer { invocation ->
            invocation.getArgument<() -> Any>(1).invoke()
        }.`when`(inventorySystem).withTrackingSource<Any>(any(), any())
        // block 里的 addHerb/addSeed/addMaterial 返回 sealed interface DomainResult ⇒
        // 默认答案要去 mock 该密封接口并抛 MockitoException（ByteBuddy 无法代理），
        // 必须显式给值（惯例见 ExplorationPatrolRouteTest:98）；回传实参即「入库成功」。
        val stocked = Mockito.doAnswer { invocation ->
            DomainResult.Success(invocation.getArgument<Any>(0))
        }
        stocked.`when`(inventorySystem).addHerb(any())
        stocked.`when`(inventorySystem).addSeed(any())
        stocked.`when`(inventorySystem).addMaterial(any())
        return GachaService(
            stateStore = store,
            scopeProvider = PullTestScopeProvider,
            spiritStoneWallet = SpiritStoneWallet(
                stateStore = store,
                ledger = SpiritStoneLedger(),
                eventBus = mockSmart(EventBus::class.java)
            ),
            gameRngManager = manager,
            inventorySystem = inventorySystem,
        )
    }

    /** 单条向量的双臂对拍：三方比对（Kotlin ↔ 黄金表、C++ ↔ 黄金表）+ 消费序锁。 */
    private fun assertVectorBothArms(vector: PullVector) {
        val tag = "[${vector.name}] pool=${vector.poolId} count=${vector.count} seed=${vector.seed}"
        val kotlin = kotlinArm(vector)
        assertEquals("$tag Kotlin 回退臂与黄金表不符", vector.expected, kotlin.snapshot)

        val native = nativeArm(vector)
        assertEquals("$tag C++ 权威臂与黄金表不符", vector.expected, native.snapshot)
        assertEquals("$tag 双臂 rows 必须逐位一致（顺序即抽取序）", kotlin.snapshot.rows, native.snapshot.rows)
        assertEquals(
            "$tag 双臂 12 号抽卡分区的事后状态必须逐位相同（消费次数与顺序的跨语言锁；" +
                "少掷/多掷/换序都会在这里分叉。0 = C++ 导出的 rngStates 里没有 12 号键）",
            kotlin.rngStateAfter, native.rngStateAfter,
        )
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    fun `出货向量双臂逐位一致 - 保底 角色 物品 十连 解锁`() {
        assumeTrue(DiffRngBridge.isAvailable())
        VECTORS.filter { it.expected.code == SUCCESS }.forEach { vector -> assertVectorBothArms(vector) }
    }

    @Test
    fun `失败臂双臂同结果码且账本零改动 - 余额不足与池不存在`() {
        assumeTrue(DiffRngBridge.isAvailable())
        VECTORS.filter { it.expected.code != SUCCESS }.forEach { vector -> assertVectorBothArms(vector) }
    }

    @Test
    fun `失败臂零消费 - 抽卡分区状态两臂都不许前移`() {
        assumeTrue(DiffRngBridge.isAvailable())
        VECTORS.filter { it.expected.code != SUCCESS }.forEach { vector ->
            val tag = "[${vector.name}] 失败臂零消费"
            val kotlin = kotlinArm(vector)
            assertEquals(
                "$tag：Kotlin 臂不得消费抽卡分区（掷点必须晚于全部校验）",
                kotlin.rngStateBefore, kotlin.rngStateAfter,
            )
            val native = nativeArm(vector)
            assertEquals(
                "$tag：C++ 臂不得消费抽卡分区（否则失败重试会挪动机率流，SL 回档不可复现）",
                native.rngStateBefore, native.rngStateAfter,
            )
        }
    }

    @Test
    fun `C++ 权威臂可读的卡池表必须已注入 - 桌面 JNI 前置条件`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 卡池表无内联兜底（见类注释「native 臂前置」），只由本类首次进 native 臂前经
        // DiffRngBridge.nativeCoreSetGameData 注入。本用例把「注入通道是否在位」单独判红
        // 一次并指名三处修复路径，避免六条双臂用例各报一次「与黄金表不符」被误读成
        // 双臂逻辑分叉（判据本身不放宽：缺前置就是红）。
        val vector = VECTORS.first { it.expected.code == SUCCESS }
        val outcome = nativeArm(vector)
        assertEquals(
            "C++ 权威臂查不到卡池 ${vector.poolId}（实测 code=${outcome.snapshot.code}）——" +
                "这是 native 臂前置缺失，不是双臂逻辑分叉。三处落点：" +
                "\n  ① 端口实现：android/app/src/main/cpp/gamecore/jni/GameCoreJni.cpp 的 " +
                "DiffRngBridge_nativeCoreSetGameData（调 gamecore::data::inject::injectFromJson，" +
                "与生产 app/src/main/cpp/GameCoreBridge.cpp 的 nativeSetGameData 同语义）；" +
                "\n  ② 数据文件：android/app/src/main/assets/data/game-data.json 的 db.gachaPools / " +
                "db.characterTemplates 两段须存在且非空（跑 node scripts/gen-game-data.mjs）；" +
                "\n  ③ 重建桌面 .so：仓库根 pwsh -File scripts/build-desktop-jni.ps1" +
                "（触碰 C++ 必跑，否则 1871/1872 分派根本不在 .so 内）。",
            SUCCESS, outcome.snapshot.code,
        )
    }

    @Test
    fun `Kotlin 臂与黄金表一致 - 无 JNI 时仍判绿的真守护`() {
        // 不依赖桌面 JNI（不 assumeTrue）：期望值与 gacha_pull_test.cpp 的 GTest 断言同源，
        // 故 .so 缺失（双臂用例按惯例跳过）时 D-18 的「两侧齐备」依然成立。
        assertEquals(
            "黄金表的单抽价与 Kotlin 常量不同值——改 GameConfig.Gacha.PRICE_PER_PULL 必须同步重对" +
                "本表与 gacha_pull_test.cpp 的期望值（配置↔Kotlin↔C++ 三向由 CharacterTemplateGuardTest 看护）",
            GameConfig.Gacha.PRICE_PER_PULL, TABLE_PRICE_PER_PULL,
        )
        assertEquals(
            "黄金表的保底阈值与 Kotlin 常量不同值（「第 N 抽本身即保底」的 N），落点同上",
            GameConfig.Gacha.PITY_PULL_THRESHOLD, TABLE_PITY_THRESHOLD,
        )
        assertEquals(
            "黄金表的保底碎片数与 Kotlin 常量不同值（保底那抽发几片），落点同上",
            GameConfig.Gacha.PITY_FRAGMENT_COUNT, TABLE_PITY_FRAGMENT_COUNT,
        )
        assertEquals(
            "黄金表的寻访历史环容量与 Kotlin 常量不同值（C++ 侧为 gacha_tx.h 的 kHistoryRingSize）",
            GameConfig.Gacha.HISTORY_RING_SIZE, TABLE_HISTORY_RING_SIZE,
        )
        assertEquals(
            "黄金表把抽卡分区 id 写死为 12（D-3 拍板的独立抽卡流）——RngPartition.GACHA 变更必须" +
                "同步 C++ rng_manager.h 的 kGacha、本表与 gacha_pull_test.cpp 的分区断言",
            RngPartition.GACHA.id, TABLE_GACHA_PARTITION_ID,
        )
        assertEquals(
            "Kotlin 回退臂解析出的 standard 池与黄金表假定值不符——概率表漂移会使全部期望值失去意义" +
                "（改中性源后跑 node scripts/gen-game-data.mjs，再重对本表）",
            GOLD_STANDARD_POOL, poolConfig.pool(POOL_ID),
        )
        VECTORS.forEach { vector ->
            val tag = "[${vector.name}] pool=${vector.poolId} count=${vector.count} seed=${vector.seed}"
            assertEquals("$tag Kotlin 臂结果与黄金表不符", vector.expected, kotlinArm(vector).snapshot)
        }
    }

    @Test
    fun `十连 rows 顺序即抽取序 - 双臂与 DTO 契约同向`() {
        // GachaPullResult.Success.rows 的契约是「顺序即抽取序，十连第 10 格在末位」，
        // 而 gachaHistory 的契约是「新在前」——两条序**相反**，回退臂若把历史序直接当
        // 回执序返回，结果页会整批倒放（G11 按下标呈现格序）。本例只锁这一条，
        // 失败消息直接给出修复落点。无需 JNI ⇒ 恒跑。
        val ten = VECTORS.first { it.count == 10 && it.expected.code == SUCCESS }
        val rows = kotlinArm(ten).snapshot.rows
        assertEquals("十连回执必须恰好 10 格", 10, rows.size)
        assertEquals(
            "十连必须恰有一条保底行，且落在下标 2（保底预置 7 ⇒ 第 3 抽即保底）——" +
                "实测下标=${rows.indexOfFirst { it.isPity }}",
            2, rows.indexOfFirst { it.isPity },
        )
        assertEquals(
            "回退臂 rows 必须是**抽取序**（第 1 格 = 第 1 抽）。出现逆序即 GachaService.pullLocally" +
                " 把历史环顺序（新在前）当回执返回了——修复落点：core/engine/…/domain/gacha/" +
                "GachaService.kt 的 LocalPull(rows = ...) 取 rows.reversed()，历史环仍保持新在前",
            ten.expected.rows, rows,
        )
        assertEquals(
            "历史环必须新在前（第 10 抽在第 0 条）——与 rows 反向，两序不得混用",
            ten.expected.history, kotlinArm(ten).snapshot.history,
        )
    }

    // ── 数据模型 ────────────────────────────────────────────────────

    /** 两条臂映射到**同一个**快照类型：回执 + 三本账 + 历史环（整表全等即锁死序与稀疏性）。 */
    private data class PullSnapshot(
        val code: String,
        val pricePaid: Long,
        val spiritStonesAfter: Long,
        val pityAfter: Int,
        val rows: List<GachaPullRow>,
        val unlockedTemplateIds: List<String>,
        val fragments: Map<String, Int>,
        val stars: Map<String, Int>,
        val history: List<GachaHistoryEntry>,
    )

    /** 一次执行的快照 + 抽卡分区的事前/事后状态（跨臂消费序锁用）。 */
    private data class ArmOutcome(
        val snapshot: PullSnapshot,
        val rngStateBefore: Long,
        val rngStateAfter: Long,
    )

    /**
     * 黄金表条目（期望值一律写成字面量，见类注释）。
     *
     * @property seed 系统种子（两臂都取 `fromSeed(seed + 12)` 的同一条抽卡流）
     * @property stones 预置下品灵石（出货向量恒等于 totalCost ⇒ 扣费后余额必为 0）
     */
    private data class PullVector(
        val name: String,
        val expected: PullSnapshot,
        val seed: Long,
        val count: Int,
        val stones: Long,
        val pityBefore: Int = 0,
        val fragments: Map<String, Int> = emptyMap(),
        val stars: Map<String, Int> = emptyMap(),
        val poolId: String = POOL_ID,
    )

    /** 纯 JVM 侧的资产读面（`:core:engine` 测试工作目录随运行方式而变，故带三候选）。 */
    private class FileAssetSource : AssetSource {
        override fun open(assetPath: String): InputStream? {
            if (assetPath != GameDataNativeBridge.ASSET_PATH) return null
            return FileInputStream(gameDataFile())
        }
    }

    // ── 信封读面（字段名与 dispatch_gacha.cpp 的编码一一对应）──────────

    private fun JsonObject.longOf(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.arrayField(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.map { it } ?: emptyList()

    private fun JsonElement.toNativeRow(): GachaPullRow {
        val obj = jsonObject
        return GachaPullRow(
            category = obj.text("category"),
            templateId = obj.text("templateId"),
            itemId = obj.text("itemId"),
            rarity = obj.longOf("rarity").toInt(),
            count = obj.longOf("count").toInt(),
            isPity = obj.text("isPity") == "true",
        )
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

    private companion object {
        /** 与 C++ GTest 夹具同池（当前只有一张常驻池） */
        const val POOL_ID = "standard"
        const val SUCCESS = "success"

        /** 数据文件相对 `android/` 的路径与三个候选工作目录前缀（同 CharacterTemplateGuardTest） */
        const val GAME_DATA_RELATIVE = "app/src/main/assets/data/game-data.json"
        val MODULE_DIR_PREFIXES = listOf("../../", "../", "")

        /**
         * 定位生产与桌面共用的那份数据文件：[FileAssetSource]（回退臂的概率口径）与
         * [ensureStaticDataInjected]（C++ 静态表注入）读的是同一个字节流，
         * 任何一侧改动都会立刻在双臂对拍上分叉。
         */
        fun gameDataFile(): File =
            MODULE_DIR_PREFIXES.map { File("$it$GAME_DATA_RELATIVE") }
                .firstOrNull { it.isFile }
                ?: error(
                    "game-data.json 定位失败——已尝试 " +
                        "${MODULE_DIR_PREFIXES.map { File("$it$GAME_DATA_RELATIVE").absolutePath }}；" +
                        "修复：在仓库根跑 node scripts/gen-game-data.mjs"
                )

        /** C++ 静态表注入是进程级一次性状态（data_store 状态机），故按进程记一次 */
        var staticDataInjected = false

        /** 预置时间：绝对月序 = 3×12+7 = 43（两臂同值，进历史条目） */
        const val PRESET_YEAR = 3
        const val PRESET_MONTH = 7
        const val MONTH_INDEX = PRESET_YEAR * 12 + PRESET_MONTH

        /** 黄金表使用的口径常量（与 GameConfig / C++ 常量的对齐由无 JNI 那条用例断言） */
        const val TABLE_PRICE_PER_PULL = 5000
        const val TABLE_PITY_THRESHOLD = 10
        const val TABLE_PITY_FRAGMENT_COUNT = 5
        const val TABLE_HISTORY_RING_SIZE = 50
        const val TABLE_GACHA_PARTITION_ID = 12

        /** 单抽与十连的价格（一次性扣费的判据：十连恒为 10 × 单抽价） */
        const val ONE_COST = TABLE_PRICE_PER_PULL.toLong()
        const val TEN_COST = TABLE_PRICE_PER_PULL * 10L

        /** 导出快照里读不到 12 号分区时的哨兵值（0 = 分区未被导出，本身即异常信号） */
        const val NO_RNG_STATE = 0L

        /**
         * 黄金表假定值：回退臂从数据文件解析出的 standard 池。表内每条期望值都按它推出
         * ⇒ 概率表漂移必须先过这一关（断言在「无 JNI 也判绿」那条用例里）。
         */
        val GOLD_STANDARD_POOL = GachaPoolSpec(
            poolId = POOL_ID,
            enabled = true,
            pricePerPull = TABLE_PRICE_PER_PULL,
            categories = listOf(
                GachaCategorySpec("character_single", 11, listOf("zhouming", "suqing"), "", 0),
                GachaCategorySpec(
                    "character_double", 10, listOf("linxuetang", "xuhe", "xieche", "zhaoyan"), "", 0
                ),
                GachaCategorySpec("beast_material", 26, emptyList(), "beastMaterials", 4),
                GachaCategorySpec("herb", 26, emptyList(), "herbs", 4),
                GachaCategorySpec("seed", 27, emptyList(), "seeds", 4),
            ),
            itemRarityWeights = listOf(
                GachaRarityWeightSpec(4, 12), GachaRarityWeightSpec(3, 33),
                GachaRarityWeightSpec(2, 33), GachaRarityWeightSpec(1, 22),
            ),
            pity = GachaPitySpec(TABLE_PITY_THRESHOLD, TABLE_PITY_FRAGMENT_COUNT, "random"),
        )

        /** 物品向量的通用期望（物品不入碎片账本 ⇒ 两张角色账本恒为空） */
        fun itemExpected(itemId: String, rarity: Int): PullSnapshot = PullSnapshot(
            code = SUCCESS, pricePaid = ONE_COST, spiritStonesAfter = 0L, pityAfter = 1,
            rows = listOf(GachaPullRow("item", "", itemId, rarity, 1, false)),
            unlockedTemplateIds = emptyList(),
            fragments = emptyMap(),
            stars = emptyMap(),
            history = listOf(GachaHistoryEntry(POOL_ID, "item", "", itemId, rarity, 1, false, MONTH_INDEX)),
        )

        /** 单条角色产出的历史条目（新在前的环里只有一条时共用） */
        fun historyOf(category: String, templateId: String, count: Int, isPity: Boolean) =
            GachaHistoryEntry(POOL_ID, category, templateId, "", 0, count, isPity, MONTH_INDEX)

        /**
         * 黄金表：两条臂共用同一组向量（期望值即本表，全部写成字面量）。
         * 序 = 抽取序；历史环 = 新在前（两者相反，见 [十连 rows 顺序即抽取序 - 双臂与 DTO 契约同向]）。
         */
        val VECTORS: List<PullVector> = listOf(
            // ① 保底抽：预置 9 ⇒ 第 10 抽本身给 5 片随机角色、计数归零（P-5：归属全随机）
            PullVector(
                name = "保底抽 第10抽本身5片且计数归零",
                expected = PullSnapshot(
                    code = SUCCESS, pricePaid = ONE_COST, spiritStonesAfter = 0L, pityAfter = 0,
                    rows = listOf(GachaPullRow("pity", "linxuetang", "", 0, 5, true)),
                    unlockedTemplateIds = emptyList(),
                    fragments = mapOf("linxuetang" to 5),
                    stars = emptyMap(),
                    history = listOf(historyOf("pity", "linxuetang", 5, true)),
                ),
                seed = 20260926L, count = 1, stones = ONE_COST, pityBefore = 9,
            ),
            // ② 第九抽：计数推进到 9 但不是保底
            PullVector(
                name = "第九抽不触发保底 计数逐位推进",
                expected = PullSnapshot(
                    code = SUCCESS, pricePaid = ONE_COST, spiritStonesAfter = 0L, pityAfter = 9,
                    rows = listOf(GachaPullRow("character", "xuhe", "", 0, 1, false)),
                    unlockedTemplateIds = emptyList(),
                    fragments = mapOf("xuhe" to 1),
                    stars = emptyMap(),
                    history = listOf(historyOf("character", "xuhe", 1, false)),
                ),
                seed = 20260929L, count = 1, stones = ONE_COST, pityBefore = 8,
            ),
            // ③ 角色抽：碎片 +1，保底计数照常累加
            PullVector(
                name = "角色抽 碎片+1",
                expected = PullSnapshot(
                    code = SUCCESS, pricePaid = ONE_COST, spiritStonesAfter = 0L, pityAfter = 1,
                    rows = listOf(GachaPullRow("character", "xuhe", "", 0, 1, false)),
                    unlockedTemplateIds = emptyList(),
                    fragments = mapOf("xuhe" to 1),
                    stars = emptyMap(),
                    history = listOf(historyOf("character", "xuhe", 1, false)),
                ),
                seed = 20260929L, count = 1, stones = ONE_COST,
            ),
            // ④ 物品抽：三张模板表各自命中（候选按 id 升序钉死 ⇒ 双臂同序）
            PullVector(
                name = "物品抽 灵草二阶命中模板表", seed = 20260926L, count = 1, stones = ONE_COST,
                expected = itemExpected("spiritFruit5", 2),
            ),
            PullVector(
                name = "物品抽 种子三阶命中模板表", seed = 20260927L, count = 1, stones = ONE_COST,
                expected = itemExpected("spiritGrass7Seed", 3),
            ),
            PullVector(
                name = "物品抽 兽材一阶命中模板表", seed = 20260930L, count = 1, stones = ONE_COST,
                expected = itemExpected("eagleFeather0", 1),
            ),
            // ⑤ 十连：一笔事务 10 行、恰一条 pity 在下标 2、一次性扣费 10×5000
            PullVector(
                name = "十连 10行恰一条保底在下标2且一次性扣费",
                expected = PullSnapshot(
                    code = SUCCESS, pricePaid = TEN_COST, spiritStonesAfter = 0L, pityAfter = 7,
                    rows = listOf(
                        GachaPullRow("item", "", "bearHide1", 2, 1, false),
                        GachaPullRow("character", "zhouming", "", 0, 1, false),
                        GachaPullRow("pity", "xuhe", "", 0, 5, true),
                        GachaPullRow("item", "", "spiritFruit2", 1, 1, false),
                        GachaPullRow("character", "linxuetang", "", 0, 1, false),
                        GachaPullRow("character", "zhaoyan", "", 0, 1, false),
                        GachaPullRow("item", "", "spiritGrass12", 4, 1, false),
                        GachaPullRow("item", "", "spiritFruit5", 2, 1, false),
                        GachaPullRow("item", "", "spiritFruit2", 1, 1, false),
                        GachaPullRow("item", "", "spiritGrass3", 1, 1, false),
                    ),
                    unlockedTemplateIds = emptyList(),
                    fragments = mapOf("zhouming" to 1, "xuhe" to 5, "linxuetang" to 1, "zhaoyan" to 1),
                    stars = emptyMap(),
                    history = listOf(
                        GachaHistoryEntry(POOL_ID, "item", "", "spiritGrass3", 1, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "item", "", "spiritFruit2", 1, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "item", "", "spiritFruit5", 2, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "item", "", "spiritGrass12", 4, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "character", "zhaoyan", "", 0, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "character", "linxuetang", "", 0, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "item", "", "spiritFruit2", 1, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "pity", "xuhe", "", 0, 5, true, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "character", "zhouming", "", 0, 1, false, MONTH_INDEX),
                        GachaHistoryEntry(POOL_ID, "item", "", "bearHide1", 2, 1, false, MONTH_INDEX),
                    ),
                ),
                seed = 20260948L, count = 10, stones = TEN_COST, pityBefore = 7,
            ),
            // ⑥ 解锁描述符：六角均差 1 片 ⇒ 保底 5 片必跨 1 星，且只报一次（99+5=104 ⇒ 余 4）
            PullVector(
                name = "解锁向量 首次跨1星才给描述符",
                expected = PullSnapshot(
                    code = SUCCESS, pricePaid = ONE_COST, spiritStonesAfter = 0L, pityAfter = 0,
                    rows = listOf(GachaPullRow("pity", "linxuetang", "", 0, 5, true)),
                    unlockedTemplateIds = listOf("linxuetang"),
                    fragments = mapOf(
                        "zhouming" to 99, "suqing" to 99, "linxuetang" to 4,
                        "xuhe" to 99, "xieche" to 99, "zhaoyan" to 99,
                    ),
                    stars = mapOf("linxuetang" to 1),
                    history = listOf(historyOf("pity", "linxuetang", 5, true)),
                ),
                seed = 20260926L, count = 1, stones = ONE_COST, pityBefore = 9,
                fragments = mapOf(
                    "zhouming" to 99, "suqing" to 99, "linxuetang" to 99,
                    "xuhe" to 99, "xieche" to 99, "zhaoyan" to 99,
                ),
            ),
            // ⑦ 余额不足（单抽差 1 片）：两臂同结果码、灵石/账本/随机流零改动
            PullVector(
                name = "灵石不足 单抽差一片",
                expected = PullSnapshot(
                    code = GachaPullOutcome.CODE_INSUFFICIENT, pricePaid = 0L,
                    spiritStonesAfter = ONE_COST - 1, pityAfter = 0,
                    rows = emptyList(), unlockedTemplateIds = emptyList(),
                    fragments = emptyMap(), stars = emptyMap(), history = emptyList(),
                ),
                seed = 20260926L, count = 1, stones = ONE_COST - 1,
            ),
            // ⑧ 十连余额只够九抽 ⇒ 整体失败而非差额抽（产品 §4.1）
            PullVector(
                name = "十连余额只够九抽 整体失败而非差额抽",
                expected = PullSnapshot(
                    code = GachaPullOutcome.CODE_INSUFFICIENT, pricePaid = 0L,
                    spiritStonesAfter = TEN_COST - 1, pityAfter = 7,
                    rows = emptyList(), unlockedTemplateIds = emptyList(),
                    fragments = emptyMap(), stars = emptyMap(), history = emptyList(),
                ),
                seed = 20260948L, count = 10, stones = TEN_COST - 1, pityBefore = 7,
            ),
            // ⑨ 池不存在：配置外的 poolId 一律 PoolNotFound
            PullVector(
                name = "池不存在 PoolNotFound",
                expected = PullSnapshot(
                    code = GachaPullOutcome.CODE_POOL_NOT_FOUND, pricePaid = 0L,
                    spiritStonesAfter = ONE_COST, pityAfter = 0,
                    rows = emptyList(), unlockedTemplateIds = emptyList(),
                    fragments = emptyMap(), stars = emptyMap(), history = emptyList(),
                ),
                seed = 20260926L, count = 1, stones = ONE_COST, poolId = "no_such_pool",
            ),
        )
    }
}
