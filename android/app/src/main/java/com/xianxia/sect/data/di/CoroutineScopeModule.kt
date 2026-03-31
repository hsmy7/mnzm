package com.xianxia.sect.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class StorageScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {
    
    @Provides
    @Singleton
    @StorageScope
    fun provideStorageCoroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
