# 邮件系统设计文档

> 日期: 2026-06-01（修订 2026-06-02）
> 项目: 仙侠宗门 (XianxiaSectNative)
> 状态: 已审核，可实施

---

## 1. 概述

为游戏新增邮件系统，用于向玩家发放全服奖励、补偿等官方邮件。邮件支持附件（货币/资源、物品道具、弟子），玩家可查看、一键已读（含领取附件）、删除邮件。

### 1.1 核心约束

- 邮件来源：纯系统邮件，玩家只读+领附件
- 投递方式：混合模式（启动时在线拉取 + 每月结算轮询 + 内置静态邮件）
- 附件类型：货币/资源（灵石、灵草）、物品道具（装备、丹药、材料、种子、草药）、弟子
- 操作模式：一键已读（含自动领取附件）+ 单封查看
- 过期机制：**30天**定时过期，过期未领附件直接丢失
- 邮件上限：**单存档最多1000封**，超出自动删除最早的已读已领邮件
- 防刷机制：同一存档内不可通过反复读档重复领取同一封邮件的附件；不同存档各自独立可领

---

## 2. 数据层

### 2.1 MailEntity（Room Entity）

独立表，通过 `slot_id` 关联存档，遵循项目中 ProductionSlot、BattleLog、StorageBag 的拆分模式。

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| id | String (PK) | UUID | 邮件唯一标识 |
| slotId | Int | 0 | 存档槽位 |
| source | String | "builtin" | 来源: "online" / "builtin" |
| mailType | String | "reward" | 类型: "reward" / "compensation" / "notice" |
| title | String | "" | 邮件标题 |
| content | String | "" | 邮件正文 |
| senderName | String | "天道意志" | 发件人显示名 |
| sendTime | Long | 0 | 发送时间戳(ms) |
| expireTime | Long | 0 | 过期时间戳(ms) = sendTime + **30天** |
| isRead | Boolean | false | 是否已读 |
| attachmentClaimed | Boolean | false | 附件是否已领取 |
| hasAttachment | Boolean | false | 是否有附件 |
| attachments | String | "[]" | JSON序列化的附件列表 |
| remoteMailId | String? | null | 服务端邮件ID，在线邮件去重用 |

索引：
- `index_mails_slot_id ON mails(slotId)`
- `index_mails_remote_id ON mails(remoteMailId)`
- **`index_mails_expire ON mails(slotId, expireTime)`** — 过期清理 & 活跃邮件查询

**邮件数量上限**：单存档 `MAX_MAILS_PER_SLOT = 200`。插入新邮件时若当前数量 ≥ 200，先删除最早的已读+已领邮件腾出空间。

### 2.2 MailAttachment（数据类）

```kotlin
@Serializable
data class MailAttachment(
    val type: String,       // spiritStones|spiritHerbs|equipment|pill|material|herb|seed|disciple
    val name: String,
    val quantity: Int,
    val rarity: Int,        // 货币类为0
    val itemId: String? = null,
    val extra: Map<String, String> = emptyMap()
)
```

### 2.3 ClaimedMailRecord（防刷审计表）

独立于存档保存/加载流程的持久审计记录表。当玩家加载旧存档时，MailEntity 可能回退到未领取状态，但此表不会被回退，从而防止反复读档刷邮件。

| 字段 | 类型 | 说明 |
|---|---|---|
| mailGlobalId | String (PK联合) | **带前缀**：在线邮件 `"remote:{remoteMailId}"`，内置邮件 `"builtin:{builtinMailId}"` |
| slotId | Int (PK联合) | 存档槽位 |
| claimedTime | Long | 领取时间戳(ms) |

主键: `(mailGlobalId, slotId)`

不同存档互不影响：存档1领取了邮件A → `(remote:mailA, slot1)` 记录存在；存档2加载 → 查询 `(remote:mailA, slot2)` 不存在 → 可正常领取。

### 2.4 MailDao

```kotlin
@Dao
interface MailDao {
    @Query("SELECT * FROM mails WHERE slotId = :slotId AND expireTime > :now ORDER BY isRead ASC, sendTime DESC")
    fun getActiveMails(slotId: Int, now: Long): Flow<List<MailEntity>>

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId AND isRead = 0 AND expireTime > :now")
    fun getUnreadCount(slotId: Int, now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM mails WHERE slotId = :slotId")
    suspend fun getMailCount(slotId: Int): Int

    @Query("SELECT EXISTS(SELECT 1 FROM mails WHERE remoteMailId = :remoteId LIMIT 1)")
    suspend fun existsByRemoteId(remoteId: String): Boolean

    @Transaction
    suspend fun insertWithEnforceLimit(mail: MailEntity, maxLimit: Int = 200) {
        val currentCount = getMailCount(mail.slotId)
        if (currentCount >= maxLimit) {
            // 删除最早的已读+已领邮件腾出空间
            deleteOldestReadAndClaimed(mail.slotId, currentCount - maxLimit + 1)
        }
        insertAll(listOf(mail))
    }

    @Query("DELETE FROM mails WHERE id IN (SELECT id FROM mails WHERE slotId = :slotId AND isRead = 1 AND attachmentClaimed = 1 ORDER BY sendTime ASC LIMIT :count)")
    suspend fun deleteOldestReadAndClaimed(slotId: Int, count: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mails: List<MailEntity>)

    @Update
    suspend fun update(mail: MailEntity)

    @Query("UPDATE mails SET isRead = 1 WHERE slotId = :slotId AND isRead = 0 AND expireTime > :now")
    suspend fun markAllAsRead(slotId: Int, now: Long)

    @Query("DELETE FROM mails WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND id IN (:ids)")
    suspend fun deleteByIds(slotId: Int, ids: List<String>)

    @Query("DELETE FROM mails WHERE slotId = :slotId AND expireTime <= :now")
    suspend fun deleteExpired(slotId: Int, now: Long)
}

@Dao
interface ClaimedMailDao {
    @Query("SELECT EXISTS(SELECT 1 FROM claimed_mail_records WHERE mailGlobalId = :globalId AND slotId = :slotId LIMIT 1)")
    suspend fun isClaimed(globalId: String, slotId: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ClaimedMailRecord)

    @Query("SELECT * FROM claimed_mail_records WHERE mailGlobalId = :globalId AND slotId = :slotId LIMIT 1")
    suspend fun getRecord(globalId: String, slotId: Int): ClaimedMailRecord?

    @Query("DELETE FROM claimed_mail_records WHERE mailGlobalId IN (SELECT 'remote:' || remoteMailId FROM mails WHERE slotId = :slotId AND expireTime <= :now) OR mailGlobalId IN (SELECT 'builtin:' || id FROM mails WHERE slotId = :slotId AND expireTime <= :now AND source = 'builtin')")
    suspend fun deleteOrphanedForExpiredMails(slotId: Int, now: Long)
}
```

### 2.5 数据库迁移 v20 → v21

```sql
CREATE TABLE IF NOT EXISTS mails (
    id TEXT NOT NULL,
    slotId INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'builtin',
    mailType TEXT NOT NULL DEFAULT 'reward',
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    senderName TEXT NOT NULL DEFAULT '天道意志',
    sendTime INTEGER NOT NULL DEFAULT 0,
    expireTime INTEGER NOT NULL DEFAULT 0,
    isRead INTEGER NOT NULL DEFAULT 0,
    attachmentClaimed INTEGER NOT NULL DEFAULT 0,
    hasAttachment INTEGER NOT NULL DEFAULT 0,
    attachments TEXT NOT NULL DEFAULT '[]',
    remoteMailId TEXT,
    PRIMARY KEY(id)
);
CREATE INDEX IF NOT EXISTS index_mails_slot_id ON mails(slotId);
CREATE INDEX IF NOT EXISTS index_mails_remote_id ON mails(remoteMailId);
CREATE INDEX IF NOT EXISTS index_mails_expire ON mails(slotId, expireTime);

CREATE TABLE IF NOT EXISTS claimed_mail_records (
    mailGlobalId TEXT NOT NULL,
    slotId INTEGER NOT NULL,
    claimedTime INTEGER NOT NULL,
    PRIMARY KEY(mailGlobalId, slotId)
);
```

---

## 3. 服务层

### 3.1 MailService

遵循项目 `GameSystem` 接口模式，注册到 `SystemManager`，**内部使用 Mutex 保护每个 slot 的操作防止竞态**。

```
MailService : GameSystem
├── initialize()              — 加载内置邮件配置，拉取在线邮件，清理过期
├── release()                 — 释放资源
├── clearForSlot(slotId)      — 清理指定存档的内存缓存（持锁）
├── fetchOnlineMails()        — 通过 SecureHttpClient 拉取服务端邮件
├── loadBuiltinMails()        — 检查并插入未领取的内置邮件
├── claimAttachment(mailId)   — 领取单封邮件附件（事务安全，含容量检查）
├── markAllAsRead()           — 一键已读：全部标已读 + 自动领取所有未领附件
├── deleteMail(mailId)        — 删除单封邮件（仅允许已领/无附件）
├── deleteAllReadAndClaimed() — 删除所有已读已领邮件
├── markAsRead(mailId)        — 标记已读
├── cleanExpired()            — 清理过期邮件 + 对应 claimed 记录（每月结算时调用）
├── enforceMailLimit(slotId)  — 邮件数量上限检查
└── getUnreadCount()          — 供红点/角标使用
```

### 3.2 附件领取逻辑（容量检查优先 + 级联清理）

**领取流程**（容量检查在审计记录之前，容量不足可自由重试）：

```
claimAttachment(mailId):
  1. 检查邮件是否存在、是否已过期
  2. 构造 mailGlobalId = ("remote:" + remoteMailId) 或 ("builtin:" + builtinMailId)
  3. 查询 claimed_mail_records WHERE mailGlobalId=? AND slotId=?
     → 已存在 → 返回 AlreadyClaimed
  4. 解析附件 JSON
  5. 【容量检查 + 级联清理】
     → 背包不足 →
       a) 自动删除已读已领邮件腾空间
       b) 仍不足 → 自动删除无附件邮件（无物品损失）
       c) 仍不足 → 返回 CapacityInsufficient（不写审计记录，可自由重试）
     → 弟子已满 → 返回 CapacityInsufficient（无级联清理，弟子不可自动删除）
  6. 【容量通过 → 写入审计记录】（此时发放一定成功）
       attempted → UNIQUE 冲突 → 并发保护 → AlreadyClaimed
  7. 按 type 分发附件到 GameStateStore.update {} 原子写入
  8. 更新 MailEntity.attachmentClaimed = true, isRead = true
  9. 返回 Success
```

**容量不足弹窗**：弹出 `StandardPromptDialog`（复用 `AutoManagementDialog` 中的"未保存更改"提示框模式），标题"无法领取"，正文为对应不足提示，确认按钮"知道了"。

**与初版的关键差异**：
- 容量检查**先于**审计记录写入 → 容量不足不视为已领取，玩家清理后可重试
- 级联清理：自动删除已读已领邮件 + 无附件邮件，最大限度避免容量不足弹窗

### 3.3 附件类型分发

复用 `RedeemCodeService.applyApiRewardsAndMarkUsed` 的奖励发放模式，按 `type` 分发：

| type | 目标字段 | 处理方式 |
|---|---|---|
| spiritStones | gameData.spiritStones | 直接加 |
| spiritHerbs | gameData.spiritHerbs | 直接加 |
| equipment | equipmentStacks | 生成随机装备或指定模板，合并同类型 |
| pill | pills | 生成丹药，合并同类型 |
| material | materials | 生成材料，合并同类型 |
| herb | herbs | 生成草药，合并同类型 |
| seed | seeds | 生成种子，合并同类型 |
| disciple | disciples | 生成弟子，加入宗门 |

通过 `GameStateStore.update {}` 原子写入。

### 3.4 容量不足处理

领取前检查各附件类型的目标容量，规则如下：

| 附件类型 | 容量检查 | 不足提示 |
|---------|---------|---------|
| spiritStones / spiritHerbs | 无上限，跳过检查 | — |
| equipment / pill / material / herb / seed | 仓库格子数 | "仓库空间不足，请清理后再领取" |
| disciple | 宗门弟子数量上限 | "宗门弟子已满，无法领取弟子" |

容量不足时弹出 `StandardPromptDialog`（复用 `AutoManagementDialog` 中的"未保存更改"提示框模式）：
- `title` = "无法领取"
- `text` = 对应的不足提示文案
- `confirmLabel` = "确定"
- `onConfirm` = 关闭弹窗

由于审计记录已在容量检查前写入（步骤4），容量检查失败意味着该邮件被标记为"已消费但未能发放"——这是安全的，玩家无法通过反复触发来刷物品，且保留了客服追溯能力。

### 3.5 一键已读（markAllAsRead）

**语义**：将所有未读邮件标记为已读，同时自动领取所有可领取的附件。这是邮件列表底部的主操作按钮。

```
markAllAsRead(slotId):
  1. 获取当前存档所有未过期邮件
  2. 遍历邮件：
     a. 若 isRead == false → markAsRead（含附件邮件先执行领取流程再标已读）
     b. 若 hasAttachment && !attachmentClaimed → 尝试 claimAttachment
        → 容量不足的跳过（继续处理剩余邮件，不中断）
  3. 汇总结果：成功领取数 / 跳过数（含原因）
  4. 返回结果（供 UI 展示简短 toast）
```

**与"一键领取"的区别**：行业头部产品（原神、网易系）的趋势是将"全部已读"作为主操作——玩家打开邮箱的核心诉求是消除红点+拿完奖励。"一键已读"同时完成两个目标，减少操作步骤。

### 3.6 在线邮件拉取流程

**拉取时机**：
- 游戏启动时（`initialize()`）
- 每月结算时（`onMonthTick()` — 约每 12 分钟触发一次在线拉取，确保长时游玩也能收到新邮件）

```
MailService.fetchOnlineMails()
  → GET ${API_BASE_URL}mail/list?version={clientVersion}
  → 响应: { mails: [{remoteId, title, content, type, sendTime, expireTime, attachments}] }
  → 遍历: if (!dao.existsByRemoteId(remoteId)) → insertWithEnforceLimit（含数量上限检查）
```

### 3.7 内置邮件配置

新建 `BuiltinMailConfig.kt`，随版本更新内置邮件数据：

```kotlin
object BuiltinMailConfig {
    data class BuiltinMail(
        val id: String,
        val title: String,
        val content: String,
        val mailType: String,
        val minVersion: Int,
        val attachments: List<MailAttachment>
    )

    val mails: List<BuiltinMail> = listOf(
        // 示例：新版本补偿
        // BuiltinMail(id = "builtin_v21_reward", title = "版本更新补偿", ...)
    )
}
```

内置邮件通过 `"builtin:{id}"` 去重，检查 `claimed_mail_records` 中是否已有 `("builtin:{id}", slotId)` 记录。

### 3.8 过期清理

- 每月结算时调用 `cleanExpired()`
- 删除 `expireTime <= now` 的邮件记录
- **同步清理** `claimed_mail_records` 中对应过期邮件的孤儿记录（通过 `deleteOrphanedForExpiredMails`）
- 过期未领附件直接丢失（与行业一致：原神/星铁同样不支持过期恢复）

---

## 4. UI层

### 4.1 入口按钮

在 `GameActionButtons.kt` 第一行 Row 中，在日志按钮左侧插入邮件按钮：

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FloatingActionButton(
        text = "邮件",
        drawableRes = R.drawable.ui_mail_button
    ) { viewModel.navigateToDialog(DialogRoute.Mail) }

    FloatingActionButton(text = "日志", ...) { ... }
    // ... 其余按钮不变
}
```

素材：`邮件按钮.png` → `res/drawable-nodpi/ui_mail_button.webp`

按钮上显示未读数角标（红点+数字），当 `unreadCount > 0` 时显示。

### 4.2 DialogRoute 扩展

在 `GameRoute.kt` 中新增：

```kotlin
// GameRoute
object Mail : GameRoute("mail")

// DialogRoute
object Mail : DialogRoute()
```

### 4.3 GameOverlayHost 注册

在 `GameOverlayHost.kt` 的 `when(route)` 中新增：

```kotlin
is DialogRoute.Mail -> {
    MailDialog(viewModel = viewModel, onDismiss = onDismiss)
}
```

### 4.4 MailDialog 布局

新建 `MailDialog.kt`，全屏 Surface 模式（参考 PlantingDialog），4:6 左右分栏。

#### 整体结构

```
Surface(fillMaxSize, GameColors.PageBackground)
  Box(fillMaxSize)
    Image(bg_horizontal, matchParentSize, Crop)
    Column(fillMaxSize)
      Row(标题栏): "邮件" + Spacer + CloseButton
      Row(weight=1f, fillMaxWidth)
        Column(weight=0.4f)         ← 左侧面板
        Box(width=1.dp, fillMaxHeight, background=Color(0xFFBDBDBD))  ← 竖线
        Column(weight=0.6f)         ← 右侧面板
```

#### 左侧面板（邮件列表区）

```
Column(weight=0.4f, fillMaxHeight, padding)
  LazyColumn(weight=1f)              ← 邮件列表，可滚动
    items(mails) { mail ->
      MailCard(                      ← 横向卡片
        background = bg_dialog_mail, ← 对话框与邮件共用.png
        alpha = if(isRead) 0.5f else 1f,
        name = mail.title,           ← 居中显示
        expireText = formatExpire(mail.expireTime)  ← 小字，名称下方
      )
    }
  Row(底部按钮区, horizontalArrangement=Center)
    GameButton("删除已读", onClick=deleteAllReadAndClaimed)
    Spacer
    GameButton("一键已读", onClick=markAllAsRead)   ← 主操作：已读+领取
```

过期时间显示规则：
- \> 1天：显示"X天后过期"
- = 1天且 > 0小时：显示"X小时后过期"
- < 1小时：显示"X分钟后过期"

邮件排序：未读在上，已读在下；同组内按 sendTime 降序。

已读状态：卡片整体 `alpha = 0.5f` 变暗。

已被其他存档领取或**容量不足无法领取**的邮件：显示"不可领取"状态（变暗），领取按钮禁用。

#### 右侧面板（邮件详情区）

```
Column(weight=0.6f, fillMaxHeight)
  标题区(固定)
    Text(mail.title, fontSize=14.sp, fontWeight=Bold)
    Text("发件人: 天道意志", fontSize=10.sp, color=Gray)

  HorizontalDivider(thickness=1.dp, color=Color(0xFFBDBDBD), padding=horizontal 12.dp)

  内容区(weight=1f, verticalScroll)
    Text("亲爱的道友，", fontSize=12.sp)
    Text(mail.content, fontSize=12.sp)

  HorizontalDivider(thickness=1.dp, color=Color(0xFFBDBDBD), padding=horizontal 12.dp)

  奖励显示区(weight=1f, verticalScroll)
    if (mail.hasAttachment && !mail.attachmentClaimed)
      FlowRow
        UnifiedItemCard(每个附件)

  HorizontalDivider(thickness=1.dp, color=Color(0xFFBDBDBD), padding=horizontal 12.dp)

  按钮区(固定)
    if (mail.hasAttachment && !mail.attachmentClaimed)
      GameButton("领取", onClick=claimAttachment)
    else if (mail.attachmentClaimed)
      Text("已领取", color=Gray)
```

### 4.5 容量不足弹窗

当领取附件时容量不足，弹出 `StandardPromptDialog`（项目已有组件）：

```kotlin
// MailDialog 内
var capacityWarning by remember { mutableStateOf<String?>(null) }

// 领取回调中：
val result = viewModel.claimAttachment(mailId)
if (result is ClaimResult.CapacityInsufficient) {
    capacityWarning = result.message  // "背包空间不足，请清理后再领取" 等
}

// 弹窗：
if (capacityWarning != null) {
    StandardPromptDialog(
        onDismissRequest = { capacityWarning = null },
        title = "无法领取",
        text = capacityWarning,
        confirmLabel = "确定",
        onConfirm = { capacityWarning = null },
        dismissLabel = null,
        onDismiss = null
    )
}
```

### 4.6 素材映射

| 素材文件 | drawable 资源名 | 用途 |
|---|---|---|
| `邮件按钮.png` | `ui_mail_button.webp` | 右上角邮件入口按钮 |
| `对话框与邮件共用.png` | `bg_dialog_mail.webp` | 左侧邮件卡片背景 |
| 已有 `按钮（长方形）.png` | `ui_button.webp` | 领取/一键已读/删除按钮（复用） |

---

## 5. 数据流

```
游戏启动
  → MailService.initialize()
    → fetchOnlineMails()          [在线拉取，含数量上限检查]
    → loadBuiltinMails()          [内置邮件检查，含数量上限检查]
    → cleanExpired()              [清理过期邮件 + 对应 claimed 记录]

每月结算（含定期在线拉取）
  → MailService.onMonthTick()
    → fetchOnlineMails()          [定期轮询，确保长时游玩也能收到新邮件]
    → cleanExpired()

玩家操作
  → 点击邮件按钮 → DialogRoute.Mail → MailDialog
  → 选中邮件 → markAsRead() → 右侧显示详情
  → 点击"领取" → claimAttachment()
      → 检查 claimed_mail_records（幂等门控）
      → 插入审计记录（危险操作前置）
      → 容量检查（不足则弹窗）
      → 发放附件
      → 更新 MailEntity
  → 点击"一键已读" → markAllAsRead()
      → 遍历所有未读/未领邮件
      → 逐封领取（跳过容量不足的）
      → 全部标记已读
  → 点击"删除已读" → deleteAllReadAndClaimed() → 删除已读已领邮件

存档切换
  → MailService.clearForSlot(oldSlotId)  [持 Mutex 清理内存缓存]
  → 加载新存档的邮件列表
```

---

## 6. 测试计划

### 6.1 单元测试（MailService / DAO）

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T1 | 同一邮件在同一存档内重复点击"领取" | 第二次返回 `AlreadyClaimed`，附件不重复发放 |
| T2 | 同一邮件在不同存档各自领取 | 两边都成功，物品各自到账 |
| T3 | 过期邮件点击领取 | 返回 `Expired`，拒绝领取 |
| T4 | 一键已读：3封未读（其中2封有附件） | 全部标已读，2封附件领取成功 |
| T5 | 一键已读：其中1封附件领取时容量不足 | 其余正常领取，容量不足的跳过，返回跳过原因 |
| T6 | 领取弟子附件时宗门已满 | 返回 `CapacityInsufficient`，弹窗提示 |
| T7 | 领取物品附件时背包已满 | 返回 `CapacityInsufficient`，弹窗提示 |
| T8 | 邮件数量达到200封时插入新邮件 | 最早已读已领邮件被删除，新邮件正常插入 |
| T9 | 邮件数量未达200封时插入 | 直接插入，不删除任何邮件 |
| T10 | 过期邮件被清理 | 对应 `claimed_mail_records` 孤儿记录同步删除 |
| T11 | 快速切换存档时点击领取 | Mutex 保护，不会竞态 |
| T12 | 删除已读已领邮件 | 仅删除符合条件的，未读/未领的保留 |
| T13 | 查看邮件 → 标记已读 | 红点数字减1 |
| T14 | 一键已读 → 全部已读 | 红点归零 |
| T15 | 启动游戏 → 拉取在线邮件 | 新邮件正确入库，已存在的跳过（remoteMailId 去重） |
| T16 | 内置邮件重复加载 | `"builtin:{id}"` 前缀去重，不重复插入 |
| T17 | 附件发放失败但审计记录已写入 | 玩家不可重新领取，日志记录完整可追溯 |

### 6.2 UI 测试

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| U1 | 无邮件时打开邮箱 | 左侧列表显示空状态提示 |
| U2 | 有未读邮件时 | 入口按钮显示红点角标+未读数 |
| U3 | 未读/已读邮件排序 | 未读在上，同组按时间降序 |
| U4 | 已读邮件 | 卡片 alpha=0.5f 变暗 |
| U5 | 选中邮件查看 | 右侧面板显示详情+附件 |
| U6 | 容量不足弹窗 | StandardPromptDialog 弹出，点击确定关闭 |

---

## 7. 新增文件清单

| 文件路径 | 说明 |
|---|---|
| `core/model/MailEntity.kt` | 邮件 Room Entity + MailAttachment 数据类 |
| `core/model/ClaimedMailRecord.kt` | 领取审计记录 Room Entity |
| `data/local/MailDao.kt` | 邮件 DAO（含 @Transaction insertWithEnforceLimit） |
| `data/local/ClaimedMailDao.kt` | 领取审计 DAO（含 deleteOrphanedForExpiredMails） |
| `core/engine/service/MailService.kt` | 邮件服务（GameSystem 实现，含 Mutex 保护） |
| `core/config/BuiltinMailConfig.kt` | 内置邮件配置 |

## 8. 修改文件清单

| 文件路径 | 修改内容 |
|---|---|
| `data/local/GameDatabase.kt` | 新增 entities、DAO、迁移 v20→v21 |
| `ui/navigation/GameRoute.kt` | 新增 Mail 路由 |
| `ui/game/components/GameActionButtons.kt` | 新增邮件按钮（日志左侧） |
| `ui/game/components/GameOverlayHost.kt` | 新增 MailDialog 注册 |
| `di/CoreModule.kt` | 注入 MailService、MailDao、ClaimedMailDao |
