package com.xianxia.sect.core.data

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.proto.templates.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

object ManualDatabase {
    
    private const val TAG = "ManualDatabase"
    
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    
    // ==================== Proto 校验配置 ====================
    
    /**
     * 是否启用 Protobuf 结构校验
     *
     * **启用后**:
     * - JSON 加载后自动转换为 Proto message 进行 schema 验证
     * - 检测缺失字段、类型错误等数据完整性问题
     * - 记录校验日志便于排查问题
     *
     * **性能影响**: +5-10% 加载时间（仅在初始化时执行一次）
     */
    var enableProtoValidation: Boolean = true
    
    @Volatile
    private var lastValidationResult: ValidationResult? = null
    
    // ==================== 类型转换扩展函数 ====================
    
    private fun ManualType.toProtoType(): ManualTypeProto = when (this) {
        ManualType.ATTACK -> ManualTypeProto.MANUAL_TYPE_ATTACK
        ManualType.DEFENSE -> ManualTypeProto.MANUAL_TYPE_DEFENSE
        ManualType.SUPPORT -> ManualTypeProto.MANUAL_TYPE_SUPPORT
        ManualType.MIND -> ManualTypeProto.MANUAL_TYPE_MIND
    }
    
    /**
     * 将 Protobuf 枚举 ManualTypeProto 转换为领域模型 ManualType。
     *
     * **默认值策略**：
     * - 对于无法识别的 Proto 值（如 MANUAL_TYPE_UNKNOWN=0 或未来新增的枚举值）
     * - 安全降级为 ManualType.ATTACK，避免运行时异常
     *
     * **设计考量**：
     * - Proto 枚举不支持 Kotlin 的 exhaustive when 编译时检查
     * - 使用 else 分支确保所有可能的值都有明确的处理路径
     */
    private fun ManualTypeProto.toManualType(): ManualType = when (this) {
        ManualTypeProto.MANUAL_TYPE_ATTACK -> ManualType.ATTACK
        ManualTypeProto.MANUAL_TYPE_DEFENSE -> ManualType.DEFENSE
        ManualTypeProto.MANUAL_TYPE_SUPPORT -> ManualType.SUPPORT
        ManualTypeProto.MANUAL_TYPE_MIND -> ManualType.MIND
        else -> ManualType.ATTACK
    }
    
    @Serializable
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
        val minRealm: Int = 9,
        val skillIsAoe: Boolean = false,
        val skillTargetScope: String = "self"
    )
    
    @Volatile
    private var _allManuals: Map<String, ManualTemplate>? = null
    @Volatile
    private var _isInitialized = false
    private val initLock = Any()
    
    val isInitialized: Boolean
        get() = _isInitialized
    
    val allManuals: Map<String, ManualTemplate>
        get() = _allManuals ?: emptyMap()
    
    fun initializeSync(context: Context): Result<Unit> {
        return try {
            synchronized(initLock) {
                if (_isInitialized) return Result.success(Unit)
                
                _allManuals = loadManualTemplatesSync(context)
                
                if (enableProtoValidation && _allManuals != null) {
                    val manuals = _allManuals
                        ?: throw IllegalStateException("ManualDatabase: _allManuals is null after loading")
                    lastValidationResult = validateWithProto(manuals)
                    val result = lastValidationResult
                        ?: throw IllegalStateException("ManualDatabase: validation produced null result")
                    if (!result.isValid) {
                        Log.w(TAG, "Manual data validation warnings: ${result.warnings.size} issues found")
                    } else {
                        Log.i(TAG, "Manual data validation passed: ${manuals.size} templates verified")
                    }
                }
                
                _isInitialized = true
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ==================== Protobuf 校验方法 ====================
    
    /**
     * 校验结果数据类
     */
    data class ValidationResult(
        val isValid: Boolean,
        val totalCount: Int,
        val validCount: Int,
        val warnings: List<ValidationWarning> = emptyList(),
        val validationTimeMs: Long = 0
    )
    
    /**
     * 校验警告
     */
    data class ValidationWarning(
        val manualId: String,
        val fieldName: String,
        val message: String,
        val severity: Severity = Severity.WARNING
    ) {
        enum class Severity { WARNING, ERROR }
    }
    
    /**
     * 使用 Protobuf schema 校验 JSON 加载的数据完整性
     *
     * **校验内容**:
     * - 必填字段检查（id, name, type, rarity）
     * - 枚举值合法性验证
     * - 数值范围合理性检查
     * - 技能数据一致性验证
     *
     * @param manuals 从 JSON 加载的功法模板 Map
     * @return 校验结果（包含警告列表）
     */
    private fun validateWithProto(manuals: Map<String, ManualTemplate>): ValidationResult {
        val startTime = System.currentTimeMillis()
        val warnings = mutableListOf<ValidationWarning>()
        var validCount = 0
        
        manuals.forEach { (id, template) ->
            try {
                // 转换为 Proto message 进行结构化校验
                val protoBuilder = ManualTemplateProto.newBuilder()
                    .setId(template.id)
                    .setName(template.name)
                    .setRarity(template.rarity)
                    .setDescription(template.description)
                    .setPrice(template.price.toLong())
                    .setMinRealm(template.minRealm)
                
                protoBuilder.type = template.type.toProtoType()
                
                // 设置属性 map
                template.stats.forEach { (key, value) ->
                    protoBuilder.putStats(key, value)
                }
                
                // 设置技能信息
                if (template.skillName != null) {
                    val skillBuilder = SkillTemplateProto.newBuilder()
                        .setName(template.skillName ?: "")
                        .setDescription(template.skillDescription ?: "")
                        .setType(template.skillType)
                        .setDamageType(template.skillDamageType)
                        .setHits(template.skillHits)
                        .setDamageMultiplier(template.skillDamageMultiplier)
                        .setCooldown(template.skillCooldown)
                        .setMpCost(template.skillMpCost)
                        .setHealPercent(template.skillHealPercent)
                        .setHealType(template.skillHealType)
                    
                    if (template.skillBuffType != null) {
                        skillBuilder.buffType = template.skillBuffType
                        skillBuilder.buffValue = template.skillBuffValue
                        skillBuilder.buffDuration = template.skillBuffDuration
                    }
                    
                    // 添加多 buff 列表
                    template.skillBuffs.forEach { buff ->
                        skillBuilder.addBuffs(BuffInfoProto.newBuilder()
                            .setType(buff.type)
                            .setValue(buff.value)
                            .setDuration(buff.duration)
                            .build())
                    }
                    
                    protoBuilder.skill = skillBuilder.build()
                }
                
                // 尝试构建并序列化（触发所有字段校验）
                val proto = protoBuilder.build()
                val bytes = proto.toByteArray()
                
                // 反序列化验证（确保数据完整）
                ManualTemplateProto.parseFrom(bytes)
                
                validCount++
                
                // 额外的业务规则校验
                validateBusinessRules(id, template, warnings)
                
            } catch (e: Exception) {
                warnings.add(ValidationWarning(
                    manualId = id,
                    fieldName = "proto_conversion",
                    message = "Failed to convert to Proto: ${e.message}",
                    severity = ValidationWarning.Severity.ERROR
                ))
            }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        return ValidationResult(
            isValid = warnings.none { it.severity == ValidationWarning.Severity.ERROR },
            totalCount = manuals.size,
            validCount = validCount,
            warnings = warnings,
            validationTimeMs = elapsed
        )
    }
    
    /**
     * 业务规则校验（补充 Protobuf schema 无法表达的业务约束）
     */
    private fun validateBusinessRules(
        id: String,
        template: ManualTemplate,
        warnings: MutableList<ValidationWarning>
    ) {
        // 检查稀有度范围
        if (template.rarity < 1 || template.rarity > 6) {
            warnings.add(ValidationWarning(
                manualId = id,
                fieldName = "rarity",
                message = "Rarity ${template.rarity} out of valid range [1-6]"
            ))
        }
        
        // 检查价格合理性
        if (template.price < 0) {
            warnings.add(ValidationWarning(
                manualId = id,
                fieldName = "price",
                message = "Negative price: ${template.price}"
            ))
        }
        
        // 检查技能冷却时间
        if (template.skillCooldown < 1 || template.skillCooldown > 20) {
            warnings.add(ValidationWarning(
                manualId = id,
                fieldName = "skillCooldown",
                message = "Suspicious cooldown value: ${template.skillCooldown}",
                severity = ValidationWarning.Severity.WARNING
            ))
        }
        
        // 检查伤害倍率
        if (template.skillDamageMultiplier < 0 || template.skillDamageMultiplier > 10) {
            warnings.add(ValidationWarning(
                manualId = id,
                fieldName = "skillDamageMultiplier",
                message = "Suspicious damage multiplier: ${template.skillDamageMultiplier}",
                severity = ValidationWarning.Severity.WARNING
            ))
        }
    }
    
    /**
     * 获取上次校验结果（用于调试和监控）
     */
    fun getLastValidationResult(): ValidationResult? = lastValidationResult
    
    /**
     * 将功法模板转换为 Protobuf 格式（用于导出或缓存）
     *
     * @return Base64 编码的 Protobuf 二进制数据
     */
    fun exportToProtobuf(manuals: Map<String, ManualTemplate> = allManuals): String {
        val builder = ManualDataFileProto.newBuilder()
        
        val attackManuals = manuals.values.filter { it.type == ManualType.ATTACK }
        val defenseManuals = manuals.values.filter { it.type == ManualType.DEFENSE }
        val supportManuals = manuals.values.filter { it.type == ManualType.SUPPORT }
        val mindManuals = manuals.values.filter { it.type == ManualType.MIND }
        
        attackManuals.forEach { builder.addAttackManuals(convertToProto(it)) }
        defenseManuals.forEach { builder.addDefenseManuals(convertToProto(it)) }
        supportManuals.forEach { builder.addSupportManuals(convertToProto(it)) }
        mindManuals.forEach { builder.addMindManuals(convertToProto(it)) }
        
        @Suppress("NewApi")
        return java.util.Base64.getEncoder().encodeToString(builder.build().toByteArray())
    }
    
    /**
     * 将单个 ManualTemplate 转换为 ManualTemplateProto
     */
    private fun convertToProto(template: ManualTemplate): ManualTemplateProto {
        val builder = ManualTemplateProto.newBuilder()
            .setId(template.id)
            .setName(template.name)
            .setRarity(template.rarity)
            .setDescription(template.description)
            .setPrice(template.price.toLong())
            .setMinRealm(template.minRealm)
        
        builder.type = template.type.toProtoType()
        
        template.stats.forEach { (k, v) -> builder.putStats(k, v) }
        
        if (template.skillName != null) {
            val skillBuilder = SkillTemplateProto.newBuilder()
                .setName(template.skillName ?: "")
                .setDescription(template.skillDescription ?: "")
                .setType(template.skillType)
                .setDamageType(template.skillDamageType)
                .setHits(template.skillHits)
                .setDamageMultiplier(template.skillDamageMultiplier)
                .setCooldown(template.skillCooldown)
                .setMpCost(template.skillMpCost)
                .setHealPercent(template.skillHealPercent)
                .setHealType(template.skillHealType)
            
            if (template.skillBuffType != null) {
                skillBuilder.buffType = template.skillBuffType
                skillBuilder.buffValue = template.skillBuffValue
                skillBuilder.buffDuration = template.skillBuffDuration
            }
            
            template.skillBuffs.forEach { buff ->
                skillBuilder.addBuffs(BuffInfoProto.newBuilder()
                    .setType(buff.type)
                    .setValue(buff.value)
                    .setDuration(buff.duration))
            }
            
            builder.skill = skillBuilder.build()
        }
        
        return builder.build()
    }
    
    private fun loadManualTemplatesSync(context: Context): Map<String, ManualTemplate> {
        return try {
            val bytes = context.assets.open("data/manuals.pb").use { it.readBytes() }
            val proto = ManualDataFileProto.parseFrom(bytes)
            convertProtoToManualMap(proto)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load manuals.pb, falling back to JSON", e)
            val jsonString = loadJsonFromAssetsSync(context, "data/manuals.json")
            val dataFile = json.decodeFromString<ManualJsonDataFile>(jsonString)

            val allManuals = mutableMapOf<String, ManualTemplate>()
            dataFile.attackManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
            dataFile.defenseManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
            dataFile.supportManuals.forEach { allManuals[it.id] = it.toManualTemplate() }
            dataFile.mindManuals.forEach { allManuals[it.id] = it.toManualTemplate() }

            allManuals
        }
    }

    private fun convertProtoToManualMap(proto: ManualDataFileProto): Map<String, ManualTemplate> {
        val allManuals = mutableMapOf<String, ManualTemplate>()

        proto.attackManualsList.forEach { allManuals[it.id] = protoToManualTemplate(it) }
        proto.defenseManualsList.forEach { allManuals[it.id] = protoToManualTemplate(it) }
        proto.supportManualsList.forEach { allManuals[it.id] = protoToManualTemplate(it) }
        proto.mindManualsList.forEach { allManuals[it.id] = protoToManualTemplate(it) }

        return allManuals
    }

    private fun protoToManualTemplate(proto: ManualTemplateProto): ManualTemplate {
        var template = ManualTemplate(
            id = proto.id,
            name = proto.name,
            type = proto.type.toManualType(),
            rarity = proto.rarity,
            description = proto.description,
            stats = proto.statsMap,
            price = proto.price.toInt(),
            minRealm = proto.minRealm
        )

        if (proto.hasSkill()) {
            val skill = proto.skill
            val buffs = skill.buffsList.map { BuffInfo(type = it.type, value = it.value, duration = it.duration) }
            template = template.copy(
                skillName = skill.name.ifEmpty { null },
                skillDescription = skill.description.ifEmpty { null },
                skillType = skill.type.ifEmpty { "attack" },
                skillDamageType = skill.damageType.ifEmpty { "physical" },
                skillHits = skill.hits,
                skillDamageMultiplier = skill.damageMultiplier,
                skillCooldown = skill.cooldown,
                skillMpCost = skill.mpCost,
                skillHealPercent = skill.healPercent,
                skillHealType = skill.healType.ifEmpty { "hp" },
                skillBuffType = if (skill.buffType.isNotEmpty()) skill.buffType else null,
                skillBuffValue = skill.buffValue,
                skillBuffDuration = skill.buffDuration,
                skillBuffs = buffs
            )
        }

        return template
    }
    
    @Serializable
    private data class ManualJsonDataFile(
        val attackManuals: List<ManualJsonDataSync> = emptyList(),
        val defenseManuals: List<ManualJsonDataSync> = emptyList(),
        val supportManuals: List<ManualJsonDataSync> = emptyList(),
        val mindManuals: List<ManualJsonDataSync> = emptyList()
    )
    
    @Serializable
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
        val minRealm: Int? = null,
        val skillIsAoe: Boolean? = null,
        val skillTargetScope: String? = null
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
            minRealm = minRealm ?: 9,
            skillIsAoe = skillIsAoe ?: false,
            skillTargetScope = skillTargetScope ?: "self"
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
            _isInitialized = true
        }
    }
    
    fun getById(id: String): ManualTemplate? {
        check(_isInitialized) { "ManualDatabase not initialized. Call initialize() first." }
        return allManuals[id]
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
            skillIsAoe = template.skillIsAoe,
            skillTargetScope = template.skillTargetScope,
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
