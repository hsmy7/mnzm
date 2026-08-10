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
| decor_overlay | 装饰叠加（草/树） | ✅ | ✅ | ✅ | 已实现 |
| building_draw | 建筑精灵绘制 | ✅ | ✅ | ✅ | 已实现 |
| camera_offset | 相机平移偏移 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| camera_zoom | 缩放 (scale) | ✅ | ✅ | ✅ | 已实现 |
| building_preview | 建造/移动预览 | ✅ | ✅ | ✅ | 已实现 |
| preview_tint | 预览精灵调色 | ✅ | ✅ | ✅ | 已实现 |
| viewport_culling | 视锥剔除 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| building_culling | 建筑视口外裁剪 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| fling_30fps | 弹射动画 30FPS | ✅ | ✅ | ✅ | 已实现 |
| fade_transition | 地图淡入动画（300ms EaseOutCubic，RenderThread 启动触发覆盖首次/重入/降级；仅地图层受 fade，预览/高亮独立不受影响） | ✅ | ✅ | ✅ | 2026-08-10 WP4 |
| heat_control_quality | 热控降质（qualityFactor + 装饰跳过） | ✅ | ✅ | ✅ | 2026-08-10 WP1 |
| decor_lod | 缩放 LOD（scale<0.6 装饰层跳过，双端同阈值；Canvas 离散档位失效，Vulkan g_scale 条件） | ✅ | ✅ | ✅ | 2026-08-10 WP5 |
| vsync_pacing | 渲染线程 vsync 帧节奏（Canvas Choreographer 对齐 + FrameDropPolicy 帧跳过；Vulkan FIFO 交换链天然对齐；失败回退 sleep 节拍） | ✅ | ✅ | ✅ | 2026-08-10 WP5 |
| building_shadow | 建筑投影阴影（半透明黑 quad + 右下偏移 0.25 格） | ✅ | ✅ | ✅ | 2026-08-10 WP3（硬边无高斯模糊，0.2 alpha 视觉补偿，已知取舍） |
| selection_highlight | 普通点击选中金色描边（动态叠加，不烘焙 chunk） | ✅ | ✅ | ✅ | 2026-08-10 WP3（RenderFlags 双端开关 + 总线脏帧防错位） |
| spirit_crop | 灵田作物三阶段生长动画（stage 边界 1/3、2/3 + crossfade × 全局 fade 乘算；Vulkan 瓦片层后批内追加，Canvas 不烘焙逐帧叠加；数据通道 RenderFrame.spiritCropData，null=跳过） | ✅ | ✅ | ✅ | 2026-08-10 WP6（NaN/越界双端防御；无专属 flag，数据驱动；cropBitmaps 死代码已删） |
| texture_compression | Vulkan GPU 图集 ASTC 4x4 LDR 压缩（KTX1 容器全字段校验，16MB→4MB；设备不支持/资产损坏全链回退 RGBA 视觉零差异；Canvas 保持运行时 RGBA 拼装不变） | ✅ | ➖（仅 Vulkan 路径） | ✅ | 2026-08-10 WP7（KtxLoader 校验 + AtlasManifestSyncTest 守卫 + 构建管线 build-atlas.mjs） |
| gesture_pan | 拖拽平移 | ✅ | ✅ | ✅ | 手势引擎共用 |
| gesture_tap | 点击建筑 | ✅ | ✅ | ✅ | 手势引擎共用 |
| gesture_longpress | 长按拖动 | ✅ | ✅ | ✅ | 手势引擎共用 |
| gesture_fling | 惯性滑行 | ✅ | ✅ | ✅ | 手势引擎共用 |

## 新增特性流程

1. 在 Vulkan 路径（`NativeBridge.cpp`/`VulkanBackend.cpp`）中实现
2. 在 Canvas 路径（`SoftwareCanvasBackend.kt` + `NativeSurfaceView.kt`）中实现
3. 在 `SoftwareCanvasBackendTest.kt` 中添加测试用例
4. 更新本清单

## 回归检测

在 CI 中运行 `./gradlew.bat :feature:game:testReleaseUnitTest`，
确保 `SoftwareCanvasBackendTest` 中 22+ 个测试全部通过。
