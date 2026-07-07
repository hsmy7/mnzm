# 加载阶段后台任务架构

## 概览

游戏启动/读档时的加载阶段统一管理后台任务，分为 **7 个独立模块**，在加载界面（`LoadingScreen`）期间并行执行。

```
加载阶段流程（v4.0.42+）：

进度 0%─15%   引擎初始化: GameDataManager + ConfigLoader + ManualDatabase
                     + FontPreloader (Typeface 加载)
                     + AudioEngine.init() (SoundPool 创建)

进度 15%─25%  存档加载: Room 读取 → 反序列化 → loadData(15领域) → 建筑修正

进度 25%─35%  存档校验: SaveValidator (6项检查) + CRC32 + 备份回退 **[新增]**

进度 35%─50%  数据快照: DiscipleSnapshotCache.prewarm() (O(n)遍历)

进度 50%─60%  资源预载 Phase1: Config + ManualDB + Audio SFX 预解压

进度 60%─75%  资源预载 Phase2: L0(头像UI) + L1(物品) 精灵解码
                     + AtlasPacker 图集打包 (L1小图合并)
                     + Audio BGM 预加载

进度 75%─85%  Vulkan 预热: prewarmDevice(VkDevice+ShaderModule)
                     + 地图纹理 ByteBuffer 预构建 (并行async)

进度 85%─90%  游戏循环启动: startGameLoop + isGameStarted

进度 90%─100% L2 后台异步加载: 剩余精灵图
```

## 模块架构

### C: UI 预组合增强

**文件：** `LoadingScreen.kt`, `SaveLoadModels.kt`, BaselineProfile 生成器

所有 LazyColumn/LazyVerticalGrid 统一添加 `contentType` 参数（37文件）。关键列表添加 `beyondViewportItemCount(3)`。Baseline Profile 覆盖弟子/仓库/建造/设置面板交互路径。

### E: 弟子属性快照

**新建：**
- `core/domain/.../model/DiscipleSnapshot.kt` — 快照数据类，从 `DiscipleTables` 直接构建
- `core/engine/.../disciple/DiscipleSnapshotCache.kt` — 加载阶段预热，O(1) Map 查询

**集成点：** `SaveLoadViewModel` 三个加载路径（新游戏/读档/读档fromSlot）均调用 `prewarm()`

### F: 存档完整性校验

**新建：**
- `core/data/.../integrity/SaveValidator.kt` — 6项检查 + 自动修复
- `core/data/.../integrity/SaveValidatorTest.kt` — 30个单元测试

**校验项：** 名称空值 → 默认值修复 | 日期越界 → 截断 | 修为超限 → 截断 | 装备孤立引用 → 清理 | 建筑引用一致性 | 年龄超寿命 → 截断

### D: Compose 精灵图集约

**新建：**
- `core/ui/.../components/AtlasPacker.kt` — shelf packing 算法，1024×1024 图集
- `core/ui/.../components/AtlasSpriteImage.kt` — 图集渲染 Composable，带回退

**集成链路：** `ResourcePreloader` → `PreloadResult.itemAtlas` → `LocalAtlasCache` (CompositionLocal)

### G: 地图预加载并行化

**修改：** `GameActivity.kt` LaunchedEffect 中背景色/道路/居住区/装饰物改为 4 路 `async` 并行

### B: 字体预渲染

**新建：** `core/ui/.../util/FontPreloader.kt` — Typeface 预初始化
**修改：** `core/ui/theme/Typography.kt` — 自定义字体引用，优雅回退到 SansSerif

### A: 音频引擎

**新建：**
- `core/engine/.../audio/AudioEngine.kt` — SoundPool(8路) + MediaPlayer
- `core/engine/.../audio/AudioConfig.kt` — 音量/静音管理
- `core/engine/.../audio/AudioPreloader.kt` — 预加载扩展点
- `app/.../di/AudioModule.kt` — Hilt 绑定

## 关键文件索引

| 模块 | 关键文件 |
|------|---------|
| UI 预组合 | `LoadingScreen.kt`, `BaselineProfileGenerator.kt` |
| 弟子快照 | `DiscipleSnapshot.kt`, `DiscipleSnapshotCache.kt` |
| 存档校验 | `SaveValidator.kt`, `StorageEngine.kt` |
| 图集约 | `AtlasPacker.kt`, `AtlasSpriteImage.kt`, `ResourcePreloader.kt` |
| 地图并行 | `GameActivity.kt` |
| 字体 | `FontPreloader.kt`, `Typography.kt` |
| 音频 | `AudioEngine.kt`, `AudioPreloader.kt`, `AudioModule.kt` |
