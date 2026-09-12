package com.xianxia.sect.core.platform

import java.io.InputStream

/**
 * 资产文件源 — 静态资产读取与平台解耦（对标 SurfaceProvider 平台端口先例）。
 *
 * 引擎层（注册表/配置服务）只面向本接口读取打包资产，
 * 不直接依赖 Android `AssetManager`。平台实现负责资产定位：
 * - Android：app 层 `AndroidAssetSource`（`Context.assets`）
 * - iOS：主 bundle 资源查找
 * - 纯 JVM 测试：classpath / 临时目录 Fake
 *
 * 错误语义：资产不存在或不可读返回 `null`（不抛异常），由调用方按缺省回退。
 */
interface AssetSource {

    /**
     * 打开资产文件只读字节流。
     *
     * @param assetPath 资产相对路径（如 `data/manuals.pb`、`config/buildings.json`）
     * @return 字节流（调用方负责 close）；资产不存在/不可读返回 `null`
     */
    fun open(assetPath: String): InputStream?
}
