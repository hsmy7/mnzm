package com.xianxia.sect.data.serialization.unified

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UnifiedSerializationModule {
    
    @Provides
    @Singleton
    fun provideUnifiedCompressionLayer(): UnifiedCompressionLayer {
        return UnifiedCompressionLayer()
    }
    
    @Provides
    @Singleton
    fun provideIntegrityLayer(): IntegrityLayer {
        return IntegrityLayer()
    }
    
    @Provides
    @Singleton
    fun provideUnifiedSerializationEngine(
        compressionLayer: UnifiedCompressionLayer,
        integrityLayer: IntegrityLayer
    ): UnifiedSerializationEngine {
        return UnifiedSerializationEngine(compressionLayer, integrityLayer)
    }
    
    @Provides
    @Singleton
    fun provideUnifiedSaveManager(
        @ApplicationContext context: Context,
        serializationEngine: UnifiedSerializationEngine,
        compressionLayer: UnifiedCompressionLayer,
        integrityLayer: IntegrityLayer
    ): UnifiedSaveManager {
        return UnifiedSaveManager(context, serializationEngine, compressionLayer, integrityLayer)
    }
    
    @Provides
    @Singleton
    fun provideSaveDataConverter(): SaveDataConverter {
        return SaveDataConverter()
    }
    
    @Provides
    @Singleton
    fun provideSaveDataMigrator(): SaveDataMigrator {
        return SaveDataMigrator()
    }
}
