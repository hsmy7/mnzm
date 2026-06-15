package com.xianxia.sect.core.di

import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.system.InventoryFactories
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 为历史 object 单体提供 DI 绑定，后续逐步迁移为 @Inject class */
@Module
@InstallIn(SingletonComponent::class)
object LegacyObjectModule {
    @Provides @Singleton
    fun provideDiscipleEquipmentManager() = DiscipleEquipmentManager
    @Provides @Singleton
    fun provideDiscipleManualManager() = DiscipleManualManager
    @Provides @Singleton
    fun provideDisciplePillManager() = DisciplePillManager
    @Provides @Singleton
    fun provideDiscipleSlotCleanup() = DiscipleSlotCleanup
    @Provides @Singleton
    fun provideInventoryFactories() = InventoryFactories
    @Provides @Singleton
    fun provideMerchantItemConverter() = MerchantItemConverter
}
