package com.xianxia.sect.core.engine.config

import com.xianxia.sect.core.config.GameConfigData
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行时游戏配置提供者。
 *
 * 从 [GameConfigData]（JSON 可序列化配置，支持远程热更新）读取游戏数值，
 * 作为 [GameConfig] 编译期常量的运行时替代源。
 *
 * ## 迁移路径
 *
 * 1. 新代码直接注入 [GameConfigProvider]，通过 provider 读取配置值
 * 2. 存量代码逐步从 `GameConfig.Production.X` 迁移至此
 * 3. 当所有调用方迁移完毕后，[GameConfig] 中的对应常量可标记 [Deprecated]
 *
 * 当前阶段：[GameConfig] 仍为默认来源，provider 提供[GameConfigData] 的读取入口
 * 供新代码使用。GameConfigData 与 GameConfig 之间的数值一致性由
 * [com.xianxia.sect.core.config.GameConfigConsistencyTest] 守卫测试保障。
 */
@Singleton
class GameConfigProvider @Inject constructor(
    private val configLoader: com.xianxia.sect.core.config.ConfigLoader
) {

    private val config: GameConfigData by lazy {
        val cfg = try {
            configLoader.load()
        } catch (e: Exception) {
            DomainLog.w(TAG, "ConfigLoader.load() failed, using defaults", e)
            GameConfigData()
        }
        DomainLog.i(TAG, "GameConfigProvider initialized: v${cfg.version}")
        cfg
    }

    /** 生产系统配置 */
    val production: ProductionConfig get() = ProductionConfig(config.production)

    class ProductionConfig(private val s: GameConfigData.ProductionSection) {
        val spiritMineBaseOutputPerMiner: Int get() = s.spiritMineBaseOutputPerMiner
        val spiritMineMiningThreshold: Int get() = s.spiritMineMiningThreshold
        val spiritMineMiningBonusRate: Double get() = s.spiritMineMiningBonusRate
    }

    /** 仓库配置 */
    val warehouse: WarehouseConfig get() = WarehouseConfig(config.warehouse)

    class WarehouseConfig(private val s: GameConfigData.WarehouseSection) {
        val baseCapacity: Int get() = s.baseCapacity
        val capacityPerBuilding: Int get() = s.capacityPerBuilding
    }

    companion object {
        private const val TAG = "GameConfigProvider"
    }
}
