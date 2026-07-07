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
| decor_overlay | 装饰叠加（草/树） | ✅ | ✅ | ❌ | 已实现 |
| building_draw | 建筑精灵绘制 | ✅ | ✅ | ✅ | 已实现 |
| camera_offset | 相机平移偏移 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| camera_zoom | 缩放 (scale) | ✅ | ✅ | ❌ | 已实现 |
| building_preview | 建造/移动预览 | ✅ | ✅ | ✅ | 已实现 |
| preview_tint | 预览精灵调色 | ✅ | ✅ | ❌ | 已实现 |
| viewport_culling | 视锥剔除 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| building_culling | 建筑视口外裁剪 | ✅ | ✅ | ✅ | v4.0.45 修复 |
| fling_30fps | 弹射动画 30FPS | ✅ | ✅ | ❌ | 已实现 |
| fade_transition | 地图淡入动画 | ❌ | ❌ | ❌ | 未实现 |
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
