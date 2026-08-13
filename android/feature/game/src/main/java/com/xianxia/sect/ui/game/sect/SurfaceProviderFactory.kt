package com.xianxia.sect.ui.game.sect

import android.view.SurfaceHolder
import com.xianxia.sect.core.platform.SurfaceProvider

/**
 * 渲染表面提供者工厂 — 创建平台 surface 提供者的可替换接线点。
 *
 * Hilt 在 `PlatformModule` 绑定本接口 → [AndroidSurfaceProviderFactory]；
 * iOS 化时提供 Metal 等价实现（持有 CAMetalLayer 包装），渲染宿主零改动。
 *
 * 工厂签名含 [SurfaceHolder]（Android 平台句柄）——本接口属于平台层
 * （:feature:game），`SurfaceProvider` 契约本体在 :core:engine（零 Android 依赖）。
 */
fun interface SurfaceProviderFactory {

    /**
     * 为渲染宿主创建平台 surface 提供者。
     *
     * 实现负责注册平台回调（Android = holder.addCallback），返回实例生命周期
     * 与渲染宿主一致（宿主销毁即弃，重建时重新创建）。
     *
     * @param holder Android surface 宿主句柄（宿主为 SurfaceView 子类，天然持有）
     * @return 绑定该 holder 的 [SurfaceProvider]
     */
    fun create(holder: SurfaceHolder): SurfaceProvider
}
