# 天道试炼活动界面改版计划

## 摘要
将天道试炼活动界面的关卡展示方式从"每关一张小图+按钮"改为"一张全景关卡图占满内容区，图中包含8个岛屿代表8关，可左右滚动查看，岛屿下方显示关卡文本按钮"。

## 当前状态分析

### 相关文件
1. **[HeavenlyTrialPanel.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/HeavenlyTrialPanel.kt)** — 天道试炼主面板，当前实现为横向滚动的Row，每关显示一张120x100dp的小图(heavenly_trial_map.png)和一个按钮。
2. **[ActivityDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/ActivityDialog.kt)** — 活动对话框，包含左侧活动列表和右侧内容区(权重0.8)，HeavenlyTrialPanel渲染在右侧内容区中。
3. **[HeavenlyTrialConfig.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/config/HeavenlyTrialConfig.kt)** — 配置8关数据(levelCount=8)。
4. **资源文件**: `heavenly_trial_map.png` 已存在，位于 `android/feature/game/src/main/res/drawable-nodpi/`。

### 当前问题
- 每关重复使用同一张小图 `heavenly_trial_map`，没有利用图中8个岛屿的设计。
- 图片尺寸固定为120x100dp，没有占满内容区。
- 没有体现"一张大图、8个岛屿、可左右滚动"的需求。

## 需求确认（经用户确认）
- 使用**一张**关卡图 `heavenly_trial_map.png`，图中有8个岛屿，从左到右对应第1关到第8关。
- 关卡图要**占满内容区**（ActivityDialog右侧内容区权重0.8的部分）。
- 内容区要**可左右滚动**，以便查看图右侧的岛屿。
- 每个岛屿**下方**显示关卡文本按钮（如"第一关"、"第二关"...）。
- 点击文本按钮后弹出挑战界面（即现有的 `HeavenlyTrialBattleDialog`）。

## 变更方案

### 1. 修改 `HeavenlyTrialPanel.kt`

**目标**: 重写关卡展示布局，实现大图+岛屿定位+滚动。

**具体改动**:
- 移除现有的 `Row` + `for` 循环布局（每关一张小图）。
- 改用 `Box` + `horizontalScroll` 作为最外层滚动容器，占满父布局。
- 在 `Box` 中放置一张大图 `heavenly_trial_map`，使用 `ContentScale.FillHeight` 或 `ContentScale.Crop` 使其高度占满内容区，宽度自适应，允许横向超出。
- 在图上**叠加**8个可点击区域（使用 `Box` + `Modifier.offset` 或 `Modifier.layout` 定位），对应8个岛屿的位置。
  - 由于岛屿在图中的相对位置是固定的，使用百分比偏移（`fractionOfWidth` / `fractionOfHeight`）来定位，确保不同屏幕尺寸下位置相对准确。
  - 每个岛屿区域下方（或内部底部）显示关卡文本按钮。
- 每个岛屿区域的点击事件绑定到 `viewModel.enterBattlePrep(i)`，和现有行为一致。
- 保留关卡状态判断（`unlocked`、`cleared`），用于控制按钮的启用状态和颜色。

**定位策略**:
- 假设 `heavenly_trial_map.png` 是一张横向长图，8个岛屿均匀或按设计分布。
- 使用 `BoxWithConstraints` 获取容器尺寸，结合图片的宽高比，计算每个岛屿的相对位置。
- 如果图片比例未知，先假设岛屿在图中水平均匀分布（每个岛屿中心点 x ≈ (i + 0.5) / 8），垂直位置固定在一个比例上（如 0.4）。后续可根据实际图片微调。

**伪代码示意**:
```kotlin
BoxWithConstraints(
    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
) {
    val boxWidth = maxWidth
    val boxHeight = maxHeight
    // 图片高度填满，宽度按图片比例计算
    Image(
        painter = painterResource(R.drawable.heavenly_trial_map),
        contentDescription = null,
        modifier = Modifier.height(boxHeight).width(boxHeight * imageAspectRatio),
        contentScale = ContentScale.FillHeight
    )
    // 8个岛屿定位
    for (i in 0 until 8) {
        val islandX = boxHeight * imageAspectRatio * ((i + 0.5f) / 8f)
        val islandY = boxHeight * 0.4f // 假设岛屿在图的40%高度位置
        Column(
            modifier = Modifier.offset(x = islandX - buttonWidth / 2, y = islandY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 岛屿点击区域（可省略，直接点按钮）
            // 关卡文本按钮
            GameButton(config.label, onClick = { viewModel.enterBattlePrep(i) }, enabled = unlocked)
        }
    }
}
```

### 2. 资源确认
- 确认 `heavenly_trial_map.png` 的宽高比和8个岛屿的大致位置。
- 如果实际图片中岛屿位置不均匀，需要在代码中硬编码每个岛屿的相对偏移百分比（如 `island1X = 0.08f`, `island2X = 0.20f`, ...）。

### 3. 状态与交互保留
- `trialState.highestClearedLevel` 用于判断解锁和通关状态。
- 按钮颜色：通关=绿色，解锁=金色，未解锁=灰色边框。
- 未解锁关卡按钮不可点击。

## 假设与决策
1. **图片比例**: 假设 `heavenly_trial_map.png` 是一张横向长图，宽高比大于 4:1（因为8个岛屿横向排列）。实际比例需在实现时确认，若无法确认则先使用 `ContentScale.FillBounds` 或 `FillHeight`。
2. **岛屿定位**: 先假设岛屿水平均匀分布，垂直位置固定在图片高度的约40%处。如果实际图片差异大，后续通过调整百分比修正。
3. **滚动容器**: 使用 `horizontalScroll` 包裹整个 `BoxWithConstraints`，确保图片宽度超出容器时可左右滑动。
4. **按钮位置**: 按钮显示在岛屿下方（即岛屿定位点的下方偏移一点），而不是覆盖在岛屿上，避免遮挡。

## 验证步骤
1. 编译运行后，进入活动界面 -> 天道试炼。
2. 确认右侧内容区显示一张大图，高度占满内容区。
3. 确认图下方（或图上）有8个文本按钮，从左到右依次为"第一关"到"第八关"。
4. 确认当图宽度超出内容区时，可以左右滑动查看右侧岛屿。
5. 确认点击已解锁关卡的按钮，能正常弹出 `HeavenlyTrialBattleDialog`。
6. 确认已通关、已解锁、未解锁状态的按钮颜色和可点击状态正确。
