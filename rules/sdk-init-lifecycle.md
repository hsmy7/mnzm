# SDK 初始化生命周期与关键路径隔离

> 2026-08-15 创建：源自两次真机回归教训（广告 SDK 重复初始化 / 退出游戏再登录卡死）。修改任何 SDK 初始化、登录、登出代码前**必须先读本文档**。

## 核心原则

1. **关键路径隔离** — 与登录无因果关系的初始化（广告 SDK / 时长统计 / 合规回调注册）**不得**与关键步骤（防沉迷验证启动、界面跳转）串行绑定在同一调用链。初始化失败只记日志，**永不阻断**登录与主流程。
2. **进程级一次性** — 广告聚合 SDK（`DirichletSdk.init`）等全局初始化 API 进程内仅调用一次（`SdkInitGuard` CAS 守卫）；进程销毁复用后静态守卫清零是预期行为，靠"调用时机收敛"而非守卫兜底。
3. **登出必须完整** — 登出 = 清本地会话 + 清 TapTap SDK 登录态 + 停时长统计 + 解绑合规回调。漏清 SDK 会话会导致下次登录走"静默登录"，防沉迷验证不触发。

## SDK 初始化清单与时机

| SDK 服务 | 初始化入口 | 时机 | 幂等守卫 |
|---|---|---|---|
| TapTap 登录 SDK | `TapTapAuthManager.init`（`MainActivity.initTapTapLoginSdk`） | **MainActivity 通用启动协程**（登录按钮前置依赖，必须早于登录发起） | `SdkInitGuard.tryInitTapTapSdk` + `TapTapAuthManager.isInitialized` |
| 广告聚合 SDK | `DirichletSdk.init`（`MainActivity.initAdSdk`） | **登录成功回调 / 已登录冷启动兜底**（`MainActivity.ensureSdkServicesInitialized`） | `SdkInitGuard.tryInitAdSdk` |
| TapDB 时长统计 | `TapDBManager.startGameDurationTracking` | 同上 | `TapDBManager.trackingStarted` |
| 合规回调注册 | `ComplianceManager.registerCallback` | 同上（**必须先于** `startComplianceCheck`） | `ComplianceManager.isCallbackRegistered` |

- 广告 SDK 依赖 `TapTapKit.context`（`TapTapAuthManager.init` 反射兜底），冷启动路径须经 `awaitTapTapSdkReady` 等待登录 SDK 就绪（防反序崩溃）。
- 进程销毁复用后 MainActivity 重建：登录 SDK 会重新初始化（登录必要，自身幂等）；广告/统计/合规回调**不会**被触发（已移出通用启动协程）——除非用户重新登录或已登录冷启动。

## 关键路径隔离契约（`safeRunAfterSdkInit`）

**文件：** `app/src/main/java/com/xianxia/sect/ui/MainActivity.kt`（顶层函数）

```kotlin
internal fun safeRunAfterSdkInit(
    initSdkServices: () -> Unit,
    onInitFailed: (Throwable) -> Unit,
    block: () -> Unit
)
```

- 语义：`initSdkServices` 抛任何 `Exception` → 记录 `onInitFailed` 日志 → **仍执行** `block`；`CancellationException` 必须重抛；`Error` 不拦截（致命缺陷崩溃暴露）。
- 调用点：登录成功回调（`block` = `startComplianceCheck`）、已登录冷启动（`block` = 界面跳转分支）。
- **语义守护：** `app/src/test/java/com/xianxia/sect/ui/SafeRunAfterSdkInitTest.kt`（6 用例）。未来改动此编排（调整顺序/吞异常/改签名）必须保持"初始化异常不阻断关键步骤"契约，测试会拦截违规。

## 登出完整清单（4 处登出入口必须一致）

登出基准 = `MainActivity.handleUserExit`（清会话 + 停时长统计 + SDK 登出 + 解绑回调）：

| 登出入口 | 位置 | 必须包含 |
|---|---|---|
| 游戏内设置退出登录 | `GameActivity.onLogout` | `clearSession` + `TapTapAuthManager.logout` + `TapDBManager.stopGameDurationTracking` + `ComplianceManager.unregisterCallback` |
| 模式选择界面退出登录 | `MainActivity` `ModeSelectionScreen.onLogout` | 同上（`recreate` 重建主界面） |
| 合规限制弹窗"退出游戏/切换账号" | `MainActivity.performComplianceLogout` | 同上 |
| 实名认证界面"切换账号" | `MainActivity` `ComplianceVerificationScreen.onLogout` | 同上 |

新增登出入口时必须复制完整四件套，禁止只做 `clearSession()`。

## 修改检查清单

- [ ] 新增初始化调用：确认归属正确的时机（登录前置 / 登录成功 / 已登录冷启动），进程级幂等守卫已接入
- [ ] 新增的初始化是否被放进了登录/主流程关键路径？若是，必须经 `safeRunAfterSdkInit` 编排或自行保证永不抛出
- [ ] 初始化入口契约 = 幂等 + 永不抛出（内部 `runCatching` 兜底 Error 类）
- [ ] 登出是否完整四件套（新增登出入口时逐项核对）
- [ ] 防沉迷验证（`startComplianceCheck`）的合规回调注册必须先于验证启动
- [ ] 运行 `SafeRunAfterSdkInitTest` + `SdkInitGuardTest` + `TapDBManagerInitGuardTest`
- [ ] 真机冒烟：登录 → 进游戏 → 退出 → 再登录（弹登录页确认）→ 防沉迷验证 → 进模式选择；杀进程重进（已登录直接进游戏）；登出后杀进程重进（登录界面）

## 相关文档

- `docs/architecture.md` 待完成项登记表：D-42（游戏内防沉迷合规回调不生效，合规回调宿主绑定 MainActivity，进游戏后 MainActivity 销毁回调被丢弃——治理方向：宿主进程级化）
- `CHANGELOG.md` 2026-08-15：「广告 SDK 重复初始化」「广告 SDK 初始化时机」「退出游戏再登录卡死」「SDK 服务初始化与登录流程解耦」四个修复小节（完整证据链）
