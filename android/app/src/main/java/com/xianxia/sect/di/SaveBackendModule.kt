package com.xianxia.sect.di

import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.taptap.TapTapSaveBackend
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * 云存档后端绑定（SR-2，方案 D4/IN3）：
 * - [SaveBackend] ← TapTap 反射桥门面（feature/game）；业务面只依赖接口，
 *   未来换自家游戏服务器时在此换绑实现即可（D4 预埋切换点）；
 * - [UploadQueue] 单飞 worker 作用域 = SupervisorJob + Dispatchers.IO：
 *   惰性启动（首次 enqueue 才拉起），LEGACY 默认模式下零入队 ⇒ 零协程活动
 *   （SR-2 硬红线的机制面保障）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SaveBackendModule {

    @Binds
    @Singleton
    abstract fun bindSaveBackend(impl: TapTapSaveBackend): SaveBackend

    companion object {
        @Provides
        @Singleton
        fun provideUploadQueue(backend: SaveBackend, ledger: UploadLedger): UploadQueue =
            UploadQueue(backend, ledger, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
