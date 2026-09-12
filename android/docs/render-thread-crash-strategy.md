# RenderThread 崩溃策略：三层方案

> 本文档记录 `libhwui.so` RenderThread SIGSEGV(SEGV_MAPERR) 崩溃的根因分析和三层防御方案。
> 创建: 2026-07-04 | 更新: 2026-07-04

---

## 问题

在 Android 15 设备上，`libhwui.so` 的 RenderThread 出现 SIGSEGV(SEGV_MAPERR) 崩溃。
已确认的受影响的设备：

| 设备 | 系统 | ROM | GPU 芯片 |
|------|------|-----|---------|
| 联想 TB320FC | Android 15 | ZUXOS 1.1.350 | Mali（展锐/联发科） |
| vivo V2232A | Android 15 | OriginOS+6 | Mali（天玑） |
| 小米 23113RKC6C | Android 15 | MIUI/澎湃OS | Mali |

## 根因

**Android 15 Vulkan 默认渲染 + 国产 ROM Mali GPU 驱动缺陷 + TapTap 沙箱 Hook 层** 三重叠加。

1. Android 15 起 HWUI 默认使用 **SkiaVK**（Vulkan 后端）
2. 中国厂商定制 ROM 的 Mali GPU Vulkan 驱动质量不足，指针操作存在竞态条件
3. TapTap 沙箱（TapSandbox）在 GPU 调用链上增加额外 Hook 层，放大了驱动缺陷
4. 游戏内 3000+ 弟子精灵图增加了 GPU 纹理负载，提高了触发概率

## 三层防御方案

### 第一层：快速止血（已实施 ✅）

| 措施 | 文件 | 说明 |
|------|------|------|
| `android:hardwareAccelerated` 安全主题 | `res/values/themes.xml` | 定义 `Theme.XianxiaSect.GameSafe`（禁用硬件加速） |
| HWUI 渲染后端提示 | `AndroidManifest.xml` | `<meta-data android:name="android.graphics.renderer" android:value="skiagl" />` |
| 崩溃自愈引擎 | `CrashRecoveryEngine.kt` | 追踪连续崩溃，自动进入安全模式 |
| 设备检测策略 | `VulkanPolicy.kt` | 按 SoC/厂商/机型分级设备，给出渲染建议 |

**生效路径**：
```
AndroidManifest 的 android.graphics.renderer=skiagl
  → 系统尝试使用 OpenGL 后端而非 Vulkan（如果系统支持此提示）
  
CrashRecoveryEngine 检测到连续 N 次崩溃
  → GameActivity 在 super.onCreate() 前切换安全主题
  → 禁用硬件加速，使用软件渲染 → 绕过 Vulkan 路径 → 不崩溃
```

### 第二层：净化核心层（待实施 ⏳）

将 `core/domain` 和 `core/engine` 中的 Android 依赖抽离，为跨平台铺路。

**目标**：

```
core/domain     → 纯 Kotlin 模块，零 Android 依赖
core/engine     → 拆出 core/engine-core（纯逻辑子模块）
core/engine-core→ JVM 上编译 + 测试
core/data       → 保留 Android 依赖（Room 是 Android 专属）
core/ui         → 保留 Compose
feature/game    → 保留 Android
```

**验证标准**：
- `./gradlew :core:domain:compileKotlin` 通过（不依赖 Android SDK）
- 核心算法（修炼、战斗、生产）在 JVM 测试中可运行
- 所有 Room/Hilt/Protobuf 依赖集中在 `core/data` 和 `core/engine` 的 Android 壳层

### 第三层：跨平台决策（一年后评估 🔭）

取决于市场数据和 DAU 规模：

| 条件 | 选项 | 工作量 |
|------|------|--------|
| DAU < 5000，仅国内 TapTap | **继续 Android 独占** | 0 |
| DAU 5000+，要上 iOS | **KMP core + SwiftUI 壳** | 3-6 个月 |
| DAU 50000+，多平台 | **Compose Multiplatform** | 6-12 个月 |

---

## 架构决策记录

### ADR-2026-001: 不自研引擎

**上下文**：考虑了跨平台需求时是否从零自研游戏引擎。

**决策**：不自研引擎。

**理由**：
- 游戏是 2D 宗门经营模拟，无 3D/复杂特效需求，不榨取 GPU 性能
- 单人/极小团队，从零自研引擎需 2 年以上，ROI 极低
- 行业数据：Steam 新游戏 ~80% 用 Unity/Unreal，独立游戏自研引擎且成功的案例极少
- 已有可用的跨平台方案：Kotlin Multiplatform、Compose Multiplatform

### ADR-2026-002: 不迁移 SurfaceView

**上下文**：为了修复 libhwui.so 崩溃，考虑将游戏渲染从 Compose Canvas 迁移到 SurfaceView。

**决策**：不迁移。

**理由**：
- 迁移 SurfaceView = 重写所有 Compose Canvas 代码（地图、精灵、对话框）
- 估算工作量 2 个月以上，只解决一个影响 1% 设备的崩溃
- 第二层净化 + 安全主题方案 2 天即可解决问题
- 游戏渲染特性（2D 精灵、UI 密集）与 Compose Canvas 设计场景契合

### ADR-2026-003: 硬件加速的安全模式选择

**上下文**：需要在问题设备上避免 Vulkan 渲染路径。

**决策**：采用三层降级路径。

**理由**：
- **第一选择**：`android.graphics.renderer=skiagl` 元数据提示（零性能损失）
- **第二选择**：`CrashRecoveryEngine` 安全模式 + 软件渲染（性能降低，但稳定）
- **第三选择**：用户设备更新或 Android 版本升级后，自动退出安全模式

---

## 关联文件

| 文件 | 作用 |
|------|------|
| `CrashRecoveryEngine.kt` | 崩溃计数 + 自愈 |
| `VulkanPolicy.kt` | 设备分级 + 渲染策略 |
| `CrashHandler.kt` | Java 异常捕获（集成 CrashRecoveryEngine） |
| `AndroidManifest.xml` | HWUI 渲染后端提示 |
| `res/values/themes.xml` | 安全模式主题 |

## 参考来源

详见 [memory/android-renderthread-crash-research.md](../../../.claude/projects/C--Mnzm-XianxiaSectNative/memory/android-renderthread-crash-research.md)
