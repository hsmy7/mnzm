package com.xianxia.sect.ui.game.saveload

import android.content.Context
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.BootSequenceController
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.data.SessionManager
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
 */
@Singleton
class PersistenceFacade @Inject constructor(
    val storageFacade: StorageFacade,
    val bootSequenceController: BootSequenceController,
    val spiritStoneWallet: SpiritStoneWallet,
    val buildingConfigService: BuildingConfigService,
    val gameRngManager: GameRngManager,
    val tapCloudSaveManager: TapCloudSaveManager,
    val sessionManager: SessionManager,
    @ApplicationContext val context: Context
)
