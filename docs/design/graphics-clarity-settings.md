# 自选清晰度（五档）功能设计方案（设置界面"自选性能"下方）

> 决策分级：**中间地带偏架构级**（新用户维度穿越设置 UI → 引擎渲染质量 → 纹理采样 → 资产生管线，需按架构级出方案，但提供最小切入路径）。
> 对齐对象：主流手游画质档位（原神/崩坏星穹铁道/王者荣耀等"流畅/标准/高清/极致"预设，及分辨率×特效分档）。
> 生成日期：2026-09-02。

---

## 一、背景与目标

### 1.1 现状（实勘）

| # | 事实 | 依据 |
|---|------|------|
| 1 | 设置界面"性能模式"（节能/均衡/性能）用 `PerformanceMode` 三档，`qualityFactor` 0.8/1.0/1.0，**与帧率绑定** | `PerformanceMode.kt` |
| 2 | 渲染质量因子 `renderingQualityFactor = min(thermal, mode.qualityFactor)`，已用于 `RenderScalePolicy` 与装饰层 LOD/关闭 | `GameEngineCore.updateRenderFrameRate` |
| 3 | 渲染缩放由 `RenderScalePolicy.computeRenderScale(gpuTier, softwarePath, area, qualityFactor)` 计算；GPU 档位经 `GpuRenderConfig.baseRenderScale`(0.6/0.8/1.0/1.0) | `RenderScalePolicy.kt`/`GpuTier.kt` |
| 4 | 性能模式持久化在 `SessionManager.performanceMode`；UI 态在 `GameViewModel._performanceMode`，引擎写入 `GameEngineCore.setPerformanceMode` | `GameViewModel.setPerformanceMode`/`SessionManager` |
| 5 | **目前没有独立的"清晰度/分辨率"用户维度**——清晰度混在"性能模式"里，玩家无法"高清晰度+低帧率"或"低清晰度+高帧率" | 对照主流可分离 |

### 1.2 需求要点

1. 新增**自选清晰度**五档（极低/低/中/高/极高），独立于性能模式（帧率）。
2. 位置：设置界面**自选性能（性能模式）正下方**（`SettingsTabContent` LazyColumn 中性能模式 item 之后）。
3. 五档对标主流预设，落到具体渲染参数。
4. 与资产管线改进联动：清晰度的"纹理质量"选项依赖地图图集 mipmap（资产管线 B.1）+ 可选各向异性。

### 1.3 成功标准

- 玩家可在设置界面独立选清晰度，立即生效（渲染缩放/纹理采样/装饰 LOD 变化）。
- 五档参数有明确档位表，可测试（纯函数档位映射 + 守卫测试）。
- 与现有热控/GPU/性能模式降级不冲突：热控/GPU 仍是"上限"，玩家清晰度只能在设备能力内选，且可被热控再压低。
- 最小侵入：不重构现有 `RenderScalePolicy` 语义，只叠加清晰度因子。

---

## 二、技术方案

### 2.1 ClarityMode 五档（`core/engine/.../render/ClarityMode.kt`）

```kotlin
enum class ClarityMode(
    val displayName: String,          // 极低/低/中/高/极高
    val description: String,          // 档位说明（展示给玩家）
    val renderScaleCap: Float,        // 目标渲染缩放上限（主清晰度旋钮）
    val qualityFactor: Float,         // 清晰度质量因子（参与装饰 LOD/关闭）
    val anisotropy: AnisotropyMode,   // 各向异性（关/2x/4x/8x）
    val mipmap: Boolean               // 是否启用 mipmap（依赖资产管线 B.1）
) {
    VERY_LOW("极低", "最低渲染 + 关纹理过滤，最省电", 0.5f, 0.40f, OFF, false),
    LOW("低", "低渲染 + 基础纹理，流畅优先", 0.6f, 0.55f, OFF, false),
    MEDIUM("中", "均衡（默认）", 0.8f, 1.00f, X2, true),
    HIGH("高", "高渲染 + 高过滤", 1.0f, 1.00f, X4, true),
    VERY_HIGH("极高", "原生渲染 + 顶级过滤", 1.0f, 1.00f, X8, true);
    companion object {
        fun fromStorage(name: String?): ClarityMode = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
```

### 2.2 档位参数表（对标主流）

| 档位 | 渲染缩放 | 质量因子 | 各向异性 | mipmap | 对标主流 |
|------|---------|---------|---------|--------|---------|
| 极低 | 0.5x | 0.40 | 关 | 关 | 主流"流畅/低"（分辨率 0.5 + 关特效） |
| 低   | 0.6x | 0.55 | 关 | 关 | 主流"标准"（中等分辨率 + 中特效） |
| 中（默认）| 0.8x | 1.00 | 2x | 开 | 主流默认"均衡/高清" |
| 高   | 1.0x | 1.00 | 4x | 开 | 主流"极致/超高" |
| 极高 | 1.0x | 1.00 | 8x | 开 | 主流顶配（原生 + 最佳过滤） |

> 设计说明：`qualityFactor`（装饰 LOD/关闭）仅 `低/极低` 降低（极低 0.4 < 0.6 阈值自动关装饰层），
> `中/高/极高` 恒 1.0，避免默认档劣化既有质量基准——清晰度的主杠杆是 `renderScaleCap`(0.5→1.0)
> + 各向异性 + mipmap。默认 = 中（0.8x 渲染缩放 + 各向异性 2x + mipmap），不改变既有质量因子。

> 说明：渲染缩放 1.0 = 原生（设备/热控可能进一步压低，见 2.3）；极高在上限处即"设备能给的最高清晰度"，配合各向异性+mipmap 与装饰全开，是真实可感知的"最清晰"。

### 2.3 与现有渲染质量模型的关系（关键设计）

现有：
```
effRenderScale = RenderScalePolicy.computeRenderScale(gpuTier, softwarePath, area, qualityFactor)
qualityFactor  = min(thermal, performanceMode.qualityFactor)
```
改为叠加清晰度（**清晰度作为用户目标上限，热控/GPU 仍是最终上限**）：
```
clarityQuality  = clarityMode.qualityFactor
effQuality      = min(thermal, performanceMode.qualityFactor, clarityMode.qualityFactor)
effRenderScale  = min(computeRenderScale(gpuTier, softwarePath, area, effQuality), clarityMode.renderScaleCap)
```

- `computeRenderScale` 加参 `clarityRenderScale: Float = 1.0f`，末尾 `min(result, clarityRenderScale)`（**含 COMPACT+Vulkan 的早退 1.0 也要被钳**——这是本设计的关键改动点：过去 COMPACT+Vulkan 恒 1.0，现在玩家选低档能被清晰度压低）。
- `decorLOD / 装饰关闭`：继续用 `effQuality < DECOR_QUALITY_THRESHOLD(0.6)`（低/极低会自动关装饰层，与热控一致）。

**正交性**：性能模式管帧率（30/60 动态），清晰度管分辨率+纹理+装饰；二者独立可组合（如"极高清晰度+节能帧率"）。

### 2.4 纹理采样运行时开关（B.1 联动，已落地 2026-09）

- 各向异性 + mipmap 影响 Vulkan sampler，需**运行时开关**：`VulkanBackend` 增 `setTextureQuality(anisotropy, mipmap)`，按 ClarityMode 重建/更新 sampler（mipmap 依赖 KTX 已带 mip——见资产管线 B.1；各向异性需设备特性 + `features.samplerAnisotropy=VK_TRUE`）。
- **已落地**：`ClarityMode.anisotropy/mipmap` 字段经 `MainGameScreen` → `NativeSurfaceView.clarityAnisotropy/clarityMipmap`（@Volatile）→ 渲染线程 `consumePendingTextureQuality` → `NativeBridge.setTextureQuality` → `VulkanBackend` 重建图集/地面采样器；设备不支持 `samplerAnisotropy` 时自动回退关闭各向异性；`ClarityMode.mipmap=true` 但 KTX 无 mip（`--no-mip` 兜底产物）时采样器依 `maxLod` 钳制到可用层级，不崩溃。

### 2.5 设置 UI

- 新增 `ClarityModeItem(clarityMode, onModeSelected)`（样式复制 `PerformanceModeItem`），插入 `SettingsTabContent` LazyColumn 的**性能模式 item 正下方**（`SettingsTab.kt` line 258 之后）。
- 五档按钮横排（极低/低/中/高/极高），选中高亮，下方描述文字。
- 默认**中**（对标主流默认均衡）+ `auto` 语义：首次进入无持久化值时用 MEDIUM。

### 2.6 状态与持久化（复用性能模式模式）

- `GameEngineCore`：`var clarityMode` + `setClarityMode(mode)`（调用 `updateRenderFrameRate()` 重算 `renderingQualityFactor`，并 publish clarity 给渲染线程）。
- `GameViewModel`：`_clarityMode = MutableStateFlow(ClarityMode.fromStorage(sessionManager.clarityMode))`；`setClarityMode(mode)` = 写引擎 + `sessionManager.clarityMode = mode.name` + 更新 UI 态。
- `SessionManager`：新增 `clarityMode` 字符串持久化（与 `performanceMode` 同款）。
- 渲染线程：`NativeSurfaceView` / `VulkanBackend` 经 `renderQualitySink` 收 `(qualityFactor, decorationsDisabled)` + 新增 clarity 的 anisotropy/mipmap 通道（或并入 renderQualitySink）。

**默认 = 中（MEDIUM）的实现**：首次进入（持久化无值）时 `ClarityMode.fromStorage(sessionManager.clarityMode)` 因 `?: MEDIUM` 回退到中档；`GameEngineCore` 初始 `clarityMode = MEDIUM`；玩家未改过则始终显示"中"（按钮默认高亮）。仅在玩家显式切换后经 `sessionManager.clarityMode = mode.name` 持久化，重启才恢复玩家档位。

**素材策略（与资产管线"全部提升"对齐）**：素材为**一套统一高分辨率版**（所有素材按源分辨率烘焙，不再 480px 降采样）；清晰度五档**只控制运行时渲染/采样**（渲染缩放、mipmap、各向异性、装饰 LOD），**不复制素材**、不按档位重烘焙。因此较高档位不增加包体/显存，仅影响 GPU 渲染负载与纹理采样质量。

---

## 三、影响范围清单

| 文件 | 变更类型 | 变更说明 |
|------|---------|---------|
| `core/engine/.../render/ClarityMode.kt` | 新增 | 五档枚举 + 档位参数 + fromStorage |
| `core/engine/.../render/RenderScalePolicy.kt` | 修改 | `computeRenderScale` 加 `clarityRenderScale` 参数，末尾 min |
| `core/engine/.../GameEngineCore.kt` | 修改 | `clarityMode` + `setClarityMode`；`updateRenderFrameRate` 并入 clarity 质量因子与 release |
| `core/engine/.../engine/SessionManager.kt`（core/data） | 修改 | 新增 `clarityMode` 持久化（与 performanceMode 同款；可能涉及 Migration 视存储方式） |
| `feature/game/.../GameViewModel.kt` | 修改 | `_clarityMode` StateFlow + `setClarityMode` |
| `feature/game/.../tabs/SettingsTab.kt` | 修改 | 新增 `ClarityModeItem`，插入性能模式 item 下方 |
| `feature/game/.../sect/NativeSurfaceView.kt` | 修改 | clarity 通道（qualityFactor/decor/anisotropy/mipmap）接入渲染线程 |
| `feature/game/.../sect/VulkanRenderBackend.kt` / `app/.../cpp/VulkanBackend.cpp` | 修改 | `setTextureQuality(anisotropy, mipmap)` 运行时 sampler 开关 |
| `app/src/main/cpp/KtxLoader.cpp/h` | 修改 | mip 解析（依赖 B.1） |
| 资产管线 B.1 | 前置 | `build-atlas.mjs`/`VulkanBackend` 生成多 mip 图集 |
| `rules/static-resources.md` / `docs/...` | 修改 | 记录清晰度档位与资产管线联动 |

**经济影响（`经济` 标签）**：清晰度为纯渲染设置，**不涉及货币/数值/发放** → 无经济影响（显式声明）。

**iOS 影响（`iOS` 标签）**：`ClarityMode` + `RenderScalePolicy` 纯 Kotlin，iOS 复用；`setTextureQuality` 的 Metal 对等 = `MTLSamplerDescriptor`（`magFilter`/`minFilter`/`mipFilter`/`maxAnisotropy`），标准支持，无平台阻碍。

---

## 四、兼容性分析

- **新增持久化字段 `clarityMode`**：若 `SessionManager` 走 SharedPreferences，无 DB Migration；若走 Room/Proto，需按 `rules/database-migration.md` 评估（**读规则后动 Entity**）——默认 SharedPreferences 方案规避 Migration。
- `RenderScalePolicy` 加**带默认值**的参数（`clarityRenderScale: Float = 1.0f`）→ 现有调用与测试**向后兼容**（默认 1.0 = 不改变行为）；仅清晰度功能传入实际值。
- `effQuality` 并入 clarity 后，低/极低会触发装饰层关闭（沿既有 `DECOR_QUALITY_THRESHOLD` 语义），**与热控行为一致，非新风险**。
- mipmap/anisotropy 开关**依赖 B.1**（KTX 带 mip + Vulkan anisotropy 特性）；设备无 anisotropy 特性时自动回退（关闭各向异性）。

---

## 五、测试方案

| 测试 | 类型 | 墙钟成本 |
|------|------|---------|
| `RenderScalePolicy` 增 clarity 参数用例（极低0.5/低0.6/中0.8/高1.0/极高1.0，含 COMPACT+Vulkan 被压低、平板面积因子、热控 qualityFactor 联动） | 单元（纯函数） | <1s |
| `ClarityMode` 档位表守卫（renderScaleCap/qualityFactor/anisotropy/mipmap 单调性 + fromStorage 回退 MEDIUM） | 守卫（枚举驱动） | <1s |
| `GameEngineCore` 清晰度接入（setClarityMode → renderingQualityFactor/装饰关闭） | 单元 | <1s |
| `SettingsTab` 清晰度 item 渲染 + 点击回调 | Robolectric | <1s |
| `SessionManager.clarityMode` 读/写/回退 | 单元 | <1s |

**墙钟预算**：全部 <10s，无 >30s 项。守卫类确定性偏差单迭代即可（无需高迭代数）。

**对抗性审查要点**：
- 清晰度与热控/GPU 上限的交互（玩家选高清晰度但设备/热控压低——最终恒 min）。
- 低/极低触发装饰层关闭是否符合预期（本文档语义与热控一致）。
- `renderScaleCap` 是否真能压低 COMPACT+Vulkan 的 1.0（关键改动点，专项测试）。
- 清晰度切换是否即时生效、渲染线程无陈旧值（StateFlow 发布 + 渲染线程读最新）。

---

## 六、风险评估与兜底

| 风险 | 缓解 |
|------|------|
| 清晰度新维度与性能模式耦合过深 | 设计正交（帧率 vs 分辨率/纹理），互不覆盖，均 min 叠加 |
| 各向异性在部分 GPU 不支持 | 设备特性守卫（`samplerAnisotropy`），不支持时该档位回退关闭各向异性 |
| mipmap 依赖 B.1 未上线时"开"无效 | ClarityMode.mipmap=true 但 KTX 无 mip → 运行时回退单 mip（沿 `uploadCompressedTexture` 现有 mip 检测），档位文案如实标注"需 B.1 后生效" |
| 低档清晰度导致画面过糊引起差评 | 档位下限 0.5 对标主流最低档；默认**中**（0.8）非极端；描述文案说明省电收益 |
| `SessionManager` 持久化字段变更 | 默认 SharedPreferences 方案，无 Migration；若走 Proto 则按 migration 规则走 |

---

## 七、未来场景推演（≥6 个月档）

| 维度 | 推演 |
|------|------|
| 规模增长 | 清晰度档位固定枚举，新增档位=枚举+表+UI 一行，线性；不影响既有 |
| 生命周期 | clarity 状态持久化；重启/重建后由 `fromStorage` 恢复默认 MEDIUM；清晰度切换即时生效，渲染线程读最新 StateFlow |
| 平台扩张 | iOS 复用 `ClarityMode`/`RenderScalePolicy`；Metal 对等 sampler；无重做 |
| 运营演进 | 清晰度不影响数值/平衡；运营无需发版（纯本地渲染设置） |
| 兼容回退 | 主要参数带默认值，默认行为=现状；如缺陷，玩家切回"中"即可，或 `fromStorage` 回退 MEDIUM |

---

## 八、技术债与偿还计划

| 债项 | 产生原因 | 偿还触发条件 |
|------|---------|-------------|
| 清晰度"装饰密度"未接入 | 装饰密度在 `SectMapTileGenerator` 地图生成期烘焙，运行时改密度需地图重生成，代价大 | 出现"低清晰度想更省装饰"的明确诉求，或地图生成期 LOD 重构时接入 |
| 各向异性/mipmap 仅作用于地图 Vulkan 渲染，Compose 通用精灵不受影响 | Compose ImageBitmap 无 GIS 压缩/anisotropy 路径；UI 精灵由资产管线 bake 分辨率控制 | 资产管线 B.2-c 或未来 UI 迁移 native 渲染时评估 |
| `setTextureQuality` 运行时重建 sampler | 需要 Vulkan 侧运行时开关，改动采样器重建路径 | **已清偿**（2026-09：B.1 多 mip KTX + `setTextureQuality` 落地，渲染线程通道接入） |

> 规则：以上为显式登记债项，均有可判断触发条件；本方案**无"现在不做、无触发"的隐藏债**。债项同步登记到 `docs/architecture.md` 待办登记表。

---

## 九、方案自检结论（design-plan-review）

- [x] 未来场景推演小节已写（≥6 个月档）
- [x] 技术债与偿还计划小节已写（三列表 + 触发条件）
- [x] 每个新抽象有当前生产消费者：`ClarityMode`（设置 UI + RenderScalePolicy + 渲染线程）
- [x] 新测试墙钟成本已核算（均 <1s）
- [x] rules/ 交叉核对：触碰 `rules/static-resources.md`（资产管线联动）、`rules/design-plan-review.md`；无经济/商业化/社交/数据库冲突（若 SessionManager 走 Proto 则按 database-migration 走）
- [x] 决策分级已声明（中间地带偏架构级 + 最小切入路径）
- [x] 影响范围清单含经济/ iOS 标签项

---

## 十、参考来源

| 来源 | 类型 | 等级 | 核心 |
|------|------|------|------|
| 原神 / 崩坏星穹铁道 画质档位（流畅/标准/高清/极致） | 官方+社区 | A | 主流预设 = 分辨率档 × 特效 × 滤波 |
| 王者荣耀 画质设置（分辨率/帧率/特效/阴影分档） | 官方 | A | 分辨率与帧率独立可调（印证本方案正交设计） |
| Unity Sprite Atlas（mipmap / anisotropy） | 官方 | S | 纹理过滤分级实践 |
| Android Texture Compression targeting | 官方 | S | 移动端纹理质量/性能平衡 |
| 项目自有 `docs/research/tile-map-industry-benchmark.md` | 内部 | S | 图集/LOD/降分辨率对标基线 |

> 说明：本方案为客户端渲染设置功能，非玩法/商业化/数据类，故参考聚焦画质档位与纹理采样（已列 S/A 级）。玩法类 20 条硬配额不适用于本类。

---

## 附：资产管线方案 3 决策点确认（全部落地）

| 上轮决策点 | 结论 | 落地说明 |
|-----------|------|---------|
| #1 小物件清晰度是否升档 | **按推荐**：源码保持高分辨率，运行时烘焙默认 480px，**通过自动化管线随时可按需重导**（不一次性改尺寸） | 清晰度运行时**不重烘焙资产**，避免多密度打包；资产管线保留升档能力 |
| #2 mapping 后填 ~600 条工作量 | **按推荐**：先建机制 + 脚本生成骨架 + 分阶段人工补齐 + 守卫先对已映射子集开放，最终闭环 | 见 `docs/design/art-asset-pipeline-improvement.md` |
| #3 B.2 大图内存优化 | **按推荐**：`inSampleSize` 上限 + LRU 缓存，做成可配置开关（低端设备默认开） | 独立于清晰度，随资产管线 B.2 落地 |

> 资产管线 A（映射自动化）+ B.1（mipmap）+ B.2（大图内存）全部纳入实施；清晰度功能的 mipmap/各向异性开关依赖 B.1 前置完成。
