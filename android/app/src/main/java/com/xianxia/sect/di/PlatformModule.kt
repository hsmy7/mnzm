package com.xianxia.sect.di

import com.xianxia.sect.ui.game.sect.AndroidSurfaceProviderFactory
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 平台抽象 DI 绑定（2026-08-13 SurfaceProvider 平台抽象）。
 *
 * 渲染表面提供者工厂绑定为 [AndroidSurfaceProviderFactory]（Android 平台实现）；
 * iOS 化时替换为 Metal 等价实现（持有 CAMetalLayer 包装），GameViewModel/
 * NativeSurfaceView 零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    /** 渲染表面提供者工厂单例（无状态，构造即接线点复用） */
    @Provides
    @Singleton
    fun provideSurfaceProviderFactory(): SurfaceProviderFactory = AndroidSurfaceProviderFactory()
}
