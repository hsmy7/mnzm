# ADR: Compose Multiplatform 迁移评估（G6 闭环，最高风险点）

> 状态：✅ 已评估 | 日期：2026-08 | 关联：docs/platform-abilities.md G6

## 背景

`docs/platform-abilities.md` G6 登记待办：UI 框架 Compose 独占（Android 独占），
Compose Multiplatform 评估（最高风险点，需专项 ADR）。iOS 迁移视角下 Jetpack Compose
无法直接复用，iOS 端 UI 需 Compose Multiplatform（CMP）或重写。

## 现状事实（2026-08 实测）

| 维度 | 现状 |
|------|------|
| UI 栈 | Jetpack Compose（BOM 2026.05.01）+ Material3 + 约 170 个 Screen/Dialog Composable |
| 平台耦合 | feature:game 大量 `android.*` API（Bitmap/SurfaceView/WindowInsets/Toast）；app 层 Activity 宿主 |
| 强跳过 | enableStrongSkippingMode=true + stability_config.conf（三模块） |
| 渲染 | 双层：Vulkan（C++ 原生）+ SoftwareCanvasBackend（纯 Kotlin Canvas）——C++ 部分 iOS 用 Metal/软渲替换，SurfaceProvider 接口已抽象 |
| 平台接口 | RenderBackend/SurfaceProvider/AdService/AudioPlayerFacade/CrashReporter 已全部接口化（本次根治补齐） |

## 评估结论

**决策：iOS 迁移立项时以 CMP 为 UI 主选方案评估落地，不预先迁移；UI 层不做"为跨平台而跨平台"的改造。**

理由：

1. **CMP 成熟度已达到可用线**——JetBrains 官方 KMP 支持 + iOS 稳定版（2025+），
   但项目 UI 深度依赖 Android 独占能力（Canvas Bitmap 管线、WindowInsets 精细控制、
   SurfaceView 原生桥、IME 系统栏战斗代码），迁移不是"换编译器"而是"UI 层平台
   能力逐项替换"，成本集中在 feature:game 的 ~170 个组件。
2. **提前迁移零收益**——无 iOS 开发计划前，把 Android 稳定运行的 UI 迁 CMP 只引入
   回归风险（本项目 UI 有大量 OEM 兼容代码，见 rules/dialog-soft-input-guard.md
   荣耀系列教训）。
3. **可迁移性底子已就绪**——渲染双路径（SoftwareCanvasBackend 纯 Kotlin 可直接复用）、
   平台接口齐备（本次根治补齐音频/崩溃/偏好/合规宿主）、触摸引擎纯 Kotlin
   （SectMapTouchEngine）。迁移时 UI 层需要替换的仅是"平台能力访问点"，业务组合逻辑
   不动。

## 迁移启动判据（iOS 立项时执行，顺序即优先级）

1. **可行性尖峰（2 周）**：最小 CMP 工程 + 渲染层接入（SoftwareCanvasBackend 直挂）+
   UnifiedGameDialog/GameButton 两个核心组件移植，评估 Canvas 性能与手势系统
2. **平台能力矩阵**：逐项核对 WindowInsets/IME/系统栏/Dialog 窗口/音频会话——
   本项目的"荣耀 X70/GT 系列键盘频闪"三件套在 iOS 的对等实现（UITextInput 系统）
3. **UI 组件分批移植**：core/ui 共享组件 → feature/game 对话框 → 主界面地图层
   （地图层可先走 Canvas 软渲，Metal 后补）
4. **失败兜底**：若尖峰评估不达标（性能/兼容），UI 层重写（SwiftUI）为后备方案，
   引擎层接口化已保证"UI 重写不影响引擎"

## 后果

- 正向：评估已闭环，迁移路径与判据明确，UI 层无需为 CMP 提前让渡设计
- 负向：iOS 立项时存在一次性尖峰验证成本（已计入判据第 1 步）
