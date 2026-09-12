package com.xianxia.sect.ui.game.sect

import android.view.SurfaceHolder
import com.xianxia.sect.core.platform.SurfaceProvider

/**
 * Android 平台 surface 提供者工厂（Hilt 绑定目标；iOS 化替换点）。
 *
 * 无状态，构造即复用 [SurfaceProviderFactory] 接线点。
 */
class AndroidSurfaceProviderFactory : SurfaceProviderFactory {

    /** 创建绑定给定 holder 的 [AndroidSurfaceProvider]（构造即注册平台回调） */
    override fun create(holder: SurfaceHolder): SurfaceProvider = AndroidSurfaceProvider(holder)
}
