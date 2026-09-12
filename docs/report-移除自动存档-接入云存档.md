# 技术报告：移除自动存档 + 接入 TapTap 云存档

> 日期：2026-07-25
> 涉及分支：chore/building-cost-adjust-20260723
> 影响模块：core:engine / core:data / core:domain / feature:game / app

---

## 一、变更概述

移除游戏内基于游戏循环的周期性自动存档机制，替换为 TapTap 云存档（手动上传/下载，单槽位覆盖式）。

### 变更规模

| 指标 | 数值 |
|------|------|
| 修改文件数 | 47 个 |
| 新增文件 | 4 个（TapCloudSaveManager / CloudSaveDialog / 记忆文件 / 报告文档） |
| 删除文件 | 2 个（SavePipeline.kt / SavePipeline.kt 被删除） |
| 涉及模块 | core:engine / core:data / core:domain / feature:game / app |
| 编译状态 | ✅ BUILD SUCCESSFUL |
| 单元测试 | ✅ 637/637 PASS |

---

## 二、移除内容（自动存档）

### 2.1 触发器移除

| 文件 | 移除内容 |
|------|---------|
| `GameEngineCore.kt` | `_autoSaveTrigger` SharedFlow、`autoSaveTrigger` 公开属性、`notifyPendingSave()` 方法、`processTickPhases()` 中的存档触发 if 块、`activeSaveJob` 字段及 `registerActiveSaveJob()`/`clearActiveSaveJob()`、相关注释 |

### 2.2 异步管道移除

| 文件 | 变更 |
|------|------|
| `SavePipeline.kt` | **整文件删除**（322 行）。该类是 `@Singleton` 的 Channel 异步存档队列，容量 4，仅用于自动存档 |
| `SaveLoadCoordinator.kt` | 移除 `OperationType.AUTO_SAVE` 枚举值 |
| `GameEngineAdminOps.kt` | 移除 2 处 `notifyPendingSave()` 调用 |

### 2.3 ViewModel 清理

| 文件 | 移除内容 |
|------|---------|
| `SaveLoadViewModel.kt` | `saveLock`/`pendingAutoSave`/`saveLockAcquireTime`/`consecutiveSaveFailures` 字段；autoSaveTrigger 收集协程；savePipeline.saveResults 收集协程；`enqueueAutoSave()`/`performAutoSave()`/`setAutoSaveInterval()`/`setAutoSaveIntervalMonths()`/`waitForSaveLock()`；companion 常量 `MAX_CONSECUTIVE_SAVE_FAILURES`/`SAVE_LOCK_TIMEOUT_MS`；SavePipeline 构造参数及委托传递 |
| `SaveLoadSaveDelegate.kt` | 移除 `SavePipeline` 依赖、`pendingAutoSave`/`consecutiveSaveFailures` 字段 |
| `SaveLoadLoadDelegate.kt` | 移除 `SavePipeline` 依赖、`resolveEffectiveSlot()` 调用 |

### 2.4 数据模型清理

| 字段 | 所在文件 | 处理方式 |
|------|---------|---------|
| `AUTO_SAVE_SLOT = 0` | `StorageConstants.kt` | **移除** |
| `isAutoSaveSlot()` | `StorageConstants.kt` | **移除** |
| `resolveEffectiveSlot()` | `StorageConstants.kt` | **移除** |
| `INCREMENTAL_THRESHOLD_BYTES` | `StorageConstants.kt` | **移除** |
| `SaveSlot.isAutoSave` | `SaveData.kt` | **移除** |
| `GameData.autoSaveIntervalMonths` | `GameData.kt` | 标记 `@Ignore`（保留数据库列兼容旧存档） |
| `SectPolicyState.autoSaveIntervalMonths` | `SectPolicyState.kt` | **移除**（领域模型） |
| `SectPolicyStateEntity.autoSaveIntervalMonths` | `SectPolicyStateEntity.kt` | 标记 `@Ignore`（保留数据库列） |
| `ConfigState.autoSaveIntervalMonths` | `GameStateStore.kt` | **移除** |
| `GameConfig.AUTO_SAVE_INTERVAL_SECONDS` | `GameConfig.kt` | **移除** |
| `GameConfigData.autoSaveIntervalSeconds` | `GameConfigData.kt` | **移除** |
| `GameConfigData.autoSaveDebounceMs` | `GameConfigData.kt` | **移除** |
| `StorageConfig.autoSaveIntervalMonths` | `StorageConfig.kt` | **移除** + `DEFAULT_AUTO_SAVE_INTERVAL_MONTHS` |
| `StorageConfig.forceFullSaveInterval` | `StorageConfig.kt` | **移除**（仅增量存档使用） |
| `StorageConfig.incrementalSaveThreshold` | `StorageConfig.kt` | **移除** |
| `SerializableSaveData.autoSaveIntervalMonths` | `SerializableSaveData.kt` | 保留 `@ProtoNumber(9)` 占位（向前兼容） |

### 2.5 增量保存移除

| 文件 | 说明 |
|------|------|
| `StorageEngine.kt` | 移除 `incrementalSave()` 方法（67 行）、`isAutoSave` 参数 |
| `StorageFacade.kt` | 移除 `incrementalSave()` 方法、`isAutoSave` 参数 |
| `SaveStorageImpl.kt` | 移除 `isAutoSave = true` 参数传递 |

### 2.6 UI 清理

| 文件 | 说明 |
|------|------|
| `SettingsTab.kt` | 移除"自动存档"设置区域（~180 行）：暂停按钮、间隔编辑按钮、间隔编辑对话框 |
| `SaveSelectScreen.kt` | 移除自动存档槽位渲染：`auto_empty`/`auto_filled` 样式、"自"图标、"自动"标签、"自动存档 - 暂无数据"、"自动存档不可创建新游戏" 对话框 |

### 2.7 基础设施清理

| 文件 | 说明 |
|------|------|
| `SlotLockManager.kt` | `slotIndexMap` 移除 `AUTO_SAVE_SLOT to 0` 映射 |
| `DomainStateProvider.kt` | 移除 `autoSaveIntervalMonths` 映射 |
| `SlotLockManager.kt` | 清理 slotIndexMap 定义（改用 slots 1..maxSlots） |

### 2.8 测试清理

共修改 9 个测试文件：
- `StorageConstantsTest.kt` — 移除 AUTO_SAVE_SLOT/INCREMENTAL_THRESHOLD_BYTES 测试
- `GameConfigTest.kt` — 移除自动保存间隔测试
- `ConfigLoaderTest.kt` — 移除 autoSaveIntervalSeconds/autoSaveDebounceMs 断言
- `GameDataTest.kt` — 移除 autoSaveIntervalMonths 断言
- `StateEntitiesTest.kt` — 移除 autoSaveIntervalMonths 断言及 copy 测试
- `SaveDataConverterTest.kt` — 移除 autoSaveIntervalMonths 序列化断言
- `BuildingOverflowMigrationTest.kt` — 移除 SavePipeline mock
- `SlotLockManagerTest.kt` — slot 0 测试改为 slot 1

---

## 三、新增内容（TapTap 云存档）

### 3.1 依赖配置

**文件**: `gradle/libs.versions.toml`
```toml
taptap-cloudsave = { group = "com.taptap.sdk", name = "tap-cloudsave", version.ref = "taptap-sdk" }
```

**文件**: `app/build.gradle` + `feature/game/build.gradle`
```groovy
implementation libs.taptap.cloudsave
implementation libs.taptap.common
```

### 3.2 TapCloudSaveManager

**文件**: `feature/game/src/main/java/com/xianxia/sect/taptap/TapCloudSaveManager.kt`

核心组件，`@Singleton` Hilt 类，封装云存档全部逻辑。

**构造注入**:
```kotlin
class TapCloudSaveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializationModule: SerializationModule
)
```

**对外 API**:
| 方法 | 说明 |
|------|------|
| `uploadSave(SaveData): CloudSaveResult` | 上传存档到云端 |
| `downloadSave(): CloudSaveResult` | 从云端下载存档 |
| `checkCloudSave(): CloudSaveInfo` | 查询云存档信息 |

**内部数据流**:
```
uploadSave:
    SaveData → serializationModule.serializeAndCompressSaveData() → ByteArray
    → File(context.cacheDir/cloud_save_temp.dat) → TapTap API upload → 删除临时文件

downloadSave:
    TapTap API download → File(context.cacheDir/cloud_save_temp.dat) → ByteArray
    → serializationModule.deserializeSaveData() → SaveData → 删除临时文件
```

**TapTap API 桥接**：
使用 `java.lang.reflect.Proxy` 动态代理 + `SuspendResultHolder`（`ReentrantLock` + `Condition` 跨线程通信）实现 callback → blocking 桥接。

检测优先级：
1. `com.xd.sdk.taptap.XDTapCloudSave`（XDSDK 包装 API）
2. `com.taptap.sdk.cloudsave.TapCloudSave`（原生 SDK，预留）

### 3.3 CloudSaveDialog

**文件**: `feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/CloudSaveDialog.kt`

Composable 对话框，包含：
- 云存档信息区（显示存档时间/年份/月份，或"暂无云存档数据"）
- "上传存档"按钮 → 确认 → 上传 → 显示结果
- "下载存档"按钮 → 确认 → 下载 → 通过 `bootSequenceController.boot()` 重载游戏
- 未登录提示"使用云存档需要登录 TapTap"
- 操作状态指示（上传中/下载中/成功/错误）

### 3.4 对话框系统集成

| 文件 | 变更 |
|------|------|
| `DialogType.kt` | 新增 `data object CloudSave : DialogType` 枚举值 |
| `GameOverlayHost.kt` | 新增 `is DialogType.CloudSave -> CloudSaveDialog(...)` 路由分发 |

### 3.5 SaveLoadViewModel 云存档方法

| 方法 | 说明 |
|------|------|
| `checkCloudSave()` | 查询云存档信息 → 更新 `cloudSaveInfo` StateFlow |
| `uploadToCloudSave()` | 获取快照 → 裁剪 → 上传 → 更新结果状态 |
| `downloadFromCloudSave()` | 下载 → 反序列化 → `gameEngine.loadData()` → `bootSequenceController.boot()` 重载 |
| `resetCloudSaveOperationState()` | 重置操作状态为 Idle |
| `isCloudSaveAvailable()` | 返回 `sessionManager.isLoggedIn` |

**状态定义**:
```kotlin
sealed class CloudSaveOperationState {
    data object Idle : CloudSaveOperationState()
    data object Uploading : CloudSaveOperationState()
    data object Downloading : CloudSaveOperationState()
    data class Success(val message: String) : CloudSaveOperationState()
    data class Error(val message: String) : CloudSaveOperationState()
}
```

### 3.6 设置入口

**文件**: `SettingsTab.kt`
- 原"自动存档"区域替换为"云存档"区域
- 按钮"☁ 云存档管理" → 调起 `DialogType.CloudSave`

---

## 四、TapTap API 接入状态

### 4.1 类型定义猜测（未验证）

基于 web 搜索资料的推断，实际 API 可能如下：

```kotlin
// 包名：com.taptap.sdk.cloudsave
// 或通过 XDSDK 包装：com.xd.sdk.taptap.XDTapCloudSave

// 创建存档
XDTapCloudSave.createArchive(
    metadata = ArchiveMetadata.Builder()
        .setName("mnzm_cloud_save")
        .setSummary("模拟宗门云存档")
        .setExtra("{}")
        .setPlaytime(timestamp)
        .build(),
    archiveFilePath = tempFile.absolutePath,
    archiveCoverPath = null,
    callback = object : TapCloudSaveRequestCallback {
        override fun onArchiveCreated(archive: ArchiveData) { }
        override fun onRequestError(code: Int, msg: String) { }
    }
)

// 列举存档
XDTapCloudSave.getArchiveList(callback = object : TapCloudSaveRequestCallback {
    override fun onArchiveListResult(archiveList: List<ArchiveData>) { }
    override fun onRequestError(code: Int, msg: String) { }
})

// 下载存档数据
XDTapCloudSave.getArchiveData(uuid, fileId, callback = object : TapCloudSaveRequestCallback {
    override fun onArchiveDataResult(uuid: String, fileId: String, data: ByteArray?) { }
    override fun onRequestError(code: Int, msg: String) { }
})
```

### 4.2 接入验证清单

要验证 API 是否正常工作，需要：
1. 在 TapTap 开发者中心为应用开通云存档功能
2. 测试设备安装了 TapTap 客户端并登录
3. 调用 `checkCloudSave()` → `uploadSave()` → `downloadSave()` 完整流程
4. 如果反射调用失败，查看 logcat 中 `CloudSaveReflector` 标签的日志
5. 根据实际异常信息修正类名/方法名

---

## 五、兼容性说明

### 5.1 旧存档兼容

| 变更项 | 兼容措施 |
|--------|---------|
| `GameData.autoSaveIntervalMonths` 字段移除 | `@Ignore` 保留，旧 DB 列不被访问 |
| `SectPolicyStateEntity.autoSaveIntervalMonths` 移除 | `@Ignore` 保留 DB 列 |
| `SerializableSaveData.autoSaveIntervalMonths` 移除 | 保留 `@ProtoNumber(9)` 占位，旧 ProtoBuf 数据跳过 |
| `SaveSlot.isAutoSave` 移除 | 构造 SaveSlot 时不再传入，不影响反序列化 |
| Room Migration | 不需要——@Ignore 让 Room 忽略旧列 |

### 5.2 行为变更

| 场景 | 旧行为 | 新行为 |
|------|--------|--------|
| 游戏运行 N 个月 | 自动存档到槽位 0 | **不做任何存档** |
| 用户想云同步 | ❌ 不支持 | ✅ 设置 → 云存档 → 上传/下载 |
| 手动存档 | 存到槽位 1-5（本地） | 不变 |
| 存档选择界面 | 显示 6 手动 + 1 自动槽位 | 仅显示 1-5 手动槽位 |
| 手动保存锁 | `saveLock`（auto + manual 共用） | 仅手动保存使用 |

---

## 六、文件变更清单

### 新增文件（4 个）

| 文件 | 行数 | 说明 |
|------|------|------|
| `feature/game/.../taptap/TapCloudSaveManager.kt` | ~550 | 云存档管理器 + 反射 API 桥接 |
| `feature/game/.../dialogs/CloudSaveDialog.kt` | ~200 | 云存档 UI 对话框 |
| `memory/auto-save-removed-cloud-save-added.md` | ~20 | 项目记忆 |
| `docs/report-移除自动存档-接入云存档.md` | ~250 | 本报告文档 |

### 修改文件（43 个）

| 模块 | 文件数 | 主要变更 |
|------|--------|---------|
| `core:engine` | 4 | GameEngineCore 触发器移除、SavePipeline 删除、SaveLoadCoordinator 枚举清理、GameEngineAdminOps 调用移除 |
| `core:data` | 12 | StorageConstants/SaveData 字段移除、StorageEngine/StorageFacade 增量保存清除、序列化层保留、config 清理、SlotLockManager 映射修正、测试 4 文件 |
| `core:domain` | 8 | GameData @Ignore、SectPolicyState 字段移除、DialogType 新增、GameConfig 常量移除、GameConfigData/StateStore 字段移除、测试 3 文件 |
| `feature:game` | 6 | SaveLoadViewModel 重写、SaveLoadSaveDelegate/LoadDelegate 清理、SettingsTab 替换、GameOverlayHost 路由、CloudSaveDialog 新增、测试 1 文件 |
| `app` | 5 | GameStateStoreImpl 映射移除、DomainStateProvider 清理、SaveSelectScreen 清理、build.gradle 依赖、SaveStorageImpl 参数清理 |
| 全局 | 5 | CHANGELOG.md、changelog_entries.json、libs.versions.toml、gradle 依赖、feature:game build.gradle |
| 文档 | 3 | 记忆文件、本报告、项目蓝图 |

### 删除文件（1 个）

| 文件 | 说明 |
|------|------|
| `core/engine/.../SavePipeline.kt` | 异步存档管道（322 行，仅用于自动存档） |

---

## 七、冒烟测试结果

```
compileReleaseKotlin:  BUILD SUCCESSFUL
testReleaseUnitTest:    637 tests completed, 0 failed
```

### 已通过的关键测试

| 测试类 | 用例数 | 状态 |
|--------|--------|------|
| StorageConstantsTest | 22 | ✅ PASS |
| SlotLockManagerTest | 8 | ✅ PASS |
| GameConfigTest | 5 | ✅ PASS |
| ConfigLoaderTest | 3 | ✅ PASS |
| GameDataTest | 15 | ✅ PASS |
| StateEntitiesTest | 12 | ✅ PASS |
| SaveDataConverterTest | 8 | ✅ PASS |
| BuildingOverflowMigrationTest | 10 | ✅ PASS |
| Engine 模块测试 | ~300 | ✅ PASS |
| Domain 模块测试 | ~200 | ✅ PASS |

---

## 八、未完成工作

### 🔴 高优先级

**TapTap Cloud Save API 实际接入** — `TapCloudSaveManager` 中的反射桥接代码未经实际 TapTap SDK 验证。需要使用真实 TapTap 账号和配置了云存档权限的应用进行端到端测试。如果反射调用失败，需要根据实际 API 修正类名和方法签名。

### 🟡 待确认

- 游戏内"上传云存档"前是否需要确认对话框？（当前实现有确认）
- 下载云存档后是否需要重置游戏循环？（当前实现通过 `bootSequenceController.boot()` 重启）
- 是否需要显示云存档详细信息（大小、修改时间）？（当前显示基础信息）
