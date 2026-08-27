package com.xianxia.sect.di

import android.os.SystemClock
import com.xianxia.sect.core.engine.system.TimeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 平台时间源 Hilt 绑定（计划 v2 阶段 5 / R-02：Android 实现随平台能力
 * 接口化移出 engine 模块——engine 模块只保留纯 JVM [TimeSource] 抽象）。
 *
 * 生产恒为 Android 单调时钟（SystemClock.elapsedRealtime()，不受 NTP/手动
 * 改时间影响）；测试直接构造 GameTimeClock(FakeTimeSource) 不经本模块。
 */
object AndroidTimeSource : TimeSource {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformTimeModule {
    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = AndroidTimeSource
}
