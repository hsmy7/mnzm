package com.xianxia.sect.core.config

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * `BuildingConfigService.initialize` 幂等守卫测试（docs/architecture.md 待办 D-30）。
 *
 * 背景：每次 boot 经 `ResourcePreloader.preloadGameResources` 重复调用 `initialize`，
 * 重复读取 assets `config/buildings.json`（无谓 I/O）。
 * 守卫语义：实例级仅首次真正执行 assets 读取，后续调用直接跳过。
 */
class BuildingConfigServiceIdempotenceTest {

    private fun newService(assetManager: AssetManager): BuildingConfigService {
        val context = mock<Context>()
        whenever(context.assets).thenReturn(assetManager)
        return BuildingConfigService(context)
    }

    @Test
    fun `initialize - 重复调用仅读取一次assets`() = runTest {
        val assetManager = mock<AssetManager>()
        // 首次读取抛异常走默认配置回退（与既有 BuildingConfigServiceFixupTest 同模式）
        whenever(assetManager.open(any())).thenThrow(IOException("no assets in unit test"))
        val service = newService(assetManager)

        service.initialize()
        service.initialize()
        service.initialize()

        verify(assetManager, times(1)).open(any())
    }

    @Test
    fun `initialize - 守卫跳过后续加载后配置仍可用`() = runTest {
        val assetManager = mock<AssetManager>()
        whenever(assetManager.open(any())).thenThrow(IOException("no assets in unit test"))
        val service = newService(assetManager)

        service.initialize()
        // 第二次 initialize 被跳过，但 ensureConfigLoaded 的默认配置回退路径仍有效
        service.initialize()
        val config = service.getAllBuildingConfigs()
        assertTrue("守卫跳过后配置应保持默认回退内容", config.isNotEmpty())
    }
}
