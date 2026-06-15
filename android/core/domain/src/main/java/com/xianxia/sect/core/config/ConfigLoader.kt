package com.xianxia.sect.core.config

import com.xianxia.sect.core.util.DomainLog
import kotlinx.serialization.json.Json

/**
 * 配置加载器 — 从 JSON 加载游戏数值配置。
 *
 * 三级配置优先级：
 *   1. 远程 JSON  (若 [remoteConfigProvider] 非空且 [remoteConfigUrl] 已配置)
 *   2. assets JSON (assets/config/game_config.json) — 主要配置来源
 *   3. [GameConfigData] 默认值兜底 (来自 GameConfig const val 基线值)
 *
 * 设计说明：
 * - 故意不使用 Hilt 注解（core/domain 模块无 Hilt 运行时）。
 *   实例化由 app 层 CoreModule 的 @Provides 方法负责，或在测试中直接构造。
 * - 复用 BuildingConfigService 已验证的 assets 加载模式 (kotlinx.serialization + ignoreUnknownKeys)。
 * - JSON 字段缺失时自动用 GameConfigData 默认值兜底，保证向前兼容（新增字段不会导致旧 JSON 解析失败）。
 * - 不直接依赖 Android Context（domain 模块零 Android 框架依赖），
 *   通过 [assetReader] 函数注入 assets 读取能力。
 *
 * @param assetReader 读取 assets 文本的函数，输入为 assets 相对路径，返回文件内容或 null（不存在时）
 * @param remoteConfigProvider 可选的远程配置提供者；null 表示不启用远程拉取
 * @param remoteConfigUrl 远程配置 URL；仅当 remoteConfigProvider 非空时生效
 */
class ConfigLoader(
    private val assetReader: (String) -> String?,
    private val remoteConfigProvider: RemoteConfigProvider? = null,
    private val remoteConfigUrl: String? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true // JSON null → 使用 data class 默认值
    }

    private var cachedConfig: GameConfigData? = null

    /**
     * 同步加载配置：仅从 assets JSON 读取，失败则使用默认值。
     *
     * 不涉及远程拉取（远程需网络 IO，应在协程中调用 [loadAsync]）。
     * 结果会被缓存，重复调用直接返回缓存值。
     */
    fun load(): GameConfigData {
        cachedConfig?.let { return it }
        val config = loadFromAssets() ?: GameConfigData()
        cachedConfig = config
        return config
    }

    /**
     * 异步加载配置：按三级优先级尝试远程 → assets → 默认值。
     *
     * 远程拉取有 10s 超时（由 [RemoteConfigProvider] 实现控制），
     * 任何环节失败均自动降级，最终必定返回有效配置。
     */
    suspend fun loadAsync(): GameConfigData {
        cachedConfig?.let { return it }

        // 1. 尝试远程 JSON（仅当 provider 和 URL 均已配置）
        if (remoteConfigProvider != null && !remoteConfigUrl.isNullOrEmpty()) {
            val remoteJson = remoteConfigProvider.fetchRemoteConfig(remoteConfigUrl)
            if (remoteJson != null) {
                parse(remoteJson, SOURCE_REMOTE)?.let {
                    DomainLog.i(TAG, "Config loaded from remote: v${it.version}")
                    cachedConfig = it
                    return it
                }
            }
        }

        // 2. 尝试 assets JSON
        loadFromAssets()?.let {
            DomainLog.i(TAG, "Config loaded from assets: v${it.version}")
            cachedConfig = it
            return it
        }

        // 3. 默认值兜底
        DomainLog.w(TAG, "Config load failed, falling back to defaults")
        val fallback = GameConfigData()
        cachedConfig = fallback
        return fallback
    }

    /** 强制清除缓存，下次 load()/loadAsync() 重新加载。 */
    fun invalidateCache() {
        cachedConfig = null
    }

    private fun loadFromAssets(): GameConfigData? {
        val jsonString = assetReader(CONFIG_PATH) ?: return null
        return parse(jsonString, SOURCE_ASSETS)
    }

    private fun parse(jsonString: String, source: String): GameConfigData? {
        return try {
            json.decodeFromString<GameConfigData>(jsonString)
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to parse config JSON from $source: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "ConfigLoader"
        const val CONFIG_PATH = "config/game_config.json"
        const val SOURCE_ASSETS = "assets"
        const val SOURCE_REMOTE = "remote"
    }
}
