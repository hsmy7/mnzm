package com.xianxia.sect.core.config

import com.xianxia.sect.core.util.DomainLog

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── BuildingConfigService 拆分域（行为零变更） ──

private val TAG = BuildingConfigService.TAG
private val CONFIG_PATH = BuildingConfigService.CONFIG_PATH
internal fun BuildingConfigService.ensureConfigLoaded(): BuildingsConfig {
    if (config == null) {
        loadConfig()
    }
    return config ?: createDefaultConfig().also { config = it }
}


@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun BuildingConfigService.loadConfig() {
    try {
        val loadedConfig = loadFromAssets()
        config = loadedConfig ?: createDefaultConfig()
        DomainLog.d(TAG, "Building config loaded with ${config?.buildings?.size ?: 0} buildings")
    } catch (e: Exception) {
        DomainLog.e(TAG, "Failed to load building config", e)
        config = createDefaultConfig()
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun BuildingConfigService.loadFromAssets(): BuildingsConfig? {
    val inputStream = assetSource.open(CONFIG_PATH)
    if (inputStream == null) {
        DomainLog.w(TAG, "Could not load config from assets: $CONFIG_PATH not found")
        return null
    }
    return try {
        inputStream.use { stream ->
            val jsonString = stream.bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString<BuildingsConfig>(jsonString)
            val errors = ConfigValidator.validate(parsed)
            if (errors.isEmpty()) {
                DomainLog.d(TAG, "Config validated successfully from assets")
            } else {
                DomainLog.w(TAG, "Config validation errors: $errors")
            }
            parsed
        }
    } catch (e: Exception) {
        DomainLog.w(TAG, "Could not load config from assets: ${e.message}")
        null
    }
}

internal fun BuildingConfigService.normalizeBuildingId(
    buildingId: String,
    cfg: BuildingsConfig = ensureConfigLoaded()
): String {
    val normalized = buildingId.lowercase(java.util.Locale.getDefault()).replace("_", "").replace("-", "")
    return cfg.buildingAliases[normalized] ?: buildingId.lowercase(java.util.Locale.getDefault())
}
