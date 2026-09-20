package com.xianxia.sect.core.engine.config

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.platform.AssetSource
import com.xianxia.sect.core.util.DomainLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * GameDataNativeBridge — 静态数据表（Kotlin assets）→ C++ `gamecore::data` DB 注入桥。
 *
 * ## 背景（R6.2 / B16 数值外置）
 *
 * 历史上 C++ `gamecore/data` 下各 DB 头把 herb/equipment/recipe/trait 等条目数值
 * 硬编码为 `static const std::vector<...>` 编译期常量表 ⇒ **改一个数值要重编译
 * C++ 逻辑**。本桥把同一批数值改由数据文件承载（`assets/data/game-data.json`，由
 * `scripts/gen-game-data.mjs` 从 `scripts/data` 下的 `*_sample.json` 生成——即
 * Kotlin Registry 的单一源快照），在**引擎初始化期一次**注入 C++。
 * （注：KDoc 内不得出现「斜杠+星号」字样——Kotlin 块注释嵌套，glob 字面量会
 * 打开嵌套注释吞掉后文。）
 *
 * ## 单源与等价性
 *
 * 数据文件与 C++ 头文件内联兜底**同源**（同一生成器的两侧产物）⇒
 * 注入与否**数值逐位相同**（由 C++ 侧 `DataStoreGuardTest` 逐行逐字段锁定）。
 * Kotlin Registry 与 `scripts/data` 下中性源 JSON 的一致性由既有
 * `StaticDataSingleSourceGuardTest` / `*RegistryGuardTest` 兜底。
 *
 * ## 注入时机与幂等
 *
 * 与 [GameConfigNativeBridge] 同型：`register()`（Dagger 单例构造期）+ `ensureInjected()`
 * （`nativeInit` 成功后补注）双点，`injected` 标志保证只注一次。
 * C++ 侧 `data_store` 另有独立的「已 seal 即拒」二次防线（重复注入被拒且表地址不变）。
 *
 * ## 失败语义
 *
 * 注入失败（资产缺失 / native 未加载 / C++ 解析失败）**不阻断引擎启动**：
 * C++ 侧保留头文件内联默认兜底（**不是空表**），并记 WARN 日志。
 */
object GameDataNativeBridge {

    private const val TAG = "GameDataNativeBridge"

    /** 数据文件路径（assets 相对路径） */
    const val ASSET_PATH = "data/game-data.json"

    /** 已注入标志（幂等——双点调用只注一次） */
    @Volatile
    private var injected = false

    /** AssetSource 引用（由 register 注册；ensureInjected 补注用） */
    @Volatile
    private var assetSourceRef: AssetSource? = null

    /** 注册 AssetSource 并尝试注入 */
    fun register(assetSource: AssetSource) {
        assetSourceRef = assetSource
        injectFrom(assetSource)
    }

    /**
     * 幂等注入：native 未加载或已注入时跳过。
     *
     * 注：`@Suppress` 承接既有的"降级契约"注解口径——异常不可枚举且非静默吞噬
     * （记 WARN 日志 + C++ 兜底），与 [GameConfigNativeBridge] 完全同型。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun injectFrom(assetSource: AssetSource) {
        if (injected || !GameCoreBridge.isLoaded) return
        try {
            val json = assetSource.open(ASSET_PATH)?.let { input ->
                BufferedReader(InputStreamReader(input)).use { it.readText() }
            }
            if (json.isNullOrEmpty()) {
                DomainLog.w(TAG, "数据文件缺失（$ASSET_PATH），C++ 使用内联默认兜底")
                return
            }
            val accepted = GameCoreBridge.nativeSetGameData(json)
            // C++ 返回 false = 已 seal（重复注入）或解析失败并已落兜底——
            // 两种情况都不重试（重试会破坏"仅初始化期一次"纪律）
            injected = true
            DomainLog.i(
                TAG,
                "静态数据表注入 C++（$ASSET_PATH，${json.length} 字符，accepted=$accepted）"
            )
        } catch (e: Exception) {
            // 注入失败不阻断引擎启动——C++ 侧内联默认兜底（与数据文件逐位相同）
            DomainLog.w(TAG, "静态数据表注入 C++ 失败，C++ 使用内联默认兜底", e)
        }
    }

    /** ensureAuthoritativeNative 补注入口（native 初始化完成后调用；幂等） */
    fun ensureInjected() {
        val assetSource = assetSourceRef ?: return
        injectFrom(assetSource)
    }
}
