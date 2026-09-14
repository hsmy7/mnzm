package com.xianxia.sect.data.prefs

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * KeyValueStore 接口绑定。
 *
 * core 层消费 [KeyValueStore] 契约，app 层经 Hilt 注入 [GamePreferences]（MMKV）实现；
 * 单元测试注入内存 Fake 绕开 MMKV native 依赖。
 */
@Module
@InstallIn(SingletonComponent::class)
interface KeyValueStoreModule {
    @Binds
    @Singleton
    fun bindKeyValueStore(impl: GamePreferences): KeyValueStore
}
