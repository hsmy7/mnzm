package com.xianxia.sect.core.di

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StatCalculatorModule {
    @Provides
    @Singleton
    fun provideDiscipleStatCalculator(): DiscipleStatCalculator = DiscipleStatCalculator
}
