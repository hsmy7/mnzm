package com.xianxia.sect.di

import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.ProductionSlotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    
    @Provides
    fun provideProductionSlotDao(database: GameDatabase): ProductionSlotDao = 
        database.productionSlotDao()
    
    @Provides
    @Singleton
    fun provideProductionSlotRepository(
        dao: ProductionSlotDao,
        configService: com.xianxia.sect.core.config.BuildingConfigService
    ): ProductionSlotRepository = ProductionSlotRepository(dao, configService)
    
    @Provides
    @Singleton
    fun provideProductionTransactionManager(
        repository: ProductionSlotRepository
    ): ProductionTransactionManager = ProductionTransactionManager(repository)
}
