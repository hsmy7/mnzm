package com.xianxia.sect.core.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xianxia.sect.core.model.production.BuildingConfig
import com.xianxia.sect.core.model.production.BuildingConfigs
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.proto.templates.*
import java.io.BufferedReader
import java.io.InputStreamReader

@Deprecated(
    message = "Use BuildingConfigModel from BuildingConfigService instead. This class is legacy Gson-based data model.",
    replaceWith = ReplaceWith("BuildingConfigModel", "com.xianxia.sect.core.config.BuildingConfigModel"),
    level = DeprecationLevel.WARNING
)
data class BuildingConfigData(
    val id: String,
    val displayName: String,
    val buildingType: BuildingType,
    val slotCount: Int,
    val baseSuccessRate: Double = 1.0,
    val maxQueueLength: Int = 1,
    val autoRestartEnabled: Boolean = false,
    val description: String = ""
) {
    fun toBuildingConfig(): BuildingConfig = BuildingConfig(
        buildingType = buildingType,
        slotCount = slotCount,
        baseSuccessRate = baseSuccessRate
    )
}

@Deprecated(
    message = "Use BuildingsConfig from BuildingConfigService instead. This class is legacy Gson-based file model.",
    replaceWith = ReplaceWith("BuildingsConfig", "com.xianxia.sect.core.config.BuildingsConfig"),
    level = DeprecationLevel.WARNING
)
data class BuildingsJsonFile(
    val version: String = "1.0.0",
    val buildings: Map<String, BuildingJsonData> = emptyMap(),
    val buildingAliases: Map<String, String> = emptyMap()
)

@Deprecated(
    message = "Use BuildingConfigModel from BuildingConfigService instead. This class is legacy Gson-based data model.",
    replaceWith = ReplaceWith("BuildingConfigModel", "com.xianxia.sect.core.config.BuildingConfigModel"),
    level = DeprecationLevel.WARNING
)
data class BuildingJsonData(
    val id: String,
    val displayName: String,
    val buildingType: String,
    val slotCount: Int,
    val baseSuccessRate: Double = 1.0,
    val maxQueueLength: Int = 1,
    val autoRestartEnabled: Boolean = false,
    val description: String = ""
)

data class BuildingValidationResult(
    val isValid: Boolean,
    val totalCount: Int,
    val validCount: Int,
    val warnings: List<BuildingValidationWarning> = emptyList(),
    val validationTimeMs: Long = 0
)

data class BuildingValidationWarning(
    val buildingId: String,
    val fieldName: String,
    val message: String,
    val severity: Severity = Severity.WARNING
) {
    enum class Severity { WARNING, ERROR }
}

object BuildingProtoLoader {
    
    private const val TAG = "BuildingProtoLoader"
    private const val BUILDINGS_JSON_PATH = "config/buildings.json"
    
    private val gson = Gson()
    
    var enableProtoValidation: Boolean = true
    
    @Volatile
    var lastValidationResult: BuildingValidationResult? = null
        private set
    
    @Volatile
    private var _buildingConfigMap: Map<String, BuildingConfigData>? = null
    @Volatile
    private var _buildingAliases: Map<String, String>? = null
    @Volatile
    private var _isInitialized = false
    private val initLock = Any()
    
    val isInitialized: Boolean
        get() = _isInitialized
    
    fun initializeSync(context: Context): Result<Unit> {
        return try {
            synchronized(initLock) {
                if (_isInitialized) return Result.success(Unit)
                
                val jsonData = loadBuildingsJson(context)
                _buildingConfigMap = parseBuildingConfigs(jsonData.buildings)
                _buildingAliases = jsonData.buildingAliases
                
                if (enableProtoValidation && _buildingConfigMap != null) {
                    val buildingConfigs = _buildingConfigMap
                        ?: throw IllegalStateException("BuildingProtoLoader: _buildingConfigMap is null after loading")
                    lastValidationResult = validateWithProto(buildingConfigs)
                    val result = lastValidationResult
                        ?: throw IllegalStateException("BuildingProtoLoader: validation produced null result")
                    if (!result.isValid) {
                        Log.w(TAG, "Building config validation warnings: ${result.warnings.size} issues")
                    } else {
                        Log.i(TAG, "Building config validation passed: ${buildingConfigs.size} buildings verified")
                    }
                }
                
                _isInitialized = true
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BuildingProtoLoader", e)
            Result.failure(e)
        }
    }
    
    fun initializeAsync(context: Context, onComplete: (Result<Unit>) -> Unit) {
        Thread {
            val result = initializeSync(context)
            onComplete(result)
        }.start()
    }
    
    fun getBuildingConfigData(buildingId: String): BuildingConfigData? {
        check(_isInitialized) { "BuildingProtoLoader not initialized" }
        return _buildingConfigMap?.get(buildingId)
    }
    
    fun getConfig(buildingType: BuildingType): BuildingConfig {
        check(_isInitialized) { "BuildingProtoLoader not initialized" }
        
        return _buildingConfigMap?.values?.find { it.buildingType == buildingType }
            ?.toBuildingConfig()
            ?: BuildingConfig(buildingType = buildingType, slotCount = 1)
    }
    
    fun resolveAlias(alias: String): String? {
        check(_isInitialized) { "BuildingProtoLoader not initialized" }
        return _buildingAliases?.get(alias.lowercase())
    }
    
    fun getAllBuildingIds(): List<String> {
        check(_isInitialized) { "BuildingProtoLoader not initialized" }
        return _buildingConfigMap?.keys?.toList() ?: emptyList()
    }
    
    private fun loadBuildingsJson(context: Context): BuildingsJsonFile {
        val inputStream = context.assets.open(BUILDINGS_JSON_PATH)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val jsonText = reader.use { it.readText() }
        return gson.fromJson(jsonText, BuildingsJsonFile::class.java)
    }
    
    private fun parseBuildingConfigs(buildingsJson: Map<String, BuildingJsonData>): Map<String, BuildingConfigData> {
        return buildingsJson.mapValues { (_, json) ->
            BuildingConfigData(
                id = json.id,
                displayName = json.displayName,
                buildingType = parseBuildingType(json.buildingType),
                slotCount = json.slotCount,
                baseSuccessRate = json.baseSuccessRate,
                maxQueueLength = json.maxQueueLength,
                autoRestartEnabled = json.autoRestartEnabled,
                description = json.description
            )
        }
    }
    
    private fun parseBuildingType(typeStr: String): BuildingType {
        return try {
            BuildingType.valueOf(typeStr)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown building type: $typeStr, defaulting to ALCHEMY")
            BuildingType.ALCHEMY
        }
    }
    
    private fun validateWithProto(buildings: Map<String, BuildingConfigData>): BuildingValidationResult {
        val startTime = System.currentTimeMillis()
        val warnings = mutableListOf<BuildingValidationWarning>()
        var validCount = 0
        
        buildings.forEach { (id, config) ->
            try {
                val protoBuilder = BuildingTemplateProto.newBuilder()
                    .setId(config.id)
                    .setDisplayName(config.displayName)
                    .setSlotCount(config.slotCount)
                    .setBaseSuccessRate(config.baseSuccessRate)
                    .setMaxQueueLength(config.maxQueueLength)
                    .setAutoRestartEnabled(config.autoRestartEnabled)
                    .setDescription(config.description)
                
                protoBuilder.buildingType = when (config.buildingType) {
                    BuildingType.ALCHEMY -> BuildingTypeProto.BUILDING_TYPE_ALCHEMY
                    BuildingType.FORGE -> BuildingTypeProto.BUILDING_TYPE_FORGE
                    BuildingType.MINING -> BuildingTypeProto.BUILDING_TYPE_MINING
                    BuildingType.HERB_GARDEN -> BuildingTypeProto.BUILDING_TYPE_HERB_GARDEN
                    else -> BuildingTypeProto.BUILDING_TYPE_UNKNOWN
                }
                
                val proto = protoBuilder.build()
                val bytes = proto.toByteArray()
                BuildingTemplateProto.parseFrom(bytes)
                
                validCount++
                validateBusinessRules(id, config, warnings)
                
            } catch (e: Exception) {
                warnings.add(BuildingValidationWarning(
                    buildingId = id,
                    fieldName = "proto_conversion",
                    message = "Failed to convert to Proto: ${e.message}",
                    severity = BuildingValidationWarning.Severity.ERROR
                ))
            }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        return BuildingValidationResult(
            isValid = warnings.none { it.severity == BuildingValidationWarning.Severity.ERROR },
            totalCount = buildings.size,
            validCount = validCount,
            warnings = warnings,
            validationTimeMs = elapsed
        )
    }
    
    private fun validateBusinessRules(
        id: String,
        config: BuildingConfigData,
        warnings: MutableList<BuildingValidationWarning>
    ) {
        if (config.slotCount < 1 || config.slotCount > 20) {
            warnings.add(BuildingValidationWarning(
                buildingId = id,
                fieldName = "slotCount",
                message = "Suspicious slot count: ${config.slotCount}"
            ))
        }
        
        if (config.baseSuccessRate < 0.0 || config.baseSuccessRate > 1.0) {
            warnings.add(BuildingValidationWarning(
                buildingId = id,
                fieldName = "baseSuccessRate",
                message = "Base success rate out of [0-1]: ${config.baseSuccessRate}",
                severity = BuildingValidationWarning.Severity.ERROR
            ))
        }
        
        if (config.maxQueueLength < 1 || config.maxQueueLength > 10) {
            warnings.add(BuildingValidationWarning(
                buildingId = id,
                fieldName = "maxQueueLength",
                message = "Suspicious max queue length: ${config.maxQueueLength}"
            ))
        }
    }
    
    fun exportToProtobuf(): String {
        check(_isInitialized) { "BuildingProtoLoader not initialized" }
        
        val builder = BuildingConfigFileProto.newBuilder()
        
        _buildingConfigMap?.forEach { (id, config) ->
            builder.putBuildings(id, convertToProto(config))
        }
        
        _buildingAliases?.forEach { (alias, targetId) ->
            builder.putBuildingAliases(alias, targetId)
        }
        
        return java.util.Base64.getEncoder().encodeToString(builder.build().toByteArray())
    }
    
    private fun convertToProto(config: BuildingConfigData): BuildingTemplateProto {
        val builder = BuildingTemplateProto.newBuilder()
            .setId(config.id)
            .setDisplayName(config.displayName)
            .setSlotCount(config.slotCount)
            .setBaseSuccessRate(config.baseSuccessRate)
            .setMaxQueueLength(config.maxQueueLength)
            .setAutoRestartEnabled(config.autoRestartEnabled)
            .setDescription(config.description)
        
        builder.buildingType = when (config.buildingType) {
            BuildingType.ALCHEMY -> BuildingTypeProto.BUILDING_TYPE_ALCHEMY
            BuildingType.FORGE -> BuildingTypeProto.BUILDING_TYPE_FORGE
            BuildingType.MINING -> BuildingTypeProto.BUILDING_TYPE_MINING
            BuildingType.HERB_GARDEN -> BuildingTypeProto.BUILDING_TYPE_HERB_GARDEN
            else -> BuildingTypeProto.BUILDING_TYPE_UNKNOWN
        }
        
        return builder.build()
    }
}
