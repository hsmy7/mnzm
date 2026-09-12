package com.xianxia.sect.core.engine.di

import com.xianxia.sect.core.engine.service.OverflowMailSender
import com.xianxia.sect.core.overflow.OverflowMailHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 溢出邮件处理器绑定：接口放 domain（解耦循环依赖），实现由 engine 提供。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OverflowMailModule {

    @Binds
    @Singleton
    abstract fun bindOverflowMailHandler(impl: OverflowMailSender): OverflowMailHandler
}
