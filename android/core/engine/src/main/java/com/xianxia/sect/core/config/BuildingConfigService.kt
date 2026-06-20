package com.xianxia.sect.core.config

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.production.BuildingType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
    val description: String = ""
)

@Singleton
class BuildingConfigService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BuildingConfigService"
        private const val CONFIG_PATH = "config/buildings.json"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private var config: BuildingsConfig? = null
    
    private fun ensureConfigLoaded(): BuildingsConfig {
        if (config == null) {
            loadConfig()
        }
        return config ?: createDefaultConfig().also { config = it }
    }
    
    suspend fun initialize() {
        loadConfig()
    }
    
    private fun loadConfig() {
        try {
            val loadedConfig = loadFromAssets()
            config = loadedConfig ?: createDefaultConfig()
            Log.d(TAG, "Building config loaded with ${config?.buildings?.size ?: 0} buildings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load building config", e)
            config = createDefaultConfig()
        }
    }
    
    private fun loadFromAssets(): BuildingsConfig? {
        return try {
            context.assets.open(CONFIG_PATH).use { inputStream ->
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString<BuildingsConfig>(jsonString)
                val errors = ConfigValidator.validate(parsed)
                if (errors.isEmpty()) {
                    Log.d(TAG, "Config validated successfully from assets")
                } else {
                    Log.w(TAG, "Config validation errors: $errors")
                }
                parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load config from assets: ${e.message}")
            null
        }
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
        return getBuildingConfig(buildingId)?.displayName ?: com.xianxia.sect.core.util.BuildingNames.getDisplayName(buildingId)
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
    
    fun getBuildingTypeFromId(buildingId: String): BuildingType {
        val config = getBuildingConfig(buildingId)
        return config?.let { 
            try {
                BuildingType.valueOf(it.buildingType)
            } catch (e: Exception) {
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

    fun fixupBuildingSizes(buildings: List<com.xianxia.sect.core.model.GridBuildingData>): List<com.xianxia.sect.core.model.GridBuildingData> {
        return buildings.map { b ->
            val (w, h) = getBuildingGridSize(b.displayName)
            if (b.width != w || b.height != h) b.copy(width = w, height = h) else b
        }
    }

    fun getBuildingConfigByDisplayName(displayName: String): BuildingConfigModel? {
        return ensureConfigLoaded().buildings.values.find { it.displayName == displayName }
    }

    fun getSlotCountByDisplayName(displayName: String): Int {
        return getBuildingConfigByDisplayName(displayName)?.slotCount ?: 1
    }

    fun reload() {
        loadConfig()
        Log.d(TAG, "Building config reloaded")
    }
    
    private fun normalizeBuildingId(buildingId: String, cfg: BuildingsConfig = ensureConfigLoaded()): String {
        val normalized = buildingId.lowercase(java.util.Locale.getDefault()).replace("_", "").replace("-", "")
        return cfg.buildingAliases[normalized] ?: buildingId.lowercase(java.util.Locale.getDefault())
    }
    
    private fun createDefaultConfig(): BuildingsConfig {
        return BuildingsConfig(
            version = "1.0.0",
            buildings = mapOf(
                "alchemy" to BuildingConfigModel(
                    id = "alchemy",
                    displayName = "炼丹炉",
                    buildingType = "ALCHEMY",
                    slotCount = 1,
                    baseSuccessRate = 0.7,
                    autoRestartEnabled = true,
                    cost = 3000,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "用于炼制各种丹药的场所"
                ),
                "forge" to BuildingConfigModel(
                    id = "forge",
                    displayName = "锻造坊",
                    buildingType = "FORGE",
                    slotCount = 1,
                    baseSuccessRate = 0.7,
                    autoRestartEnabled = true,
                    cost = 3000,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "锻造装备的场所"
                ),
                "mining" to BuildingConfigModel(
                    id = "mining",
                    displayName = "灵矿场",
                    buildingType = "MINING",
                    slotCount = 3,
                    baseSuccessRate = 1.0,
                    cost = 500,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "开采灵石和矿石"
                ),
                "herb_garden" to BuildingConfigModel(
                    id = "herb_garden",
                    displayName = "灵植阁",
                    buildingType = "HERB_GARDEN",
                    slotCount = 3,
                    baseSuccessRate = 1.0,
                    cost = 2000,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "种植灵草的园地"
                ),
                "tianshu_hall" to BuildingConfigModel(
                    id = "tianshu_hall",
                    displayName = "天枢殿",
                    buildingType = "ADMINISTRATION",
                    slotCount = 2,
                    baseSuccessRate = 1.0,
                    cost = 20000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "处理宗门事务的核心建筑"
                ),
                "library" to BuildingConfigModel(
                    id = "library",
                    displayName = "藏经阁",
                    buildingType = "LIBRARY",
                    slotCount = 3,
                    baseSuccessRate = 1.0,
                    cost = 5000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "弟子修习功法的场所，提升修炼速度"
                ),
                "wen_dao_peak" to BuildingConfigModel(
                    id = "wen_dao_peak",
                    displayName = "问道塔",
                    buildingType = "WEN_DAO_PEAK",
                    slotCount = 5,
                    baseSuccessRate = 1.0,
                    cost = 4000,
                    gridWidth = 2,
                    gridHeight = 3,
                    description = "管理外门弟子与传道授业"
                ),
                "qingyun_peak" to BuildingConfigModel(
                    id = "qingyun_peak",
                    displayName = "青云塔",
                    buildingType = "QINGYUN_PEAK",
                    slotCount = 5,
                    baseSuccessRate = 1.0,
                    cost = 4000,
                    gridWidth = 2,
                    gridHeight = 3,
                    description = "管理内门弟子与精英培养"
                ),
                "law_enforcement_hall" to BuildingConfigModel(
                    id = "law_enforcement_hall",
                    displayName = "执法堂",
                    buildingType = "LAW_ENFORCEMENT_HALL",
                    slotCount = 3,
                    baseSuccessRate = 1.0,
                    cost = 3000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "维护宗门纪律，执行奖惩"
                ),
                "mission_hall" to BuildingConfigModel(
                    id = "mission_hall",
                    displayName = "任务阁",
                    buildingType = "MISSION_HALL",
                    slotCount = 4,
                    baseSuccessRate = 1.0,
                    cost = 3000,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "派遣弟子执行宗门任务"
                ),
                "reflection_cliff" to BuildingConfigModel(
                    id = "reflection_cliff",
                    displayName = "监牢",
                    buildingType = "REFLECTION_CLIFF",
                    slotCount = 6,
                    baseSuccessRate = 1.0,
                    cost = 4000,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "悔过自新之地，关押违规弟子"
                ),
                "single_residence" to BuildingConfigModel(
                    id = "single_residence",
                    displayName = "单人住所",
                    buildingType = "SINGLE_RESIDENCE",
                    slotCount = 1,
                    baseSuccessRate = 1.0,
                    cost = 20000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "为弟子提供清修之所，修炼速度+20%，可升级"
                ),
                "multi_residence" to BuildingConfigModel(
                    id = "multi_residence",
                    displayName = "多人住所",
                    buildingType = "MULTI_RESIDENCE",
                    slotCount = 4,
                    baseSuccessRate = 1.0,
                    cost = 30000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "供多名弟子共同修炼，修炼速度+10%"
                ),
                "single_residence_upgraded" to BuildingConfigModel(
                    id = "single_residence_upgraded",
                    displayName = "中级单人住所",
                    buildingType = "SINGLE_RESIDENCE",
                    slotCount = 1,
                    baseSuccessRate = 1.0,
                    cost = 50000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "单人修炼之所，修炼速度+40%"
                ),
                "spirit_field" to BuildingConfigModel(
                    id = "spirit_field",
                    displayName = "灵田",
                    buildingType = "SPIRIT_FIELD",
                    slotCount = 1,
                    cost = 200,
                    gridWidth = 1,
                    gridHeight = 1,
                    description = "种植灵草的田地"
                ),
                "warehouse" to BuildingConfigModel(
                    id = "warehouse",
                    displayName = "仓库",
                    buildingType = "WAREHOUSE",
                    slotCount = 1,
                    cost = 1000,
                    gridWidth = 3,
                    gridHeight = 2,
                    description = "储存宗门物资，每座+50格容量"
                ),
                "patrol_tower" to BuildingConfigModel(
                    id = "patrol_tower",
                    displayName = "巡视楼",
                    buildingType = "PATROL",
                    slotCount = 8,
                    cost = 50000,
                    gridWidth = 2,
                    gridHeight = 3,
                    description = "驻守弟子自动巡视地图攻击妖兽"
                ),
                "blood_refining_pool" to BuildingConfigModel(
                    id = "blood_refining_pool",
                    displayName = "血炼池",
                    buildingType = "BLOOD_REFINING_POOL",
                    slotCount = 1,
                    baseSuccessRate = 1.0,
                    cost = 50000,
                    gridWidth = 2,
                    gridHeight = 2,
                    description = "消耗妖兽精血材料淬炼弟子肉身，永久提升战斗属性"
                )
            ),
            buildingAliases = mapOf(
                // 灵矿 (mining)
                "mine" to "mining",
                "mining" to "mining",

                // 炼丹炉 (alchemy)
                "alchemyroom" to "alchemy",
                "alchemy" to "alchemy",

                // 锻造坊 (forge)
                "forging" to "forge",
                "forge" to "forge",

                // 灵植阁 (herb_garden)
                "herb" to "herb_garden",
                "herbgarden" to "herb_garden",
                "herb_garden" to "herb_garden",

                // 天枢殿 (administration / tianshu_hall)
                "tianshu" to "tianshu_hall",
                "tianshuhall" to "tianshu_hall",
                "tianshu_hall" to "tianshu_hall",
                "administration" to "tianshu_hall",

                // 藏经阁 (library)
                "library" to "library",
                "藏经阁" to "library",

                // 问道塔 (wen_dao_peak)
                "wendaopeak" to "wen_dao_peak",
                "wendaopeak" to "wen_dao_peak",
                "wendao" to "wen_dao_peak",
                "wen_dao_peak" to "wen_dao_peak",
                "问道塔" to "wen_dao_peak",

                // 青云塔 (qingyun_peak)
                "qingyunpeak" to "qingyun_peak",
                "qingyunpeak" to "qingyun_peak",
                "qingyun" to "qingyun_peak",
                "qingyun_peak" to "qingyun_peak",
                "青云塔" to "qingyun_peak",

                // 执法堂 (law_enforcement_hall)
                "lawenforcementhall" to "law_enforcement_hall",
                "lawenforcement" to "law_enforcement_hall",
                "zhifatang" to "law_enforcement_hall",
                "law_enforcement_hall" to "law_enforcement_hall",
                "执法堂" to "law_enforcement_hall",

                // 任务阁 (mission_hall)
                "missionhall" to "mission_hall",
                "missionhall" to "mission_hall",
                "renwuge" to "mission_hall",
                "mission_hall" to "mission_hall",
                "任务阁" to "mission_hall",

                // 监牢 (reflection_cliff)
                "reflectioncliff" to "reflection_cliff",
                "reflectioncliff" to "reflection_cliff",
                "siguoya" to "reflection_cliff",
                "reflection_cliff" to "reflection_cliff",
                "监牢" to "reflection_cliff",

                // 住所 (residence)
                "singleresidence" to "single_residence",
                "single_residence" to "single_residence",
                "multiresidence" to "multi_residence",
                "multi_residence" to "multi_residence",
                "singleresidenceupgraded" to "single_residence_upgraded",
                "single_residence_upgraded" to "single_residence_upgraded",
                "warehouse" to "warehouse",
                "patrol_tower" to "patrol_tower",
                "patroltower" to "patrol_tower",
                "bloodrefiningpool" to "blood_refining_pool",
                "blood_refining_pool" to "blood_refining_pool"
            )
        )
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
            } catch (e: IllegalArgumentException) {
                errors.add("Unknown buildingType for $id: ${building.buildingType}")
            }
        }

        return errors
    }

}
