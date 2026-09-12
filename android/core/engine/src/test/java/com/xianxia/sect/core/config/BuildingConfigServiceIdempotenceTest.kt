package com.xianxia.sect.core.config

import com.xianxia.sect.core.platform.AssetSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `BuildingConfigService.initialize` 幂等守卫测试。
 *
 * 背景：每次 boot 经 `ResourcePreloader.preloadGameResources` 重复调用 `initialize`，
 * 重复读取资产 `config/buildings.json`（无谓 I/O）。
 * 守卫语义：实例级仅首次真正执行资产读取，后续调用直接跳过。
 */
class BuildingConfigServiceIdempotenceTest {

    private fun newService(assetSource: AssetSource): BuildingConfigService {
        return BuildingConfigService(assetSource)
    }

    @Test
    fun `initialize - 重复调用仅读取一次assets`() = runTest {
        val assetSource = mock<AssetSource>()
        // 资产不存在走默认配置回退（与既有 BuildingConfigServiceFixupTest 同模式）
        whenever(assetSource.open(any())).thenReturn(null)
        val service = newService(assetSource)

        service.initialize()
        service.initialize()
        service.initialize()

        verify(assetSource, times(1)).open(any())
    }

    @Test
    fun `initialize - 守卫跳过后续加载后配置仍可用`() = runTest {
        val assetSource = mock<AssetSource>()
        whenever(assetSource.open(any())).thenReturn(null)
        val service = newService(assetSource)

        service.initialize()
        // 第二次 initialize 被跳过，但 ensureConfigLoaded 的默认配置回退路径仍有效
        service.initialize()
        val config = service.getAllBuildingConfigs()
        assertTrue("守卫跳过后配置应保持默认回退内容", config.isNotEmpty())
    }
}
