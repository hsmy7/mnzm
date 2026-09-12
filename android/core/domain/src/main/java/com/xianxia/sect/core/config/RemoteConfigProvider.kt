package com.xianxia.sect.core.config

/**
 * 远程配置提供者接口
 *
 * 定义从远程服务器拉取配置 JSON 的抽象接口。
 * 默认不启用；需要远程热更新能力时在 DI 层绑定具体实现（如 [HttpRemoteConfigProvider]）。
 *
 * 设计动机：国内无法访问 Firebase Remote Config，改用自建 HTTP JSON 方案。
 * 复用现有 HttpClientProvider（OkHttp + 签名 + 证书钉选）基础设施，零新增依赖。
 *
 * 三级配置优先级（在 ConfigLoader 中实现）：
 *   1. 远程 JSON  (RemoteConfigProvider.fetchRemoteConfig)
 *   2. assets JSON (assets/config/game_config.json)
 *   3. GameConfig const val 兜底 (GameConfigData 默认值)
 */
interface RemoteConfigProvider {

    /**
     * 远程配置是否可用。
     *
     * 实现应返回 false 直到首次成功拉取过远程配置，或在网络不可达/服务关闭时返回 false。
     * ConfigLoader 据此决定是否尝试远程拉取。
     */
    val isAvailable: Boolean

    /**
     * 拉取远程配置 JSON。
     *
     * @param url 远程配置 JSON 的完整 HTTPS URL
     * @return 成功时返回 JSON 字符串；失败（网络错误、HTTP 非 2xx、解析失败等）返回 null
     */
    suspend fun fetchRemoteConfig(url: String): String?
}
