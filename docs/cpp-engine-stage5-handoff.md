# 计划 v2 阶段 5 交接文档（游戏循环入 C++）

> ✅ **已完成（2026-08-27）**：剩余工作全部执行完毕——§5.1 编译验证通过、§5.2/§5.3 对拍测试全绿
> （DiffEngineLoopTest 15 + DiffWatchdogTest 24）、§5.4 全量回归通过（engine JUnit 2922/2922 ·
> GTest 538/538 · NDK externalNativeBuildRelease · detekt）、§5.5 文档已登记（docs/cpp-engine.md
> §7 阶段 5 行 / docs/architecture.md R-02·C-07 / CHANGELOG.md / changelog_entries.json）、§5.6 已提交。
> 途中发现并根因修复 S-09（对拍测试隔离缺口，见 cpp-engine.md §8）。本文件保留作交接记录。
>
> 写于 2026-08-27，交接原因：上下文预算耗尽。本文档供下一个会话继续完成阶段 5 收尾。
> 权威计划文档：`docs/cpp-engine.md` §7 阶段表（阶段 5 行）。
> 基线：阶段 4 已完成（GTest 478/478 → 本阶段新增后应为 536/536；engine JUnit 2396/2396；NDK 构建通过）。

## 1. 任务定义

阶段 5 原文（cpp-engine.md §7）：
> **游戏循环入 C++**：平台能力接口化（Clock/Input/IO/Telemetry/热控/电量——ADR Clock/Logger 注入先例扩展）；引擎循环 + 看门狗判据迁 C++ | R-02（core/engine Android 依赖随引擎退役自然消除）

**已确定的设计切分**（本次会话已定稿并实现）：
- **C++ 真相源**：帧累积/逻辑步进（100ms×5 步）/墙钟消费（速度/暂停/refund 状态机，即 GameTimeClock 语义）/tick 计数/循环心跳/看门狗判据
- **Kotlin 驱动侧（平台机制，保留）**：协程线程本体/delay 与 OEM 反挂起忙等（adaptiveWait/antiFreezeDelay）/场景与闲置帧率策略/ADPF 上报/热控电量判据（ThermalController，阶段 6 迁）
- **Kotlin 残留执行器**：每旬 ①-⑤ 互插管线（processAuthoritativeTick，阶段 2d 语义不变）
- IO 能力不被循环消费，留 Kotlin 平台层（存档编码，阶段 7 决策 T-CPP-1）
- AUTHORITATIVE 模式默认 OFF（灰度开关）不变，回退契约：native 帧计划不可用 → 下一帧走纯 Kotlin 累积器路径

## 2. 已完成（本次会话交付，均未提交 git）

### 2.1 C++ 侧 —— ✅ 完成且已验证（GTest 536/536 全绿 + 桌面对拍库构建通过）

| 文件 | 状态 | 内容 |
|---|---|---|
| `android/app/src/main/cpp/gamecore/include/gamecore/core/platform.h` | 新建 | 平台能力端口：MonotonicClock（+Steady/Fixed 实现）、TelemetrySink（+Null）、ThermalState 枚举 + ThermalStatusProvider（+Settable）、BatteryStatus 结构 + BatteryStatusProvider（+Settable）。输入经显式 notify 调用（非 pull 接口） |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/engine_loop.h` | 新建 | **PhaseClock**（GameTimeClock 逐位移植：start/setSpeed 旧速度结算/tick 追补上限 3×speed 超限丢弃余量/consumeDeadTime/forceConsumeOnePhase/refundPhases/msPerPhase/phaseProgress；accumulatedGameMs/speed 为 atomic 镜像 Kotlin @Volatile）+ **EngineLoop**（gameLoopIteration 判据移植：iterate(pausedOrLoading, isSaving) 返回 LoopFramePlan；kLogicDtNs=100ms、kMaxAccumulatorNs=5 步、kMaxStepsPerFrame=5；心跳 lastLoopActivityMs；notifyUserActivity；onLoopRestart）。全部 header-only，复用 settlement.h 的 kMsPerPhase1x/kMaxPhasesPerTick |
| `android/app/src/main/cpp/gamecore/include/gamecore/system/watchdog.h` | 新建 | **ProgressMonitor**：GameTimeProgressMonitor 逐位移植（ProgressSnapshot 12 字段/StallVerdict 数值码 0-4/evaluate/classify/classifyFlags/三阈值 45s·90s·20s/lastPhaseProgressedAtMs 基准/std::mutex 线程安全）。S1/S4/S5/F2/V1/V6 修复分支全部随行移植 |
| `android/app/src/main/cpp/gamecore/include/gamecore/game_core.h` + `src/game_core.cpp` | 修改 | 新增：`PlatformProviders` 结构 + `setPlatformProviders()`、`WatchdogFlags` 结构 + `watchdogVerdict(flags)`（组合 loop_ 状态 + state totalPhases + flags → ProgressMonitor）、`EngineLoop& loop()`、`loop_`/`progressMonitor_`/`batteryProvider_` 成员、SteadyMonotonicClock 实现 |
| `android/app/src/main/cpp/gamecore/test/engine_loop_test.cpp` | 新建 | PhaseClock 19 用例（对齐 Kotlin GameTimeClockTest 全部 23 条语义）+ EngineLoop 帧迭代 10 用例 + 平台端口 2 用例 |
| `android/app/src/main/cpp/gamecore/test/watchdog_test.cpp` | 新建 | 25 用例，与 Kotlin GameTimeProgressMonitorTest 全分支矩阵逐条对齐（含阈值常量校验） |
| `android/app/src/main/cpp/gamecore/test/CMakeLists.txt` | 修改 | 加入两个新测试文件 |
| `android/app/src/main/cpp/GameCoreBridge.cpp` | 修改 | Android 桥新增：`AndroidMonotonicClock`（CLOCK_BOOTTIME，与 elapsedRealtime 一致含深度睡眠）、`AndroidTelemetrySink`（logcat）、Settable 热/电 provider 全局实例；nativeInit 中 `setPlatformProviders` 注入；新 JNI 入口（见 §4 清单）；`packLoopFramePlan` 17 槽打包 |
| `android/app/src/main/cpp/gamecore/jni/GameCoreJni.cpp` | 修改 | 桌面对拍桥新增镜像入口（nativeCoreLoop*/nativeCoreWatchdogVerdict/nativeCoreMonitorEvaluate + nativeCoreMonitorReset 独立判据通道 + nativeCoreLoopSetMonoMs 固定时钟控制 + nativeCoreLoopAccumulatedGameMs/TickTotal 查询）；nativeCoreInit/nativeCoreInitMode 注入 FixedMonotonicClock；nativeDestroy 清理 g_monitor |

**验证已通过**：
```bash
# 桌面 GTest（536/536 PASS，含新增 58 用例）
cd android/app/src/main/cpp/gamecore
C:/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe --build build -j 8
export PATH="/c/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin:$PATH"   # exe 需要 libc++.dll
./build/test/game-core-tests.exe

# 桌面对拍库（构建通过，产出 android/core/engine/build/desktop-jni/libgamecorejni.so）
cd C:\Mnzm\XianxiaSectNative && powershell -File scripts/build-desktop-jni.ps1
```

### 2.2 Kotlin 侧 —— ⚠️ 已写完但**未编译验证**（最后一次 `gradlew :core:engine:compileReleaseKotlin` 被取消，状态未知）

| 文件 | 状态 | 内容 |
|---|---|---|
| `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/GameCoreBridge.kt` | 修改 | 新增 external 声明（见 §4）+ VERDICT_* 常量 + `NativeLoopPlan` 类（17 槽 LongArray 解包，unpack 返回 null=不可用） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/system/GameTimeClock.kt` | 修改 | **R-02**：删除 `import android.os.SystemClock/android.util.Log`（SystemTimeSource+TimeSourceModule 移出至 app 模块；Log.w→DomainLog.w）；新增 `onSpeedChanged` 钩子（setSpeed 尾部触发）+ `mirrorFromNative(accumulatedGameMs)`（AUTHORITATIVE 下本时钟降级 UI 镜像） |
| `android/app/src/main/java/com/xianxia/sect/di/PlatformTimeModule.kt` | 新建 | R-02：app 模块 TimeSource Hilt 绑定（AndroidTimeSource = SystemClock.elapsedRealtime()）。确认过 SystemTimeSource 在 engine 模块无其他引用 |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCoreLoopOps.kt` | 新建 | `authoritativeLoopIteration()` 扩展（帧迭代 AUTHORITATIVE 化：心跳/玉符 tick/nativeLoopFrame/暂停分支/逐 tick 执行/alpha+镜像回推/闲置检测/平台推送/adaptiveWait/ADPF）+ `tickAuthoritativeStep(phases)`（快照采样/热控/processAuthoritativeTick/postTickResidualDuties）+ `nativeVerdictToStall(code)` + `thermalSeverityCode(state)` |
| `android/core/engine/src/main/java/com/xianxia/sect/core/GameEngineCore.kt` | 修改 | ① init 块注册 gameClock.onSpeedChanged → nativeLoopSetSpeed（authoritative 时）② `nativeLoopPipelineActive` @Volatile 标志（refund 分流依据）③ gameLoopIteration 顶部 AUTHORITATIVE 分支（`if (NativeEngineFlag.authoritative && ensureAuthoritativeNative()) return authoritativeLoopIteration()`；非 authoritative 时置 false）④ prepareLoopStart 加 nativeLoopStart ⑤ performEmergencyRestart 加 nativeLoopOnRestart ⑥ onUserActivity 加 nativeLoopNotifyUserActivity ⑦ progressVerdict 加 native 判据分支（-1/未知码回退 Kotlin）⑧ 新 internal 辅助：notifyLoopActivity/publishNativeTickTotal/publishNativeAlpha/tickThermalControl/postTickResidualDuties/reportAdpfWorkDuration/pushPlatformStatusToNative ⑨ 可见性 private→internal：sampleProgressSnapshot/checkIdleTimeout/adaptiveWait/handleTickCrash/updateRenderFrameRate/LoopIterationState ⑩ tickInternal 的热控两行与 drain/巡逻段改为调共享辅助（行为零变化）⑪ **R-02**：删 `import android.os.Build`（doBusyWait 的 SDK_INT≥33 改 `supportsOnSpinWait` 反射探测字段） |
| `android/core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngineCoreAuthoritativeOps.kt` | 修改 | ① processAuthoritativeTick catch 块 refund 分流：`nativeLoopPipelineActive` 为 true → nativeLoopRefundPhases(capped) + gameClock.consumeDeadTime()；否则原 gameClock.refundPhases(capped) ② ensureAuthoritativeNative 初始化成功后调 `GameCoreBridge.nativeLoopStart()`（防 PhaseClock 残留 lastWallMs 造成首帧巨量 delta） |

## 3. 关键设计决策（不要推翻，除非发现缺陷）

1. **帧计划协议**：`nativeLoopFrame(pausedOrLoading, isSaving)` 返回 17 槽 LongArray：
   `[0]paused · [1]tickCount · [2..6]tickKind(1=active/0=isSaving跳过) · [7..11]tickPhases · [12]alpha位模式 · [13]frameDeltaNs · [14]idleNs(<0=从未活跃) · [15]tickTotal · [16]accumulatedGameMs`。
   槽位协议注释三处同源：`engine_loop.h`（LoopFramePlan）、`GameCoreBridge.kt`（NativeLoopPlan）、两个桥的打包函数。
2. **每帧一次 JNI**（标量 LongArray，禁 JSON——阶段 0 基准：JSON 往返 12µs 是成本大头）；每旬互插仍走既有 nativeSettlePhase 通道（阶段 2d 不变）。
3. **per-tick phases 语义**：一帧内 5 个逻辑 tick，首个 tick 的 PhaseClock.tick() 消费全部墙钟 delta（后续 tick delta=0）——与 Kotlin 原循环逐位一致（GTest `Frame2000msClampedToFiveSteps` 锚定：2000ms 帧 → 钳制 500ms → 5 tick → phases=[1,0,0,0,0]）。
4. **refund 分流**：native 管线活跃时只还 native（gameClock 是镜像，被 mirrorFromNative 每帧覆盖）；阶段 2d 遗留路径（tickInternal 里）仍还 gameClock。
5. **speed 双写**：UI 仍直接调 gameClock.setSpeed（speedFlow 不变），onSpeedChanged 钩子推送 native；两端各自保证"旧速度结算"语义。看门狗自愈 setSpeed(1) 经同一钩子生效。
6. **tick 计数真相源在 C++**（plan.tickTotal 镜像回推 _tickCount，UI 消费方不变）；gameClock 经 mirrorFromNative 每帧刷新（UI phaseProgress/remainingPhaseMs/speedFlow 不变 + 回退 OFF 无缝）。
7. **看门狗**：AUTHORITATIVE 下 progressVerdict 走 `nativeWatchdogVerdict(6 个平台侧 flags)`——引擎侧状态（tickCount/totalPhases/accumulatedGameMs/speed/loopActiveAtMs）由 C++ 组合，判定码 0-4 映射 StallVerdict；-1（未初始化）或异常 → 回退 Kotlin GameTimeProgressMonitor。恢复动作（handleWatchdogVerdict）留 Kotlin（换线程/清锁是平台操作）。
8. **热控/电量端口**：阶段 5 只做"接口化 + 注入 + 推送"（tickAuthoritativeStep→pushPlatformStatusToNative 每帧推送；C++ 侧 EngineLoop::reportThermalTelemetry 记录遥测）；帧率降级判据（ThermalController）留 Kotlin，阶段 6 渲染统一迁入。
9. **C++ ProgressMonitor 线程安全**：std::mutex（对应 Kotlin synchronized）；EngineLoop 的 tickCount/lastLoopActivityMs 与 PhaseClock 的 accumulatedGameMs/speed 为 atomic（镜像 Kotlin @Volatile）。

## 4. JNI 入口清单（已全部实现于两个桥，Kotlin 声明已写于 GameCoreBridge.kt）

生产桥（GameCoreBridge.cpp → GameCoreBridge.kt）：
`nativeLoopStart() · nativeLoopSetSpeed(Int) · nativeLoopFrame(Boolean, Boolean): LongArray · nativeLoopConsumeDeadTime() · nativeLoopRefundPhases(Int) · nativeLoopNotifyUserActivity() · nativeLoopOnRestart() · nativeWatchdogVerdict(6参): Int · nativeLoopSetThermalStatus(Int) · nativeLoopSetBatteryStatus(Boolean, Boolean, Int, Float)`

桌面对拍桥（GameCoreJni.cpp，**Kotlin 侧 DiffRngBridge.kt 声明尚未添加——见 §5 待办 2**）：
`nativeCoreLoopStart · nativeCoreLoopSetSpeed(Int) · nativeCoreLoopSetMonoMs(Long) · nativeCoreLoopConsumeDeadTime · nativeCoreLoopRefundPhases(Int) · nativeCoreLoopNotifyUserActivity · nativeCoreLoopAccumulatedGameMs(): Long · nativeCoreLoopTickTotal(): Long · nativeCoreLoopFrame(Boolean, Boolean): LongArray · nativeCoreWatchdogVerdict(6参): Int · nativeCoreMonitorReset() · nativeCoreMonitorEvaluate(12参快照): Int`

## 5. 剩余工作（按顺序执行）

### 5.1 编译验证 + 修错（首要）
```bash
cd C:\Mnzm\XianxiaSectNative\android
./gradlew.bat :core:engine:compileReleaseKotlin --console=plain
./gradlew.bat :app:compileReleaseKotlin --console=plain   # 验证 PlatformTimeModule
```
可能的错误点（写代码时未验证的假设）：
- `GameEngineCoreLoopOps.kt` 访问的成员可见性/存在性（依赖 GameEngineCore.kt 的 internal 辅助是否全部就位）
- `ThermalMonitor.thermalState` 是否为 `StateFlow<ThermalState>`（perf 包 5 值枚举 NORMAL/LIGHT/MODERATE/SEVERE/EMERGENCY——thermalSeverityCode 按此映射 0/1/2/3/5）
- `BatteryStatusProvider` 的 4 属性（isLowBattery/isPowerSaveMode/fpsCap/thermalThresholdOffsetC）——已确认存在
- detekt（engine 模块全绿要求）：新文件可能有 LongMethod/TooGenericExceptionCaught 等告警，按项目惯例加 @Suppress + 理由注释

### 5.2 DiffRngBridge.kt 补桌面通道声明
在 `android/core/engine/src/test/java/com/xianxia/sect/core/nativebridge/DiffRngBridge.kt` 添加 §4 桌面桥清单的 external 声明（对应 C++ 已实现的 GameCoreJni.cpp 入口，函数名 Java_com_xianxia_sect_core_nativebridge_DiffRngBridge_nativeCoreLoop* / nativeCoreMonitor*）。

### 5.3 新增 JUnit 对拍测试（阶段 5 验收核心）
- **DiffEngineLoopTest.kt**（nativebridge 测试包，参照 DiffAuthoritativeTickTest 的 assumeTrue(DiffRngBridge.isAvailable()) 模式）：
  - 场景 A（时钟状态机对拍）：Kotlin GameTimeClock(FakeTimeSource) vs C++（nativeCoreInit → nativeCoreLoopStart → nativeCoreLoopSetMonoMs 驱动 FixedMonotonicClock → nativeCoreLoopSetSpeed/settle 语义）。注意 C++ 侧每帧消费由 nativeCoreLoopFrame 驱动，Kotlin 侧用 gameClock.tick() 逐 tick 对比 phasesToAdvance/accumulatedGameMs（用 nativeCoreLoopAccumulatedGameMs 查询）。覆盖：1x/2x/暂停/追补截断/速度切换保累积/refund/catch-up 丢弃余量（对齐 GameTimeClockTest 23 条）
  - 场景 B（帧计划语义）：nativeCoreLoopSetMonoMs 按脚本推进 → nativeCoreLoopFrame 断言 17 槽（暂停分支/5 步钳制/isSaving 跳过/alpha）
- **DiffWatchdogTest.kt**：nativeCoreMonitorReset → 同一快照序列分别喂 Kotlin GameTimeProgressMonitor 与 nativeCoreMonitorEvaluate，判定逐位一致（把 GameTimeProgressMonitorTest 的 24 条场景跑成双端对拍）
- 运行（需先构建桌面库，见 §2.1 命令）：
```bash
./gradlew.bat :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.nativebridge.Diff*' "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --max-workers=1
```

### 5.4 全量回归 + NDK 验证
```bash
./gradlew.bat :core:engine:testReleaseUnitTest --max-workers=1        # 2396 基线 + 新增
./gradlew.bat :core:engine:detekt                                      # 全绿要求
# NDK（阶段 2/4 均跑过 externalNativeBuildRelease；GameCoreBridge.cpp 新增入口需 NDK 编译验证）
./gradlew.bat :app:externalNativeBuildRelease
```
注意：engine 模块既有测试可能受影响的理论点——GameTimeClock 构造签名未变（TimeSource 注入不变）；GameEngineCore 新 init 块会在测试构造时注册钩子（lambda 内有 GameCoreBridge.isLoaded 守卫，未加载时安全）。若 GameEngineCoreWatchdogTest 等对 progressVerdict 有精确断言，注意 NativeEngineFlag 默认 OFF → 走 Kotlin 路径，行为不变。

### 5.5 文档登记（全部待写）
1. `docs/cpp-engine.md`：§7 阶段表阶段 5 行标 ✅ + 完成纪要（参照阶段 3/4 的多行格式：批 5-1 平台端口/批 5-2 引擎循环/批 5-3 看门狗判据/批 5-4 AUTHORITATIVE 接线/批 5-5 R-02）；文件头"已完成阶段"列表加 5；§2 目录结构补 core/platform.h、system/engine_loop.h、system/watchdog.h；基线数字更新（GTest 536、engine JUnit 新数）
2. `docs/cpp-engine.md` §5.2 / `docs/architecture.md`：C-06/C-07 相关行更新（引擎循环已迁，批次 10 只剩阶段 6/7）；architecture.md R-02 行更新状态（见下）
3. `docs/architecture.md` R-02 登记（L456 附近）：**阶段 5 已消除循环路径 3 处**（GameTimeClock 的 SystemClock+Log、GameEngineCore 的 Build）；剩余 ~29 处（perf/thermal/registry/config/service/domain）随阶段 7 Kotlin 引擎退役时移 app 层——保留侧无需清理的结论不变
4. `CHANGELOG.md`：新版本条目（参照既有批次格式，写明交付物/验证数字）
5. cpp-engine.md §8 可新增 S-09 登记（若途中发现新问题）

### 5.6 提交
git commit（项目惯例：中文提交信息，如 `完成计划 v2 阶段 5：引擎循环 + 看门狗判据迁 C++（平台能力接口化）`）。工作区当前有 5 个 C++ 修改 + 5 个新建 + Kotlin 6 个修改/新建，全部属于本阶段，未提交。

## 6. 途中已知风险/未验证假设

1. **Kotlin 代码完全未编译**（§5.1 首要）。GameEngineCoreLoopOps.kt 是盲写，最可能有编译错误。
2. GameEngineJni.cpp 用了 C++20 designated initializers（`.monotonicClock = ...`）——llvm-mingw 构建已通过，NDK clang 也不会有问题。
3. `nativeLoopFrame` 在 AUTHORITATIVE 但引擎未初始化时返回空 LongArray → unpack null → 回退 Kotlin 路径（设计如此，非错误）。
4. DiffAuthoritativeTickTest 等既有对拍不受影响（未改既有通道）。
5. 一个已知的历史怪点（非本阶段引入，未处理）：processAuthoritativeTick 内 `capped = phasesToAdvance.coerceAtMost(3)` 在 2x 速度下会把 6 旬截为 3——与纯 Kotlin processTickPhases 行为一致（两者都截），语义保留未动。
6. 桌面 GTest exe 需 llvm-mingw 的 DLL 在 PATH（§2.1 命令已含）；cmake 用 SDK 3.22.1 那份（bash 里无 cmake）。

## 7. 本次会话未做、明确排除的事项

- AlarmWatchdogReceiver（app 层 Alarm 兜底）未改——它调 progressVerdict()，AUTHORITATIVE 下自动走 native 判据，无需改动
- 线程工厂/优先级/OemPowerProfile/看门狗退避（computeWatchdogBackoff）留 Kotlin（平台机制）
- 33 处 R-02 中其余 ~29 处 Android import（ThermalMonitor/FrameMetricsMonitor/registry/config 等）——阶段 7 范围
- 渲染 RHI/合成器（阶段 6）、Kotlin 引擎退役与存档决策（阶段 7）
