package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaCategorySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaItemGrant
import com.xianxia.sect.core.engine.domain.gacha.GachaPullLedger
import com.xianxia.sect.core.engine.domain.gacha.GachaPullOutcome
import com.xianxia.sect.core.engine.domain.gacha.GachaPitySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaRarityWeightSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaService
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回退臂夹具的无界 scope（生产由 Dagger 提供；此处只需 StateFlow 有个落点）。 */
private object GuardPullScopeProvider : CoroutineScopeProvider {
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val ioScope = scope
}

/**
 * GachaPullGuardTest — 寻访回退臂 [GachaPullLedger] 的**口径面**守卫（G09，无 JNI）。
 *
 * ## 与本批另两面守卫的分工
 * - `gacha_pull_test.cpp`（C++ GTest）锁 C++ 权威臂自身；
 * - [DiffGachaPullTest] 锁两条臂的逐位等价（含事后分区状态）；
 * - **本类**锁 Kotlin 回退臂与 C++ 头注释写死的三条契约，且**不依赖 native**：
 *   1. **掷点消费序**：保底 1 次 / 角色 3 次 / 物品 4 次 `nextInt(bound)`
 *      （见 `gacha_tx.h` 头注释「RNG 契约」与 [GachaPullLedger] 文件头）；
 *      判据不止次数——还断言**bound 序列**，故「换序」「少掷一次」「候选集规模算错」
 *      都会红；
 *   2. **失败零消费**：[GachaPullLedger.poolError] 拒绝的每一种输入都不产生任何掷点；
 *   3. **拒绝码逐分支同名**：每个不自洽配置都落到与 C++ `checkPool` 相同的结果码
 *      （`PoolNotFound` / `PoolDisabled` / `PoolMalformed`），正常配置返回 null。
 *   另加三条口径：`itemCandidates` 的**升序**（双臂同序的必要条件）、寻访历史环的
 *   淘汰语义（新在前 + 容量 = [GameConfig.Gacha.HISTORY_RING_SIZE]）与**数量加权边界**
 *   （碎片 30/20/20/20/10、物品 2/4/9/15/20/20/15/9/4/2 逐档断言）。
 *
 * ## 判别力来源：计数型随机源 [RollCountingRng]
 * [DeterministicRng] 的子类，只记账不改变数值（`super.nextInt(bound)` 原样转发）。
 * 「物品抽少掷一次」如何被捕获：物品臂的 bound 序列必须是 `[100, 100, 候选数, 100]`
 * ——若实现漏掉品阶掷点（旧口径：直接取类别 `maxRarity`），序列变成
 * `[100, 候选数, 100]` ⇒ [物品抽掷四次 - 类别 品阶 候选 数量] 的 `size` 与 `bounds` 双红；
 * 若实现把品阶掷点提到类别掷点之前，`bounds` 仍是 `[100,100,N,100]` 但**出货分叉**，
 * 由 [DiffGachaPullTest] 的 rows/事后状态断言兜住（两臂分工：本类锁形状，Diff 锁值）。
 *
 * ## 判别力自证（把实现改回旧口径 ⇒ 哪条断言变红）
 * | 构造反例（只改一处） | 变红的用例 / 消息片段 |
 * |---|---|
 * | 保底抽顺手 roll 一次类别（`nextInt(100)`） | `保底抽只掷一次` —— bounds `[2]` → `[100, 2]` |
 * | 保底候选不过滤灵根（退回全角色集） | `保底归属限单灵根` —— bounds `[2]` → `[3]`（双灵根混进候选） |
 * | 角色抽去掉数量掷点（退回恒 1 片） | `角色抽掷三次` —— bounds 少一个 `100`、size 3 → 2 |
 * | 碎片数量表换成恒 1 片（`[100]`） | `碎片数量加权边界` —— roll≥30 的档位全数判红 |
 * | 物品抽去掉品阶掷点（直接用类别 maxRarity） | `物品抽掷四次` —— bounds 少一个 `100`、size 4 → 3 |
 * | 物品数量表换成恒 1 件 | `物品数量加权边界` —— roll≥2 的档位全数判红 |
 * | 物品抽候选不排序（回退表迭代序） | `itemCandidates 按模板 id 升序` —— 首元素由 `spiritFlower1` 变 `spiritGrass1` |
 * | 候选排序改成按 name | 同上（消息给出实测 id 序列） |
 * | `poolError` 去掉 `pickMode` 判据 | `poolError 逐分支拒绝码` —— 该条期望值不符（返回 null） |
 * | 失败臂改成「先掷点后校验」 | `失败臂零消费` —— bounds 非空（正向对照保证本判据不是空转） |
 * | 历史环写成 `(旧 + 新).take(N)` | `寻访历史环` —— 第 0 条不是本次新抽 |
 * | 环容量硬编码 30（绕过常量） | `寻访历史环` —— 长度 30 ≠ HISTORY_RING_SIZE 50 |
 * | 保底判定 `>=` 改 `>` | `保底抽只掷一次`（走成角色抽的 3 次）+ `同种子重放同结果` 的 pityAfter |
 * | `clampedRarity` 去掉截断 | `品阶截断` —— 期望 4 实得 6 |
 *
 * 断言的期望值类型与被测表达式类型一致（`emptyList<Int>()` 对 `List<Int>`、
 * `emptyList<GachaItemGrant>()` 对 `List<GachaItemGrant>`）——本仓踩过
 * `emptySet()` 对 `List` 恒红的坑。
 */
class GachaPullGuardTest {

    // ── ① 掷点消费序 ────────────────────────────────────────────────

    @Test
    fun `保底抽只掷一次 - 不 roll 类别 只在角色候选里选归属`() {
        val rng = RollCountingRng(GUARD_SEED)
        val step = GachaPullLedger.pullOnce(
            rng = rng, pool = characterOnlyPool(), poolId = POOL_ID,
            pityBefore = PITY_THRESHOLD - 1, fragmentCounts = emptyMap(),
            starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        assertEquals(
            "保底抽的掷点必须只有「候选归属」一次（第 10 抽本身即保底，不 roll 类别）",
            listOf(CHARACTER_CANDIDATES), rng.bounds,
        )
        assertEquals(
            "保底抽的掷点次数（少掷/多掷都会挪动整条抽卡流，双臂事后状态随即分叉）",
            PITY_ROLL_COUNT, rng.bounds.size,
        )
        assertEquals("保底抽的 category 必须是 pity", GachaPullLedger.CATEGORY_PITY, step.row.category)
        assertTrue("保底抽必须标 isPity", step.row.isPity)
        assertEquals("保底抽发 fragmentCount 片", PITY_FRAGMENT_COUNT, step.row.count)
        assertEquals("保底抽不带物品", null, step.itemGrant)
        assertEquals("保底后计数归零", 0, step.pityAfter)
    }

    @Test
    fun `保底归属限单灵根 - singleSpiritRoot 只在灵根数为一的候选里选`() {
        val rng = RollCountingRng(GUARD_SEED)
        val pool = broken {
            it.copy(
                categories = listOf(characterCategory(ids = listOf("zhouming", "suqing", "linxuetang"))),
                pity = it.pity.copy(pickMode = PICK_MODE_SINGLE_SPIRIT_ROOT),
            )
        }
        val step = GachaPullLedger.pullOnce(
            rng = rng, pool = pool, poolId = POOL_ID,
            pityBefore = PITY_THRESHOLD - 1, fragmentCounts = emptyMap(),
            starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        assertEquals(
            "singleSpiritRoot 保底的候选掷点只数**过滤后**的单灵根候选" +
                "（双灵根模板不得进集——过滤在掷点之前，bound 才是 2 而非 3）",
            listOf(SINGLE_ROOT_CANDIDATES), rng.bounds,
        )
        assertTrue(
            "归属必须落在单灵根角色上（实测 ${step.row.templateId}）",
            step.row.templateId == "zhouming" || step.row.templateId == "suqing",
        )
    }

    @Test
    fun `角色抽掷三次 - 类别 候选 碎片数量`() {
        val rng = RollCountingRng(GUARD_SEED)
        val step = GachaPullLedger.pullOnce(
            rng = rng, pool = characterOnlyPool(), poolId = POOL_ID,
            pityBefore = 0, fragmentCounts = emptyMap(),
            starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        assertEquals(
            "角色抽的掷点序列必须是「类别 nextInt(100) → 候选 nextInt(候选数) → " +
                "碎片数量 nextInt(100)」（少一次数量掷点 = 退回「恒 1 片」的旧口径）",
            listOf(WEIGHT_TOTAL, CHARACTER_CANDIDATES, WEIGHT_TOTAL), rng.bounds,
        )
        assertEquals("角色抽的掷点次数", CHARACTER_ROLL_COUNT, rng.bounds.size)
        assertEquals("角色抽的 category", GachaPullLedger.CATEGORY_CHARACTER, step.row.category)
        assertTrue(
            "角色命中得 1..5 片（数量加权，实测 ${step.row.count}）",
            step.row.count in 1..5,
        )
        assertEquals("未触发保底的抽计数 +1", 1, step.pityAfter)
    }

    @Test
    fun `物品抽掷四次 - 类别 品阶 候选 数量`() {
        val rng = RollCountingRng(GUARD_SEED)
        val candidates = GachaPullLedger.itemCandidates(ITEM_SOURCE_HERBS, RARITY_ONE_LEVEL).size
        val step = GachaPullLedger.pullOnce(
            rng = rng, pool = herbOnlyPool(), poolId = POOL_ID,
            pityBefore = 0, fragmentCounts = emptyMap(),
            starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        assertEquals(
            "物品抽的掷点序列必须是「类别 nextInt(100) → 品阶 nextInt(100) → " +
                "候选 nextInt(候选数) → 数量 nextInt(100)」（少一次品阶掷点 = 回退到" +
                "「取类别 maxRarity」的旧口径；少末位掷点 = 退回「恒 1 件」）",
            listOf(WEIGHT_TOTAL, WEIGHT_TOTAL, candidates, WEIGHT_TOTAL), rng.bounds,
        )
        assertEquals("物品抽的掷点次数", ITEM_ROLL_COUNT, rng.bounds.size)
        assertEquals("物品抽的 category", GachaPullLedger.CATEGORY_ITEM, step.row.category)
        assertNotNull("物品抽必须给出入库描述符", step.itemGrant)
        assertTrue(
            "物品入库描述符必须带掷出的数量 1..10（实测 ${step.itemGrant?.count}）",
            step.itemGrant?.count in 1..10,
        )
        assertEquals("角色两张账本不得被物品抽写键", emptyMap<String, Int>(), step.fragmentCounts)
        assertTrue("物品行不带 templateId", step.row.templateId.isEmpty())
    }

    @Test
    fun `碎片数量加权边界 - 30 20 20 20 10 声明序累加`() {
        val boundaries = mapOf(
            FIRST_ROLL to 1, LAST_FRAGMENT_OF_FIRST_TIER to 1, SECOND_TIER_START to 2,
            SECOND_TIER_END to 2, THIRD_TIER_START to 3, THIRD_TIER_END to 3,
            FOURTH_TIER_START to 4, FOURTH_TIER_END to 4, FIFTH_TIER_START to 5, LAST_ROLL to 5,
        )
        boundaries.forEach { (roll, expected) ->
            val step = GachaPullLedger.pullOnce(
                rng = FixedRollRng(roll), pool = characterOnlyPool(), poolId = POOL_ID,
                pityBefore = 0, fragmentCounts = emptyMap(), starMap = emptyMap(),
                monthIndex = MONTH_INDEX,
            )
            assertEquals(
                "roll=$roll 必须得 $expected 片（30/20/20/20/10 按声明序累加，" +
                    "与 C++ weightedPickIndex 同式）",
                expected, step.row.count,
            )
        }
    }

    @Test
    fun `物品数量加权边界 - 正态钟形表声明序累加`() {
        // 角色类只留 1% 权重 ⇒ roll≥1 必落物品臂，FixedRollRng 的同一掷值依次喂给
        // 类别（≥1 → 灵草）、品阶（单行表恒命中）、数量三处 nextInt(100)，互不串档
        val boundaries = mapOf(
            1 to 1, 2 to 2, 5 to 2, 6 to 3, 14 to 3, 15 to 4, 29 to 4, 30 to 5,
            49 to 5, 50 to 6, 69 to 6, 70 to 7, 84 to 7, 85 to 8, 93 to 8, 94 to 9,
            97 to 9, 98 to 10, 99 to 10,
        )
        boundaries.forEach { (roll, expected) ->
            val step = GachaPullLedger.pullOnce(
                rng = FixedRollRng(roll), pool = itemCountBoundaryPool(), poolId = POOL_ID,
                pityBefore = 0, fragmentCounts = emptyMap(), starMap = emptyMap(),
                monthIndex = MONTH_INDEX,
            )
            assertEquals(
                "roll=$roll 必须得 $expected 件（2/4/9/15/20/20/15/9/4/2 按声明序累加）",
                expected, step.row.count,
            )
        }
    }

    @Test
    fun `同种子重放同结果 - 回退臂是纯函数`() {
        val first = GachaPullLedger.pullOnce(
            rng = DeterministicRng.fromSeed(GUARD_SEED), pool = herbOnlyPool(), poolId = POOL_ID,
            pityBefore = 0, fragmentCounts = emptyMap(), starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        val again = GachaPullLedger.pullOnce(
            rng = DeterministicRng.fromSeed(GUARD_SEED), pool = herbOnlyPool(), poolId = POOL_ID,
            pityBefore = 0, fragmentCounts = emptyMap(), starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        assertEquals("同一种子必须重放出同一行（SL 回档可复现的前提）", first.row, again.row)
        assertEquals("同一种子必须重放出同一保底计数", first.pityAfter, again.pityAfter)
    }

    // ── ② 失败零消费 ────────────────────────────────────────────────

    @Test
    fun `失败臂零消费 - 被拒的池一律不产生掷点`() {
        rejectCases().forEach { case ->
            val rng = RollCountingRng(GUARD_SEED)
            val reject = GachaPullLedger.poolError(case.pool)
            // 生产调用序（GachaFacadeImpl.pull）：poolError 非 null ⇒ 直接 Failure，不取随机数
            if (reject == null && case.pool != null) {
                GachaPullLedger.pullOnce(
                    rng = rng, pool = case.pool, poolId = POOL_ID, pityBefore = 0,
                    fragmentCounts = emptyMap(), starMap = emptyMap(), monthIndex = MONTH_INDEX,
                )
            }
            assertEquals(
                "${case.label}：校验未过的池不得消费任何随机数（掷点晚于全部校验是双臂等价" +
                    "与失败重试不挪动机率流的前提）。落点：GachaPullLedger.poolError 与 " +
                    "GachaFacadeImpl.pull 的调用次序",
                emptyList<Int>(), rng.bounds,
            )
        }
        // 正向对照：同一条流程喂合法池**必须**产生掷点，否则上面的零消费判据是在空转
        val control = RollCountingRng(GUARD_SEED)
        GachaPullLedger.pullOnce(
            rng = control, pool = realShapePool(), poolId = POOL_ID, pityBefore = 0,
            fragmentCounts = emptyMap(), starMap = emptyMap(), monthIndex = MONTH_INDEX,
        )
        assertTrue(
            "正向对照失效：合法池一次抽卡未产生任何掷点，说明本用例的「零消费」断言没有判别力",
            control.bounds.isNotEmpty(),
        )
    }

    // ── ③ poolError 逐分支拒绝码（与 C++ checkPool 同表）──────────────

    @Test
    fun `poolError 逐分支拒绝码与 cpp checkPool 同表 - 含正常配置放行`() {
        rejectCases().forEach { case ->
            assertEquals(
                "${case.label}：拒绝码与 C++ `gacha_tx.h::checkPool` 不同值。三向落点：" +
                    "① Kotlin GachaPullLedger.poolError ② C++ checkPool ③ 本用例的期望码表",
                case.expectedCode, GachaPullLedger.poolError(case.pool),
            )
        }
        // 合法配置必须放行——逐条对应「可抽形状」的三种：纯角色池 / 角色+单表物品池 /
        // 与常驻池同形状的五类池（新增可抽形状时在此登记，不另开判据）
        listOf(
            "角色单类池（保底与角色共用）" to characterOnlyPool(),
            "角色 + 灵草两类池（物品臂）" to herbOnlyPool(),
            "常驻池形状（角色两类 + 物品三类）" to realShapePool(),
        ).forEach { (label, validPool) ->
            assertNull(
                "$label：合法池被 poolError 误拒 ⇒ 玩家一次寻访都发不出。" +
                    "落点：GachaPullLedger.poolError 的判据（C++ checkPool 必须同步同判据）",
                GachaPullLedger.poolError(validPool),
            )
        }
        assertEquals(
            "失败信封的结果码字面量不得单侧改名（C++ dispatch_gacha.cpp 直传 errorType、" +
                "Kotlin GachaPullOutcome 与 UI 文案都按它拼）",
            listOf(
                GachaPullOutcome.CODE_POOL_NOT_FOUND, GachaPullOutcome.CODE_POOL_DISABLED,
                GachaPullOutcome.CODE_POOL_MALFORMED, GachaPullOutcome.CODE_INSUFFICIENT,
                GachaPullOutcome.CODE_INVALID_PULL_COUNT,
            ),
            REJECT_CODES_DISTINCT,
        )
    }

    // ── ④ 候选序（双臂同序的必要条件）──────────────────────────────

    @Test
    fun `itemCandidates 按模板 id 升序 - 三张表与未知来源`() {
        // 灵草一阶的表声明序是 spiritGrass1..3 → spiritFlower1..3 → spiritFruit1..3，
        // 按 id 升序后首元素必须是 spiritFlower1 ⇒ 这条字面量同时锁住「排序键是 id」与
        // 「不是表迭代序」（C++ itemCandidates 的 std::sort 同式）
        assertEquals(
            "灵草一阶候选必须是**按模板 id 升序**的九条（去掉 sortedBy(itemId) 会退回表声明序，" +
                "双臂出货随即分叉）。落点：GachaPullLedger.itemCandidates 与 gacha_tx.h 同名函数",
            HERB_RARITY1_IDS_BY_ID,
            GachaPullLedger.itemCandidates(ITEM_SOURCE_HERBS, RARITY_ONE_LEVEL).map { it.itemId },
        )
        listOf(ITEM_SOURCE_HERBS, ITEM_SOURCE_SEEDS, ITEM_SOURCE_BEAST_MATERIALS).forEach { source ->
            val ids = GachaPullLedger.itemCandidates(source, RARITY_ONE_LEVEL).map { it.itemId }
            assertTrue("$source 一阶候选不能为空（保底/十连中途不得出现空候选）", ids.isNotEmpty())
            assertEquals("$source 的候选必须按 id 升序", ids.sorted(), ids)
            assertEquals("$source 的候选 id 不得重复", ids.distinct(), ids)
        }
        assertEquals(
            "未知 itemSource 必须返回空候选（poolError 已拒，这里锁「不得兜底到某张表」）——" +
                "落点：GachaPullLedger.itemCandidates 的 else 分支",
            emptyList<GachaItemGrant>(), GachaPullLedger.itemCandidates(UNKNOWN_ITEM_SOURCE, RARITY_ONE_LEVEL),
        )
    }

    @Test
    fun `品阶截断 - 池内最高品阶口径`() {
        assertEquals(
            "掷出的品阶必须被类别 maxRarity 截断（池内最高品阶投放口径，Q37：寻访不出五阶）",
            MAX_RARITY_IN_POOL, GachaPullLedger.clampedRarity(OVER_MAX_RARITY, MAX_RARITY_IN_POOL),
        )
        assertEquals("掷出值不超上限时原样保留", 2, GachaPullLedger.clampedRarity(2, MAX_RARITY_IN_POOL))
        val weights = listOf(GachaRarityWeightSpec(4, 50), GachaRarityWeightSpec(1, 50))
            .map { it.weightPct }
        assertEquals(
            "加权抽取必须按**声明序**累加 weightPct（C++ weightedPickIndex 同式）：" +
                "roll=0 落在首项、roll=99 落在末项",
            listOf(0, 1),
            listOf(
                GachaPullLedger.weightedPickIndex(FixedRollRng(FIRST_ROLL), weights),
                GachaPullLedger.weightedPickIndex(FixedRollRng(LAST_ROLL), weights),
            ),
        )
    }

    // ── ⑤ 寻访历史环（事务面：真服务 + 真钱包）─────────────────────

    @Test
    fun `寻访历史环 - 新在前且容量等于 HISTORY_RING_SIZE`() {
        assertEquals(
            "环容量与夹具不符（本用例按 50 条预置旧环）——改口径必须同步 C++ gacha_tx.h 的 " +
                "kHistoryRingSize 与配置 gachaDefaults.historyRingSize（三向由 " +
                "CharacterTemplateGuardTest 看护，本类只锁回退臂按常量截断）",
            EXPECTED_RING_SIZE, GameConfig.Gacha.HISTORY_RING_SIZE,
        )
        val ringBefore = (0 until GameConfig.Gacha.HISTORY_RING_SIZE).map { index ->
            GachaHistoryEntry(POOL_ID, "item", "", "oldItem$index", 1, 1, false, MONTH_INDEX - 1)
        }
        val store = FakeGameStateStore().apply {
            gameDataValue = GameData().apply {
                spiritStones = PRICE_PER_PULL.toLong()
                gachaHistory = ringBefore
                gameYear = 1
                gameMonth = 1
            }
        }
        val manager = GameRngManager().apply { initSystemSeed(GUARD_SEED) }
        val local = service(store, manager).pullLocally(characterOnlyPool(), POOL_ID, 1)
        assertNotNull("余额恰好够一抽，回退臂必须出货", local)
        val history = store.gameDataValue.gachaHistory
        assertEquals("环容量封顶（超出部分淘汰）", EXPECTED_RING_SIZE, history.size)
        assertEquals(
            "历史必须**新在前**：第 0 条是本次抽出的条目（写成 (旧 + 新) 即淘汰语义倒置）",
            local?.rows?.first()?.let { row -> row.category to row.templateId },
            history.first().let { it.category to it.templateId },
        )
        assertEquals(
            "淘汰的是旧环的**末条**（oldItem49），旧环第 48 条降级为环尾",
            "oldItem48", history.last().itemId,
        )
        assertEquals(
            "保底计数必须回写到**该池**的键上（跨池不串号；D-15 的 key 域口径）",
            mapOf(POOL_ID to 1), store.gameDataValue.gachaPityCounters,
        )
        assertEquals("单抽必须按定价扣满", PRICE_PER_PULL.toLong(), local?.pricePaid)
        assertEquals("扣费后余额归零（回退臂走 SpiritStoneWallet，不裸减）", 0L, store.gameDataValue.spiritStones)
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 历史环/掷点用例的服务夹具（纯角色池 ⇒ 不触达入库口，InventorySystem 只占位）。 */
    private fun service(store: FakeGameStateStore, manager: GameRngManager): GachaService = GachaService(
        stateStore = store,
        scopeProvider = GuardPullScopeProvider,
        spiritStoneWallet = SpiritStoneWallet(
            stateStore = store,
            ledger = SpiritStoneLedger(),
            eventBus = mockSmart(EventBus::class.java)
        ),
        gameRngManager = manager,
        inventorySystem = mockSmart(InventorySystem::class.java),
    )

    /** 角色类条目（kind 以 `character` 前缀为判据；候选模板 id 即保底可归属集）。 */
    private fun characterCategory(
        weight: Int = WEIGHT_TOTAL,
        ids: List<String> = CHARACTER_IDS,
        kind: String = CATEGORY_KIND_CHARACTER,
    ): GachaCategorySpec = GachaCategorySpec(kind, weight, ids, "", 0)

    /** 物品类条目（来源表 + 池内最高品阶）。 */
    private fun itemCategory(
        weight: Int = HALF_WEIGHT,
        source: String = ITEM_SOURCE_HERBS,
        maxRarity: Int = MAX_RARITY_IN_POOL,
        kind: String = CATEGORY_KIND_HERB,
    ): GachaCategorySpec = GachaCategorySpec(kind, weight, emptyList(), source, maxRarity)

    /** 只有一个角色类别的合法池（权重和 = 100，保底与角色两臂共用）。 */
    private fun characterOnlyPool(): GachaPoolSpec = GachaPoolSpec(
        poolId = POOL_ID,
        enabled = true,
        pricePerPull = PRICE_PER_PULL,
        categories = listOf(characterCategory()),
        itemRarityWeights = listOf(GachaRarityWeightSpec(RARITY_ONE_LEVEL, WEIGHT_TOTAL)),
        fragmentCountWeights = FRAGMENT_COUNT_WEIGHTS,
        itemCountWeights = ITEM_COUNT_WEIGHTS,
        pity = GachaPitySpec(PITY_THRESHOLD, PITY_FRAGMENT_COUNT, PICK_MODE_RANDOM),
    )

    /** 角色 + 灵草两类的合法池（物品臂用；品阶权重只放一阶，候选数可预测）。 */
    private fun herbOnlyPool(): GachaPoolSpec = GachaPoolSpec(
        poolId = POOL_ID,
        enabled = true,
        pricePerPull = PRICE_PER_PULL,
        categories = listOf(characterCategory(HALF_WEIGHT), itemCategory()),
        itemRarityWeights = listOf(GachaRarityWeightSpec(RARITY_ONE_LEVEL, WEIGHT_TOTAL)),
        fragmentCountWeights = FRAGMENT_COUNT_WEIGHTS,
        itemCountWeights = ITEM_COUNT_WEIGHTS,
        pity = GachaPitySpec(PITY_THRESHOLD, PITY_FRAGMENT_COUNT, PICK_MODE_RANDOM),
    )

    /**
     * 角色只占 1% 权重的物品臂池（物品数量加权边界用）：FixedRollRng 的掷值 ≥1 时
     * 必落物品臂，三处 nextInt(100) 里只有末位数量掷点落在钟形表的档位上。
     */
    private fun itemCountBoundaryPool(): GachaPoolSpec = GachaPoolSpec(
        poolId = POOL_ID,
        enabled = true,
        pricePerPull = PRICE_PER_PULL,
        categories = listOf(characterCategory(1), itemCategory(WEIGHT_TOTAL - 1)),
        itemRarityWeights = listOf(GachaRarityWeightSpec(RARITY_ONE_LEVEL, WEIGHT_TOTAL)),
        fragmentCountWeights = FRAGMENT_COUNT_WEIGHTS,
        itemCountWeights = ITEM_COUNT_WEIGHTS,
        pity = GachaPitySpec(PITY_THRESHOLD, PITY_FRAGMENT_COUNT, PICK_MODE_RANDOM),
    )

    /** 与常驻池 `db.gachaPools[0]` 同形状的合法池（正向对照与放行用例用；含单灵根保底）。 */
    private fun realShapePool(): GachaPoolSpec = GachaPoolSpec(
        poolId = POOL_ID,
        enabled = true,
        pricePerPull = PRICE_PER_PULL,
        categories = listOf(
            characterCategory(11, listOf("zhouming", "suqing")),
            characterCategory(10, listOf("linxuetang", "xuhe", "xieche", "zhaoyan"), "character_double"),
            itemCategory(26, ITEM_SOURCE_BEAST_MATERIALS, kind = "beast_material"),
            itemCategory(26),
            itemCategory(27, ITEM_SOURCE_SEEDS, kind = "seed"),
        ),
        itemRarityWeights = listOf(
            GachaRarityWeightSpec(4, 12), GachaRarityWeightSpec(3, 33),
            GachaRarityWeightSpec(2, 33), GachaRarityWeightSpec(1, 22),
        ),
        fragmentCountWeights = FRAGMENT_COUNT_WEIGHTS,
        itemCountWeights = ITEM_COUNT_WEIGHTS,
        pity = GachaPitySpec(PITY_THRESHOLD, PITY_FRAGMENT_COUNT, PICK_MODE_SINGLE_SPIRIT_ROOT),
    )

    /** 以合法角色池为基线做**单点**改写（一次只破坏一个字段，红点才指得准）。 */
    private fun broken(tweak: (GachaPoolSpec) -> GachaPoolSpec): GachaPoolSpec = tweak(characterOnlyPool())

    /** 只替换类别表的池（其余字段保持合法基线）。 */
    private fun poolWithCategories(vararg categories: GachaCategorySpec): GachaPoolSpec =
        broken { it.copy(categories = categories.toList()) }

    /** 只替换品阶权重表的池（其余字段保持合法基线）。 */
    private fun poolWithRarityWeights(vararg rows: GachaRarityWeightSpec): GachaPoolSpec =
        broken { it.copy(itemRarityWeights = rows.toList()) }

    /** 只替换保底段的池（其余字段保持合法基线）。 */
    private fun poolWithPity(tweak: (GachaPitySpec) -> GachaPitySpec): GachaPoolSpec =
        broken { it.copy(pity = tweak(it.pity)) }

    /**
     * 拒绝分支枚举表——逐条对应 C++ `checkPool` 的一个 `return "Pool..."`，与
     * `gacha_pull_test.cpp` 的 `池不自洽一律拒绝_含非随机保底与权重漂移` 同集。
     * 少一条 = 该分支在两臂间失去看护（新增判据时三处一起加）。
     */
    private fun rejectCases(): List<RejectCase> = listOf(
        RejectCase("查无此池", null, GachaPullOutcome.CODE_POOL_NOT_FOUND),
        RejectCase("池未启用", broken { it.copy(enabled = false) }, GachaPullOutcome.CODE_POOL_DISABLED),
        RejectCase("poolId 为空", broken { it.copy(poolId = "") }, MALFORMED),
        RejectCase("单抽价为 0", broken { it.copy(pricePerPull = 0) }, MALFORMED),
        RejectCase("单抽价为负", broken { it.copy(pricePerPull = -PRICE_PER_PULL) }, MALFORMED),
        RejectCase("类别表为空", poolWithCategories(), MALFORMED),
        RejectCase("品阶权重表为空", poolWithRarityWeights(), MALFORMED),
        RejectCase("保底阈值小于 1", poolWithPity { it.copy(pullThreshold = 0) }, MALFORMED),
        RejectCase("保底碎片数小于 1", poolWithPity { it.copy(fragmentCount = 0) }, MALFORMED),
        RejectCase("保底挑选方式不在白名单（自选属 G13）", poolWithPity { it.copy(pickMode = "manual") }, MALFORMED),
        RejectCase("类别权重和 99", poolWithCategories(characterCategory(99)), MALFORMED),
        RejectCase("类别权重和 101", poolWithCategories(characterCategory(101)), MALFORMED),
        RejectCase("品阶权重和 99", poolWithRarityWeights(GachaRarityWeightSpec(RARITY_ONE_LEVEL, 99)), MALFORMED),
        RejectCase("品阶值小于 1", poolWithRarityWeights(GachaRarityWeightSpec(0, WEIGHT_TOTAL)), MALFORMED),
        RejectCase("碎片数量权重表为空", broken { it.copy(fragmentCountWeights = emptyList()) }, MALFORMED),
        RejectCase("碎片数量权重和 99", broken { it.copy(fragmentCountWeights = listOf(30, 20, 20, 20, 9)) }, MALFORMED),
        RejectCase("物品数量权重表为空", broken { it.copy(itemCountWeights = emptyList()) }, MALFORMED),
        RejectCase(
            "物品数量权重和 101",
            broken { it.copy(itemCountWeights = listOf(2, 4, 9, 15, 20, 20, 15, 9, 4, 3)) },
            MALFORMED,
        ),
        RejectCase(
            "singleSpiritRoot 但池内无单灵根候选（保底无处可落）",
            broken {
                it.copy(
                    categories = listOf(characterCategory(ids = listOf("linxuetang"))),
                    pity = it.pity.copy(pickMode = PICK_MODE_SINGLE_SPIRIT_ROOT),
                )
            },
            MALFORMED,
        ),
        RejectCase("角色候选为空", poolWithCategories(characterCategory(ids = emptyList())), MALFORMED),
        RejectCase("池引用模板表外的角色", poolWithCategories(characterCategory(ids = OUTSIDE_TABLE_ID)), MALFORMED),
        RejectCase("类别 kind 为空", poolWithCategories(characterCategory(kind = "")), MALFORMED),
        RejectCase("类别权重为 0（权重和仍 100）", poolWithCategories(characterCategory(), itemCategory(0)), MALFORMED),
        RejectCase(
            "纯物品池（无角色候选 ⇒ 保底无处可落）",
            poolWithCategories(itemCategory(weight = WEIGHT_TOTAL)),
            MALFORMED,
        ),
        RejectCase("itemSource 非三表之一", poolWithCategories(
            characterCategory(HALF_WEIGHT), itemCategory(HALF_WEIGHT, UNKNOWN_ITEM_SOURCE)
        ), MALFORMED),
        RejectCase("类别 maxRarity 小于 1", poolWithCategories(
            characterCategory(HALF_WEIGHT), itemCategory(HALF_WEIGHT, maxRarity = 0)
        ), MALFORMED),
        RejectCase(
            "可达品阶桶为空（该品阶一张模板都没有）",
            poolWithCategories(
                characterCategory(HALF_WEIGHT),
                itemCategory(HALF_WEIGHT, maxRarity = EMPTY_BUCKET_RARITY),
            ).let { it.copy(itemRarityWeights = listOf(GachaRarityWeightSpec(EMPTY_BUCKET_RARITY, WEIGHT_TOTAL))) },
            MALFORMED,
        ),
    )

    /** 一条拒绝用例：标签 + 池（null = 查无此池）+ 期望结果码。 */
    data class RejectCase(val label: String, val pool: GachaPoolSpec?, val expectedCode: String)

    /** 一个只会按给定值掷 `nextInt(100)` 的随机源（加权抽取的边界断言用）。 */
    private class FixedRollRng(private val roll: Int) : DeterministicRng(0L) {
        override fun nextInt(bound: Int): Int =
            if (bound == GachaPullLedger.WEIGHT_TOTAL) roll else super.nextInt(bound)
    }

    private companion object {
        const val POOL_ID = "guardPool"
        const val GUARD_SEED = 20260926L
        const val MONTH_INDEX = 12
        const val PRICE_PER_PULL = 5000
        const val WEIGHT_TOTAL = 100
        const val HALF_WEIGHT = 50
        const val PITY_THRESHOLD = 10
        const val PITY_FRAGMENT_COUNT = 5
        const val PICK_MODE_RANDOM = "random"
        const val PICK_MODE_SINGLE_SPIRIT_ROOT = "singleSpiritRoot"
        const val ITEM_SOURCE_HERBS = "herbs"
        const val ITEM_SOURCE_SEEDS = "seeds"
        const val ITEM_SOURCE_BEAST_MATERIALS = "beastMaterials"
        const val CATEGORY_KIND_CHARACTER = "character_single"
        const val CATEGORY_KIND_HERB = "herb"
        const val RARITY_ONE_LEVEL = 1
        const val MAX_RARITY_IN_POOL = 4
        const val OVER_MAX_RARITY = 6
        const val UNKNOWN_ITEM_SOURCE = "chests"
        const val FIRST_ROLL = 0
        const val LAST_ROLL = 99

        /** 碎片数量权重表（拍板数值，与配置 `fragmentCountWeights` 逐档同值） */
        val FRAGMENT_COUNT_WEIGHTS = listOf(30, 20, 20, 20, 10)

        /** 物品数量权重表（拍板数值：正态钟形，与配置 `itemCountWeights` 逐档同值） */
        val ITEM_COUNT_WEIGHTS = listOf(2, 4, 9, 15, 20, 20, 15, 9, 4, 2)

        /** 碎片加权边界掷值（30/20/20/20/10 的各档首末，命名即档位语义） */
        const val LAST_FRAGMENT_OF_FIRST_TIER = 29
        const val SECOND_TIER_START = 30
        const val SECOND_TIER_END = 49
        const val THIRD_TIER_START = 50
        const val THIRD_TIER_END = 69
        const val FOURTH_TIER_START = 70
        const val FOURTH_TIER_END = 89
        const val FIFTH_TIER_START = 90

        /** 掷点契约的三次基准（`gacha_tx.h` 头注释「RNG 契约」） */
        const val PITY_ROLL_COUNT = 1
        const val CHARACTER_ROLL_COUNT = 3
        const val ITEM_ROLL_COUNT = 4

        /** 角色候选（两个模板 id ⇒ 候选掷点的 bound 可预测） */
        val CHARACTER_IDS = listOf("zhouming", "suqing")
        const val CHARACTER_CANDIDATES = 2

        /** 单灵根候选数（zhouming/suqing 过滤后的 bound；双灵根 linxuetang 不得进集） */
        const val SINGLE_ROOT_CANDIDATES = 2

        /** 环容量的期望字面量（与 GameConfig.Gacha.HISTORY_RING_SIZE 的对齐由用例断言） */
        const val EXPECTED_RING_SIZE = 50

        /** 灵草一阶的九个模板 id（按 id 升序 = 双臂出货的唯一序） */
        val HERB_RARITY1_IDS_BY_ID = listOf(
            "spiritFlower1", "spiritFlower2", "spiritFlower3",
            "spiritFruit1", "spiritFruit2", "spiritFruit3",
            "spiritGrass1", "spiritGrass2", "spiritGrass3",
        )

        /** 本表用到的拒绝码（顺序即 [GachaPullOutcome] 的声明序） */
        val REJECT_CODES_DISTINCT = listOf(
            "PoolNotFound", "PoolDisabled", "PoolMalformed",
            "InsufficientSpiritStones", "InvalidPullCount",
        )

        /** 池允许引用的模板 id 上限探针（表外角色 ⇒ 抽卡臂不得出货） */
        val OUTSIDE_TABLE_ID = listOf("ghost")

        /** 数据表里不存在任何模板的最高品阶（灵草/种子/兽材表实测到 6 阶，7 阶必空） */
        const val EMPTY_BUCKET_RARITY = 7

        /** 与 C++ `checkPool` 同字面量的拒绝码简写（表内其余码直取 GachaPullOutcome） */
        const val MALFORMED = GachaPullOutcome.CODE_POOL_MALFORMED
    }
}

/**
 * 计数型随机源——只记账、不改数值（`super.nextInt(bound)` 原样转发）。
 *
 * 起点状态与 [DeterministicRng.fromSeed] 逐位相同（借工厂产物取快照），
 * 因此本类的掷点结果与真随机源完全一致，区别只在 [bounds] 可断言。
 * 只记 `nextInt(bound)`（= 契约里的一次「掷点」），内部原始 `nextInt()` 不进账。
 */
private class RollCountingRng(seed: Long) : DeterministicRng(DeterministicRng.fromSeed(seed).snapshot()) {
    /** 按调用序记下的 bound 序列（次数与候选集规模同时可断言） */
    val bounds: MutableList<Int> = mutableListOf()

    override fun nextInt(bound: Int): Int {
        bounds += bound
        return super.nextInt(bound)
    }
}
