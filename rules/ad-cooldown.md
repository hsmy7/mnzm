# 广告冷却机制

所有激励视频广告（包括未来新增的广告类型）必须遵循统一的冷却规则。

## 冷却规则

- **冷却时长：** 60 秒（1 分钟）
- **触发时机：** 广告奖励验证通过（`onRewardVerify` 返回 `rewardVerify = true`）后才进入冷却，广告播放中或提前关闭不计入冷却
- **生效范围：** 全局冷却，所有广告类型共享同一个冷却计时器

## 实现位置

### 冷却状态管理

**文件：** `feature/game/.../GameViewModel.kt`

```kotlin
companion object {
    private const val AD_COOLDOWN_MS = 60_000L
}

private var adCooldownUntilMs: Long = 0L

fun isAdOnCooldown(): Boolean = System.currentTimeMillis() < adCooldownUntilMs

fun markAdWatched() {
    adCooldownUntilMs = System.currentTimeMillis() + AD_COOLDOWN_MS
}
```

### 冷却中时 UI 表现

**文件：** `feature/game/.../components/detail/DetailCultivationSection.kt`

- 点击广告按钮时先调用 `viewModel?.isAdOnCooldown()` 检查
- 冷却中 → 弹出提示框，标题"不可播放广告"，内容"一分钟内只可观看一次广告"，居中"确认"按钮
- 未冷却 → 弹出正常确认弹框，点击"观看"后播放广告

### 冷却触发

**文件：** `app/.../GameActivity.kt`

在 `RewardVideoAdManager` 的 `onRewardVerify` 回调中，`rewardVerify = true` 时调用：

```kotlin
if (rewardVerify && !activity.isFinishing) {
    viewModel.applyAdBreakthroughBonus(discipleId, bonus)
    viewModel.markAdWatched()  // ← 冷却从此开始
}
```

## 新增广告类型时必须做的

1. 在 `GameActivity` 的新广告回调中调用 `viewModel.markAdWatched()`
2. 广告按钮点击前调用 `viewModel.isAdOnCooldown()` 判断是否显示冷却提示
3. 冷却提示框统一使用标题"不可播放广告"、内容"一分钟内只可观看一次广告"、居中"确认"按钮

## 设计意图

- 奖励验证后才冷却：防止用户打开广告但不观看就触发冷却
- 全局共享冷却：防止用户通过切换广告类型绕过限制
- `GameViewModel` 持有状态：进程重启后冷却自动重置，无需持久化

---

## 商业化触发源冷却/限额（预留规范，2026-08-04 起）

> 本文件机制部分即时生效；本节为**未来商业化扩展（IAP/月卡/战令/推送/离线收益）的预留约束**，当前代码尚未实现这些触发源。

### 边界声明

| 触发源 | 是否适用冷却 | 说明 |
|--------|-------------|------|
| 激励视频广告 | ✅ 60 秒全局冷却（本文档机制部分） | 广告是免费资源，需频控防滥用 |
| **IAP 真钱购买** | ❌ **不设冷却** | 消费行为无频控需求，设置冷却反而损害付费体验 |
| 月卡/战令每日领取 | ⚠️ 不是冷却，是**领取窗口** | 每日领取窗口 + 过期补领规则，属商业化领取状态而非冷却计时 |
| 推送通知频率 | ⚠️ 需频控 | 未来推送点位统一走 RateLimit 辅助实现 |
| 离线收益领取 | ⚠️ 需频控 | 未来离线收益领取间隔统一走 RateLimit 辅助实现 |

### 预留规则

1. **未来新增频控点位**（推送频率/离线收益领取间隔等）**统一走 RateLimit 辅助实现**（单点实现，参照 `AdsDelegate` 每日次数限制模式），禁止各点位各自维护计时器——分散计时器会导致限额规则漂移、绕过路径不可审计
2. **IAP 购买校验**属 `rules/commercialization.md` 范畴（防重放/防漏发/双端一致性），与本文件的广告冷却机制互不重叠
3. **新 AdPurpose 接入流程**见 `docs/knowledge-base.md#免广告特权白名单`（`adService.watchAd()` 统一入口 + 白名单守卫自动继承）与 `rules/commercialization.md`——本文件不复制该流程，只约束冷却机制
