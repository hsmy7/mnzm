package com.xianxia.sect.ui.game

import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.domain.dialog.DialogManager
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.service.AdService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.data.SessionManager
import javax.inject.Inject

/**
 * GameViewModel 构造依赖归组值对象（P-17 拆分，2026-08-05）。
 *
 * 背景：GameViewModel 20 个构造参数超 CLAUDE.md 3.4 阈值（≤7），
 * baseline 豁免掩盖技术债。按域归组为 4 个 @Inject 值对象后构造收敛为 5 参。
 * Delegate 类签名零改动（与 GameEngine 33→7 的访问器模式同思路）。
 */
class GameVmAudioServices @Inject constructor(
    val audioConfig: AudioConfig,
    val audioEngine: AudioEngine
)

class GameVmCoreServices @Inject constructor(
    val gameEngineCore: GameEngineCore,
    val systemManager: SystemManager,
    val thermalMonitor: ThermalMonitor
)

class GameVmUiServices @Inject constructor(
    val dialogManager: DialogManager,
    val adService: AdService
)

class GameVmDelegateServices @Inject constructor(
    val mailService: MailService,
    val buildingConfigService: BuildingConfigService,
    val buildingFacade: BuildingFacade,
    val discipleFacade: DiscipleFacade,
    val ioDispatcher: IoDispatcher,
    val sessionManager: SessionManager
)
