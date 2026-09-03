# C++ 迁移情况调查报告（独立技术审计）

| 项 | 内容 |
|---|---|
| 文档性质 | Kotlin → C++ 自研游戏引擎迁移的全面独立审计（调查报告） |
| 审计日期 | 2026-09-04 |
| 审计方法 | 不采信注释/文档/类名/"已完成"标记，仅以实际调用链 + 实际运行路径 + 实际依赖关系为据；全部结论经两个以上独立取证路径交叉验证，关键定性论断经主审复核源码确认 |
| 审计范围 | android/（Kotlin ~8 万行 985 生产文件；C++ ~6.6 万行含测试）、app/src/main/cpp 全部、构建体系、资产管线、docs 声称交叉核对 |
| 配套文档 | 总实施方案见 [cpp-migration-implementation-plan.md](cpp-migration-implementation-plan.md) |
| 状态标记约定 | 【伪完成】=存在但与宣称不符；【未接入】=实现存在但无实际调用；【未完成】=明确没做完；【架构债务】=能运行但方向/结构有问题；【性能风险】=可能造成性能问题；【跨平台风险】=Android 可用但 iOS 无法复用 |

---

## 1. 项目真实架构

### 1.1 真实代码分布

| 层 | 规模 | 内容 |
|---|---|---|
| C++ `gamecore` 静态库 | ~6.6 万行（含 60 个桌面测试文件） | 旬/月/年结算、战斗、修炼、经济等 47 个系统头文件 + 6 个实现 cpp；SoA 弟子存储（DiscipleStore）；EngineLoop 帧计划器；PCG RNG |
| C++ 渲染库 `native-renderer` | ~3,900 行 | VulkanBackend (2390)、GlesBackend (332)、NativeBridge (1013)、SpriteBatcher (84)、KtxLoader (120)、Rhi.h |
| C++ 模拟桥 `native-game-core` | 723 行 | GameCoreBridge.cpp：33 个 JNI 导出，全静态 `Java_` 导出，无 RegisterNatives |
| Kotlin | ~8 万行（985 生产文件 + 594 测试文件 7512 @Test） | GameEngine 门面（17 个 Ops 分文件）、GameStateStore、StateSyncService、全套 Jetpack Compose UI、渲染适配层（NativeSurfaceView 1703 行、SoftwareCanvasBackend 1497 行） |
| iOS | **0 个文件** | 全仓无 .swift/.mm/.xcodeproj/.metal/Podfile/ios 目录 |

### 1.2 真实调用架构（实测）

```
【模拟链路 · 双真相源混合，AUTHORITATIVE 为生产默认（NativeEngineFlag.kt）】
GameForegroundService / SaveLoadViewModel
 → GameEngineCore.startGameLoop → 专用单线程 "GameEngine-Thread" 协程 while(isActive)
 → 每帧: nativeLoopFrame (JNI, 17×jlong)  ← C++ EngineLoop::iterate = 时间真相源
     (帧累积/固定步长≤5/旬数/alpha/暂停/死区 全在 C++)
 → 每旬(2s@1x): 五步互插管线 (GameEngineCoreAuthoritativeOps.kt:46-78):
    ① nativeSettlePhase → C++ SettlementEngine (结算真相源)
       └ PhaseCoreBatchSystem → JobSystem 真并行（全项目唯一生产并行点）
    ② nativeExportDirty → DirtyTracker → Kotlin 镜像 GameStateStore（UI 唯一读源）
    ③ Kotlin 残留执行器（自动装备/丹药/突破）← Kotlin 每旬仍执行真实逻辑
    ④ 月/年边界 → nativeSettleMonth/Year → C++ 结算 → Kotlin 残留扇出（炼丹/锻造/邮件/任务/秘境/AI兽战）
    ⑤ applyDirtyToNative → nativeApplyReverseDirty（gameData 整段 + 弟子增量）
 → 持久化：Kotlin Room，仅手动存档（产品决策，见 docs/report-移除自动存档-接入云存档.md）
 → RNG：Kotlin 每次抽取 → nativeRngNextInt（C++ PCG 真相源）
 → 战斗/库存/AI宗门决策：C++ 优先，异常回退 Kotlin 全量保留实现

【渲染链路 · 真迁移】
VulkanPolicy 设备决策 → MainGameScreen(Compose) → AndroidView(NativeSurfaceView)
 → Kotlin "NativeRenderer" 线程（自旋+节拍+脏帧跳过，16/33ms 帧率门控）
 → 后端三级：Vulkan(旗舰默认) / GLES(低端/降级) / CPU Canvas(兜底) — Kotlin 适配 + C++ 实现
 → 每帧 JNI：beginFrame/setCamera/drawAllTiles/drawRect×N/submitFrame
 → C++ NativeBridge::drawAllTiles：视口钳制(~1700 可见格) → SpriteBatcher → 真实 vkCmdDraw
 → 纹理：Vulkan=ASTC KTX 4096² 一次性上传；GLES/软件=Kotlin 主线程运行时拼 RGBA
 → 其上 100% Jetpack Compose UI（C++ 无任何文本/UI 绘制能力）
```

### 1.3 一句话定性

**这不是假迁移**：模拟核心（时间/旬结算/RNG/战斗/库存）与宗门地图渲染是真迁移且 C++ 是真相源；**但** Kotlin 保留每旬残留执行器、全部旧实现作回退、以及整个 UI/持久化/地图数据模型层，且状态同步通道昂贵、ECS 为展品、iOS 为零。

---

## 2. 实际运行路径追踪

| 链路 | 实际执行者 | 结论 |
|---|---|---|
| 启动 | GameActivity → VulkanPolicy 决策（旗舰=Vulkan，API<31 非白名单=GLES，云游戏/安全模式=软渲）→ startGameLoop → nativeInit（创建 JobSystem worker）→ 循环启动 | ✅ 真实：App→Activity→Kotlin 协程线程→JNI→C++ 时间机 |
| 地图加载 | **Kotlin** SectMapController.buildSectMap 生成 128×128=16384 格装饰底图 IntArray，按种子缓存；渲染时 C++ 每帧视口钳制装配 | ⚠️ Tile 数据生成仍在 Kotlin；占用判定=Kotlin GridSystem `Set<Long>`（GridSystem.kt:17-18），非 per-tile 数组 |
| NPC/弟子 | **地图上不存在任何 NPC/弟子行走实体**：图集无人物精灵、Disciple 模型无坐标/朝向/动画字段、RenderFrame 无实体层 | 【未完成】双端均不存在该系统（管理模拟玩法所致） |
| 建筑放置 | UI 拖拽 → GridSystem.validatePlacement（Kotlin）→ 引擎事务三层校验 → Kotlin updateGameData → RenderCommandBus 单槽 + FloatArray 重建 → native 绘制；持久化 Room JSON 列，仅手动存档落库 | ⚠️ 数据真相源 Kotlin，C++ 只是镜像 |
| 摄像机 | setCamera JNI 绕过帧率门控直达渲染线程 → C++ 重算投影+可见区间+LOD；Kotlin 端缩放零重算 | ✅ 全项目治理最好的链路之一 |
| 资源 | APK 内 atlas_astc.ktx（4096² ASTC 4×4，22MB）仅 Vulkan 路径一次性上传；GLES/软渲=Kotlin 主线程 SectAtlasAssembler 运行时拼 RGBA；数据表双轨（Kotlin 解析 assets JSON + C++ 编译期静态表 + JNI 配置注入） | ⚠️ 回退路径弱，数据表双轨易漂移 |
| 存档 | 唯一入口 SettingsTab 手动按钮 → IO 线程 → StorageEngine 全量 Room 事务；**自动存档不存在**（见 §5 勘误） | 产品既定设计，但存在文案与字段残留（见 §6） |

---

## 3. 迁移完成度总表（按真实运行路径判定）

| 模块 | 实际完成度 | 是否真正迁移 | 主要问题 |
|---|---|---|---|
| Core（平台端口） | 85% | ✅ 真迁移 | 无 Android 依赖，注入式端口干净；"禁异常/RTTI"注释与编译旗标不符 |
| Game Loop | 80% | ✅ 真迁移（有意混合） | C++ 时间真相源 + Kotlin 线程本体；遗留双时钟（PhaseClock 与 SettlementEngine 墙钟累积器并存） |
| ECS | 25% | 【伪完成】 | 数据结构教科书级，但生产运行时 0 实体；系统签名带 `World&` 却弃用；仅测试消费（**已决策：全面启用，见方案 WS-3**） |
| World（状态所有权） | 60% | ⚠️ 部分 | 双真相源：C++ 模拟态 + Kotlin UI 态/持久化；每旬五步互插 + 双向 JSON 镜像（**已决策：收敛为 C++ 唯一真相源，见 WS-1/WS-2**） |
| TileMap | 45% | ⚠️ 部分 | 渲染已下沉 C++；Tile 生成/占用/重建仍在 Kotlin；无数据模型 Chunk/Streaming |
| NPC | N/A | 不适用 | 地图 NPC 系统双端均不存在（**已决策：将新增 NPC 移动，见 WS-4**） |
| Building | 65% | ⚠️ 部分 | 渲染+C++ 镜像真；占位判定/持久化/工期月历全在 Kotlin |
| Animation | 30% | ⚠️ 部分 | 仅云层（Kotlin）+作物三阶段（C++）；无角色/建筑帧动画系统 |
| Asset | 60% | ⚠️ 部分 | ASTC→Vulkan 通道真实；GLES/软渲回退靠主线程运行时拼图；数据表双轨 |
| Renderer | 80% | ✅ 真迁移 | 宗门地图绘制全链 C++；draw-list 装配在 C++（NativeBridge.cpp:519-954） |
| Vulkan | 85% | ✅ 真实可用 | 完整 instance/device/swapchain/pipeline/vkCmdDraw/present；渲染真实游戏内容 |
| Metal | 0% | 【未完成】 | 零实现零工程（**已决策：iOS 暂缓，见 WS-6**） |
| UI | 0%（有意保留） | ✅ 合理 | 100% Compose；不建议迁移 |
| Audio | 0%（Kotlin） | ⚠️ 可接受 | SoundPool+MediaPlayer；`release()` 全库无调用 |
| Input | 0%（Kotlin） | ✅ 合理 | Kotlin 手势 → setCamera/渲染状态下发；低频，JNI 化无收益 |
| Job System | 70% | ✅ 真实接入 | 真线程池、真生产消费（每旬核心批次）；无 work-stealing；消费点唯一 |
| Android | 85% | ✅ | Surface 生命周期/纪元/join-再释放体系防御完备；存档文案残留、native 符号不入包 |
| iOS | 5% | 【未完成】 | 仅 gamecore 可移植性合格；渲染层 Android 绑定；零工程 |

---

## 4. 分项审计要点

### 4.1 Game Loop
- 时间真相源在 C++：`EngineLoop::iterate` 纯函数式单帧状态机（delta 钳制、暂停死区、固定步长≤5、alpha 插值），无 C++ 循环线程——线程本体与帧间等待（delay/忙等）归 Kotlin 协程，是**有意设计**（engine_loop.h:33-35 自述）。
- Kotlin `tick` 结算路径已删除（批 9-2）：native 未就绪时本旬 refund 跳过，无纯 Kotlin 兜底，靠看门狗→紧急重启自愈。
- 【架构债务】双时钟并存：EngineLoop::PhaseClock 与 SettlementEngine 墙钟累积器（settlement.h:83-98，仅 legacy advance() 消费）。

### 4.2 Kotlin↔C++ 边界（双实现系统表）

| 系统 | Kotlin 实现 | C++ 实现 | 运行时实际执行 |
|---|---|---|---|
| 时间/旬结算 | TimeSystem.onPhaseTick（**生产死代码**） | phase_settlement/time_system.h | **C++** |
| 引擎循环时钟 | GameTimeClock（速度钩子） | engine_loop.h PhaseClock | **C++ 真相源** |
| 月变/年变 | MonthSettlementExecutor/YearSettlementExecutor（回退） | month/year_settlement.h | **C++ + Kotlin 残留扇出** |
| 旬残留：自动装备/丹药/突破 | PhaseSettlementExecutor.executeResidual | auto_gear/pill_system/breakthrough.h（**生产未接入**） | **Kotlin** |
| 内部战斗 | BattleSystem（完整实现，回退） | battle_execution.h | **C++ 优先**（MissionSystem/AISectBeastAttackProcessor 直连 Router） |
| 宗门战/AI 攻击决策 | AISectAttackManager | sect_battle/sect_attack_decision.h | **C++ 优先** |
| 库存 | InventorySystem | handleInventory | **C++ 优先**（仅 AUTHORITATIVE） |
| 炼丹/锻造/邮件 | Alchemy/Forge/MailSystem | 无对应头 | **纯 Kotlin 残留** |
| 生产排程 | ProductionCoordinator/ProductionProcessor(1862行) | 月结内 checkpointAllProduction | 排程 Kotlin；月度结算 C++ |
| 秘境 | SecretRealmService(1278行) | secret_realm_settlement.h（到期关闭） | 双端各半 |
| RNG | DeterministicRng/GameRngManager | rng.cpp PCG | **C++ 单一真相源**（每次抽取一次 JNI） |
| 道路合成 | RoadTiling.kt（遗留） | road_compositor.h | **C++**（RoadCompositorBridge） |

### 4.3 ECS
- 数据结构层（entity/storage/view）：真 SoA 稀疏集、代际指针、LIFO freelist——教科书质量。
- 运行时层：【伪完成】`ecsWorld_` 全 src/ 仅出现一次（作 runAll 参数）；`PhaseCoreBatchSystem::run(ecs::World&)` 形参无名、完全忽略 World（phase_settlement.h:1371-1383）；View/buildDiscipleEntities 全部调用点在 test/。
- 生产真相是 `DiscipleStore` 列式存储（100+ 并行列向量 + idToRow 索引）+ 直接函数式系统——这是**面向列式存储的数据导向设计，不是 ECS**。行序=RNG 确定性红线（disciple_store.h:22-27）。
- `eraseAt` 逐列 erase 且清空重建 idToRow（disciple_store.cpp:422-541），批量回导 O(N×列数)（有 bench 测试，作者知情）。

### 4.4 地图系统
- 地图 **128×128=16384 格**（GameConfig.kt:995-999）。Tile 语义纯装饰；游戏数据=建筑列表+占用 Set。
- 无数据模型 Chunk——唯一 Chunk 是软渲器 4×4 渲染位图缓存（32 格/块，内容哈希失效）。
- 复杂度：tap 命中 O(1)/格+O(B) 建筑；放置校验 O(脚印)；每帧渲染 **O(可见 ~1700 格)**（C++ 视口钳制）；缩放 C++ 帧内 O(可见)、Kotlin 零成本。
- 【架构债务】每变动 O(全图)：flatTileData 展平 `flatMap{toList()}`（MainGameScreen.kt:481-483）、每格修路重建整图 roadData（:513-519）——128² 无感，但封死扩容。
- 3000×3000 推演：flatTileData 每宗 36MB×按宗缓存→GB 级；Canvas chunk 网格按 128 写死；C++ 渲染与 GridSystem 天然可扩——瓶颈全在 Kotlin 数据侧。

### 4.5 Renderer / Vulkan / GLES / Metal
- **VulkanBackend【真实可用，非预留接口】**：完整链路 instance→物理设备筛选（API≥1.1、ETC2/ASTC 强制）→逻辑设备→vkCreateAndroidSurfaceKHR→swapchain（FIFO）→RenderPass→图形管线（PipelineCache 持久化）→per-image cmd buffer→双缓冲 mapped VBO→vkCmdBeginRenderPass→逐图元 vkCmdDraw→可选 renderScale blit→vkQueuePresentKHR；渲染内容为真实地图。瑕疵：acquire OUT_OF_DATE 仅记日志跳帧，不在帧内重建（VulkanBackend.cpp:2191-2204）。
- **GlesBackend【真实可用，功能子集】**：真 EGL+GLES2；无 ASTC/renderScale/地面 REPEAT；每帧全量 glBufferData 重传顶点（:304-306）；每条网格线一次 glDrawArrays。
- **SoftwareCanvasBackend【真实可用，兜底】**：android.graphics.Canvas+Bitmap 4×4 chunk 管线；缩放封顶 0.6；真机实测曾 77~990ms/帧故限帧。
- **Metal【未完成，0%】**：Rhi.h 有接入指南注释但接口不足（无压缩纹理上传、无着色器抽象、SPIR-V 预编译头不可用于 Metal）。
- 后端选择链：VulkanPolicy.getRenderStrategy（940 行设备策略：崩溃自愈安全模式/云游戏检测/SIGSEGV 标记/模拟器 API≥31 白名单/厂商分级）→ 默认旗舰 Vulkan；GpuTierDetector 只影响 renderScale 不影响后端选择。
- 动画：云层（Kotlin 渲染线程推进）+作物三阶段 crossfade（C++ 帧内计算）；建筑静态精灵；**无角色动画系统**。
- 每帧分配：SpriteBatcher >512 顶点堆增长；Kotlin RenderFrame copy/云 FloatArray/FrameRenderState 小对象；updateRenderState 有引用缓存防稳态复制。

### 4.6 JNI
- 面：GameCoreBridge 33↔33 齐全；NativeBridge 25↔24（isRendererReady 缺失）；桌面 DiffRngBridge ~45 导出（不进 Android 构建）。
- 频率：引擎线程每帧 3 次（loopFrame 17×jlong+热控+电量，便宜）；渲染线程每帧 4-10 次；看门狗 ~1/s；RNG 结算期高频单次往返；每旬 5 次重通道。**无每 NPC/每 Tile JNI**。
- 核心问题在封送：全部业务数据走 UTF-8 JSON jbyteArray 双向拷贝（每方向 ≥2 次拷贝+双端 JSON 树分配，GameCoreBridge.cpp:124-140），叠加全量段语义（§6 P0-2）。无 direct ByteBuffer。
- 【风险·中】`nativeWatchdogVerdict` 三线程并发（WATCHDOG/Main/引擎线程），桥层无锁，安全仅靠 PhaseClock atomics+线程约定（GameCoreBridge.cpp 全文件无 mutex）。

### 4.7 内存 / 多线程
- C++ 侧 RAII 干净、无裸 new 遗留、单线程无锁契约明确。风险集中边界层：4096² RGBA 图集主线程 3×64MB 瞬时分配（NativeSurfaceView.kt:345-385，封顶只护软渲路径）；DirtyTracker 每旬 baseline 全量深拷贝；全 Bitmap 禁 recycle 策略（国产 ROM double-free 规避）合理但依赖 GC。
- 线程：JobSystem worker 池（真线程、真生产消费、确定性合并有守护测试）+ Kotlin 引擎单线程 + 渲染线程 + 看门狗线程 + VsyncGate HandlerThread。真实并行的只有每旬核心批次。

### 4.8 Android
- 横屏锁+configChanges 防重建；onPause/onResume 与循环/音频/wakeLock 联动完整；GameEngineCore 进程级单例（设计使然）。
- Surface 体系：AndroidSurfaceProvider 纪元状态机（genCounter 代际、stale 事件拒绝、10s 初始化超时安全网）；NativeSurfaceView 2s join 截止、join 失败跳过 backend.release() 防 use-after-free；451f669c 修复"CPU API 连接残留致 vkCreateAndroidSurface 失败"——**全仓质量最高的部分**。
- 缺口：native 符号不入包不上传（无 debugSymbolLevel，Bugly 仅 mapping.txt）；onTrimMemory 仅 LOW 以下不动作；`.cxx` 残留 wwise-engine.dir 构建垃圾。
- 构建：NDK 27.0.12077973、AGP 8.10.0、Kotlin 2.2.20、minSdk 24/targetSdk 35、R8 开启、release 原生=RelWithDebInfo(-O2 -g)、双 ABI（armeabi-v7a+arm64-v8a）、extractNativeLibs=true、纹理 split 开关开启但仅一种 KTX 格式（形同虚设）。

### 4.9 iOS
- 全仓零 iOS 文件。gamecore 可原样编 iOS（C++20、零平台依赖、桌面 llvm-mingw 构建已验证跨工具链）——唯一真实资产。
- 渲染库全部 Android 绑定（ANativeWindow/EGL/VK_KHR_android_surface）；Rhi.h 接口窄于真实需求；VsyncGate/setFrameRate 需 CADisplayLink 对等物。
- **结论：当前无法编译 iOS；"双端"目标下 iOS 完成度 5%**（仅核心可移植性）。

### 4.10 Asset / 数据表
- ASTC KTX（4096²，11 级 mip，22MB）仅 Vulkan 路径；GLES/软渲=Kotlin 主线程逐精灵 decodeResource 拼 RGBA。
- 数据表双轨：Kotlin 解析 assets JSON（game_config/buildings/manuals）+ C++ 编译期静态表（manual_db.h 7628 行，scripts/gen-templates.mjs 生成）+ GameConfigNativeBridge JNI 运行时注入。双端漂移无 CI 校验。
- 音频：SoundPool 8 流+MediaPlayer BGM；`AndroidAudioPlayer.release()` 全库无调用（自认 A2 债）。

---

## 5. 伪完成 / 未接入 / 未完成清单

| # | 标记 | 项 | 证据 |
|---|---|---|---|
| 1 | 【伪完成】 | ECS 骨架展品：生产 0 实体、系统弃用 World | game_core.h:202-203；phase_settlement.h:1371-1383 |
| 2 | 【伪完成】 | 事件队列恒空 `"[]"`，"批次 1 实现"未兑现 | game_core.cpp:419-422 |
| 3 | 【伪完成】 | "46 个 ActionId 已 C++ 化"实际通用通道仅库存一族接线 | GameEngineNativeOps.kt:15 vs InventoryNativeForward.kt:57（唯一业务调用方） |
| 4 | 【伪完成·命名】 | GameLoopDelegate 不是游戏循环（是 1s 心跳看门狗+杂务） | GameLoopDelegate.kt:24,70-93 |
| 5 | 【伪完成·地雷】 | NativeBridge.isRendererReady 声明无 C++ 导出，调用即抛 UnsatisfiedLinkError | NativeBridge.kt:215 |
| 6 | 【未接入】 | nativePollEvents（双侧齐全零调用）、nativeAdvance（仅基准测试） | GameCoreBridge.kt:237/65 |
| 7 | 【未接入】 | C++ auto_gear/pill_system/breakthrough 头文件生产未走（走 Kotlin 残留执行器）；Kotlin TimeSystem.onPhaseTick 死代码 | AuthoritativeOps:62；TimeSystem.kt:33 |
| 8 | 【未完成】 | iOS 零基础设施，跨平台主张仅存注释 | clock.h:12、road_system.h:23、CMakeLists 注释 |
| 9 | 【未完成·残留】 | 自动存档已决策移除但清理不彻底（详见 §6 勘误） | changelog_entries.json:813；见 §6 |
| 10 | 【伪完成】 | 纹理分包 enableSplit=true 但仅一种 KTX 格式 | build.gradle:165 |
| 11 | 【过程性】 | 零 TODO 政策致 marker 扫描系统性漏报（债务藏于 detekt baseline 3075 行）；45 个 Diff 对拍测试缺省环境 assumeTrue 静默跳过（绿但零执行） | CLAUDE.md:603；DiffExecuteTest.kt:30 等 |
| 12 | 【注释失实】 | 模拟器必走软渲（实际 API≥31 走 Vulkan）；gamecore"禁异常/RTTI"与编译旗标不符；game_core.h:151"当前返回未实现错误"（execute 已完整实现） | NativeSurfaceView.kt:35；gamecore/CMakeLists.txt:7 |

---

## 6. 问题清单（P0/P1/P2/P3）

### 勘误（审计后经产品决策核实）

原 **P0-1"自动存档缺失+退出不保存"** 经查证为**已裁决的既定设计**：自动存档机制曾被正式移除（changelog_entries.json:813："移除自动存档机制——移除游戏循环触发的周期性自动存档、SavePipeline 异步管道、增量保存、AUTO_SAVE_SLOT=0、autoSaveIntervalMonths 字段"），纯手动存档为产品决策。**原 P0-1 重新定性为【残留清理缺陷】**：移除执行不彻底，遗留下述残留，且正是这些残留导致本次审计将其误判为"功能缺失"：

| 残留项 | 位置 | 说明 |
|---|---|---|
| 退出确认弹窗文案"游戏进度会自动保存"（与事实相反，出现在退出瞬间，误导性最强） | SettingsTab.kt:356 | 需改为如实文案 |
| `autoSaveIntervalSeconds: 60` / `autoSaveDebounceMs: 30000` 配置键（零消费者） | assets/config/game_config.json:6-7 | 直接删除 |
| `autoSaveIntervalMonths` Room 列（存在于 game_data **与** sect_policy_state 两张表） | GameDatabase schema + OldSerializableSaveData.kt:126（proto 139） | 需 Room 迁移删列；旧档兼容 DTO 保留解析-忽略 |
| `flushDirtyState`（GameStateRepository.kt:122）/`consumeDirty`（GameStateStoreImpl.kt:134）死代码 | core/data、app | 先清查 markDirty 读写链是否整体 write-only，再整链删除 |
| GameTimeClock.kt:97 注释"自动保存已累积的游戏时间" | GameTimeClock.kt:97 | 指内存时钟累积而非磁盘存档，建议措辞改为"保留"以绝后患（可选） |

### P0

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| P0-2 | 双真相源 + 每旬 O(全状态) JSON 双向同步：gameData 引擎只要变一次即整段重发（StateSyncService.kt:376-377）；DirtyTracker 每次 diff 两次全量序列化+深拷贝（dirty_tracker.cpp:63-64）；**每次库存操作触发全状态导出+全表替换**（GameEngineNativeOps.kt:81）；全量兜底双解析（StateSyncService.kt:124,137）；弟子任一变化 assembleAll+replaceAll O(N)（:658-681） | StateSyncService / dirty_tracker / GameEngineNativeOps | 增量通道实际 O(全状态)，随状态增长在引擎线程造成旬延迟抖动；混合架构中央税 |
| P0-3 | 非 ASTC 路径主线程拼 4096² RGBA 图集：Bitmap 64MB+IntArray 64MB+ByteArray 64MB 瞬时并存，封顶只护软渲路径 | NativeSurfaceView.kt:345-385 + SectAtlasAssembler.kt:77-78 | GLES 后端（API<31 低端机默认路径）初始化 OOM/ANR 高危；失败即永久软渲 |

### P1

| # | 问题 | 位置 |
|---|---|---|
| P1-1 | ECS 骨架展品（已决策：全面启用 → 转入方案 WS-3） | 见 §5-1 |
| P1-2 | iOS 零基础设施 + RHI 抽象不足（已决策：暂缓 → WS-6 仅做文案收敛） | Rhi.h:53-75 |
| P1-3 | nativeExecute 业务面与宣称不符 + 死导出群（isRendererReady/nativePollEvents/nativeAdvance/pollEvents 桩） | 见 §5 |
| P1-4 | JNI 三线程并发无锁（watchdog 三方进入，安全靠约定+atomics） | GameCoreBridge.cpp 全文件无 mutex |
| P1-5 | 月结 O(N²) 配对（month_settlement.h:247-249，1000 人≈25 万对/月）+ 生产结算 O(slots×N)（ProductionProcessor.kt:699,800,882）+ AI 宗门 O(sects²)（AISectAttackManager.kt:85-92） | C++/Kotlin 月结路径 |
| P1-6 | 零 TODO 文化掩盖债务（detekt baseline 3075 行压制）+ Diff 对拍测试假绿（assumeTrue 静默跳过） | CLAUDE.md:603；Diff*Test |
| P1-7 | release native 符号不入包不上传（无 debugSymbolLevel，Bugly 仅 mapping.txt） | build.gradle |

### P2/P3（择要）

P2：Vulkan OUT_OF_DATE 帧内不自愈；GLES 每帧全量顶点重传+每网格线一 draw；SpriteBatcher 每帧堆分配；Kotlin 每建筑/道路变动 O(16384) 全图重算（封死扩容）；每次 RNG 抽取一次 JNI 往返；双实现死面收敛；音频 release 泄漏；过时注释纠偏；sectMapCache 整图缓存 onTrimMemory 不释放。
P3：双 ABI 包体成本；三套序列化栈并存（kotlinx+protobuf+gson）；GameLoopDelegate 命名；98+102 object 单例与 156 处 companion var 全局可变态；wwise-engine.dir 残留；astcenc 缺失静默跳过。

### 正面发现（避免过度矫正）

Vulkan 渲染链真实完整且渲染真实内容；gamecore 零 Android 依赖 + 桌面 llvm-mingw 构建 + GTest + 跨语言对拍三重验证闭环（同类项目罕见）；Surface 生命周期纪元体系防御完备；C++ 视口钳制/chunk 哈希失效缓存/LOD/三层建筑放置校验均经过认真复杂度治理。

---

## 7. 架构评分

| 维度 | 评分 | 简评 |
|---|---|---|
| 架构合理性 | 6.5/10 | 混合双真相源是务实过渡态而非终态；同步通道是中央税；ECS 展品扣分 |
| C++ Core | 7.5/10 | 干净、可移植、测试闭环好；约半数系统仍 Kotlin 残留/双实现 |
| ECS | 3/10 | 结构优秀但运行时为零（按列式存储计 7 分） |
| Renderer | 7.5/10 | Vulkan 主路径完整真实；GLES 功能子集；软件兜底真实 |
| Vulkan | 8/10 | 完整管线+设备策略工程化；OUT_OF_DATE 小瑕疵 |
| Metal | 0/10 | 不存在 |
| Android | 7.5/10 | 生命周期优秀；存档残留+符号运维缺口 |
| iOS | 0.5/10 | 仅核心可移植 |
| JNI | 5/10 | 面小而纪律好；封送全 JSON 拷贝+全量段语义昂贵 |
| 内存管理 | 6/10 | C++ 侧干净；边界处 3×64MB 与每旬深拷贝 |
| 多线程 | 6.5/10 | 真并行但消费点单一；无锁三线程约定风险 |
| Asset System | 5.5/10 | ASTC 通道真；回退路径弱；数据表双轨 |
| 地图系统 | 6/10 | 渲染侧优秀；数据侧 O(area) 重建封死扩容 |
| 性能 | 6.5/10 | 渲染健康；CPU 旬同步与月结尖刺是真实瓶颈 |
| 跨平台能力 | 3/10 | 单端事实 |
| 可维护性 | 6/10 | 文档/测试纪律强；双实现对账成本高；零 TODO 文化掩盖债务 |
| AI 可继续开发性 | 6.5/10 | 注释自证边界+对拍框架利于续作；需先懂五步互插才敢动 |
| **总评** | **5.9/10** | **真实但未完成的迁移，质量高于"伪完成"平均水准** |

---

## 8. 审计结论与决策衔接

本报告的全部 P0/P1 问题已经产品/架构决策裁决，处置方案见 [cpp-migration-implementation-plan.md](cpp-migration-implementation-plan.md)：

| 审计发现 | 决策结果 |
|---|---|
| 双真相源每旬全量 JSON（P0-2） | 决策 1 选 A：C++ 收敛为唯一模拟真相源，反向通道终态删除 |
| 自动存档残留（原 P0-1 勘误） | 决策 2：纯手动存档为既定设计，残留彻底清理（§6 残留表全项） |
| ECS 骨架展品（P1-1） | 决策 3：游戏要全面使用 ECS（转入 WS-3 运行时化） |
| iOS 零基础设施（P1-2） | 决策 4：暂缓，仅做文档收敛与可移植性护栏 |
| nativeExecute 未接线域（P1-3） | 决策 5：与决策 1 联动，随下沉批次逐域接线 |
| 地图无 NPC（§2） | 决策 6：将新增 NPC 移动（转入 WS-4/WS-5） |
| 双 ABI + 无效纹理分包 | 决策 7：两项均删除（arm64-v8a 单架构；移除 split 开关） |
| detekt baseline 压制 3075 行（P1-6） | 决策 8：债务必须实际解决（转入 WS-7） |
