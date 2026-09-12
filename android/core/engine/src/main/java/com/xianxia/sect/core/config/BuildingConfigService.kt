package com.xianxia.sect.core.config

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.platform.AssetSource
import com.xianxia.sect.core.model.production.BuildingType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.GameConfig

@Serializable
data class BuildingsConfig(
    val version: String = "1.0.0",
    val buildings: Map<String, BuildingConfigModel> = emptyMap(),
    val buildingAliases: Map<String, String> = emptyMap()
)

@Serializable
data class BuildingConfigModel(
    val id: String,
    val displayName: String,
    val buildingType: String,
    val slotCount: Int = 1,
    val baseSuccessRate: Double = 1.0,
    val maxQueueLength: Int = 1,
    val autoRestartEnabled: Boolean = false,
    val cost: Long = 1000,
    val gridWidth: Int = 2,
    val gridHeight: Int = 2,
    /** 精灵视觉比例宽度（格数），0 = 使用 gridWidth */
    val spriteWidth: Int = 0,
    /** 精灵视觉比例高度（格数），0 = 使用 gridHeight */
    val spriteHeight: Int = 0,
    val description: String = ""
) {
    /** 获取实际精灵宽度，为 0 时回退到占地宽度 */
    fun effectiveSpriteWidth(): Int = if (spriteWidth > 0) spriteWidth else gridWidth

    /** 获取实际精灵高度，为 0 时回退到占地高度 */
    fun effectiveSpriteHeight(): Int = if (spriteHeight > 0) spriteHeight else gridHeight

}

@Singleton
class BuildingConfigService @Inject constructor(

    internal val assetSource: AssetSource
) {
    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "BuildingConfigService"
        internal const val CONFIG_PATH = "config/buildings.json"
    }

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    internal var config: BuildingsConfig? = null

    /** 幂等守卫：实例级仅首次真正执行 assets JSON 读取 */
    private var initialized = false

    /**
     * 初始化建筑配置（每次 boot 经 `ResourcePreloader.preloadGameResources` 调用）。
     * 重复调用直接跳过，避免 `config/buildings.json` 重复 I/O（首次加载失败已回退默认配置，
     * 无需重试语义）。
     */
    suspend fun initialize() {
        if (initialized) return
        loadConfig()
        initialized = true
    }

    fun getBuildingConfig(buildingId: String): BuildingConfigModel? {
        val cfg = ensureConfigLoaded()
        val normalizedId = normalizeBuildingId(buildingId, cfg)
        return cfg.buildings[normalizedId]
    }

    fun getBuildingConfigByType(buildingType: BuildingType): BuildingConfigModel? {
        val cfg = ensureConfigLoaded()
        return cfg.buildings.values.find { it.buildingType == buildingType.name }
    }

    fun getAllBuildingConfigs(): List<BuildingConfigModel> {
        return ensureConfigLoaded().buildings.values.toList()
    }

    fun getSlotCount(buildingId: String): Int {
        return getBuildingConfig(buildingId)?.slotCount ?: 1
    }

    fun getSlotCountByType(buildingType: BuildingType): Int {
        return getBuildingConfigByType(buildingType)?.slotCount ?: 1
    }

    fun getBaseSuccessRate(buildingId: String): Double {
        return getBuildingConfig(buildingId)?.baseSuccessRate ?: 1.0
    }

    fun getBuildingDisplayName(buildingId: String): String {
        return getBuildingConfig(buildingId)?.displayName ?: com.xianxia.sect.core.util.BuildingNames
            .getDisplayName(buildingId)
    }

    fun isValidSlotIndex(buildingId: String, slotIndex: Int): Boolean {
        val slotCount = getSlotCount(buildingId)
        return slotIndex >= 0 && slotIndex < slotCount
    }

    fun isValidSlotIndexByType(buildingType: BuildingType, slotIndex: Int): Boolean {
        val slotCount = getSlotCountByType(buildingType)
        return slotCount >= 0 && slotIndex < slotCount
    }

    fun resolveBuildingId(input: String): String {
        val cfg = ensureConfigLoaded()
        return cfg.buildingAliases[input.lowercase(java.util.Locale.getDefault()).replace("_", "").replace("-", "")]
            ?: input.lowercase(java.util.Locale.getDefault())
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun getBuildingTypeFromId(buildingId: String): BuildingType {
        val config = getBuildingConfig(buildingId)
        return config?.let {
            try {
                BuildingType.valueOf(it.buildingType)
            } catch (ignored: Exception) {
                BuildingType.ALCHEMY
            }
        } ?: BuildingType.ALCHEMY
    }

    fun getBuildingCost(buildingId: String): Long {
        return getBuildingConfig(buildingId)?.cost ?: 1000L
    }

    fun getBuildingGridSize(displayName: String): Pair<Int, Int> {
        val config = getBuildingConfigByDisplayName(displayName)
        return (config?.gridWidth ?: 2) to (config?.gridHeight ?: 2)
    }

    fun getBuildingConfigByDisplayName(displayName: String): BuildingConfigModel? {
        return ensureConfigLoaded().buildings.values.find { it.displayName == displayName }
    }

    fun getSlotCountByDisplayName(displayName: String): Int {
        return getBuildingConfigByDisplayName(displayName)?.slotCount ?: 1
    }

    fun reload() {
        loadConfig()
        DomainLog.d(TAG, "Building config reloaded")
    }


    /**
     * 修正建筑占地尺寸为当前配置值（旧档兼容），并在尺寸变化时把坐标钳回地图界内。
     *
     * 旧档建筑按旧尺寸落位，修正为当前尺寸后若位于地图边缘会越界
     * （越界部分不可点、占地突出）。仅在尺寸变化时
     * 钳制坐标到完整地图边界；尺寸已正确但坐标越界的损坏数据不动（交由溢出迁移拆除退款）。
     *
     * @param buildings 建筑列表
     * @param worldWidthCells 地图宽度（格），默认当前世界尺寸
     * @param worldHeightCells 地图高度（格），默认当前世界尺寸
     * @return 修正后的建筑列表
     */
    fun fixupBuildingSizes(
        buildings: List<com.xianxia.sect.core.model.GridBuildingData>,
        worldWidthCells: Int = GameConfig.SectMap.WORLD_WIDTH_CELLS,
        worldHeightCells: Int = GameConfig.SectMap.WORLD_HEIGHT_CELLS
    ): List<com.xianxia.sect.core.model.GridBuildingData> {
        return buildings.map { b ->
            val (w, h) = getBuildingGridSize(b.displayName)
            if (b.width != w || b.height != h) {
                val clampedX = if (w >= worldWidthCells) b.gridX
                else b.gridX.coerceIn(0, worldWidthCells - w)
                val clampedY = if (h >= worldHeightCells) b.gridY
                else b.gridY.coerceIn(0, worldHeightCells - h)
                b.copy(width = w, height = h, gridX = clampedX, gridY = clampedY)
            } else {
                b
            }
        }
    }
}

object ConfigValidator {

    fun validate(config: BuildingsConfig): List<String> {
        val errors = mutableListOf<String>()

        config.buildings.forEach { (id, building) ->
            if (building.id != id) {
                errors.add("Building ID mismatch: key=$id, id=${building.id}")
            }

            if (building.slotCount < 1 || building.slotCount > 8) {
                errors.add("Invalid slotCount for $id: ${building.slotCount}")
            }

            if (building.baseSuccessRate < 0 || building.baseSuccessRate > 1) {
                errors.add("Invalid baseSuccessRate for $id: ${building.baseSuccessRate}")
            }

            try {
                BuildingType.valueOf(building.buildingType)
            } catch (ignored: IllegalArgumentException) {
                errors.add("Unknown buildingType for $id: ${building.buildingType}")
            }
        }

        return errors
    }

}
