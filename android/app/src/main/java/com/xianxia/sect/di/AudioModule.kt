package com.xianxia.sect.di

import android.content.Context
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.audio.AudioPreloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 音频模块
 *
 * 提供单例音频组件：配置管理器、音频引擎、预加载助手。
 * 所有音频依赖由 Hilt 自动注入，无需手动管理生命周期。
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideAudioConfig(): AudioConfig = AudioConfig()

    @Provides
    @Singleton
    fun provideAudioEngine(
        @ApplicationContext context: Context,
        audioConfig: AudioConfig
    ): AudioEngine = AudioEngine(context, audioConfig)

    @Provides
    @Singleton
    fun provideAudioPreloader(
        audioEngine: AudioEngine
    ): AudioPreloader = AudioPreloader(audioEngine)
}
