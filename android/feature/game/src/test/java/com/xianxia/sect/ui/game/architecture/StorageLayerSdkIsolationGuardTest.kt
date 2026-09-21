package com.xianxia.sect.ui.game.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN3 接口隔离静态守卫（SR-2）：存储层/引擎层/云存档链**零 TapTap SDK 类型引用**。
 *
 * 业务面只依赖 StorageFacade / SaveBackend（core:data cloud 包，零 SDK 编译期类型）；
 * TapTap 云存档 SDK 只在 feature/game 的反射桥（CloudSaveApiBridge，Class.forName 字符串
 * 探测）与 TapTapSaveBackend 后被触达——云存档全链无 `import com.taptap.*`。
 *
 * 第二层守卫 = core:data SaveBackendModeTest 的 classpath 负向断言
 * （存储层依赖面结构性不可见 SDK 类型）。新增任何 SDK import 立即红灯。
 *
 * **白名单（既有豁免登记，新增须拍板）**：
 * - `TapTapLeaderboardApi.kt`：排行榜域**既有**直接 SDK 引用（tap-leaderboard，非云存档
 *   存储层），先于本批存在；是否收编进接口隔离归产品/后续批拍板，本批不夹带修复。
 */
class StorageLayerSdkIsolationGuardTest {

    /** 既有豁免（见类 KDoc 白名单）；Konsist file.name 不带扩展名，新增须拍板 */
    private val exemptedFiles = setOf(
        "TapTapLeaderboardApi" // 排行榜域既有直接 SDK 引用，非存储层；待拍板
    )

    private fun assertNoSdkImports(scopeDir: String) {
        val scope = Konsist.scopeFromDirectory(scopeDir)
        assertTrue("守卫应扫到源码: $scopeDir", scope.files.isNotEmpty())
        val violations = scope.files
            .filter { it.name !in exemptedFiles }
            .filter { file ->
                val text = file.text
                text.contains("import com.taptap.") || text.contains("import com.xd.sdk.")
            }
        assertTrue(
            "IN3 违规：$scopeDir 出现 TapTap SDK 类型引用，违规文件: ${violations.map { it.name }.take(10)}",
            violations.isEmpty()
        )
    }

    @Test
    fun `IN3 - feature 层云存档链零 TapTap SDK import（排行榜既有豁免已登记）`() {
        assertNoSdkImports("feature/game/src/main")
    }

    @Test
    fun `IN3 - core engine 层零 TapTap SDK import`() {
        assertNoSdkImports("core/engine/src/main")
    }

    @Test
    fun `IN3 - core data 存储层零 TapTap SDK import`() {
        assertNoSdkImports("core/data/src/main")
    }
}
