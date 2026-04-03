package com.xianxia.sect.di

import com.xianxia.sect.data.monitor.StoragePerformanceMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PerformanceModule {
    
    @Provides
    @Singleton
    fun provideStoragePerformanceMonitor(
        scopeProvider: ApplicationScopeProvider
    ): StoragePerformanceMonitor {
        return StoragePerformanceMonitor(scopeProvider.ioScope)
    }
}
