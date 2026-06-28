# 规则：静态资源管理

**所有新增的静态图片资源必须满足两项要求：无损 WebP 格式 + 预加载注册。**

---

## 1. 无损 WebP 转换

### 1.1 格式要求

- 所有游戏图片资源（精灵图、UI 元素、建筑图、头像、地图瓦片等）**必须是 WebP 无损格式**
- 资源放置目录：`android/app/src/main/res/drawable-nodpi/`（主模块）或 `android/feature/game/src/main/res/drawable-nodpi/`（feature 模块）
- 跨模块共享的 UI 资源放 `android/core/ui/src/main/res/drawable-nodpi/`
- **禁止**直接提交 PNG/JPG 格式的游戏图片资源

### 1.2 转换工具

有现成的 Node.js 转换脚本：

```bash
# 批量转换 PNG → 无损 WebP（扫描所有资源目录，转换后自动删除原 PNG）
node scripts/convert-remaining-pngs-to-webp.mjs

# 建筑图片优化（从外部源目录取 PNG，输出 WebP 到 drawable-nodpi）
node scripts/optimize-building-images.mjs
```

转换参数（`convert-remaining-pngs-to-webp.mjs`）：
- `lossless: true` — 无损压缩
- `effort: 6` — 最高压缩率

### 1.3 构建配置

`android/app/build.gradle` 已配置：
```groovy
androidResources {
    noCompress 'webp'  // 避免对已压缩的 WebP 进行 zip 二次压缩
}
```

### 1.4 Play 商店图标例外

`android/app/src/main/ic_launcher-playstore.png` 是唯一保留 PNG 格式的文件，供 Google Play 控制台上传使用，不属于游戏内资源。

---

## 2. 预加载注册

新增静态资源必须根据资源类型在对应的注册点完成注册。注册后预加载系统会自动将其纳入对应的加载阶段。

### 2.1 注册清单

所有精灵图通过 `SpriteResRegistry` 统一注册。新增精灵图只需在 `XianxiaApplication.kt` 中调用 `SpriteResRegistry.register(category, map)` 即可，预加载系统自动发现。

| 资源类型 | 注册分类 | 预加载优先级 | 说明 |
|----------|---------|-------------|------|
| **UI 按钮/控件** | `SpriteCategory.UI` | L0 (priority=0) | 底部按钮栏、关闭按钮、加载背景等首屏可见 UI |
| **弟子头像** | `SpriteCategory.PORTRAIT` + `PortraitPool` | L0 (priority=0) | 动态命名头像 + `disciple_portrait` 兜底 |
| **装备精灵图** | `SpriteResRegistry.initialize()` — `equipmentSprites` + `allEquipmentResIds` | L1 + L2 | 旧版兼容，逐步迁移到 `ITEM` 分类 |
| **功法精灵图** | `SpriteResRegistry.initialize()` — `manualSprites`（按稀有度 1-6） | L1 + L2 | 同上 |
| **丹药精灵图** | `SpriteResRegistry.initialize()` — `pillSprites`（按稀有度 1-6） | L1 + L2 | 同上 |
| **妖兽材料精灵图** | `SpriteResRegistry.initialize()` — `materialSprites` | L2 | 同上 |
| **草药/种子/成长中** | `SpriteResRegistry.initialize()` — `herbSprites`/`seedSprites`/`growingSprites` | L2 | 同上 |
| **储物袋精灵图** | `SpriteResRegistry.initialize()` — `storageBagSprites`（按稀有度 1-6） | L2 | 同上 |
| **灵石精灵图** | `SpriteResRegistry.initialize()` — `spiritStoneSprites`（按等级） | L2 | 同上 |
| **宗门图标** | `SpriteResRegistry.initialize()` — `sectIconSprites`（按等级） | L2 | 同上 |
| **建筑精灵图** | `SpriteCategory.BUILDING` + `BuildingRegistry` | L1 (priority=1) | 自动纳入 `preloadBuildingBitmaps()` |
| **背景精灵图** | `SpriteCategory.BACKGROUND` | L1 (priority=1) | `bg_horizontal`、`dialog_box`、`map_zhongzhou` 等 |
| **妖兽精灵图** | `SpriteCategory.BEAST` | L2 (priority=2) | `tiger`、`wolf`、`snake` 等 8 种 |
| **洞穴精灵图** | `SpriteCategory.CAVE` | L2 (priority=2) | `cave_1`、`cave_2`、`cave_3` |
| **天劫试炼精灵图** | `SpriteCategory.HEAVENLY_TRIAL` | L2 (priority=2) | 岛屿、挑战背景、战斗场景等 |
| **地图资源** | `GameActivity.kt` — `MapPreloadData` 构建逻辑 | 地图预加载 | `sect_ground_map`、`decoration_grass`、`decoration_trees` |

### 2.2 注册流程（统一接口）

```
新增静态图片资源
  │
  ├─ 1. 将图片转为无损 WebP → 放入 drawable-nodpi/
  │      (如源文件是 PNG，运行 node scripts/convert-remaining-pngs-to-webp.mjs)
  │      注意：资源需同时在 feature/game 和 app 两个模块的 drawable-nodpi/ 中各放一份
  │
  ├─ 2. 在 XianxiaApplication.kt 中注册到对应的 SpriteCategory
  │      SpriteResRegistry.register(SpriteCategory.XXX, mapOf(
  │          "精灵图名称" to R.drawable.xxx
  │      ))
  │      如属于旧版类型（装备/功法/丹药等），在 initialize() 参数中注册
  │
  ├─ 3. 界面中使用统一入口显示精灵图
  │      SpriteImage(name = "精灵图名称", contentDescription = "描述")
  │      或 Canvas 中用 drawSprite(name, cache, ...)
  │
  └─ 4. 完成！预加载系统自动发现新注册的精灵图并纳入对应阶段
         不需要修改 ResourcePreloader
         不需要在界面中写 R.drawable.xxx
```

### 2.3 统一精灵图 API

项目提供两个统一的精灵图加载入口，所有界面应使用它们而非直接 `painterResource(R.drawable.xxx)`：

**Image composable 场景：**
```kotlin
SpriteImage(
    name = "tiger",                    // 在 SpriteResRegistry 中注册的名称
    contentDescription = "虎妖",
    modifier = Modifier.size(48.dp)
)
```
自动：name → SpriteResRegistry.resolve() → resId → LocalItemSpriteCache 查缓存 → painterResource 回退

**Canvas drawImage 场景：**
```kotlin
Canvas(modifier) {
    drawSprite("building_alchemy", cache, dstOffset = Offset(x, y))
}
```
仅使用预加载缓存中的 ImageBitmap，不执行回退加载。

**旧版辅助函数（仍可用，用于 ItemCard 等场景）：**
```kotlin
equipmentSpriteRes("精铁剑")    // → Int?
beastSpriteRes(0)               // → Int? (0=tiger)
caveSpriteRes(2)                // → Int? (cave_3)
backgroundRes("bg_horizontal")  // → Int?
```

### 2.4 预加载阶段说明

预加载系统根据 `SpriteCategory.priority` 自动分配加载阶段：

| 阶段 | 何时加载 | priority | 阻塞首屏 | 包含分类 |
|------|---------|----------|---------|---------|
| **阶段 1** | 游戏启动 | — | 是 | GameDataManager + ConfigLoader + ManualDatabase |
| **L0** | 阶段 1 之后 | 0 | **是** | `UI`、`PORTRAIT` |
| **L1** | 与 L0 并行 | 1 | **是** | `ITEM`、`BUILDING`、`BACKGROUND`、`MAP` |
| **L2** | 首屏渲染后 | 2 | 否（后台异步） | `BEAST`、`CAVE`、`HEAVENLY_TRIAL` |

**关键原则：首屏可见的资源必须在 priority=0 或 priority=1 的分类中注册。** 如果新增资源会出现在首屏（弟子列表、底部按钮、建筑列表、仓库物品），确保其 SpriteCategory.priority ≤ 1。

**ResourcePreloader 自动发现机制：**
- L0：读取所有 priority=0 分类的 resId，并行预加载
- L1：读取所有 priority=1 分类的 resId，并行预加载  
- L2：读取所有 priority=2 分类的 resId，后台异步加载
- 不再需要手动维护预加载列表，注册到 SpriteResRegistry 即自动纳入

---

---

## 3. 检查清单

新增静态资源时，确认以下全部完成：

- [ ] 图片已转为**无损 WebP** 格式（`lossless: true, effort: 6`）
- [ ] 图片已放入 `feature/game/src/main/res/drawable-nodpi/` **和** `app/src/main/res/drawable-nodpi/` 两个模块
- [ ] 已在 `XianxiaApplication.kt` 中通过 `SpriteResRegistry.register()` 或 `initialize()` 注册
- [ ] 使用了正确的 `SpriteCategory`（首屏可见 → priority 0/1，其余 → priority 2）
- [ ] 界面中使用 `SpriteImage("名称")` 或 `SpriteResRegistry.resolve("名称")` 显示，不使用直接 `R.drawable.xxx`
- [ ] 源 PNG 文件已删除（不提交到仓库）
- [ ] 编译通过：`cd android && ./gradlew.bat compileReleaseKotlin`

---

## 4. 自动化范围说明

以下步骤由系统**自动完成**，开发者无需手动操作：

| 自动化步骤 | 机制 | 开发者操作 |
|-----------|------|-----------|
| **预加载** | `ResourcePreloader` 通过 `SpriteResRegistry.categoryResIds()` 自动发现所有注册的精灵图 | ❌ 无需手动维护预加载列表 |
| **缓存查找** | `SpriteImage` composable 自动检查 `LocalItemSpriteCache`，命中则用预加载位图 | ❌ 无需手动写缓存逻辑 |
| **回退加载** | `SpriteImage` 缓存未命中时自动调用 `painterResource` 回退 | ❌ 无需手动写回退代码 |
| **L0/L1/L2 分配** | 根据 `SpriteCategory.priority` 自动分配到对应加载阶段 | ❌ 无需关心 `ResourcePreloader` 内部 |

以下步骤**无法自动化**（受 Android R 类编译时生成限制），开发者**必须手动完成**：

| 手动步骤 | 原因 | 不做的后果 |
|---------|------|-----------|
| **文件放入两个模块** | `feature/game` 和 `app` 的 `R.drawable` 各自独立生成，必须各有一份 WebP | 其中一个模块编译报 `Unresolved reference` |
| **注册到 SpriteResRegistry** | `R.drawable.xxx` 是编译时生成的整数常量，无法运行时自动映射到精灵图名称 | `SpriteImage("名称")` 返回空，精灵图不显示 |
| **界面用 SpriteImage** | `painterResource(R.drawable.xxx)` 绕过注册表和缓存系统 | 精灵图不被预加载、不被缓存、不被注册表管理 |

**结论：新增精灵图只需遵循 3 步手动流程（放文件 → 注册 → SpriteImage 显示），其余全部自动。**
