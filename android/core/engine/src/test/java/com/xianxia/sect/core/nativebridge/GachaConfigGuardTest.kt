package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.config.GameDataNativeBridge
import com.xianxia.sect.core.engine.domain.gacha.GachaFragmentLedger
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullLedger
import com.xianxia.sect.core.engine.domain.gacha.GachaPullOutcome
import com.xianxia.sect.core.engine.domain.gacha.GachaService
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.platform.AssetSource
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * G01 守卫：`gachaPools` / `characterTemplates` / Q31 色表单源；
 * G09 D-15 起追加**两条运行期账本 key 域守卫**（见
 * [runtime fragment and star ledger key domain stays inside the template table] 与
 * [runtime pity counter key domain stays inside the configured pools]）。
 *
 * G08 c340-2 起**取消全部 `assumeTrue` 跳过**（TASKBOOK §4 c340-2 + X-2 #6 口径）：
 * 缺文件、缺 `gachaPools`/`characterTemplates` 键、空数组一律**判红**。
 * 原写法「缺键即跳过」会让「配置被删干净」这种最严重的回归显示成全绿（假绿源头）。
 *
 * 修复：改 `scripts/data/gacha_config_sample.json` 后重跑
 * `node scripts/gen-game-data.mjs`（产物 `android/app/src/main/assets/data/game-data.json`）。
 *
 * 与同包 `CharacterTemplateGuardTest` 的分工：本类锁**配置自身的口径**（权重和、品阶、
 * 保底、门槛、id 集合、引用封闭性）**加上运行期账本的键域**（D-15：配置面 G08 已锁，
 * 运行期面此前无人看护）；配置 ↔ Kotlin 镜像 ↔ C++ 常量的三向比对归
 * `CharacterTemplateGuardTest`；两条臂的出货等价归 `DiffGachaPullTest`。
 */
class GachaConfigGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 相对 engine 模块工作目录的 game-data 路径（ManualRegistryGuardTest 同构）。 */
    private fun gameDataPath(): File {
        val candidates = listOf(
            File("../../app/src/main/assets/data/game-data.json"),
            File("../app/src/main/assets/data/game-data.json"),
            File("app/src/main/assets/data/game-data.json"),
        )
        val found = candidates.firstOrNull { it.exists() }
        // 🔴 判红而非跳过：缺产物说明生成器没跑（X-2 #6：assumeTrue 静默跳过 = 假绿源头）
        assertTrue(
            "game-data.json 不存在——已尝试 ${candidates.map { it.absolutePath }}；" +
                "在仓库根跑 node scripts/gen-game-data.mjs 后重试",
            found != null,
        )
        return found ?: candidates.first()
    }

    private fun loadRoot(): JsonObject =
        json.parseToJsonElement(gameDataPath().readText()).jsonObject

    /** 取 `db` 下的必需数组段：缺键或空数组一律判红（原 `assumeTrue` 的两层假绿口子）。 */
    private fun requiredArray(db: JsonObject, key: String): List<JsonObject> {
        val element = db[key]
        assertTrue(
            "game-data.json 的 db.$key 缺失（$REPAIR_HINT）",
            element != null,
        )
        val items = db.getValue(key).jsonArray.map { it.jsonObject }
        assertTrue(
            "game-data.json 的 db.$key 为空数组（$REPAIR_HINT）——配置被清空属于严重回归，不允许跳过",
            items.isNotEmpty(),
        )
        return items
    }

    @Test
    fun `standard pool category weights sum to 100 and rarity max 4`() {
        val db = loadRoot().getValue("db").jsonObject
        val pools = requiredArray(db, "gachaPools")

        val pool = pools.first {
            it.getValue("poolId").jsonPrimitive.content == "standard"
        }
        val categories = pool.getValue("categories").jsonArray
        val catSum = categories.sumOf { it.jsonObject.getValue("weightPct").jsonPrimitive.long }
        assertEquals("类别权重和必须 = 100%", 100L, catSum)

        val rarities = pool.getValue("itemRarityWeights").jsonArray
        val raritySum = rarities.sumOf { it.jsonObject.getValue("weightPct").jsonPrimitive.long }
        assertEquals("品阶权重和必须 = 100%", 100L, raritySum)
        val maxRarity = rarities.maxOf { it.jsonObject.getValue("rarity").jsonPrimitive.long }
        assertTrue("寻访池最高品阶必须 ≤ 4（Q37）", maxRarity <= 4)

        assertCountWeightTables(pool)

        val pity = pool.getValue("pity").jsonObject
        assertEquals("保底阈值 = 10（Q33）", 10L, pity.getValue("pullThreshold").jsonPrimitive.long)
        assertEquals("保底碎片 = 5（Q33）", 5L, pity.getValue("fragmentCount").jsonPrimitive.long)
        assertEquals(
            "保底归属 = 单灵根角色（singleSpiritRoot；改回全随机须同步 C++ checkPool 白名单）",
            "singleSpiritRoot",
            pity.getValue("pickMode").jsonPrimitive.content,
        )
        assertEquals(
            "每星碎片 = 100（Q35）",
            100L,
            pool.getValue("fragmentsPerStar").jsonPrimitive.long,
        )
        assertEquals(
            "星级上限 = 5（Q35）",
            5L,
            pool.getValue("maxStar").jsonPrimitive.long,
        )
        assertEquals(
            "配置每星碎片与 Kotlin 常量 GameConfig.Gacha.FRAGMENTS_PER_STAR 不同值" +
                "（三向比对含 C++ kFragmentsPerStar，见 CharacterTemplateGuardTest）",
            GameConfig.Gacha.FRAGMENTS_PER_STAR.toLong(),
            pool.getValue("fragmentsPerStar").jsonPrimitive.long,
        )
        assertEquals(
            "配置星级上限与 Kotlin 常量 GameConfig.Gacha.MAX_STAR 不同值（同上，三向由模板守卫看护）",
            GameConfig.Gacha.MAX_STAR.toLong(),
            pool.getValue("maxStar").jsonPrimitive.long,
        )
        assertEquals(
            "无转化比例（Q44）",
            false,
            pool.containsKey("fragmentToSpiritStoneRatio"),
        )
    }

    /**
     * 数量维度两张权重表的拍板数值逐档比对（2026-09-30）：碎片 1..5 片 30/20/20/20/10、
     * 物品 1..10 件正态钟形 2/4/9/15/20/20/15/9/4/2，各表和恒 100。
     */
    private fun assertCountWeightTables(pool: JsonObject) {
        val fragmentCounts = pool.getValue("fragmentCountWeights").jsonArray
            .map { it.jsonPrimitive.long }
        assertEquals(
            "角色碎片数量权重 = 30/20/20/20/10（拍板数值：抽中角色随机 1..5 片）",
            listOf(30L, 20L, 20L, 20L, 10L),
            fragmentCounts,
        )
        assertEquals("碎片数量权重和必须 = 100%", 100L, fragmentCounts.sum())
        val itemCounts = pool.getValue("itemCountWeights").jsonArray.map { it.jsonPrimitive.long }
        assertEquals(
            "物品数量权重 = 2/4/9/15/20/20/15/9/4/2（拍板数值：正态钟形，随机 1..10 件）",
            listOf(2L, 4L, 9L, 15L, 20L, 20L, 15L, 9L, 4L, 2L),
            itemCounts,
        )
        assertEquals("物品数量权重和必须 = 100%", 100L, itemCounts.sum())
    }

    @Test
    fun `character templates are the six named cast with stable ids`() {
        val db = loadRoot().getValue("db").jsonObject
        val templates = requiredArray(db, "characterTemplates")

        val ids = templates.map { it.getValue("id").jsonPrimitive.content }.toSet()
        val expected = setOf(
            "zhouming", "suqing", "linxuetang", "xuhe", "xieche", "zhaoyan",
        )
        assertEquals("六角色模板 id 集合固定（Q36）", expected, ids)
        assertEquals(
            "配置模板 id 集合与 Kotlin 镜像 CharacterTemplateDb.ids 不同（三向比对见 " +
                "CharacterTemplateGuardTest，此处只锁本类单独跑时的锚点）",
            CharacterTemplateDb.ids, ids,
        )

        templates.forEach { t ->
            assertNotNull("模板须有姓名", t["name"])
            val roots = t.getValue("spiritRoots").jsonArray
            assertTrue(
                "模板 ${t.getValue("id").jsonPrimitive.content} 灵根数须为 1 或 2",
                roots.size in 1..2,
            )
        }
    }

    /**
     * G08 c340-2 补断言①：碎片/星级两本账的 **key 域封闭**。
     *
     * `GameData.gachaFragmentCounts` / `gachaStarMap` 以模板 id 为唯一键域（入账写者
     * `gacha_fragment.h::addFragment` / `GachaFragmentLedger.grant` 都按 templateId 写键），
     * 所以配置里任何被引用的 `templateIds` 都必须在 `CharacterTemplateDb.ids` 内——
     * 引用表外的 id 会让玩家拿到一个永远查不到模板、立绘与姓名都为空的账本键。
     */
    @Test
    fun `pool referenced template ids are closed within the template table`() {
        val db = loadRoot().getValue("db").jsonObject
        val pools = requiredArray(db, "gachaPools")
        val referenced = pools.flatMap { pool ->
            pool["categories"]?.jsonArray.orEmpty().flatMap { category ->
                category.jsonObject["templateIds"]?.jsonArray.orEmpty()
                    .map { it.jsonPrimitive.content }
            }
        }.toSet()
        assertTrue(
            "卡池未引用任何模板 id（寻访池必须能出角色，否则验收①的模板层形同虚设）",
            referenced.isNotEmpty(),
        )
        val dangling = referenced.filterNot { CharacterTemplateDb.ids.contains(it) }
        assertEquals(
            "卡池 categories[*].templateIds 出现模板表外的 id：$dangling——" +
                "gachaFragmentCounts/gachaStarMap 的 key 域就是 CharacterTemplateDb.ids，" +
                "表外 id 会落进玩家账本却查不到模板。$REPAIR_HINT",
            emptyList<String>(), dangling,
        )
    }

    /**
     * G08 c340-2 补断言②：开局 id 必在模板表内（配置自洽面）。
     *
     * Kotlin 镜像常量与 `gachaDefaults` 的等值比对由 `CharacterTemplateGuardTest` 负责，
     * 这里只锁「开局 id 落在产物模板表里」，让本类单独跑也能守住验收①的前提。
     */
    @Test
    fun `startup template id exists in the generated template table`() {
        val root = loadRoot()
        val defaults = root["gachaDefaults"]?.jsonObject
        assertTrue("game-data.json 缺顶层 gachaDefaults 段（$REPAIR_HINT）", defaults != null)
        val startup = defaults?.getValue("startupTemplateId")?.jsonPrimitive?.content
        val ids = requiredArray(root.getValue("db").jsonObject, "characterTemplates")
            .map { it.getValue("id").jsonPrimitive.content }
        assertTrue(
            "gachaDefaults.startupTemplateId=\"$startup\" 不在产物模板表 $ids 内（$REPAIR_HINT）",
            startup != null && ids.contains(startup),
        )
    }

    @Test
    fun `gacha color single source matches Q31`() {
        assertEquals("#ffd700", GameConfig.Gacha.rarityColor(6))
        assertEquals("#f44336", GameConfig.Gacha.rarityColor(5))
        assertEquals("#9c27b0", GameConfig.Gacha.rarityColor(4))
        assertEquals("#2196f3", GameConfig.Gacha.rarityColor(3))
        assertEquals("#4caf50", GameConfig.Gacha.rarityColor(2))
        assertEquals("#b8b8b8", GameConfig.Gacha.rarityColor(1))

        assertEquals("#ffd700", GameConfig.Gacha.spiritRootCountColor(1))
        assertEquals("#f44336", GameConfig.Gacha.spiritRootCountColor(2))
        assertEquals("#9c27b0", GameConfig.Gacha.spiritRootCountColor(3))
        assertEquals("#2196f3", GameConfig.Gacha.spiritRootCountColor(4))
        assertEquals("#b8b8b8", GameConfig.Gacha.spiritRootCountColor(5))
    }

    // ── G09 D-15：运行期账本 key 域守卫 ────────────────────────────────

    /**
     * 守卫①：`gachaFragmentCounts` / `gachaStarMap` 的 key 域 ⊆ `CharacterTemplateDb.ids`。
     *
     * 三要素（根 AGENTS.md §9.5）：
     * 1. **枚举驱动** —— 以配置 `db.gachaPools[].categories[].templateIds`（抽卡臂唯一可
     *    落键集）与开局直写模板为锚点，逐个走**真实入账口** [GachaFragmentLedger.grant]；
     * 2. **故意排除项** —— 见 [intentionallyExcluded]（空串 id 属拒绝臂、镜像回写面另有看护）；
     * 3. **操作指引** —— 失败消息直接点名「改哪张表 / 改哪段配置 / 落哪个文件」。
     *
     * 为什么要守运行期面：配置的引用封闭性 G08 已锁（见
     * [pool referenced template ids are closed within the template table]），但**旧档污染**
     * （手改存档 / 版本回退）与服务端下发（兑换码、邮件、活动的 templateId 由 API 决定）
     * 都能绕过配置面直写账本；表外键在 UI 上表现为「立绘与姓名全空的幽灵角色」，
     * 在 `DiscipleService.instantiateTemplate` 上表现为静默查不到模板。
     */
    @Test
    fun `runtime fragment and star ledger key domain stays inside the template table`() {
        val referenced = referencedTemplateIds() + CharacterTemplateDb.STARTUP_TEMPLATE_ID
        assertTrue("配置未引用任何模板 id（枚举集为空 ⇒ 本守卫失去锚点）", referenced.isNotEmpty())

        val produced = linkedSetOf<String>()
        referenced.forEach { templateId ->
            val outcome = GachaFragmentLedger.grant(
                fragmentCounts = emptyMap(), starMap = emptyMap(),
                templateId = templateId, count = GameConfig.Gacha.FRAGMENTS_PER_STAR,
            )
            assertNotNull("$templateId：真实入账口拒绝入账（配置引用与开局模板必须可入账）", outcome)
            produced += outcome?.fragmentCounts?.keys ?: emptySet()
            produced += outcome?.starMap?.keys ?: emptySet()
        }
        val outside = produced.filterNot { CharacterTemplateDb.ids.contains(it) }
        assertEquals(
            "碎片/星级两本账的 key 域越界：$outside —— 落点：① 卡池 categories[*].templateIds " +
                "（中性源 scripts/data/gacha_config_sample.json + node scripts/gen-game-data.mjs）" +
                "② CharacterTemplateDb 镜像表（须与产物 db.characterTemplates 同步，见 " +
                "CharacterTemplateGuardTest）③ 服务端下发的 templateId（兑换码/邮件/活动一律经 " +
                "GachaFacade.grantFragments 入账，发放侧须先查表）。" +
                "\n本守卫故意不覆盖的面：\n" +
                intentionallyExcluded.entries.joinToString("\n") { "  · ${it.key} —— ${it.value}" },
            emptyList<String>(), outside,
        )
        // 门必须真的能拒住表外 id：抽卡臂若放行引用，账本会在运行期凭空长出幽灵键
        assertEquals(
            "引用模板表外 id 的池必须被 poolError 拒（C++ checkPool 的 characterTemplateById " +
                "命中判据同式）——否则配置一改就能造出表外键",
            GachaPullOutcome.CODE_POOL_MALFORMED,
            GachaPullLedger.poolError(poolReferencingOutsideTemplate()),
        )
        assertEquals(
            "表外 id 探针本身必须在模板表外（探针失效等于本用例失去判别力）",
            false, CharacterTemplateDb.ids.contains(OUTSIDE_TEMPLATE_ID_PROBE),
        )
    }

    /**
     * 守卫②：`gachaPityCounters` 的 key 域 ⊆ 配置 `db.gachaPools[].poolId`。
     *
     * 枚举驱动 = 配置里的**每一张池**都真跑一次回退臂事务（`GachaService.pullLocally`），
     * 断言事务产出的保底键恰等于被抽的那张池；再对配置外池 id 断言 `poolError` 拒绝
     * ⇒ 保底计数不可能为「玩家抽不到的池」建键（多一个键 = UI 保底进度条读到一个
     * 永远为 0 的池，且随存档永久累积）。
     */
    @Test
    fun `runtime pity counter key domain stays inside the configured pools`() {
        val db = loadRoot().getValue("db").jsonObject
        val configuredPoolIds = requiredArray(db, "gachaPools")
            .map { it.getValue("poolId").jsonPrimitive.content }
        val config = GachaPoolConfig(assetSourceAtGamePath())
        val seen = linkedSetOf<String>()
        configuredPoolIds.forEach { poolId ->
            val spec = config.pool(poolId)
            assertNotNull(
                "$poolId：产物里的池在 Kotlin 读面查不到——两条臂的池读面分叉" +
                    "（GachaPoolConfig 解析的就是这份产物）。$REPAIR_HINT",
                spec,
            )
            assertNull(
                "$poolId：配置里的池被回退臂判为不可抽 ⇒ 玩家点寻访只会拿到失败码。" +
                    "落点：GachaPullLedger.poolError 与 gacha_tx.h::checkPool（须同判据）",
                GachaPullLedger.poolError(spec),
            )
            seen += pullOnceAgainstFallbackArm(requireNotNull(spec), poolId).keys
        }
        val outside = seen.filterNot { configuredPoolIds.contains(it) }
        assertEquals(
            "保底计数的 key 域越界：$outside ∉ 配置池 id $configuredPoolIds —— 落点：① 中性源的 " +
                "db.gachaPools[].poolId（改后跑 node scripts/gen-game-data.mjs）② 调用方传入的 " +
                "poolId（GachaFacade.pullOnce/pullTen 的参数须来自卡池列表页）③ C++ " +
                "gacha_tx.h 的 gachaPityCounters[poolId] 写入点。" +
                "\n本守卫故意不覆盖的面：\n" +
                intentionallyExcluded.entries.joinToString("\n") { "  · ${it.key} —— ${it.value}" },
            emptyList<String>(), outside,
        )
        assertEquals(
            "配置外池 id 必须走门拒（PoolNotFound）：保底计数与出货都不得发生",
            GachaPullOutcome.CODE_POOL_NOT_FOUND,
            GachaPullLedger.poolError(config.pool(OUTSIDE_POOL_ID_PROBE)),
        )
    }

    // ── D-15 夹具 ───────────────────────────────────────────────────

    /** 配置里被卡池引用到的全部角色模板 id（抽卡臂可落键的唯一来源）。 */
    private fun referencedTemplateIds(): Set<String> {
        val db = loadRoot().getValue("db").jsonObject
        return requiredArray(db, "gachaPools").flatMap { pool ->
            pool["categories"]?.jsonArray.orEmpty().flatMap { category ->
                category.jsonObject["templateIds"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            }
        }.toSet()
    }

    /** 只把角色候选换成表外探针的池（其余字段取配置首池 ⇒ 单点破坏，红点指得准）。 */
    private fun poolReferencingOutsideTemplate(): GachaPoolSpec? {
        val spec = GachaPoolConfig(assetSourceAtGamePath()).pool(
            requiredArray(loadRoot().getValue("db").jsonObject, "gachaPools")
                .first().getValue("poolId").jsonPrimitive.content
        ) ?: return null
        return spec.copy(
            categories = spec.categories.map { category ->
                if (category.isCharacter) {
                    category.copy(templateIds = listOf(OUTSIDE_TEMPLATE_ID_PROBE))
                } else {
                    category
                }
            }
        )
    }

    /**
     * 用真实回退臂（[GachaService.pullLocally]）抽一次，回读事务产出的保底计数。
     *
     * 余额按十连价备足（抽多少卡都不该被余额截断成假绿）；入库口按仓库惯例 mockSmart
     * ——本用例的判据是**键域**，不是仓库内容（物品入库对拍见 GachaPullGuardTest/Diff 族）。
     */
    private fun pullOnceAgainstFallbackArm(spec: GachaPoolSpec, poolId: String): Map<String, Int> {
        val store = FakeGameStateStore().apply {
            gameDataValue = GameData().apply {
                spiritStones = TEN_PULL_COST
            }
        }
        val service = GachaService(
            stateStore = store,
            scopeProvider = object : CoroutineScopeProvider {
                override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                override val ioScope = scope
            },
            spiritStoneWallet = SpiritStoneWallet(
                stateStore = store,
                ledger = SpiritStoneLedger(),
                eventBus = mockSmart(EventBus::class.java)
            ),
            gameRngManager = GameRngManager().apply { initSystemSeed(GUARD_SEED) },
            inventorySystem = mockSmart(InventorySystem::class.java),
        )
        assertNotNull("$poolId：余额已备足十连价，回退臂却未出货", service.pullLocally(spec, poolId, 1))
        return store.gameDataValue.gachaPityCounters
    }

    /** 指向 `app/src/main/assets/data/game-data.json` 的纯 JVM 资产读面。 */
    private fun assetSourceAtGamePath(): AssetSource = object : AssetSource {
        override fun open(assetPath: String): InputStream? =
            if (assetPath == GameDataNativeBridge.ASSET_PATH) FileInputStream(gameDataPath()) else null
    }

    /**
     * 故意不覆盖的面（必须显式声明——否则「守卫没抓到」会被后来者误读成「不存在这种面」）。
     */
    private val intentionallyExcluded: Map<String, String> = linkedMapOf(
        "空串 templateId" to
            "GachaFragmentLedger.grant / C++ addFragment 对空串一律拒绝且不建空键（G08 D-19），" +
                "拒绝臂没有键可谈域；门面 GachaFacadeImpl 另有 Kotlin-only 的 isBlank() 预闸，" +
                "该不对称已在 report-G08 §七 登记",
        "C++ 权威臂直写、经脏段镜像回 Kotlin 的账本键" to
            "稳态写者是 C++（gacha_fragment.h / gacha_tx.h），镜像面只读；其键域封闭由 " +
            "checkPool 的 characterTemplateById 命中判据 + 本文件的引用封闭与门拒断言共同保证，" +
            "镜像只读契约另见 MirrorReadOnlyGuardTest",
        "开局直写 gachaStarMap 的 STARTUP_TEMPLATE_ID" to
            "开局量由 CharacterTemplateDb.STARTER_STAR 写死且 startBonusFragments 必须为 0" +
                "（见 CharacterTemplateGuardTest 的「开局模板与配置一致且不送碎片」）；" +
                "本守卫已把该 id 并入枚举集，不单独再测一次开局"
    )

    private companion object {
        /** 表外探针：模板表里绝不存在的角色 id 与池 id（门必须拒住它们） */
        const val OUTSIDE_TEMPLATE_ID_PROBE = "__no_such_template__"
        const val OUTSIDE_POOL_ID_PROBE = "__no_such_pool__"

        /** 回退臂夹具的余额与种子（备足十连价 ⇒ 抽卡次数不会被余额截断成假绿） */
        const val GUARD_SEED = 20260926L
        const val PULL_COUNT_TEN = 10
        val TEN_PULL_COST: Long = GameConfig.Gacha.PRICE_PER_PULL.toLong() * PULL_COUNT_TEN

        /** 统一的修复指引（判红消息里反复用） */
        const val REPAIR_HINT =
            "修复：改 scripts/data/gacha_config_sample.json 后在仓库根跑 node scripts/gen-game-data.mjs"
    }
}
