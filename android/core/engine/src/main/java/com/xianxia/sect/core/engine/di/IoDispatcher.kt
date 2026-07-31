package com.xianxia.sect.core.engine.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * IO Dispatcher 注入包装器。
 *
 * Hilt KSP 无法直接解析 kotlinx.coroutines.CoroutineDispatcher 类型，
 * 因此通过此包装器间接注入 IO Dispatcher。
 * 2026-08-01：构造参数带默认值——生产 DI 不变，测试可注入 TestDispatcher
 * （修复测试注入真实 Dispatchers.IO 导致 runTest 等待不到的问题）。
 */
class IoDispatcher @Inject constructor(
    val dispatcher: CoroutineDispatcher = Dispatchers.IO
)

@Module
@InstallIn(SingletonComponent::class)
object EngineDispatcherModule {

    @Provides
    @Singleton
    fun provideIoDispatcher(): IoDispatcher = IoDispatcher()
}
