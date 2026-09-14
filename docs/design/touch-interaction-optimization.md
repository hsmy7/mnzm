# 宗门地图/世界地图触控交互优化方案（已实施）

*生成: 2026-08-30 | 状态: ✅ 已实施并提交（`5b5ece6b`）| 用途: 触控交互设计定案与技术债登记*

---

## ⚠️ 变更记录（2026-09）：A 组"点击命中宽容"部分回退

> 本方案 A 组中的 **hit slop 命中外扩** 与 **最近建筑兜底** 已于 2026-09 移除（玩家反馈"点空白处自动选中附近建筑"）。tap/长按/拆除/触控回调查找改为 **精确格命中** `BuildingSpatialIndex.findBuildingAt`；`HitSlopPolicy`、`findNearestBuilding`/`findBuildingAtRect`/`queryRect` 及 `nearestFallbackMaxDp`/`minHitTargetDp` 配置删除。删除详因见 `CHANGELOG.md` 4.01.12 本次条目。
>
> A 组中**保留**的：tap 双点判定（`onTap(downX,downY,upX,upY)` 签名）、`worldToGrid`(roundToInt) 命中统一、拆除精确格优先语义。B/C/D/E 组不受影响。

---


## 一、背景与目标

### 需求要点
1. **点击建筑不准确** — 明明点到建筑却没选中，小建筑（灵田等 1×1）尤其明显
2. **拖动建筑时地图格（地图）跟着动** — 拖建筑时整张地图/网格被误平移
3. **拖动建筑不够流畅** — 预览跟随手指有卡顿/延迟
4. **拖动视角不够流畅** — 相机平移与惯性滑行手感差
5. **世界地图同类问题一并解决**（用户追加指令）

### 成功标准（已达成）
- 1×1 小建筑点击命中面积 ≥ 40dp；灵田等小建筑不再"点不中"
- 拖动建筑不再出现无意的地图平移；确需的边缘滚屏符合 CoC 手感（建筑始终跟随手指、速度与设备刷新率无关）
- 建筑拖动预览在软件渲染路径下达渲染帧率响应（原 33ms 门控限 30fps）
- 相机拖动与惯性滑行在 60Hz/120Hz 设备上手感一致、无明显卡顿
- 世界地图补惯性滑行 + 标记最小命中面积

### 与 C++ 迁移的关系（用户确认的决策前提）
本方案与进行中的 C++ 迁移**正交，独立实施**：
- `docs/cpp-engine.md` §1 终态决策：Kotlin 终态保留"输入桥"；§5.2 表"Kotlin 保留（终态）= UI/Compose + 平台能力 + 存档编码 + 输入桥"；§7.1 批 8-4"~200 非 ActionId 操作终态属 Kotlin 输入桥，不迁移"
- C++ 迁移批次 0–13（结算/战斗/生产/探索/内政/经济/存储/道路合成/RHI）**不覆盖触控/手势/命中检测**；`SectMapTouchEngine` KDoc 引用的 "GameNative C++ StatefulTouchHandler" 仅为设计参考（C++ 侧无此文件）
- 本方案只改：① 纯 Kotlin 输入核心（`:core:engine/touch/`、`BuildingSpatialIndex`、`GridSnapHelper`，零 Android 依赖、iOS 可复用）；② Compose 侧接线；③ 渲染帧合成点。按 `rules/cpp-priority.md` 边界规则 3（纯 UI/外围改动不受 C++ 约束）用 Kotlin 实现符合规则
- "相机/预览快通道"在 renderTick（Kotlin 侧）合成最新输入后交 C++/Canvas 后端——即未来 C++ RHI 每帧消费输入的接缝契约，方向一致、不返工

---

## 二、根因分析（因果链，全部经代码验证）

| 问题 | 根因 | 证据 |
|------|------|------|
| 点击小建筑不准确 | 命中区 = 单格 32 世界像素 ≈ 15dp（默认缩放 1.326、420dpi），远低于 44pt/48dp；tap 单格 floor 命中、无外扩/最近兜底、仅按下点判定 | `GameConfig.kt:995`、`SectCameraState.kt:111-117`、`BuildingSpatialIndex.kt`、`SectMapTouchEngine.kt:373`（旧） |
| 拖建筑时地图乱动 | BuildingDrag 每 MOVE 无条件边缘平移；`0.016f` 硬编码在 120Hz 设备约 2 倍速；建筑不随手指补偿 | `SectMapTouchEngine.kt:318-322`（旧）、`EdgePanDetector.kt` |
| 拖建筑不流畅 | 软件路径预览走 RenderFrame 33ms 门控（30fps），相对 60fps 相机步进；非编辑模式需 200ms 长按拾起 | `SectMapViewport.kt:138-140`、`TouchEngineConfig.kt:27`（旧） |
| 拖视角不流畅 | fling 固定 33ms（30fps）；相机经 Compose 重组管线延迟 1-2 帧；MOVE 历史采样被丢弃；`drawCrops` 每帧 HashSet | `FlingPhysics.kt:88-89`（旧）、`NativeSurfaceView.kt:1413-1434`（旧）、`SoftwareCanvasBackend.kt:1124`（旧） |

---

## 三、技术方案（已实施，语言归属逐项标注）

> 触控/命中/fling 属"输入桥"，终态 Kotlin（`docs/cpp-engine.md` §1/§5.2）；零引擎核心逻辑变更。

### A 点击命中优化
- 新增 `HitSlopPolicy`（core/engine/touch，纯 Kotlin）：命中区按 40dp 最小触控目标外扩，`expandCells = ceil(minHitPx / (tileSize × scale))`
- `BuildingSpatialIndex` 新增 `queryRect`/`findBuildingAtRect`/`findNearestBuilding`（最近兜底，距离并列按绘制顺序决胜）；`findBuildingAt` 抽取 `pickTopmost` 共用决胜规则
- tap 双点判定：`TouchEngineCallbacks.onTap(downX, downY, upX, upY)` 新增默认实现（委托旧签名，向后兼容）；按下/抬起点任一做外扩命中，未中则最近兜底（半径 32dp）
- 命中统一 `GridSnapHelper.worldToGrid`（roundToInt），消除 floor 偏差；`selectedBuildingGrid` 记录命中建筑占地格
- 拆除模式：精确格优先（保道路删除语义）→ 道路删除 → 外扩兜底

### B 拖动建筑时地图不乱动
- 边缘自动平移重做：进入边缘区（100px）驻留 ≥150ms（16ms 节拍计数，虚拟/真实时间一致可测）才激活；固定 60fps 节拍平移（与事件率解耦，120Hz 不翻倍）；激活时同步 `onBuildingDragUpdate(panDx/scale, panDy/scale)` 补偿——建筑保持手指下（CoC 手感）
- 拖拽起始 `movingId` 总线排除前置到 `state.movingBuilding` 写入前（压缩双渲染窗口）

### C 拖动建筑流畅
- **预览独立快通道**：`FastPreviewChannel` 独立类（`@Volatile state + version`，快照先写版本后写），触控回调直接 `set(snapshot)`，渲染线程 `renderTick` 按版本合成进帧（`mergeFastPreviewInto` 纯函数，双后端零改动）——拖拽预览不经 Compose 重组/33ms 门控
- 快通道清理时机：**编辑模式退出**（确认/取消/切 Tab，`MainGameScreen` LaunchedEffect 统一 `set(null)`），不在 `onBuildingDragEnd` 清理——避免 33ms 门控窗口内预览位置回跳
- `drawCrops` 每帧 `HashSet` 改成员复用（拖视角 GC 抖动）
- 按下即拾起（`pickUpBuildingOnDown=true`，CoC 手感）：Down 在建筑上立即进入 BuildingDrag，`onLongPress` 延迟到首次移动（按下点定位）或驻留超时触发；`buildingDragMoved` 保证位移 ≤slop 快速抬起仍算 tap

### D 视角流畅
- fling 60fps 节拍（`FlingPhysics.DEFAULT_FRAME_INTERVAL_MS` 33→16）+ 固定 dt（确定性可测，与事件率解耦）
- 相机直接写通道：`onPanCamera`/`onPinchZoom` 立即 `setCamera` 到渲染线程（`pushCameraDirect`），不等 Compose 重组，减 1-2 帧延迟
- `toTouchData` 展开 MOVE 历史采样（`getHistorySize`，事件 batch 不丢中间位置、速度追踪更准）

### E 世界地图
- `WorldMapScreen` 补惯性滑行（`FlingPhysics` + `CustomVelocityTracker`，60fps 固定节拍，`PointerInputScope` 扩展函数承载手势）
- `SectMarker` 命中面积下限 40dp（外层 Box 承载点击，视觉盒居中）

---

## 四、影响范围清单（已实施）

| 文件路径 | 语言/模块 | 变更类型 | 说明 |
|---------|----------|---------|------|
| `core/engine/.../touch/HitSlopPolicy.kt` | Kotlin / core:engine | 新增 | 命中外扩策略 |
| `core/engine/.../touch/TouchEngineConfig.kt` | Kotlin / core:engine | 修改 | 新增 minHitTargetDp/nearestFallbackMaxDp/edgePanDwellMs/pickUpBuildingOnDown |
| `core/engine/.../touch/TouchEngineCallbacks.kt` | Kotlin / core:engine | 修改 | onTap 双点默认方法 |
| `core/engine/.../touch/SectMapTouchEngine.kt` | Kotlin / core:engine | 修改 | 拾起/tap 双点/边缘平移/fling 60fps |
| `core/engine/.../touch/FlingPhysics.kt` | Kotlin / core:engine | 修改 | 节拍 33→16 |
| `core/engine/.../util/BuildingSpatialIndex.kt` | Kotlin / core:engine | 修改 | queryRect/findNearest/pickTopmost |
| `feature/game/.../MainGameScreenGestures.kt` | Kotlin / feature:game | 修改 | 回调装配/拆除语义 |
| `feature/game/.../MainGameScreenTouchHelpers.kt` | Kotlin / feature:game | 新增 | 命中宽容 + 预览快照计算 |
| `feature/game/.../sect/FastPreviewChannel.kt` | Kotlin / feature:game | 新增 | 预览快通道 |
| `feature/game/.../sect/NativeSurfaceView.kt` | Kotlin / feature:game | 修改 | 相机直接写/历史采样/快通道消费 |
| `feature/game/.../SectMapViewport.kt` | Kotlin / feature:game | 无（未改） | 快通道在 renderTick 合成，门控语义不变 |
| `feature/game/.../sect/SoftwareCanvasBackend.kt` | Kotlin / feature:game | 修改 | drawCrops HashSet 复用 |
| `feature/game/.../sect/VulkanRenderBackend.kt` | Kotlin/C++ 边界 | 无（零改动） | 后端只读 frame |
| `feature/game/.../map/WorldMapScreen.kt` | Kotlin / feature:game | 修改 | fling 惯性滑行 |
| `feature/game/.../map/markers/SectMarker.kt`、`MapStyle.kt` | Kotlin / feature:game | 修改 | 命中面积下限 |
| 测试（HitSlopPolicyTest/FlingPhysicsTest/SectMapTouchEngineTest/BuildingSpatialIndexTest/NativeSurfaceViewTest） | Kotlin | 新增/修改 | 全量覆盖 |
| `CHANGELOG.md`、`changelog_entries.json` | 文档 | 修改 | 双 Changelog 更新 |

**兼容性**：无存档/Entity/序列化变更（无 Migration）；`onTap` 为新增默认方法（旧实现/Fake 零改动）；新配置带默认值；行为可经 `pickUpBuildingOnDown=false` 等回退。

---

## 五、测试方案（已实施）

- `HitSlopPolicyTest`：dp→px 换算、三档缩放外扩达标、非法输入防御
- `FlingPhysicsTest`：16ms 节拍、线性减速时长、位移梯形积分守恒、阈值归零、钳制
- `SectMapTouchEngineTest`（48 用例）：拾起/双点 tap/边缘驻留与补偿/fling 60fps/行为回退
- `BuildingSpatialIndexTest`：queryRect 去重、矩形命中决胜、最近兜底半径/等距决胜
- `NativeSurfaceViewTest`：快通道写入/版本/合成纯函数（保留 tileData 引用）、MockK MotionEvent 历史采样展开（单指/双指）

验证：`compileReleaseKotlin` + `testReleaseUnitTest`（串行）全绿 + `lintRelease` 全绿 + 全模块 `detekt` 全绿（无新增违规）。

---

## 六、风险评估与兜底（已实施）

| 风险 | 兜底 |
|------|------|
| 快通道与 Compose 帧预览字段竞争 | renderTick 以版本号为准；快照引用原子写（单 64 位引用）；清理延迟到编辑模式退出 |
| 外扩命中误选相邻建筑 | 外扩矩形内保留绘制顺序决胜；最近兜底半径封顶 32dp |
| 按下即拾起误触拖拽 | `buildingDragMoved` 保 tap；`pickUpBuildingOnDown=false` 可回退 |
| fling 16ms 节拍低端机 | dt 固定 + 既有 EWMA 能力帧率天然限速 |
| 历史采样放大事件量 | 仅 MOVE 且每事件条数有限，成本可忽略 |

---

## 七、未来场景推演（≥6 个月）

1. **规模增长**：外扩命中/最近兜底保持 O(1)；预览快通道不随建筑数退化
2. **平台扩张（iOS）**：触控/命中/fling 全在 `:core:engine` 纯 Kotlin，iOS `UITouch → TouchData` 复用现有引擎与测试；快通道为 View 层字段，iOS Metal 宿主按同一"渲染前合成最新预览/相机"契约实现
3. **C++ 迁移（进行中）**：零引擎核心逻辑变更；renderTick 快通道合成点即未来 C++ RHI 每帧消费输入的接缝契约
4. **运营演进**：新建筑类型自动获得外扩命中（策略由 registry 尺寸驱动）；新编辑模式复用 BuildingDrag 状态机
5. **兼容回退**：行为参数集中在 `TouchEngineConfig`，线上问题配置回退无需发版

---

## 八、技术债与偿还计划

| 现在不全做的决定 | 说明 | 偿还触发条件 |
|----------------|------|-------------|
| **预览 UV 数组预分配复用（原计划 C2 项）** | 快通道已解决渲染侧预览响应（实质目标，软件路径预览达渲染帧率）；Compose 侧 `computeMapPreview` 残留的 `floatArrayOf(4)` 分配约 2.4KB/s（60 次/秒 × ~40B），与既存 `RenderFrame.copy`（门控 33ms）同量级，GC 噪声可忽略；复用数组有共享可变对象别名竞争风险（渲染线程可能未读完上一帧 UV）——拿正确性换微优化不划算。**注：快通道未消除 Compose 侧该分配（拖拽期间重组仍在跑，仅渲染端优先消费快通道）；此前报告"Compose 侧预览只在放置进入/确认时低频推送"表述不准确，以此表为准** | 真机 per-frame 分配剖析（Studio Profiler）显示拖拽期间主线程分配显著影响帧时间 → 实施零拷贝路径（UV 拆为 4 标量进快照，彻底移除 `floatArrayOf`） |
| 渲染线程相机插值（输入采样率 > 渲染率时画面更顺滑） | 相机直接写通道已减 1-2 帧延迟；低端机渲染率受限时可选 | 真机实测拖动仍有 ≥5ms 抖动报告 → 渲染线程对 camera 采样做帧间线性插值（与作物层 `currentAlpha` 插值同模式） |
| 双阶段缩放即时反馈（Janix 方案） | 当前双指缩放直接重渲染，无中间缓冲 | 宗门地图引入高成本渲染特性后评估 |

---

## 九、调研参考来源清单（≥20 条）

**S 级（官方文档/权威规范，持续更新）：**
1. Apple HIG — Buttons（触控目标 ≥44×44pt）：https://developer.apple.com/design/human-interface-guidelines/buttons
2. Apple HIG — Accessibility：https://developer.apple.com/design/human-interface-guidelines/accessibility
3. Material Design 3 — Accessibility basics（触控目标 ≥48dp）：https://m3.material.io/foundations/accessible-design/accessibility-basics
4. Google 无障碍帮助 — 触摸目标尺寸：https://support.google.com/accessibility/android/answer/7101858
5. Android Developers — 在 ViewGroup 中管理触摸事件（touch slop）：https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup
6. Android Developers — MotionEvent（getHistorySize/历史采样）：https://developer.android.com/reference/android/view/MotionEvent
7. Android Developers — ViewConfiguration.scaledTouchSlop：https://developer.android.com/reference/android/view/ViewConfiguration
8. Android Developers — View.requestUnbufferedDispatch：https://developer.android.com/reference/android/view/View
9. Android Developers — Compose gestures 包：https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/package-summary
10. Unity Docs — Touch / Input.GetTouch（Unity 6.5，2026 构建）：https://docs.unity3d.com/6000.5/Documentation/ScriptReference/Touch.html
11. Flutter API — BaseTapGestureRecognizer：https://api.flutter.dev/flutter/gestures/BaseTapGestureRecognizer-class.html
12. Godot Docs — InputEventScreenDrag：https://docs.godotengine.org/en/stable/classes/class_inputeventscreendrag.html
13. AOSP — Touch slop 定义（frameworks/base）：https://android.googlesource.com/platform/frameworks/base/

**A 级（头部产品/知名团队）：**
14. GameDeveloper — Looking back on 10 years of Clash of Clans：https://www.gamedeveloper.com/game-platforms/looking-back-on-10-years-of-clash-of-clans
15. 腾讯云开发者（Front_Yue）— Unity 移动端相机控制系统详解（2025-04-20）：https://cloud.tencent.com/developer/article/2515078
16. 闲鱼团队 — Flutter 滑动体验优化实践（阿里团队复盘）：https://heapdump.cn/article/3764435
17. Godot GitHub Issue #120322 — InputEventScreenDrag 在 Android 上事件间隔过长（2024-2025）：https://github.com/godotengine/godot/issues/120322
18. Godot GitHub Issue #93115 — 相机平滑错误使用 deltaTime（2024）：https://github.com/godotengine/godot/issues/93115
19. Unity Discussions — Camera follow with delay without jitter（2024-10）：https://discussions.unity.com/t/camera-follow-player-with-delay-without-jitter/1533546
20. Unity Discussions — Input stutters on Android：https://discussions.unity.com/t/input-stutters-android/927902

**B 级（高质量社区）：**
21. OSCHINA — VelocityTracker 原理与滑动冲突解决方案：https://my.oschina.net/emacs_7998085/blog/19372379
22. Unity Discussions — Click/drag map view so point under mouse remains under mouse：https://discussions.unity.com/t/click-drag-map-view-so-that-point-under-mouse-remains-under-mouse/763291
23. GitHub — 2D-RTS-Camera-Movement：https://github.com/yasib48/2D-RTS-Camera-Movement
24. Meta Community — Mobile Controls Design 101: Forgiveness, Feedback & Feel：https://communityforums.atmeta.com/t5/Unreal-Engine-Development/Mobile-Controls-Design-101-Forgiveness-Feedback-amp-Feel/td-p/1028812
25. GameRes — Cocos2d-x 类 COC 手游与 RTS 编程实践（补充参考，2014）：https://www.gameres.com/286912.html

> 说明：S/A 级来源 13+ 条（≥12 达标）；官方文档持续更新视为当前有效；闲鱼/GameRes 为经典补充参考，仅佐证手感曲线与交互惯例。
