# 优化物品数量显示 - Floor 格式化

## Summary

将物品/灵石数量显示从「直接显示数字」或「四舍五入万/亿」统一改为「floor 向下取整到 1 位小数」的格式化显示。当数量 >= 10000 时使用"万"单位，>= 1_000_000_000 时使用"亿"单位，小数位为 0 时省略小数（如 1 万而非 1.0 万）。覆盖所有数量显示点：核心格式化函数、统一物品卡片组件、以及所有零散直接字符串拼接点。

**floor 规则验证**（用户例子）：
- 10001 → 1.0001 万 → floor 1 位 → **1万**
- 10999 → 1.0999 万 → floor 1 位 → **1万**
- 11999 → 1.1999 万 → floor 1 位 → **1.1万**
- 19999 → 1.9999 万 → floor 1 位 → **1.9万**
- 19000 → 1.9 万 → floor 1 位 → **1.9万**

## Current State Analysis

### 现有格式化函数（核心问题）
[GameUtils.kt:61-67](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/util/GameUtils.kt#L61-L67) 使用 `String.format("%.1f万", ...)`，这是**标准四舍五入**，与用户要求"只少不多"（floor）**不符**。
- 现状：10999 → "1.1万"（四舍五入进位）
- 要求：10999 → "1万"（floor 不进位）

### 显示点分类（基于代码库研究）

| 类别 | 现状 | 数量 |
|------|------|------|
| 已调用 `formatNumber` | 自动生效（修复函数后） | 5 处 |
| `ItemCard` 组件直接显示 `"${data.quantity}"` | 需接入格式化 | 1 处（覆盖卡片场景） |
| 零散直接字符串拼接（灵石/物品/价格） | 需改为调用 `formatNumber` | ~20 处 |
| 硬编码"万"计算（BeastAttackWarningDialog） | 需改为调用 `formatNumber` | 1 处 |
| 死代码 `formatRewardQuantity` | 删除 | 1 处 |

### 类型现状
- `GameUtils.formatNumber(value: Long)` - 仅 Long 参数
- `ItemCardData.quantity: Int` - 卡片组件用 Int
- 多数零散点 quantity/price 为 Int 或 Long 不一

## Proposed Changes

### 改动 1：重写 `GameUtils.formatNumber` 为 floor 逻辑（核心）

**文件**：[GameUtils.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/main/java/com/xianxia/sect/core/util/GameUtils.kt)

**What**：替换行 61-67 的 `formatNumber`，新增 Int 重载，新增私有 `formatWithUnit` 辅助函数。

**Why**：现有四舍五入与用户要求"只少不多"冲突。整数运算避免 toDouble 精度损失。

**How**（整数运算，无精度风险）：
```kotlin
fun formatNumber(value: Long): String {
    return when {
        value >= 1_000_000_000L -> formatWithUnit(value, 1_000_000_000L, "亿")
        value >= 10_000L -> formatWithUnit(value, 10_000L, "万")
        else -> value.toString()
    }
}

fun formatNumber(value: Int): String = formatNumber(value.toLong())

private fun formatWithUnit(value: Long, unit: Long, unitName: String): String {
    val intPart = value / unit
    val remainder = value % unit
    val decPart = (remainder * 10L) / unit   // floor 到 1 位小数
    return if (decPart == 0L) "$intPart$unitName" else "$intPart.$decPart$unitName"
}
```

**溢出安全**：`remainder < unit <= 1e9`，`remainder * 10 < 1e10`，远小于 Long.MAX_VALUE，无溢出。

### 改动 2：`ItemCard` 组件接入格式化

**文件**：[ItemCard.kt:161](file:///c:/Mnzm/XianxiaSectNative/android/core/ui/src/main/java/com/xianxia/sect/ui/components/ItemCard.kt#L161)

**What**：将 `text = "${data.quantity}"` 改为 `text = GameUtils.formatNumber(data.quantity)`，添加 import。

**Why**：这是统一卡片组件，覆盖背包/商店/战利品/奖励/邮件附件/签到/天劫/种植等几乎所有物品展示场景，一处改动覆盖所有卡片场景。

### 改动 3：零散直接显示点改为调用 `formatNumber`

逐个文件修改，将 `"${value}灵石"` / `"${value}万灵石"` / `"数量: ${value}"` 等改为 `${GameUtils.formatNumber(value)}灵石` / `${GameUtils.formatNumber(value)}` 等。

#### 3.1 灵石数量显示

| 文件 | 行 | 现状 | 改后 |
|------|----|------|------|
| [MainGameScreen.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt) | ~1043 | `"下:$lowStones 中:$midStones 上:$highStones"` | 三个值分别 `formatNumber` |
| [MerchantDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantDialog.kt) | ~108 | `"下品:${...} 中品:${...} 上品:${...}"` | 三个值分别 `formatNumber` |
| [MissionHallDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MissionHallDialog.kt) | ~488,490 | `"${rewards.spiritStones}灵石"` | `formatNumber` |
| [SalaryConfigDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/SalaryConfigDialog.kt) | ~106 | `"$salary 灵石"` | `formatNumber` |
| [WarehouseBulkSellDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/WarehouseBulkSellDialog.kt) | ~331 | `"获得灵石: $totalValue（原价80%）"` | `formatNumber` |
| [BeastAttackWarningDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/BeastAttackWarningDialog.kt) | ~78 | `"(${tributeAmount / 10000}万灵石)"` 硬编码 | 改为 `${GameUtils.formatNumber(tributeAmount)}灵石`，去掉手动 /10000 |
| [ItemDetailDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/ItemDetailDialog.kt) | ~128 | `"数量: ${item.quantity.toLong()}"` | `formatNumber` |

#### 3.2 物品数量显示（非卡片）

| 文件 | 行 | 现状 |
|------|----|------|
| [MailDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MailDialog.kt) | ~448 | `"${attachment.quantity}"` |
| [MerchantDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantDialog.kt) | ~363,~829 | `"${item.quantity}"` |
| [MerchantDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantDialog.kt) | ~588 | `"商人收购: 最多 ${item.quantity} 个"` |
| [ActivityDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/ActivityDialog.kt) | ~310 | `"${reward.name} x${reward.quantity}"` |
| [RewardDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/RewardDialog.kt) | ~74 | `"${reward.name} ×${reward.quantity}"` |
| [LevelDetailDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/LevelDetailDialog.kt) | ~165 | `"数量：${level.count}"` |
| [SectLevelRewardDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/SectLevelRewardDialog.kt) | ~155 | `"${card.quantity}"` |
| [AlchemyDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/AlchemyDialog.kt) | ~514 | `"${herb?.quantity ?: 0}/$requiredQuantity"` |
| [ForgeDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/ForgeDialog.kt) | ~473 | `"${material?.quantity ?: 0}/$requiredQuantity"` |

**AlchemyDialog/ForgeDialog 特殊处理**：`"当前/所需"` 格式，仅对"当前"值格式化（所需值通常较小且为设计值，保持原样；若所需值也大则同样格式化）。实际修改时读代码确认：若 requiredQuantity 一般为 1-10 的配方需求，保持不格式化更清晰。

#### 3.3 价格显示

| 文件 | 行 | 现状 |
|------|----|------|
| [MerchantDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantDialog.kt) | ~227,~340,~481,~538,~627 | `"${item.price}灵石"` / `"单价: ${item.price} 灵石"` / `"总价: $totalPrice 灵石"` |
| [SectTradeDialog.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/SectTradeDialog.kt) | ~184,~245,~302 | `"${adjustedPrice}灵石"` / `"单价: $adjustedPrice 灵石"` / `"总价: $totalPrice 灵石"` |

**注意**：价格显示需要读代码确认语义。单价通常较小（一件物品价格），总价可能很大（批量购买）。原则上对数值统一应用 `formatNumber`，保持一致性。

### 改动 4：删除死代码

**文件**：[DailySignInDialog.kt:549-556](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/DailySignInDialog.kt#L549-L556)

**What**：删除未调用的 `formatRewardQuantity` 函数。

**Why**：死代码，与统一 `formatNumber` 重复且行为不一致（支持"千"、仅整除才格式化）。

### 改动 5：硬编码文案处理

**文件**：[BloodRefiningPoolDialog.kt:149](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/BloodRefiningPoolDialog.kt#L149)

**现状**：`"消耗 100 万灵石"` 是固定设计文案（数值 1000000 写死成"100 万"）。

**处理**：保持原样。这是字面量文案而非动态数值显示，不属于本次格式化范围。若该数值实际由变量驱动则改为 `formatNumber`；读代码确认。

### 改动 6：更新测试

**文件**：[GameUtilsTest.kt:230-249](file:///c:/Mnzm/XianxiaSectNative/android/app/src/test/java/com/xianxia/sect/core/util/GameUtilsTest.kt#L230-L249)

**What**：更新现有 3 个测试断言为 floor 逻辑，新增用户例子测试用例。

**新增测试用例**：
```kotlin
@Test fun formatNumber_10001_returns1wan() = assertEquals("1万", GameUtils.formatNumber(10001L))
@Test fun formatNumber_10999_returns1wan() = assertEquals("1万", GameUtils.formatNumber(10999L))
@Test fun formatNumber_11999_returns1_1wan() = assertEquals("1.1万", GameUtils.formatNumber(11999L))
@Test fun formatNumber_19999_returns1_9wan() = assertEquals("1.9万", GameUtils.formatNumber(19999L))
@Test fun formatNumber_19000_returns1_9wan() = assertEquals("1.9万", GameUtils.formatNumber(19000L))
@Test fun formatNumber_10000_returns1wan() = assertEquals("1万", GameUtils.formatNumber(10000L))
@Test fun formatNumber_9999_returns9999() = assertEquals("9999", GameUtils.formatNumber(9999L))
@Test fun formatNumber_1_9999_9999_returns1_9yi() = assertEquals("1.9亿", GameUtils.formatNumber(1_9999_9999L))
@Test fun formatNumber_1_0000_0001_returns1yi() = assertEquals("1亿", GameUtils.formatNumber(1_0000_0001L))
@Test fun formatNumber_int_overload() = assertEquals("1万", GameUtils.formatNumber(10001))
```

**更新现有测试**：
- `formatNumber_10000_containsWan`：保持（10000 → "1万" 含"万"）
- `formatNumber_1Billion_containsYi`：保持（1e9 → "1亿" 含"亿"）
- 新增精确断言替代宽松 `contains` 检查

## Assumptions & Decisions

### 决策
1. **floor 算法用整数运算**（非 toDouble）：避免大 Long 精度损失，无溢出风险。
2. **新增 `formatNumber(Int)` 重载**：避免零散点到处 `.toLong()`，调用更简洁。
3. **保留"亿"单位**：用户确认。>= 1e9 用"亿"，同样 floor 逻辑。
4. **小数位为 0 时省略小数**：用户例子 "1万" 而非 "1.0万"。
5. **阈值 >= 10000 触发"万"**：用户"5位数"=10000，与现有阈值一致。
6. **覆盖所有显示点**：用户确认。包括灵石、物品数量、价格。

### 假设（需在实现时读代码确认）
1. **`ItemCardData.quantity` 保持 Int**：不改类型（改动面大）。若灵石通过卡片显示且超 Int.MAX_VALUE（~21亿）会截断，这是**现有问题**，本次不修复。实际游戏中灵石通常不超此量级。
2. **`AlchemyDialog/ForgeDialog` 的 requiredQuantity 保持原样**：配方需求通常为小整数（1-10），格式化反而不清晰。实现时读代码确认，若为大数则一并格式化。
3. **`BloodRefiningPoolDialog` "100 万灵石" 为固定文案**：实现时读代码确认是否变量驱动。
4. **价格显示统一格式化**：单价/总价均应用 `formatNumber`，保持一致性。

### 不在本次范围
- 不修改 `ItemCardData.quantity` 类型（Int → Long）
- 不修改 `WarehouseTab.kt:295` 的 `.toInt()` 转换
- 不修改 `MissionHallDialog.formatSpiritStoneReward` 的组装逻辑（仅改其中的数值显示）
- 不处理国际化（仅中文"万/亿"）

## Verification Steps

1. **单元测试**：运行 `GameUtilsTest`，确认所有 floor 用例通过（含用户 5 个例子 + 边界值）。
   ```bash
   ./gradlew :app:testDebugUnitTest --tests "com.xianxia.sect.core.util.GameUtilsTest"
   ```

2. **编译验证**：全量编译确认无破坏。
   ```bash
   ./gradlew assembleDebug
   ```

3. **关键场景人工核对**（读代码确认改动正确）：
   - 灵石 10001/10999/11999/19999/19000 显示为 1万/1万/1.1万/1.9万/1.9万
   - 物品卡片大数量显示（如 99999 → "9.9万"）
   - 价格显示（如总价 15000 → "1.5万灵石"）
   - 亿级显示（如 1_9999_9999 → "1.9亿"）
   - 小数位为 0 时无 ".0"（如 10000 → "1万" 非 "1.0万"）

4. **回归检查**：
   - 确认已调用 `formatNumber` 的 5 处（MainGameScreen/GiftDialog×2/SectTradeDialog/AllianceDialog）显示正确
   - 确认 `BeastAttackWarningDialog` 去掉手动 /10000 后显示正确
   - 确认死代码 `formatRewardQuantity` 删除后无编译错误

## 实现顺序

1. 改动 1：重写 `GameUtils.formatNumber`（核心）
2. 改动 6：更新测试，先跑通核心逻辑
3. 改动 2：`ItemCard` 接入格式化
4. 改动 3：逐文件修改零散显示点（按 3.1 → 3.2 → 3.3 顺序）
5. 改动 4：删除死代码
6. 改动 5：读代码确认 BloodRefiningPoolDialog 处理方式
7. 全量编译 + 单元测试验证
