package com.xianxia.sect.core.data

import android.content.Context
import com.google.gson.Gson
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualType
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

object ManualDatabase {
    
    private val gson = Gson()
    
    data class BuffInfo(
        val type: String,
        val value: Double,
        val duration: Int
    )
    
    data class ManualTemplate(
        val id: String,
        val name: String,
        val type: ManualType,
        val rarity: Int,
        val description: String,
        val stats: Map<String, Int> = emptyMap(),
        val skillName: String? = null,
        val skillDescription: String? = null,
        val skillType: String = "attack",
        val skillDamageType: String = "physical",
        val skillHits: Int = 1,
        val skillDamageMultiplier: Double = 1.0,
        val skillCooldown: Int = 3,
        val skillMpCost: Int = 10,
        val skillHealPercent: Double = 0.0,
        val skillHealType: String = "hp",
        val skillBuffType: String? = null,
        val skillBuffValue: Double = 0.0,
        val skillBuffDuration: Int = 0,
        val skillBuffs: List<BuffInfo> = emptyList(),
        val price: Int = 0,
        val minRealm: Int = 9
    )
    
    @Volatile
    private var _allManuals: Map<String, ManualTemplate>? = null
    @Volatile
    private var _legacyMapping: Map<String, String>? = null
    @Volatile
    private var _isInitialized = false
    private val initLock = Any()
    
    val isInitialized: Boolean
        get() = _isInitialized
    
    val allManuals: Map<String, ManualTemplate>
        get() = _allManuals ?: emptyMap()
    
    private val legacyManualIdMapping: Map<String, String>
        get() = _legacyMapping ?: buildLegacyMapping()
    
    private fun buildLegacyMapping(): Map<String, String> {
        return mapOf(
            "commonMovement" to "support_common_speed_crit_buff",
            "uncommonMovement" to "support_uncommon_speed_crit_buff",
            "rareMovement" to "support_rare_speed_crit_buff",
            "epicMovement" to "support_epic_speed_crit_buff",
            "legendaryMovement" to "support_legendary_speed_crit_buff",
            "mythicMovement" to "support_mythic_speed_crit_buff"
        )
    }
    
    fun initializeSync(context: Context): Result<Unit> {
        return try {
            synchronized(initLock) {
                if (_isInitialized) return Result.success(Unit)
                
                _allManuals = loadManualTemplatesSync(context)
                _legacyMapping = buildLegacyMapping()
                _isInitialized = true
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun loadManualTemplatesSync(context: Context): Map<String, ManualTemplate> {
        val jsonString = loadJsonFromAssetsSync(context, "data/manuals.json")
        val dataFile = gson.fromJson<ManualJsonDataFile>(jsonString, ManualJsonDataFile::class.java)
        
        val allManuals = mutableMapOf<String, ManualTemplate>()
        dataFile.attackManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
        dataFile.defenseManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
        dataFile.supportManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
        dataFile.mindManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
        
        return allManuals
    }
    
    private data class ManualJsonDataFile(
        val attackManuals: List<ManualJsonDataSync> = emptyList(),
        val defenseManuals: List<ManualJsonDataSync> = emptyList(),
        val supportManuals: List<ManualJsonDataSync> = emptyList(),
        val mindManuals: List<ManualJsonDataSync> = emptyList()
    )
    
    private data class ManualJsonDataSync(
        val id: String,
        val name: String,
        val type: String,
        val rarity: Int,
        val description: String,
        val stats: Map<String, Int>? = null,
        val skillName: String? = null,
        val skillDescription: String? = null,
        val skillType: String? = null,
        val skillDamageType: String? = null,
        val skillHits: Int? = null,
        val skillDamageMultiplier: Double? = null,
        val skillCooldown: Int? = null,
        val skillMpCost: Int? = null,
        val skillHealPercent: Double? = null,
        val skillHealType: String? = null,
        val skillBuffType: String? = null,
        val skillBuffValue: Double? = null,
        val skillBuffDuration: Int? = null,
        val skillBuffs: List<BuffInfo>? = null,
        val price: Int? = null,
        val minRealm: Int? = null
    ) {
        fun toManualTemplate(): ManualTemplate = ManualTemplate(
            id = id,
            name = name,
            type = ManualType.valueOf(type),
            rarity = rarity,
            description = description,
            stats = stats ?: emptyMap(),
            skillName = skillName,
            skillDescription = skillDescription,
            skillType = skillType ?: "attack",
            skillDamageType = skillDamageType ?: "physical",
            skillHits = skillHits ?: 1,
            skillDamageMultiplier = skillDamageMultiplier ?: 1.0,
            skillCooldown = skillCooldown ?: 3,
            skillMpCost = skillMpCost ?: 10,
            skillHealPercent = skillHealPercent ?: 0.0,
            skillHealType = skillHealType ?: "hp",
            skillBuffType = skillBuffType,
            skillBuffValue = skillBuffValue ?: 0.0,
            skillBuffDuration = skillBuffDuration ?: 0,
            skillBuffs = skillBuffs ?: emptyList(),
            price = price ?: 0,
            minRealm = minRealm ?: 9
        )
    }
    
    private fun loadJsonFromAssetsSync(context: Context, fileName: String): String {
        val inputStream = context.assets.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        return reader.use { it.readText() }
    }
    
    fun initializeWithManuals(manuals: Map<String, ManualTemplate>) {
        synchronized(initLock) {
            _allManuals = manuals
            _legacyMapping = buildLegacyMapping()
            _isInitialized = true
        }
    }
    
    fun getById(id: String): ManualTemplate? {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        return allManuals[id] ?: allManuals[legacyManualIdMapping[id]]
    }

    fun getByName(name: String): ManualTemplate? {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        return allManuals.values.find { it.name == name }
    }

    fun getByNameAndRarity(name: String, rarity: Int): ManualTemplate? {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        return allManuals.values.find { it.name == name && it.rarity == rarity }
    }

    fun getByType(type: ManualType): List<ManualTemplate> {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        return allManuals.values.filter { it.type == type }
    }
    
    fun getByRarity(rarity: Int): List<ManualTemplate> {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        return allManuals.values.filter { it.rarity == rarity }
    }
    
    fun createFromTemplate(template: ManualTemplate): Manual {
        val skillBuffsJson = template.skillBuffs.joinToString("|") { buff ->
            "${buff.type},${buff.value},${buff.duration}"
        }
        return Manual(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            type = template.type,
            rarity = template.rarity,
            description = template.description,
            stats = template.stats,
            skillName = template.skillName,
            skillDescription = template.skillDescription,
            skillType = template.skillType,
            skillDamageType = template.skillDamageType,
            skillHits = template.skillHits,
            skillDamageMultiplier = template.skillDamageMultiplier,
            skillCooldown = template.skillCooldown,
            skillMpCost = template.skillMpCost,
            skillHealPercent = template.skillHealPercent,
            skillHealType = template.skillHealType,
            skillBuffType = template.skillBuffType,
            skillBuffValue = template.skillBuffValue,
            skillBuffDuration = template.skillBuffDuration,
            skillBuffsJson = skillBuffsJson,
            minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
        )
    }
    
    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6, type: ManualType? = null): Manual {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        
        val rarity = generateRarity(minRarity, maxRarity)
        val templates = if (type != null) {
            getByType(type).filter { it.rarity == rarity }
        } else {
            getByRarity(rarity)
        }
        
        if (templates.isEmpty()) {
            throw NoSuchElementException("No manual templates found for rarity=$rarity, type=$type")
        }
        
        val template = templates.random()
        return createFromTemplate(template)
    }
    
    private fun generateRarity(minRarity: Int, maxRarity: Int): Int {
        val random = Random.nextDouble()
        return when {
            random < 0.5 -> minRarity.coerceAtMost(maxRarity)
            random < 0.75 -> (minRarity + 1).coerceAtMost(maxRarity)
            random < 0.9 -> (minRarity + 2).coerceAtMost(maxRarity)
            random < 0.97 -> (minRarity + 3).coerceAtMost(maxRarity)
            else -> maxRarity
        }
    }
}
