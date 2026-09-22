package com.xianxia.sect.ui.game.saveload

import android.content.Context
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.BootSequenceController
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendModeProvider
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.taptap.TapCloudSaveManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化与公共服务 Facade。
 *
 * 封装 SaveLoadViewModel 所需的全部持久化/基础设施依赖，
 * 将构造参数从 15 个降至 ViewModel 可接受的 7 个以内。
 *
 * 注：持久化层不直接触碰 RNG——重启播种在引擎线程
 *（restartGameInternal）内完成。
 *
 * LongParameterList：聚合门面即职责本身（把 VM 可接受的构造参数上限保持在 7，
 * 依赖收拢于本类一个注入点）——SR-2 增补云上传收口三依赖（queue/ledger/mode）、
 * SR-6 增补落盘段（cloudSaveCacheWriter），与既有先例同口径，非参数失控。
 */
@Singleton
@Suppress("LongParameterList")
class PersistenceFacade @Inject constructor(
    val storageFacade: StorageFacade,
    val bootSequenceController: BootSequenceController,
    val spiritStoneWallet: SpiritStoneWallet,
    val buildingConfigService: BuildingConfigService,
    val tapCloudSaveManager: TapCloudSaveManager,
    val sessionManager: SessionManager,
    // SR-2 云上传收口（LEGACY 默认模式下零活动）
    val uploadQueue: UploadQueue,
    val uploadLedger: UploadLedger,
    val saveBackendModeProvider: SaveBackendModeProvider,
    // SR-3 云主路径：云槽位下载/列表数据源（接口隔离 IN3，业务面零 SDK 类型）
    val saveBackend: SaveBackend,
    // SR-6 C4：云档→本地缓存的落盘段（VM 的 boot 前段与迁移侧共用，见该类 KDoc）
    val cloudSaveCacheWriter: CloudSaveCacheWriter,
    @ApplicationContext val context: Context
)
