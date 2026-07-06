package com.xianxia.sect.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IO Dispatcher 注入包装器。
 *
 * Hilt KSP 无法直接解析 kotlinx.coroutines.CoroutineDispatcher 类型，
 * 因此通过此包装器间接注入 IO Dispatcher。
 */
class IoDispatcher @Inject constructor() {
    val dispatcher: CoroutineDispatcher = Dispatchers.IO
}

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    fun provideIoDispatcher(): IoDispatcher = IoDispatcher()
}
