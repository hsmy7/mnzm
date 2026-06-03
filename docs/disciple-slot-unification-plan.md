# 弟子槽位统一方案

> 日期：2026-06-03 | 目标：所有弟子槽位共用一个组件，统一尺寸，新增分割横线

---

## 一、现状

游戏中有两个弟子槽位组件：

| 组件 | 文件 | 尺寸 | 布局 |
|------|------|------|------|
| `UnifiedDiscipleSlot` | `DiscipleComponents.kt:369` | 52×88dp | 名称→精灵图→境界（无分割线） |
| `DiscipleSlotWithActions` | `DiscipleComponents.kt:409` | 52×88dp + 操作按钮 | 包裹 `UnifiedDiscipleSlot` + "卸任""更换" |

调用方共 **7 处**直接使用这两个组件：

| 文件 | 使用组件 |
|------|---------|
| `ProductionComponents.kt:249` | `DiscipleSlotWithActions` |
| `ProductionComponents.kt:309` | `DiscipleSlotWithActions` |
| `PeakScreenComponents.kt:151` | `DiscipleSlotWithActions` |
| `PeakScreenComponents.kt:246` | `DiscipleSlotWithActions` |
| `SpiritMineDialog.kt:353` | `DiscipleSlotWithActions` |
| `AttackDiscipleDialog.kt:168` | `DiscipleSlotWithActions` |
| `BloodRefiningPoolDialog.kt` | `DiscipleSlotWithActions` |
| `AlchemyDialog.kt` | `DiscipleSlotWithActions`（间接） |

---

## 二、目标

1. 合并为一个组件 `DiscipleSlot`，所有地方用同一个
2. 槽位尺寸统一 52×88dp
3. 境界、精灵图、名称之间用横线分隔（`DiscipleDetailScreen` 同款：`HorizontalDivider(thickness=1.dp, color=Color(0xFF757575))`）
4. 操作按钮（卸任/更换）保留在组件内，通过参数控制显隐

---

## 三、新组件设计

### 视觉规格

```
┌──────────────┐
│   炼气一层    │  ← 境界（顶部，9sp，黑色）
├──────────────┤  ← 分割线 1dp #757575
│              │
│   👤 精灵图  │  ← 40×48dp 居中
│              │
├──────────────┤  ← 分割线 1dp #757575
│    张三      │  ← 名称（底部，9sp，黑色加粗）
└──────────────┘
```

- 槽位：52×88dp，6dp 圆角，GameColors.Border 边框
- 底色：填充 = 白色，空置 = GameColors.PageBackground
- 空置态：中央显示 "+"（20sp 加粗黑色）

### 组件签名

```kotlin
@Composable
fun DiscipleSlot(
    disciple: DiscipleAggregate?,
    modifier: Modifier = Modifier,
    borderColor: Color = GameColors.Border,
    showActions: Boolean = false,       // 是否显示卸任/更换按钮
    onSlotClick: () -> Unit = {},
    onEmptySlotClick: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,    // 卸任回调
    onSwap: (() -> Unit)? = null        // 更换回调
)
```

### 完整实现代码

```kotlin
// ==================== 统一弟子槽位 ====================

/**
 * 统一的弟子槽位组件。
 * 所有弟子槽位（生产、建筑、战斗等）共用此组件。
 *
 * 布局：境界 → 分割线 → 精灵图 → 分割线 → 名称
 * 分割线样式与 DiscipleDetailScreen 标签页一致。
 */
@Composable
fun DiscipleSlot(
    disciple: DiscipleAggregate?,
    modifier: Modifier = Modifier,
    borderColor: Color = GameColors.Border,
    showActions: Boolean = false,
    onSlotClick: () -> Unit = {},
    onEmptySlotClick: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    onSwap: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 槽位本体
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (disciple != null) Color.White else GameColors.PageBackground)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable {
                    if (disciple != null) onSlotClick() else onEmptySlotClick()
                }
        ) {
            if (disciple != null) {
                val dividerColor = Color(0xFF757575)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 境界（顶部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = disciple.realmName,
                            fontSize = 9.sp,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 分割线
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    // 精灵图（中部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        if (disciple.isAlive) {
                            val context = LocalContext.current
                            val portraitRes = disciple.portraitRes
                            val isBeastPortrait = portraitRes.startsWith("beast_")
                            val portraitResId = remember(portraitRes) {
                                if (isBeastPortrait) {
                                    val suffix = portraitRes.removePrefix("beast_")
                                    val index = suffix.toIntOrNull() ?: -1
                                    if (index in 0..7) beastDrawables.getOrNull(index) ?: 0
                                    else if (index > 0) index
                                    else 0
                                } else PortraitPool.getResourceId(context, portraitRes)
                            }
                            Image(
                                painter = if (portraitResId != 0) painterResource(id = portraitResId)
                                          else painterResource(id = R.drawable.disciple_portrait),
                                contentDescription = null,
                                modifier = Modifier.width(40.dp).height(48.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = "死亡",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF44336),
                                maxLines = 1
                            )
                        }
                    }
                    // 分割线
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    // 名称（底部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = disciple.name,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        // 操作按钮（可选）
        if (showActions && disciple != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDismiss != null) {
                    Text(
                        text = "卸任",
                        fontSize = 9.sp,
                        color = Color(0xFFE53935),
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }
                if (onSwap != null) {
                    Text(
                        text = "更换",
                        fontSize = 9.sp,
                        color = Color.Black,
                        modifier = Modifier.clickable { onSwap() }
                    )
                }
            }
        }
    }
}
```

---

## 四、迁移方案

### Step 1：在 `DiscipleComponents.kt` 中新增 `DiscipleSlot`

在原 `UnifiedDiscipleSlot` 之前插入新组件。保留旧组件并标注 `@Deprecated`。

### Step 2：逐文件替换调用方

| 优先级 | 文件 | 替换内容 |
|--------|------|---------|
| P0 | `BloodRefiningPoolDialog.kt` | `DiscipleSlotWithActions` → `DiscipleSlot(showActions=true)` |
| P0 | `SpiritMineDialog.kt` | `SpiritMineSlotItem` 内部的 `DiscipleSlotWithActions` → `DiscipleSlot(showActions=true)` |
| P1 | `ProductionComponents.kt` | `ProductionElderSlotSection` + `ProductionDirectDiscipleSlotItem` 内部 → `DiscipleSlot(showActions=true)` |
| P1 | `PeakScreenComponents.kt` | 2 处 `DiscipleSlotWithActions` → `DiscipleSlot(showActions=true)` |
| P2 | `AttackDiscipleDialog.kt` | 1 处 `DiscipleSlotWithActions` → `DiscipleSlot` |
| P2 | `AlchemyDialog.kt` | 间接引用 → 一并替换 |

### Step 3：编译验证

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

### Step 4：清理

确认所有调用方迁移后，将 `UnifiedDiscipleSlot` 和 `DiscipleSlotWithActions` 标记 `@Deprecated`，`SlotContent` 改为 `private`。

---

## 五、影响范围

| 文件 | 改动类型 |
|------|---------|
| `ui/components/DiscipleComponents.kt` | 新增 `DiscipleSlot`，废弃旧组件 |
| `ui/game/dialogs/BloodRefiningPoolDialog.kt` | 替换槽位组件 |
| `ui/game/dialogs/SpiritMineDialog.kt` | 替换槽位组件 |
| `ui/game/ProductionComponents.kt` | 替换 2 处槽位组件 |
| `ui/game/PeakScreenComponents.kt` | 替换 2 处槽位组件 |
| `ui/game/dialogs/AttackDiscipleDialog.kt` | 替换 1 处槽位组件 |

> 无需 DB Migration，无 GameData 字段变更。
