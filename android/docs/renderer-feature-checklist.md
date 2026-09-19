# 渲染特性清单 (Renderer Feature Checklist)

每新增渲染特性必须两端同步实现，确保 Vulkan 和 Canvas 路径行为一致。

## 命名约定

使用注释标记标明特性在两端的状态：

```kotlin
// @RenderFeature(name="camera_offset", vulkan=true, canvas=true, test=true)
```

## 特性清单

| 特性 | 描述 | Vulkan | Canvas | 测试 | 状态 |
|------|------|--------|--------|------|------|
| ground_tiling | 地面平铺绘制 | ✅ | ✅ | ✅ | 已实现 |
| far_view_ground_quad | 远景观看容量路径（整岛缩小档 `scale ≤ DECOR_ZOOM_THRESHOLD` 时地面层由逐格改整图 REPEAT quad；几何 = 世界可见域 ∩ 地图矩形，1 draw call 替代最坏 ≈16384 sprite/帧） | ✅（仅白名单设备；默认禁用） | —（Canvas 兜底独立绘制，不受本路径影响） | ✅ | **2026-09 R3.5/B12**：`FarViewGroundPolicy` 四重门（用户旗标 ∧ 图集就绪 ∧ 缩放达标 ∧ 设备白名单）；白名单默认为空 = 未验证设备恒走逐格地面（安全默认）；`NativeEngineFlag.farViewGroundQuad` 默认 false（回滚臂）；`scene_draw.h::buildMapBatch` 增 `submitGround` 回调以独立纹理提交整图 quad（修正原分支构建后丢弃的缺陷）；C++ 3 用例 + Kotlin 8 用例守卫 |
| gles_backend_isomorphic | GLES 后端与 Vulkan 同构（消费同一环境数据 + 同一判定策略，非各自维护渲染分支） | ✅ | — | ✅ | **2026-09 R3.6/B12**：`GlesRenderBackend : VulkanRenderBackend` 构造性同构（判定/提交逻辑在基类，两 GPU 后端共享）；GLES 不支持 REPEAT 纹理 ⇒ `groundTextureReady=false` ⇒ 远景整图路径**策略层自动拒绝**（无 GLES 专属分支）；Canvas 兜底零改动；`RenderBackendIsomorphismTest` 5 用例锁同构 + 降级链语义 |
| decor_overlay | 装饰叠加（草/石/树：显示尺寸按素材纵横比取小数格，锚点 = 格底边居中；草/石走地面层逐格绘制） | ✅ | ✅ | ✅ | 2026-09 立绘尺寸口径（TILE_SPRITE_W/H codegen + SpriteSizingFidelityTest） |
| decor_object_layer | 立体层装饰（树）与建筑同一画家序（按地面接触点归并，同键建筑在后，覆盖同接触点装饰；树冠可向上越出 2.29 格而不再被北侧建筑无脑压掉） | ✅ | ✅ | ✅ | 2026-09 立绘尺寸口径（gamecore/map/draw_order.h + draw_order_test + SoftwareCanvasBackendDecorLayerTest） |
| decor_overhang_range | 装饰越界绘制范围外扩（渲染遍历/可见性按 DECOR_MARGIN_COLS/ROWS 外扩——否则 chunk 缝处树冠被整块裁掉） | ✅ | ✅ | ✅ | 2026-09 立绘尺寸口径（SoftwareCanvasBackendDecorLayerTest chunk 顶行树用例） |
| building_draw | 建筑精灵绘制 | ✅ | ✅ | ✅ | 已实现 |
| camera_offset | 相机平移偏移 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| camera_zoom | 缩放 (scale) | ✅ | ✅ | ✅ | 已实现 |
| building_preview | 建造/移动预览 | ✅ | ✅ | ✅ | 已实现；**2026-09 R3.3/B11**：预览精灵与占地框改由 drawFrame 内 C++ 生成（几何经 sceneSetPreview 变化驱动导入，overlayFlags bit2/bit3 驱动；层序不变=精灵先、占地框后） |
| preview_tint | 预览精灵调色 | ✅ | ✅ | ✅ | **2026-09 R3.3/B11**：r/g/b/a 随预览几何一并导入，C++ 侧按同式（UV 收缩 kUvEpsilon）装配顶点 |
| viewport_culling | 视锥剔除 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| building_culling | 建筑视口外裁剪 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| fling_30fps | 弹射动画 30FPS | ✅ | ✅ | ✅ | 已实现 |
| fade_transition | 地图淡入动画（300ms EaseOutCubic，RenderThread 启动触发覆盖首次/重入/降级；仅地图层受 fade，预览/高亮独立不受影响） | ✅ | ✅ | ✅ | 2026-08-10 WP4 |
| heat_control_quality | 热控降质（qualityFactor + 装饰跳过） | ✅ | ✅ | ✅ | 2026-08-10 WP1 |
| decor_lod | 缩放 LOD（scale<0.6 装饰层跳过，双端同阈值；Canvas 离散档位失效，Vulkan g_scale 条件） | ✅ | ✅ | ✅ | 2026-08-10 WP5 |
| vsync_pacing | 渲染线程 vsync 帧节奏（Canvas Choreographer 对齐 + FrameDropPolicy 帧跳过；Vulkan FIFO 交换链天然对齐；失败回退 sleep 节拍） | ✅ | ✅ | ✅ | 2026-08-10 WP5 |
| building_shadow | 建筑投影阴影（半透明黑 quad + 右下偏移 0.25 格） | ✅ | ✅ | ✅ | 2026-08-10 WP3（硬边无高斯模糊，0.2 alpha 视觉补偿，已知取舍） |
| selection_highlight | 普通点击选中金色描边（动态叠加，不烘焙 chunk） | ✅ | ✅ | ✅ | 2026-08-10 WP3（RenderFlags 双端开关 + 总线脏帧防错位）；**2026-09 R3.3/B11**：Vulkan/GLES 几何改由 C++ `scene_draw.h::buildOverlayLayers` 生成（overlayFlags bit5 驱动，占地矩形/线宽/alpha 常量在 C++ 算），旧逐 rect 路径保留为回滚臂 |
| demolish_highlight | 一键拆除模式绿/红占地高亮（数据通道 RenderFrame.demolishHighlightData，与 buildingData 同序；总线脏帧跳帧防索引错位；null=跳过） | ✅ | ✅ | ✅ | 2026-08-11（从 Compose 覆盖层迁移至 native——同帧同相机，消除拖拽相位差）；**2026-09 R3.3/B11**：逐建筑矩形改由 C++ 生成（标记经 sceneSetDemolishMarkers 变化驱动导入，每建筑不再逐次跨线） |
| grid_overlay | 放置/移动模式全视口网格线（RenderFrame.gridOverlayVisible 标志驱动，范围钳制到世界边界；Canvas drawLine / Vulkan 薄 quad） | ✅ | ✅ | ✅ | 2026-08-11（从 Compose GridOverlay 迁移至 native——同帧同相机，消除拖拽相位差）；**2026-09 R3.3/B11**：逐列/逐行 rect 改由 C++ 生成并合批（消每帧最坏 258 次 drawRect 跨线，overlayFlags bit1 驱动）；**同批修复前置缺陷 A**——行范围补乘俯视 Y 压缩系数（旧 Vulkan 漏乘致视口底部缺横线、与 Canvas 不一致），三端口径由 SceneOverlayProtocolGuardTest 锁定 |
| overlay_geometry_cpp | 世界叠加层几何 C++ 单份生成（R3.3/B11：网格线/占地预览框+预览精灵/选中高亮/拆除高亮四要素，SceneStore 持状态 + drawFrame overlayFlags 位驱动；层序 选中→拆除→精灵→占地框→网格线 = 旧 Kotlin 序；常量单源 LAYOUT.overlay） | ✅ | ✅（C++ 生成；Canvas 兜底路径保持自身独立绘制 = R3.6 红线零改动） | ✅ | 2026-09 B11（C++ SceneOverlayEquivalenceTest 12 用例顶点流逐位对照 + Kotlin SceneOverlayProtocolGuardTest 5 + SceneUvTablesMirrorGuardTest 叠加层常量 30 项逐位；G4 draw call 276→3） |
| spirit_crop | 灵田作物三阶段生长动画（stage 边界 1/3、2/3 + crossfade × 全局 fade 乘算；Vulkan 瓦片层后批内追加，Canvas 不烘焙逐帧叠加；数据通道 RenderFrame.spiritCropData，null=跳过） | ✅ | ✅ | ✅ | 2026-08-10 WP6（NaN/越界双端防御；无专属 flag，数据驱动；cropBitmaps 死代码已删） |
| texture_compression | Vulkan GPU 图集 ASTC 4x4 LDR 压缩（KTX1 容器全字段校验，16MB→4MB；设备不支持/资产损坏全链回退 RGBA 视觉零差异；Canvas 保持运行时 RGBA 拼装不变） | ✅ | ➖（仅 Vulkan 路径） | ✅ | 2026-08-10 WP7（KtxLoader 校验 + AtlasManifestSyncTest 守卫 + 构建管线 build-atlas.mjs） |
| gesture_pan | 拖拽平移 | ✅ | ✅ | ✅ | 手势引擎共用 |
| gesture_tap | 点击建筑 | ✅ | ✅ | ✅ | 手势引擎共用 |
| gesture_longpress | 长按拖动 | ✅ | ✅ | ✅ | 手势引擎共用 |
| gesture_fling | 惯性滑行 | ✅ | ✅ | ✅ | 手势引擎共用 |
| shared_constants | 双端共享渲染常量 codegen 收敛（LOD 阈值/阴影常量/瓦片索引/语义建筑索引——LAYOUT 单一数据源，Kotlin SpriteAtlasDef + C++ TextureAtlas.h 双产物自动一致） | ✅ | ✅ | ✅ | 2026-08-13 批次 2（SpriteCodegenSyncTest 双端常量全等守卫）；**2026-09 R3.3/B11**：新增 LAYOUT.overlay 30 项叠加层视觉常量（颜色/不透明度/线宽），双端生成 scene_uv_tables.h 与 SpriteAtlasDef，旧路径改引用别名 ⇒ 两路共用单源（SceneUvTablesMirrorGuardTest 逐位锁） |
| render_command_bus | 渲染命令总线（单槽覆盖式建筑数据通道——命令 FIFO 双通道已按对抗性审查删除：零生产消费者，见 RenderCommandBus.kt KDoc） | ✅ | ✅ | ✅ | 2026-08-13 批次 2 + 对抗性审查修正；**2026-09 R3.4/B11**：同一「变化才跨线」语义平移到 C++ 场景更新侧 = `SceneUpdateChannel`（十路导入端口的判据与基线；稳态帧触线 0 次；RenderMetrics.sceneUpdatePushes/Frames 即 G4 每帧 JNI 遥测口径） |
| render_scale | 渲染分辨率缩放（平板/大屏省电：RenderScalePolicy 决策 → Vulkan 离屏目标 + vkCmdBlitImage 上采样 / Canvas 降采样帧缓冲 + 双线性拉伸提交；renderScale=1.0 时两端行为与现状逐位一致为回归基线；接口契约保持物理像素） | ✅ | ✅ | ✅ | 2026-08-14 平板省电 WP1（RenderScalePolicyTest + SoftwareCanvasBackendRenderScaleTest + RenderBackendContractTest 基线） |
| refresh_rate_declaration | 帧率↔刷新率联动声明（>60Hz 面板声明 {60,30} 两档省屏耗 + 升档 2s 防抖；≤60Hz 面板旧行为逐位一致；RenderFlags.refreshRateDeclaration 开关回退） | ✅ | ✅ | ✅ | 2026-08-14 平板省电 WP2（FrameRateDeclarationPolicyTest） |
| dirty_frame_skip | 脏帧跳过（静止画面跳过渲染与指标：相机/帧引用/总线/淡入/缩放五守卫；EWMA 跳帧不统计防虚高） | ✅ | ✅ | ✅ | 2026-08-14 平板省电 WP3（FrameSkipPolicyTest） |
| power_save_mode | 系统省电模式监听（ACTION_POWER_SAVE_MODE_CHANGED → fpsCap 30；与低电量 45 取 min；evaluatePowerPolicy 纯函数） | ✅ | ✅ | ✅ | 2026-08-14 平板省电 WP5（BatteryAwareControllerTest 扩展） |
| dynamic_adpf_target | ADPF 目标帧时长动态化（实际帧率 → 系统性能预算；frameDurationNs 纯函数 + renderFrameRate collect 联动） | ✅ | ✅ | ✅ | 2026-08-14 平板省电 WP4（GameEngineCoreFpsPolicyTest + ThermalMonitorTest 扩展） |
| cloud_layer | 世界顶部动态云朵（CloudLayerAnimator 渲染线程驱动：只在世界外生成/横向穿越/出界消失，速度 3 格/秒（2026-08 由 5 调低），随机类型/方向/Y/缩放（0.4~0.8，2026-08 整体缩小 50%）/透明度；实例数据快照 host.cloudData 双后端共享；绘制在建筑/作物层之上、高亮/预览/网格线之下；skipDecor 同判定降级；云活跃时 cloudDirty 阻止脏帧跳过） | ✅ | ✅ | ✅ | 2026-08-22（CloudLayerAnimatorTest + SoftwareCanvasBackendCloudTest + FrameSkipPolicyTest） |
| sky_background | 程序绘制天空渐变背景（Screen Space / Background Layer：纯 GPU/渐变绘制**四段** top→second→third→bottom，非图片纹理、无大 Bitmap、每帧零临时分配；绘制于所有世界内容之下（最底图层），Camera 平移/缩放不影响；Vulkan/GLES 走 C++ SkyBackground + RHI drawBackground（天空管线 sky.vert + sky.frag：片元内分段 smoothstep 解析渐变 + 有序抖动去色带，参数经 push-constant/uniform），Canvas 走 screen-space LinearGradient + isDither；配置化 SkyBackgroundConfig（四色/位置/强度），为天气时间系统预留接口；配置变化才重建、非每帧；skyConfig 变更经 FrameSkipPolicy.skyDirty 强制渲染一帧——静止画面也更新配色） | ✅ | ✅ | ✅ | 2026-09（SoftwareCanvasBackendTest 天空用例组 + FrameSkipPolicyTest skyDirty 守卫 + 淡入/云层 alpha 复算更新） |
| island_cliff_edge | 浮空岛崖壁地图边缘（左/右/下三侧 + 左下/右下转角；**独立纹理**而非图集——单张最大 1180×3552 超出 4096² 容量。布局合成单一权威 = C++ `gamecore/map/island_cliff.h`，输出 stride=10 `[texIdx,x,y,w,h,u0,v0,u1,v1,flags]`；右崖由**镜像位**复用左侧纹理；末块裁剪时 UV 按比例收缩不拉伸素材；角块最后绘制覆盖边环越界末块。纹理三级降级：ASTC KTX（Vulkan）→ RGBA mip 链（Vulkan）→ RGBA 单级（GLES/Canvas 位图），单张失败以 textureMask 排除该条目。z 序：天空 → 崖壁 → 地面。相机 outset 2500 使拖到地图边缘可见崖壁） | ✅ | ✅ | ✅ | 2026-09（island_cliff_test 12 用例 + EdgeKtxSyncTest 三向守卫 + SoftwareCanvasBackend 崖壁用例） |
| floating_text_pool | 浮字对象池 + 动画核心（全 C++：`scene/float_text.h`，零 Android 依赖桌面可直测。固定容量 256，实例 = 世界空间锚点 + 词条/数字资产索引（+charCount 表达多字形串）+ 样式档 + 出生时刻 + 动画参数；**池满覆盖最旧**= spawnSeq 最小者，循环缓冲语义；动画 上浮/淡出/暴击弹跳 经 `sampleFloatText()` 纯函数由时间标量驱动——**零每帧 JNI**、不查系统时钟、不分配；`shutdownRenderer` 后池纪元复位；池满/溢出遥测接 R0.3 三计数器口径） | ✅ | —（浮字状态**不经** `RenderFrame` 契约 ⇒ Canvas 兜底零改动，为 GPU 路径增强特性） | ✅ | **2026-09 R3.8/B13**：`kFloatPoolCapacity=256` / `kFloatLifetimeSeconds=1.1f`；C++ 35 用例 5 套件（池语义含池满覆盖最旧/纪元复位/参数防御五路/序号严格单调；动画含上浮单调/淡出归零/暴击弹跳/负龄钳位；批绘制含空池零 draw call；遥测三计数器） |
| floating_text_tier1_assets | Tier1 预烘焙文本资产（数字 0-9 / 拉丁字母 / 冻结固定词条 `会心/格挡/闪避/连击/暴击/破防/吸血/免疫` / 符号 `+ - . %`，共 48 项：词条 8 + 字形 40。经 `build-atlas.mjs` 图集管线烘焙（`sharp`/pango 渲染 CJK，固定字号 + **共用缩放因子**保证视觉一致）；图集 y3640..4096 段，词条行 8 格 × 329px / 字形行 40 格 × 88px；`kFloatUv[48]` 等常量双端同源生成，纳入 codegen hash 门） | ✅ | — | ✅ | **2026-09 R3.8/B13**：Kotlin `SceneUvTablesMirrorGuardTest` 追加 5 用例（UV 表 `toRawBits` 逐位 / 资产计数与索引 / 冻结词表与字形表文本含数字 0-9 必需项 / 样式档索引 / spawn 端口步长镜像）；**Tier2 动态字形明确不在本批** |
| floating_text_spawn | 浮字 spawn 通道（事件驱动低频 JNI 端口 `sceneSpawnFloatingText(jfloatArray)`——扁平 10 标量/条 `[worldX, worldY, assetIndex, charCount, styleIndex, nowSeconds, scale, riseScale, bounce, reserved]`，`kFloatSpawnStride=10` 双端镜像守卫锁定；保留位非 0 即拒（为字段扩展留位不破 ABI）。**既有 JNI 签名零变更**：动画时间用 C++ 内部固定帧步长累加器（1/60s）而非追加 drawFrame 第 9 参——规避 ABI 变更；**JNI 端口总数 渲染面 48→49 / 生产面 89→90**） | ✅ | — | ✅ | **2026-09 R3.8/B13**：端口豁免登记在位（沿 R0.2/B06/B10/B11/B12 先例）；`FLOAT_SPAWN_STRIDE` ↔ `kFloatSpawnStride` 镜像守卫 |
| floating_text_batch | 浮字批绘制（`scene_draw.h::buildFloatTextBatch`——走既有 push-constant 投影，多字形串按连续资产索引**水平排布**、单纹理段合批、UV 向内收缩防渗色、可见性剔除；**空池 = 0 draw call**（不 begin/不 submit）；层序 = **浮字在最上层**（叠加层之上），drawFrame 尾部提交，图集未就绪整层跳过。Vulkan/GLES **构造性同构**：判定/推进/提交全落共享头与基类，零 GLES 专属分支） | ✅ | — | ✅ | **2026-09 R3.8/B13**：10 用例（空池零 draw call / 单实例单纹理段 6 顶点 / 多字形连续单 draw / 20 实例仍单 draw = G4 友好 / 顶点流合 SpriteBatcher 契约 / 样式色逐实例 / 视外剔除 / 缺图集整层跳过 / UV 越界回落不越读） |
| scene_pixel_regression | 场景像素级回归集（在既有 `RecorderRenderer`（顶点流记录）**之上**加**确定性软光栅化**：顶点流 → 软光栅扫描线（整数采样 + float 重心插值 + source-over 混合）→ 像素缓冲 → **FNV-1a64 像素校验和** golden 入库（`test/golden/scene_regression_golden.txt`，22 键 640×360）；覆盖六要素场景 + 浮字四场景（出生/上浮中/淡出/池满覆盖）× 相机三档位（近 2.0/中 1.0/远 0.3）；纹理采样用程序化 32×32 棋盘渐变（无 ASTC 解码器环境）；`-ffp-contract=off` 保证确定性。**golden 变更流程**：`SCENE_GOLDEN_UPDATE=1` 显式重生，默认比对失败即红、绝不自动改写） | —（桌面测试基建，不涉运行期后端） | — | ✅ | **2026-09 R3.8/B13**（批次验收门 4 要求，补 B11/B12 残余③的桌面形态）：12 用例（六要素×三档位 / 叠加层全开×三档位 / **三档位像素互不相同** / 浮字四场景×三档位 / **浮字三帧互不相同** / **空池 = 无浮字层逐位相同** / 浮字层最上层 / golden 基线自洽）；实现要点见 `scene_pixel_regression_test.cpp` 头部 KDoc（顶点是世界坐标须自行投影；`drawnPixelCount` 活性判据；重心内侧须与 area 同号；三层提交语义差异）|

## 新增特性流程

1. 在 Vulkan 路径（`NativeBridge.cpp`/`VulkanBackend.cpp`）中实现
2. 在 Canvas 路径（`SoftwareCanvasBackend.kt` + `NativeSurfaceView.kt`）中实现
3. 在 `SoftwareCanvasBackendTest.kt` 中添加测试用例
4. 更新本清单

## 回归检测

在 CI 中运行 `./gradlew.bat :feature:game:testReleaseUnitTest`，
确保 `SoftwareCanvasBackendTest` 中 22+ 个测试全部通过。
