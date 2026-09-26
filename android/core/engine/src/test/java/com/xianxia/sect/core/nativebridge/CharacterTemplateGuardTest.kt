package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.CharacterTemplate
import com.xianxia.sect.core.model.CharacterTemplateDb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.full.memberProperties

/**
 * CharacterTemplateGuardTest — 具名角色模板层「中性源 → 生成产物 → Kotlin 镜像 → C++ 常量」守卫
 * （G08 c340-2，决策 D-1 / D-4 / D-8 / D-19 / D-20）。
 *
 * ## 为什么必须有这条守卫（D-1）
 * 模板真源链条是 `scripts/data/gacha_config_sample.json` → `node scripts/gen-game-data.mjs`
 * → 产物 `android/app/src/main/assets/data/game-data.json` 的 `db.characterTemplates`；
 * [CharacterTemplateDb] 是挂在这条链**之外**的 Kotlin 手写镜像（本域 C++ 无查表需求，
 * 故 game-core 里没有第二份表）。`ConfigLoader` 只读 `config/game_config.json`、不读
 * `game-data.json`，Kotlin 侧也没有任何类解析该产物 ⇒ 镜像表在编译器与生成器眼里都是
 * **孤儿面**：改中性源不重跑生成器、或只改镜像不改中性源，`compileReleaseKotlin` 与
 * `gen-game-data.mjs --check` 都不报警（HANDOVER §2.3 坑 9「手工复刻的静态期望表」同族，
 * 该坑的结论是「编译与生成器都抓不到」）。没有本守卫，D-1 的「真源」就等于不存在。
 *
 * ## 守卫三要素（根 AGENTS.md §9.5）
 * 1. **枚举驱动** —— 以 [CharacterTemplateDb.ALL] 与 [MIRRORED_FIELDS] 为锚点逐条逐字段遍历，
 *    并用反射要求 `CharacterTemplate` 的声明属性集恰等于「比对域 ∪ 派生域」：加第 7 个
 *    字段而不同步本守卫即判红；
 * 2. **故意排除项显式声明** —— [DERIVED_FIELDS] 中的 `spiritRootType` 是逗号拼接的只读派生
 *    属性、产物里没有该键，故不进逐字段比对，改由 [灵根取值域合法] 单独断言其派生式。
 *    池内 `categories[*].templateIds` 对模板 id 的引用封闭性由同包 `GachaConfigGuardTest`
 *    负责（该片已有该断言），本类不重复；
 * 3. **失败消息带落点** —— 每条断言都指名「改哪个文件 / 跑哪条命令」，见 [REPAIR_NEUTRAL]
 *    与 [REPAIR_CPP]。
 *
 * ## 判别力自证（退回旧状态判红；主线程抽验）
 * | 测试 | 构造反例（只改一处） | 期望失败消息片段 |
 * |---|---|---|
 * | [模板表与生成产物逐字段全等] | 把 `CharacterTemplateDb.ALL` 删到 5 条；或把 `周明` 改 `周铭` | `逐字段比对不一致` |
 * | 同上 | 把产物 `gender` 写成 `"male"`（映射域只接受 M/F，见 `mappedGender`） | `允许的写法只有` |
 * | [产物键域与镜像比对域双向覆盖] | 给 `CharacterTemplate` 加 `val title: String`（不动本文件的 [MIRRORED_FIELDS]） | `声明属性集` |
 * | [镜像表自洽] | 把 `ALL` 里两条的 id 改成同名 | `id 必须唯一` |
 * | [开局模板与配置一致且不送碎片] | 把中性源 `gachaDefaults.startBonusFragments` 改 5 后重跑生成器 | `开局送碎片` |
 * | [碎片升星常量三向一致] | 把 `gacha_fragment.h` 的 `kFragmentsPerStar` 改 99；或把产物 `db.gachaPools[0].maxStar` 改 6 | `三向不同值` |
 * | [星级乘区与历史环常量三向一致 - 三向臂逐值] | 把 `star_zone.h` 的 `kStarBattlePctPerStar` 改 0.10 | `三向不同值`（0.08/0.08/0.1） |
 * | 同上 | 把 `gacha_tx.h` 的 `kHistoryRingSize` 改 30（历史环少留 20 抽） | `三向不同值`（historyRingSize 那一行） |
 * | 同上 | 只改中性源 `gachaDefaults.starCultPctPerStar` 并 `node scripts/gen-game-data.mjs` 重生成 | `三向不同值`（配置臂先分叉） |
 * | 同上 | 把 C++ 常量改名或挪进别的头文件（三向臂取不到值） | `解析不到 \`inline constexpr double kStarCultPctPerStar\`` |
 * | 同上（int 版臂） | 把 `kHistoryRingSize` 改名、或去掉行末分号 | `解析不到 \`inline constexpr int32_t kHistoryRingSize\`` |
 * | [卡池经济常量与配置同值] | 把 `GameConfig.Gacha.PRICE_PER_PULL` 改 5001 | `配置 ↔ Kotlin` |
 * | [灵根取值域合法] | 把某条 `spiritRoots` 扩到 3 个元素，或写成 `"thunder"` | `模板灵根域校验失败` |
 *
 * 反例都是「改一处 → 判红 → 当场还原 → 判绿」的一轮实验。本文件只读仓库内文本，
 * 不写盘、不需要桌面 JNI（对拍另见 `DiffGachaFragmentTest`）。
 */
class CharacterTemplateGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 仓库内文件定位（🔴 缺文件一律判红，禁止 assumeTrue 静默跳过——X-2 #6 假绿源头） ──

    /** `:core:engine` 测试工作目录的三个候选前缀（与 `GachaConfigGuardTest.gameDataPath()` 同构）。 */
    private val moduleDirPrefixes = listOf("../../", "../", "")

    /**
     * 按候选工作目录定位仓库内文件，全部落空即判红并给出已尝试的绝对路径。
     *
     * @param relativePath 相对 `android/` 的文件路径
     * @param what 人类可读的文件用途（进失败消息）
     * @param fixHint 修复指引（进失败消息）
     */
    private fun locate(relativePath: String, what: String, fixHint: String): File =
        moduleDirPrefixes.map { prefix -> File("$prefix$relativePath") }
            .firstOrNull { it.isFile }
            ?: run {
                val tried = moduleDirPrefixes.map { File("$it$relativePath").absolutePath }
                unreachable("$what 定位失败——已尝试 $tried；当前工作目录 ${System.getProperty("user.dir")}。\n$fixHint")
            }

    /**
     * 产物配置的三个比对锚点：模板数组（顺序敏感）、`gachaDefaults` 段、`db.gachaPools[0]`。
     * 任一段缺失或为空数组一律判红（缺即说明生成器没跑或产物被手改）。
     */
    private fun gachaConfig(): Triple<List<JsonObject>, JsonObject, JsonObject> {
        val root = locate(GAME_DATA_RELATIVE, "生成产物 game-data.json", REPAIR_NEUTRAL)
            .readText().let { json.parseToJsonElement(it).jsonObject }
        val db = root["db"]?.jsonObject ?: unreachable("game-data.json 缺顶层 db 段。$REPAIR_NEUTRAL")
        val defaults = root["gachaDefaults"]?.jsonObject
            ?: unreachable("game-data.json 缺 gachaDefaults 段（开局口径与经济常量的载体）。$REPAIR_NEUTRAL")
        val templates = db["characterTemplates"]?.jsonArray?.map { it.jsonObject }
            ?: unreachable("db.characterTemplates 缺失或非数组，无法与镜像表比对。$REPAIR_NEUTRAL")
        assertTrue("db.characterTemplates 为空数组。$REPAIR_NEUTRAL", templates.isNotEmpty())
        val pools = db["gachaPools"]?.jsonArray?.map { it.jsonObject }
            ?: unreachable("db.gachaPools 缺失或非数组，无法做常量三向比对。$REPAIR_NEUTRAL")
        assertTrue("db.gachaPools 为空数组。$REPAIR_NEUTRAL", pools.isNotEmpty())
        return Triple(templates, defaults, pools.first())
    }

    /** 判红出口：消息带修复指引；正常路径由调用点的 `assertTrue` 先抛出 AssertionError。 */
    private fun unreachable(message: String): Nothing {
        assertTrue(message, false)
        error("unreachable：上面的判红已抛出 AssertionError")
    }

    /**
     * 解析 C++ `inline constexpr int32_t <name> = <值>;`（解析不到即判红）。
     *
     * 默认读碎片入账头；卡池侧常量按所在头文件显式取（见 [GACHA_TX_HEADER_RELATIVE]），
     * 三向比对的第三臂**必须**指向常量真正所在的那个文件——读错文件等于没看护。
     */
    private fun cppIntConstant(name: String): Int =
        cppIntConstant(GACHA_FRAGMENT_HEADER_RELATIVE, "C++ 碎片入账头 gacha_fragment.h", name)

    /** 指定头文件里的 `inline constexpr int32_t`（int 版三向比对第三臂）。 */
    private fun cppIntConstant(headerRelative: String, headerLabel: String, name: String): Int {
        val header = locate(headerRelative, headerLabel, REPAIR_CPP).readText()
        val value = Regex("""inline constexpr int32_t\s+$name\s*=\s*(-?\d+)\s*;""")
            .find(header)?.groupValues?.get(1)?.toIntOrNull()
        return value ?: unreachable(
            "$headerLabel 里解析不到 `inline constexpr int32_t $name`——常量被改名、挪到别的头文件" +
                "或改成非常量表达式，都会让三向比对失去意义（此时必须同步改本守卫的取值落点）。" +
                "$REPAIR_CPP"
        )
    }

    /**
     * 指定头文件里的 `inline constexpr double`（星级乘区这类百分比常量的第三臂）。
     *
     * 与 int 版分开写而非共用正则：`0.08` 走 `toIntOrNull()` 会直接解析失败，
     * 而把 int 常量按 double 读会把 `50` 这类环容量悄悄变成浮点比对。
     */
    private fun cppDoubleConstant(
        headerRelative: String,
        headerLabel: String,
        name: String,
    ): Double {
        val header = locate(headerRelative, headerLabel, REPAIR_CPP).readText()
        val value = Regex("""inline constexpr double\s+$name\s*=\s*(-?\d+(?:\.\d+)?)\s*;""")
            .find(header)?.groupValues?.get(1)?.toDoubleOrNull()
        return value ?: unreachable(
            "$headerLabel 里解析不到 `inline constexpr double $name`——若把它改写成 " +
                "`1 + star * 0.08` 之类的复合表达式，三向比对就取不到值了（口径 A 的每星加成" +
                "必须是可直接比对的常量）。$REPAIR_CPP"
        )
    }

    /** 产物对象的必填字符串键（缺键即判红，禁止静默按空串比对）。 */
    private fun str(obj: JsonObject, key: String): String = obj[key]?.jsonPrimitive?.content
        ?: unreachable("产物对象缺键 \"$key\"（键名即快照协议，禁止单侧改名）：$obj。\n$REPAIR_NEUTRAL")

    private fun num(obj: JsonObject, key: String): Double = str(obj, key).toDouble()

    /**
     * 产物性别写法 → 模型侧写法。
     *
     * 只有 `M`/`F` 两种合法写法（D-1：这是模板镜像**唯一**允许的字面改写），
     * 其它取值一律判红——静默原样透传会让 `Disciple.gender` 拿到 `"M"` 而性别域失配。
     */
    private fun mappedGender(code: String): String = GENDER_CODE_TO_MODEL_NAME[code] ?: unreachable(
        "产物模板 gender 写作 \"$code\"：允许的写法只有 ${GENDER_CODE_TO_MODEL_NAME.keys}" +
            "（映射到 ${GENDER_CODE_TO_MODEL_NAME.values}）——Kotlin 镜像与 Disciple.gender 都用 " +
            "male/female，配置侧 M/F 是模板镜像唯一允许的字面改写。$REPAIR_NEUTRAL"
    )

    /** 产物模板对象 → 比对域行（`gender` 按 M→male / F→female 映射，其余零改写）。 */
    private val configRow: (JsonObject) -> Map<String, String> = { obj ->
        MIRRORED_FIELDS.associateWith { field ->
            when (field) {
                FIELD_SPIRIT_ROOTS ->
                    obj[field]?.jsonArray?.joinToString(",") { it.jsonPrimitive.content } ?: "«缺键»"
                FIELD_GENDER -> mappedGender(str(obj, field))
                else -> obj[field]?.jsonPrimitive?.content ?: "«缺键»"
            }
        }
    }

    /** Kotlin 镜像条目 → 比对域行（与 [configRow] 同键同序，逐键比对）。 */
    private val mirrorRow: (CharacterTemplate) -> Map<String, String> = { template ->
        mapOf(
            FIELD_ID to template.id,
            FIELD_NAME to template.name,
            FIELD_GENDER to template.gender,
            FIELD_SPIRIT_ROOTS to template.spiritRoots.joinToString(","),
            FIELD_AVATAR_KEY to template.avatarKey,
            FIELD_PORTRAIT_KEY to template.portraitKey,
        )
    }

    // ── ① 镜像表 ↔ 产物逐字段全等 ─────────────────────────────────────

    @Test
    fun `模板表与生成产物逐字段全等 - 六条同序同值`() {
        val (configured, _, _) = gachaConfig()
        val diff = StringBuilder()
        if (configured.size != CharacterTemplateDb.ALL.size) {
            diff.append("条数：产物 ${configured.size} 条 ↔ 镜像 ${CharacterTemplateDb.ALL.size} 条\n")
        }
        configured.zip(CharacterTemplateDb.ALL).forEach { (cfg, mirror) ->
            val label = "产物 id=${str(cfg, "id")} / 镜像 id=${mirror.id}"
            val configuredRow = configRow(cfg)
            mirrorRow(mirror).forEach { (field, mirrorValue) ->
                val configuredValue = configuredRow[field] ?: "«产物缺键 $field»"
                if (configuredValue != mirrorValue) {
                    diff.append("$label $field：产物=\"$configuredValue\" 镜像=\"$mirrorValue\"\n")
                }
            }
        }
        assertTrue(
            "CharacterTemplateDb 与 game-data.json 的 db.characterTemplates 逐字段比对不一致" +
                "（顺序同样要求一致——镜像表 ALL 的顺序即产物顺序）：\n$diff$REPAIR_NEUTRAL",
            diff.isEmpty(),
        )
    }

    // ── ② 比对域双向覆盖（枚举驱动 + 故意排除项） ─────────────────────

    @Test
    fun `产物键域与镜像比对域双向覆盖 - 加字段必须同步本守卫`() {
        val (configured, _, _) = gachaConfig()
        assertEquals(
            "产物模板对象的键域与镜像比对域 MIRRORED_FIELDS 不一致——中性源/生成器加了字段，" +
                "就必须同步 CharacterTemplate 与本守卫的比对表（模板口径钉死为 6 项，见 TASKBOOK D-5）。" +
                "$REPAIR_NEUTRAL",
            MIRRORED_FIELDS, configured.flatMap { it.keys }.toSet(),
        )
        assertEquals(
            "CharacterTemplate 的声明属性集 ≠「镜像比对域 ∪ 派生域」——新增属性要么进 " +
                "MIRRORED_FIELDS 参与逐字段比对，要么进 DERIVED_FIELDS 并写清为什么不进产物" +
                "（当前派生域只有 spiritRootType = 灵根逗号拼接）。$REPAIR_NEUTRAL",
            MIRRORED_FIELDS + DERIVED_FIELDS,
            CharacterTemplate::class.memberProperties.map { it.name }.toSet(),
        )
    }

    // ── ③ 镜像表自洽 ─────────────────────────────────────────────────

    @Test
    fun `镜像表自洽 - id 唯一且 byId 与 ALL 同值`() {
        val ids = CharacterTemplateDb.ALL.map { it.id }
        assertEquals(
            "CharacterTemplateDb.ALL 的 id 必须唯一——碎片/星级两本账都以 id 为键，" +
                "重复 id 会让两个角色串号。改数据先改中性源：$REPAIR_NEUTRAL",
            ids.distinct(), ids,
        )
        assertEquals("ids 视图必须与 ALL 同源", ids.toSet(), CharacterTemplateDb.ids)
        val lookupDiff = ids.filter { id ->
            CharacterTemplateDb.byId(id) != CharacterTemplateDb.ALL.first { it.id == id }
        }
        assertTrue("byId 与 ALL 指向不同条目：$lookupDiff", lookupDiff.isEmpty())
        assertTrue(
            "未知 id 必须返回 null（禁止兜底到开局模板，否则限持判定会串到周明身上）：" +
                "byId($UNKNOWN_TEMPLATE_ID_PROBE)=${CharacterTemplateDb.byId(UNKNOWN_TEMPLATE_ID_PROBE)}",
            CharacterTemplateDb.byId(UNKNOWN_TEMPLATE_ID_PROBE) == null,
        )
    }

    // ── ④ 开局口径（D-4 / D-19） ─────────────────────────────────────

    @Test
    fun `开局模板与配置一致且不送碎片 - startupTemplateId 在表内`() {
        val (_, defaults, _) = gachaConfig()
        val configuredStartup = str(defaults, "startupTemplateId")
        assertTrue(
            "gachaDefaults.startupTemplateId=\"$configuredStartup\" 不在 CharacterTemplateDb.ids 内" +
                "——开局实例化查不到模板会静默退回随机弟子，违反验收①。$REPAIR_NEUTRAL",
            CharacterTemplateDb.ids.contains(configuredStartup),
        )
        assertEquals(
            "CharacterTemplateDb.STARTUP_TEMPLATE_ID 与产物 gachaDefaults.startupTemplateId 必须同值" +
                "——Kotlin 开局三臂读常量、配置读产物，分叉即双真相源。$REPAIR_NEUTRAL",
            configuredStartup, CharacterTemplateDb.STARTUP_TEMPLATE_ID,
        )
        assertEquals(
            "gachaDefaults.startBonusFragments 必须为 0：开局送碎片与 D-4/Q34" +
                "「仅 star=1 + 碎片进度 0/100」冲突（开局账本只直写 gachaStarMap，" +
                "gachaFragmentCounts 不得含开局键）。$REPAIR_NEUTRAL",
            0L, str(defaults, "startBonusFragments").toLong(),
        )
        assertTrue(
            "开局境界常量不可为零：STARTUP_REALM=${CharacterTemplateDb.STARTUP_REALM} " +
                "STARTUP_REALM_LAYER=${CharacterTemplateDb.STARTUP_REALM_LAYER}（口径 9 = 炼气、层数 1 起算）",
            CharacterTemplateDb.STARTUP_REALM > 0 && CharacterTemplateDb.STARTUP_REALM_LAYER > 0,
        )
        assertTrue(
            "STARTER_STAR=${CharacterTemplateDb.STARTER_STAR} 必须落在 0..MAX_STAR" +
                "（=${GameConfig.Gacha.MAX_STAR}）：开局直写 gachaStarMap，越界会让后续升星循环失效",
            CharacterTemplateDb.STARTER_STAR in 0..GameConfig.Gacha.MAX_STAR,
        )
    }

    // ── ⑤ 碎片升星常量三向（D-8） ────────────────────────────────────

    @Test
    fun `碎片升星常量三向一致 - 配置表与 Kotlin GameConfig 与 C++ gacha_fragment 头文件`() {
        val (_, _, pool) = gachaConfig()
        val rows = listOf(
            "fragmentsPerStar（每升 1 星所需碎片）" to listOf(
                num(pool, "fragmentsPerStar"),
                GameConfig.Gacha.FRAGMENTS_PER_STAR.toDouble(),
                cppIntConstant("kFragmentsPerStar").toDouble(),
            ),
            "maxStar（星级上限）" to listOf(
                num(pool, "maxStar"),
                GameConfig.Gacha.MAX_STAR.toDouble(),
                cppIntConstant("kMaxStar").toDouble(),
            ),
        )
        val drift = rows.filter { it.second.distinct().size > 1 }
        assertTrue(
            "碎片升星换算常量三向不同值：\n" +
                drift.joinToString(separator = "\n") { (label, values) -> "$label：配置/Kotlin/C++ = $values" } +
                "\n三向落点：① 中性源 scripts/data/gacha_config_sample.json → 产物 " +
                "android/app/src/main/assets/data/game-data.json 的 db.gachaPools[0]" +
                "（跑 node scripts/gen-game-data.mjs）② android/core/domain/src/main/java/" +
                "com/xianxia/sect/core/GameConfig.kt 的 object Gacha ③ " +
                "android/app/src/main/cpp/gamecore/include/gamecore/system/gacha_fragment.h。" +
                "C++ 侧改动必须同步 gacha_fragment_test.cpp 与 DiffGachaFragmentTest 的向量期望值。",
            drift.isEmpty(),
        )
        assertEquals(
            "db.gachaPools[0] 必须是 standard 池——三向比对按池位取值，换池/加池需同步本守卫锚点。" +
                "$REPAIR_NEUTRAL",
            "standard", str(pool, "poolId"),
        )
    }

    // ── ⑥ 卡池经济常量 ↔ 配置（D-1 镜像纪律的经济面） ─────────────────

    @Test
    fun `卡池经济常量与配置同值 - gachaDefaults 与 standard 池`() {
        val (_, defaults, pool) = gachaConfig()
        val pity = pool["pity"]?.jsonObject
            ?: unreachable("db.gachaPools[0] 缺 pity 段（保底阈值与碎片数的载体）。$REPAIR_NEUTRAL")
        val rows = listOf(
            "gachaDefaults.injuryHealPctPerPhase ↔ INJURY_HEAL_PCT_PER_PHASE" to
                listOf(num(defaults, "injuryHealPctPerPhase"), GameConfig.Gacha.INJURY_HEAL_PCT_PER_PHASE),
            "gachaDefaults.breakthroughCompBonus ↔ BREAKTHROUGH_COMP_BONUS" to
                listOf(num(defaults, "breakthroughCompBonus"), GameConfig.Gacha.BREAKTHROUGH_COMP_BONUS),
            "gachaDefaults.startSpiritStones ↔ START_SPIRIT_STONES（开局灵石唯一取值点）" to
                listOf(num(defaults, "startSpiritStones"), GameConfig.Gacha.START_SPIRIT_STONES.toDouble()),
            "db.gachaPools[0].pricePerPull ↔ PRICE_PER_PULL" to
                listOf(num(pool, "pricePerPull"), GameConfig.Gacha.PRICE_PER_PULL.toDouble()),
            "db.gachaPools[0].pity.pullThreshold ↔ PITY_PULL_THRESHOLD" to
                listOf(num(pity, "pullThreshold"), GameConfig.Gacha.PITY_PULL_THRESHOLD.toDouble()),
            "db.gachaPools[0].pity.fragmentCount ↔ PITY_FRAGMENT_COUNT" to
                listOf(num(pity, "fragmentCount"), GameConfig.Gacha.PITY_FRAGMENT_COUNT.toDouble()),
        )
        val drift = rows.filter { it.second.distinct().size > 1 }
        assertTrue(
            "卡池经济常量「配置 ↔ Kotlin」不同值：\n" +
                drift.joinToString(separator = "\n") { (label, values) -> "$label：$values" } +
                "\n改口径的唯一入口是中性源 scripts/data/gacha_config_sample.json + " +
                "node scripts/gen-game-data.mjs；确需改 Kotlin 时落点为 android/core/domain/" +
                "src/main/java/com/xianxia/sect/core/GameConfig.kt 的 object Gacha。",
            drift.isEmpty(),
        )
    }

    // ── ⑥b 星级乘区与历史环常量三向（G09 D-14：配置 ↔ Kotlin ↔ C++ 头文件）─────

    /**
     * G09 把这三项从「配置 ↔ Kotlin」两向升级为**三向**。
     *
     * 为什么必须是三向：星级乘区（口径 A）与寻访历史环都是**两条臂各自实现一遍**的口径
     * ——C++ 在 `star_zone.h` / `gacha_tx.h` 里写死，Kotlin 在 `StarZone` /
     * `GameConfig.Gacha` 里写死，配置在 `gachaDefaults` 里再写一份。只比两向时，改 C++
     * 一侧（例如把 8% 改成 10%）编译、生成器与既有两向守卫全部沉默，后果是「同一份存档、
     * 两条臂算出两套战力」——AUTHORITATIVE 与回退臂的分叉恰好是最难复现的那类缺陷。
     * D-14 已拍板「C++ 用 inline constexpr、不注入 gachaDefaults」⇒ 本用例是这条决定
     * 唯一的机器看护点。
     */
    @Test
    fun `星级乘区与历史环常量三向一致 - 三向臂逐值`() {
        val (_, defaults, _) = gachaConfig()
        val rows = listOf(
            "starBattlePctPerStar（战斗侧每星加成，口径 A：1★ 基线 ×1.00）" to listOf(
                num(defaults, "starBattlePctPerStar"),
                GameConfig.Gacha.STAR_BATTLE_PCT_PER_STAR,
                cppDoubleConstant(STAR_ZONE_HEADER_RELATIVE, STAR_ZONE_HEADER_LABEL, "kStarBattlePctPerStar"),
            ),
            "starCultPctPerStar（修炼侧每星加成）" to listOf(
                num(defaults, "starCultPctPerStar"),
                GameConfig.Gacha.STAR_CULT_PCT_PER_STAR,
                cppDoubleConstant(STAR_ZONE_HEADER_RELATIVE, STAR_ZONE_HEADER_LABEL, "kStarCultPctPerStar"),
            ),
            "historyRingSize（寻访历史环容量）" to listOf(
                num(defaults, "historyRingSize"),
                GameConfig.Gacha.HISTORY_RING_SIZE.toDouble(),
                cppIntConstant(GACHA_TX_HEADER_RELATIVE, GACHA_TX_HEADER_LABEL, "kHistoryRingSize").toDouble(),
            ),
        )
        val drift = rows.filter { it.second.distinct().size > 1 }
        assertTrue(
            "星级乘区 / 历史环常量三向不同值：\n" +
                drift.joinToString(separator = "\n") { (label, values) -> "$label：配置/Kotlin/C++ = $values" } +
                "\n三向落点：① 中性源 scripts/data/gacha_config_sample.json 的 gachaDefaults → 产物 " +
                "android/app/src/main/assets/data/game-data.json（跑 node scripts/gen-game-data.mjs）" +
                "② android/core/domain/src/main/java/com/xianxia/sect/core/GameConfig.kt 的 object Gacha" +
                "（战斗/修炼乘区的 Kotlin 实现是 core/domain/…/model/StarZone.kt）③ C++ 常量本体：" +
                "system/star_zone.h 的 kStarBattlePctPerStar / kStarCultPctPerStar、" +
                "system/gacha_tx.h 的 kHistoryRingSize。" +
                "\n改 C++ 侧任一常量都必须同步 Kotlin 与配置，并复查 star_zone_test.cpp 与 " +
                "gacha_pull_test.cpp 的期望值（口径 A 的 1★ ⇒ ×1.00 判据另见 StarZone 的用例）。",
            drift.isEmpty(),
        )
    }

    // ── ⑦ 灵根取值域（模板 6 项之一的合法域） ─────────────────────────

    @Test
    fun `灵根取值域合法 - 每条 1 至 2 个元素且都在 SpiritRoot 五元素内`() {
        val allowed = GameConfig.SpiritRoot.TYPES.keys
        val violations = mutableListOf<String>()
        CharacterTemplateDb.ALL.forEach { template ->
            if (template.spiritRoots.size !in 1..MAX_SPIRIT_ROOTS_PER_TEMPLATE) {
                violations += "${template.id}: 灵根数 ${template.spiritRoots.size} 不在 " +
                    "1..$MAX_SPIRIT_ROOTS_PER_TEMPLATE 内"
            }
            violations += template.spiritRoots
                .filterNot { allowed.contains(it) }
                .map { root -> "${template.id}: 灵根 \"$root\" 非法" }
            if (template.spiritRootType != template.spiritRoots.joinToString(",")) {
                violations += "${template.id}: spiritRootType=\"${template.spiritRootType}\" ≠ 逗号拼接"
            }
        }
        val (configured, _, _) = gachaConfig()
        configured.forEach { cfg ->
            val mirror = cfg["id"]?.jsonPrimitive?.content?.let { CharacterTemplateDb.byId(it) }
            if (mirror != null && configRow(cfg).getValue(FIELD_SPIRIT_ROOTS) != mirror.spiritRootType) {
                violations += "${mirror.id}: 产物 spiritRoots 与镜像 spiritRootType 不同序/不同值"
            }
        }
        assertTrue(
            "模板灵根域校验失败——元素必须落在 GameConfig.SpiritRoot.TYPES 的 $allowed 内" +
                "（Disciple.spiritRootType、悟性阶梯与灵根数色表都以这套英文键为域，写中文或自造" +
                "元素会静默失配），且每条 1..$MAX_SPIRIT_ROOTS_PER_TEMPLATE 个（产品方案 §4.3 " +
                "模板口径）：\n$violations\n$REPAIR_NEUTRAL",
            violations.isEmpty(),
        )
    }

    private companion object {
        const val GAME_DATA_RELATIVE = "app/src/main/assets/data/game-data.json"
        const val GACHA_FRAGMENT_HEADER_RELATIVE =
            "app/src/main/cpp/gamecore/include/gamecore/system/gacha_fragment.h"

        /** 星级乘区（口径 A）的 C++ 常量所在头文件（三向比对的第三臂） */
        const val STAR_ZONE_HEADER_RELATIVE =
            "app/src/main/cpp/gamecore/include/gamecore/system/star_zone.h"
        const val STAR_ZONE_HEADER_LABEL = "C++ 星级乘区头 star_zone.h"

        /** 寻访抽卡事务的 C++ 常量所在头文件（历史环容量三向比对的第三臂） */
        const val GACHA_TX_HEADER_RELATIVE =
            "app/src/main/cpp/gamecore/include/gamecore/system/gacha_tx.h"
        const val GACHA_TX_HEADER_LABEL = "C++ 寻访事务头 gacha_tx.h"

        /** 中性源侧修复指引（产物比对的落点） */
        const val REPAIR_NEUTRAL =
            "修复：改 scripts/data/gacha_config_sample.json 后在仓库根跑 node scripts/gen-game-data.mjs，" +
                "再同步 android/core/domain/src/main/java/com/xianxia/sect/core/model/CharacterTemplate.kt"

        /** C++ 侧修复指引（三向比对的第三臂；常量按所在头文件逐个点名） */
        const val REPAIR_CPP =
            "修复：C++ 常量落在 gamecore/include/gamecore/system/ 下的 gacha_fragment.h" +
                "（kFragmentsPerStar / kMaxStar）、star_zone.h（每星加成百分比）、" +
                "gacha_tx.h（kHistoryRingSize / kWeightTotal）；改任一常量必须同步 " +
                "GameConfig.Gacha 与配置 gachaDefaults（改配置走中性源 + " +
                "node scripts/gen-game-data.mjs），并复查同名 GTest 与 Diff* 期望值"

        /** 比对域的 6 个键名（D-5：模板口径钉死为这 6 项，产物键与镜像属性同名） */
        const val FIELD_ID = "id"
        const val FIELD_NAME = "name"
        const val FIELD_GENDER = "gender"
        const val FIELD_SPIRIT_ROOTS = "spiritRoots"
        const val FIELD_AVATAR_KEY = "avatarKey"
        const val FIELD_PORTRAIT_KEY = "portraitKey"

        /** 逐字段比对域：镜像表与产物模板对象共有的 6 个键 */
        val MIRRORED_FIELDS: Set<String> = setOf(
            FIELD_ID, FIELD_NAME, FIELD_GENDER, FIELD_SPIRIT_ROOTS, FIELD_AVATAR_KEY, FIELD_PORTRAIT_KEY,
        )

        /**
         * 故意排除项：`spiritRootType` 是 [CharacterTemplate] 的只读派生属性
         * （灵根列表逗号拼接），产物里没有这个键 ⇒ 不进逐字段比对，
         * 由 [灵根取值域合法] 单独断言其派生式与顺序。
         */
        val DERIVED_FIELDS: Set<String> = setOf("spiritRootType")

        /** 配置侧性别写法 → 模型侧写法（模板镜像唯一允许的字面改写，D-1） */
        val GENDER_CODE_TO_MODEL_NAME: Map<String, String> = mapOf("M" to "male", "F" to "female")

        /** 模板灵根元素数上限（产品方案 §4.3：模板只钉 1..2 灵根） */
        const val MAX_SPIRIT_ROOTS_PER_TEMPLATE = 2

        /** 未知 id 探针：镜像表查不到才符合「禁止兜底到开局模板」的契约 */
        const val UNKNOWN_TEMPLATE_ID_PROBE = "__no_such_template__"
    }
}
