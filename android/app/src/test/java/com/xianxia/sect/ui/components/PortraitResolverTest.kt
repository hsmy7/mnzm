package com.xianxia.sect.ui.components

import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.R
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.registerAllSprites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [resolvePortraitResId] 解析链单测（G08 验收⑤ · TASKBOOK-G08 D-11）。
 *
 * ## 被测语义
 * `portraitRes` 一个字段承载**两个互斥取值域**：37 张通用弟子像（`PortraitPool` 域）
 * 与具名角色立绘键（`SpriteResRegistry` 的 `SpriteCategory.CHARACTER` 域）。
 * 本测试锁死五路口径：通用像 → 命中、角色键 → 命中、未知名 → 0、空/空白 → 0、
 * 妖兽展示键（`beast_<index>`）→ 0（该形态**不属于**这两个域，由调用方在调用前
 * 自行分支处理，见 `DiscipleSlotComponents` 的 `beast_*` 前置分支）。
 *
 * ## 为什么落在 `:app` 而不是 `:core:ui`
 * 两个域的解析**都要求真实资源在场**，而 `:core:ui` 的单测环境拿不到：
 * - 通用像：37 张 WebP 只放在 `:feature:game`（`app` 经资源合并可见），
 *   `:core:ui` 不依赖 `:feature:game`（依赖方向 `core:ui ← feature:game`），
 *   故在 core:ui 里 `PortraitPool.initialize` 的 `getIdentifier` 恒返回 0 ⇒ 断言恒假；
 * - 角色键：注册映射 `registerAllSprites()` 是 `scripts/build-atlas.mjs --codegen`
 *   产出到 **app 的 `build/generated/sprite/` 源集**的产物，core:ui 测试不可达。
 * 因此按 taskbook §4 c340-3「`core/ui` 或 `app` 侧」的第二分支落 `:app`，
 * 五路全部是**真解析断言**，无降级用例。
 *
 * ## 前置：与生产同一入口
 * [registerAllSprites]（`XianxiaApplication.onCreate:146`）+
 * [PortraitPool.initialize]（`XianxiaApplication.onCreate:161`）——
 * 用生产注册入口而非手搓 map，才能同时证明「素材 → codegen → 注册表 → resolver」
 * 整条链在运行期可用（这正是 report-G16 §七 缺口的表现形式：链断在注册面时
 * 编译与既有测试全绿）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // targetSdk 35 超 Robolectric 上限（DefaultSdkPicker 直接抛 IAE）
class PortraitResolverTest {

    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun setUp() {
        registerAllSprites()
        PortraitPool.initialize(context)
    }

    @Test
    fun `通用弟子像 - 走 PortraitPool 第一跳命中 app 合并资源`() {
        val resolved = resolvePortraitResId(GENERIC_PORTRAIT)
        assertTrue(
            "通用像解析为 0 = PortraitPool 未初始化，或素材未进 app 合并资源表（resolved=$resolved）",
            resolved != 0
        )
        assertEquals(
            "resolver 第一跳必须等价于 PortraitPool 的预构建映射",
            PortraitPool.getResourceId(GENERIC_PORTRAIT), resolved
        )
        // 不比对 R.drawable.<通用像>：37 张通用像只放在 feature/game 模块资源里
        // （app 无该字段可引用），而 12 个角色键是双模块放置、app 侧可直接引用（见下）。
    }

    @Test
    fun `通用像池全部 37 名 - 逐个解析非 0（素材漏放或改名即红）`() {
        val unresolved = PortraitPool.allPortraitNames().filter { resolvePortraitResId(it) == 0 }
        assertEquals(
            "PortraitPool 成员清单与 app 合并资源表已漂移（D-12：池=37 张通用像）：$unresolved\n" +
                "处置：核对该 WebP 是否仍在 feature/game/src/main/res/drawable-nodpi，" +
                "以及是否被从 app 的合并资源里移除",
            emptyList<String>(), unresolved
        )
    }

    @Test
    fun `角色立绘键 - 走 SpriteResRegistry 第二跳命中 CHARACTER 分类`() {
        val resolved = resolvePortraitResId(CHARACTER_PORTRAIT)
        assertTrue(
            "角色键解析为 0 = CHARACTER 分类未注册或素材未入库" +
                "（G08 验收⑤ / report-G16 §七 缺口的表现形式，resolved=$resolved）",
            resolved != 0
        )
        assertEquals(
            "resolver 第二跳必须等价于 SpriteResRegistry.resolve",
            SpriteResRegistry.resolve(CHARACTER_PORTRAIT), resolved
        )
        assertEquals(
            "两域互斥：角色键不得在 PortraitPool 里命中（D-12 禁止把角色键塞进池）",
            0, PortraitPool.getResourceId(CHARACTER_PORTRAIT)
        )
        assertEquals(
            "角色立绘键必须解析到 $CHARACTER_PORTRAIT 的 drawable",
            R.drawable.portrait_zhouming, resolved
        )
    }

    @Test
    fun `未知名 - 两域皆不命中返回 0 由调用方兜底`() {
        assertEquals(0, resolvePortraitResId(UNKNOWN_PORTRAIT))
    }

    @Test
    fun `空串与空白串 - 短路返回 0 且不触碰两个域`() {
        assertEquals("空串必须直接判 0（D-11 第 0 步）", 0, resolvePortraitResId(""))
        assertEquals("纯空白必须等价于空串", 0, resolvePortraitResId(BLANK_PORTRAIT))
        // 空白串即便被喂进两个域也不该有键——这里锁的是「池与注册表都不接受空白键」
        assertTrue(
            "PortraitPool 不应收录空白键",
            PortraitPool.allPortraitNames().none { it.isBlank() }
        )
    }

    @Test
    fun `妖兽展示键 - 不经 resolver 命中（须由调用方前置分支处理）`() {
        // `beast_<index>` 是战斗槽位与试炼界面的展示约定，实际取图走 beastSpriteRes(index)；
        // 注册表 BEAST 分类的键是兽种名（tiger/wolf/…），故 resolver 对它恒 0。
        assertEquals(0, resolvePortraitResId(BEAST_KEY))
        assertNull(
            "妖兽键不得进两个域（否则 A-7 保留的 beast_* 前置分支会被静默绕过）",
            SpriteResRegistry.resolve(BEAST_KEY)
        )
    }

    /**
     * 消费点台账（验收⑤后半 + taskbook §5 的「A-7 未改即判红」判据）。
     *
     * 缺陷形态（report-G16 §七 的原始症状）：某处读点绕过 resolver 直引
     * `PortraitPool.getResourceId` ⇒ 角色立绘键查不到 → 回落通用像，而编译与既有测试全绿。
     * 故此处锁两面：**读点必须经 resolver**、**除 resolver 本体与预载清单外不得直引肖像池**。
     */
    @Test
    fun `消费点台账 - 主源读点必须经 resolvePortraitResId 且不得直引肖像池`() {
        val scanned = MAIN_SOURCE_DIRS.map { (module, dir) ->
            assertTrue("主源目录不可达（测试工作目录应为 android/app）：${dir.absolutePath}", dir.isDirectory)
            scanConsumerPoints(module, dir)
        }
        val resolverCallers = scanned.flatMap { it.first }.toSet()
        val poolDirectCallers = scanned.flatMap { it.second }.toSet()
        assertEquals(
            "弟子立绘读点集合漂移（读点须走 resolvePortraitResId 才能同时覆盖通用像域与角色键域）。" +
                "\n  实测 $resolverCallers \n  登记 $REGISTERED_CONSUMER_FILES\n" +
                "处置：新读点若确需直接取资源，请说明为何不经 resolver；否则改用 resolvePortraitResId。",
            REGISTERED_CONSUMER_FILES, resolverCallers
        )
        assertEquals(
            "PortraitPool.getResourceId 的直引面只允许 resolver 本体与预载清单：" +
                "\n  实测 $poolDirectCallers \n  登记 $REGISTERED_POOL_DIRECT_FILES\n" +
                "处置：UI 读点直引肖像池 = 角色立绘会回落通用像（D-11 缺口原症状），必须改走 resolver。",
            REGISTERED_POOL_DIRECT_FILES, poolDirectCallers
        )
    }

    /** 扫描单模块主源，返回（经 resolver 的读点文件集, 直引肖像池的文件集）。 */
    private fun scanConsumerPoints(module: String, dir: File): Pair<Set<String>, Set<String>> {
        var resolverFiles = emptySet<String>()
        var poolDirectFiles = emptySet<String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = "$module/src/main/${file.relativeTo(dir).path.replace(File.separatorChar, '/')}"
            val code = file.readLines().map(String::trim).filterNot(::isNonCodeLine)
            val usesResolver = code.any { it.contains("resolvePortraitResId(") && !it.startsWith("fun ") }
            val usesPoolDirectly = code.any { it.contains("PortraitPool.getResourceId(") }
            if (usesResolver) resolverFiles = resolverFiles + relative
            if (usesPoolDirectly) poolDirectFiles = poolDirectFiles + relative
        }
        return resolverFiles to poolDirectFiles
    }

    /** KDoc/行注释/import 里的函数名引用不构成消费点。 */
    private fun isNonCodeLine(text: String): Boolean =
        text.startsWith("*") || text.startsWith("//") || text.startsWith("import ")

    private companion object {
        /** 六模块主源（测试工作目录 = android/app，故从 `..` 起算） */
        val MAIN_SOURCE_DIRS: List<Pair<String, File>> = listOf(
            "app" to File("../app/src/main"),
            "feature/game" to File("../feature/game/src/main"),
            "core/ui" to File("../core/ui/src/main"),
            "core/engine" to File("../core/engine/src/main"),
            "core/data" to File("../core/data/src/main"),
            "core/domain" to File("../core/domain/src/main"),
        )

        /**
         * A-7 实测读点台账（2026-09-25，7 文件 / 9 处调用；行号会漂故按文件登记）：
         * DiscipleComponents ×1、DiscipleSlotComponents ×2、DetailRightPanel ×1、
         * DiscipleChatDialog ×1、SecretRealmComponents ×1、HeavenlyTrialComponents ×2、
         * DiplomacyChatUi ×2（另 `PortraitImage.kt` 仅改 KDoc，不构成读点）。
         */
        val REGISTERED_CONSUMER_FILES = setOf(
            "feature/game/src/main/java/com/xianxia/sect/ui/components/DiscipleComponents.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/components/DiscipleSlotComponents.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailRightPanel.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/DiscipleChatDialog.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/SecretRealmComponents.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/DiplomacyChatUi.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/heavenlytrial/" +
                "HeavenlyTrialComponents.kt",
        )

        /** resolver 本体（第一跳）+ ResourcePreloader（预载清单走 allPortraitNames/getResourceId，非读点） */
        val REGISTERED_POOL_DIRECT_FILES = setOf(
            "core/ui/src/main/java/com/xianxia/sect/ui/components/PortraitResolver.kt",
            "feature/game/src/main/java/com/xianxia/sect/ui/game/ResourcePreloader.kt",
        )

        /** 通用弟子像（PortraitPool 域，`male_disciple_1..20` 之一） */
        const val GENERIC_PORTRAIT = "male_disciple_1"

        /** 具名角色立绘键（SpriteCategory.CHARACTER 域，G16 注册的 12 键之一） */
        const val CHARACTER_PORTRAIT = "portrait_zhouming"

        /** 两个域都不存在的键 */
        const val UNKNOWN_PORTRAIT = "no_such_portrait"

        /** 空白键（必须与空串同义） */
        const val BLANK_PORTRAIT = "   "

        /** 妖兽展示键形态 */
        const val BEAST_KEY = "beast_3"
    }
}
