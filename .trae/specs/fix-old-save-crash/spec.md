# 修复读取旧存档闪退问题 Spec

## Why

用户报告读取旧存档时游戏闪退。经分析发现，主要原因是游戏版本更新过程中新增了枚举值和数据字段，但 Gson 反序列化时无法正确处理这些变化，导致异常。

## What Changes

* 为 Gson 配置添加枚举容错反序列化器，处理未知枚举值
* 为所有枚举类型添加默认值回退机制
* 在 `loadFromFile` 方法中添加更详细的错误日志
* 添加存档版本检测和数据迁移逻辑

## Impact

* Affected code:
  * `GsonConfig.kt` - 添加枚举容错反序列化器
  * `ModelConverters.kt` - 更新枚举转换器以支持容错
  * `SaveManager.kt` - 添加存档版本检测和迁移
  * `GameEngine.kt` - 添加数据初始化和修复逻辑

## 根本原因分析

### 问题定位

1. **枚举新增值问题**：
   * `DiscipleStatus` 枚举新增了 `ON_MISSION` 值
   * `MissionType` 枚举新增了 `GUARD_CITY` 值
   * 旧存档中可能包含无效的枚举字符串，Gson 默认抛出 `IllegalArgumentException`

2. **数据模型字段变更**：
   * `GameData` 新增了 `activeMissions`、`availableMissions`、`lastMissionRefreshYear` 等字段
   * `SaveData` 新增了 `alchemySlots` 字段
   * 虽然有默认值，但嵌套对象的反序列化可能失败

3. **Gson 默认行为**：
   * Gson 对未知枚举值会抛出异常
   * 没有配置容错机制

### 验证路径

```
旧存档 JSON → Gson.fromJson() → 枚举反序列化失败 → IllegalArgumentException → 应用崩溃
```

## ADDED Requirements

### Requirement: 枚举容错反序列化

系统 SHALL 在反序列化枚举时提供容错机制，遇到未知枚举值时使用默认值而非抛出异常。

#### Scenario: 未知枚举值使用默认值

* **GIVEN** 存档中包含一个当前版本不存在的枚举值
* **WHEN** 系统加载该存档
* **THEN** 系统应使用该枚举类型的默认值
* **AND** 不应抛出异常或崩溃

#### Scenario: 有效枚举值正常加载

* **GIVEN** 存档中包含有效的枚举值
* **WHEN** 系统加载该存档
* **THEN** 系统应正确反序列化该枚举值

### Requirement: 存档版本兼容性检测

系统 SHALL 在加载存档时检测版本差异，并提供适当的迁移或警告。

#### Scenario: 旧版本存档加载

* **GIVEN** 存档版本低于当前游戏版本
* **WHEN** 系统加载该存档
* **THEN** 系统应尝试迁移数据
* **AND** 缺失字段应使用默认值填充

### Requirement: 详细错误日志

系统 SHALL 在存档加载失败时记录详细的错误信息，便于问题诊断。

#### Scenario: 加载失败日志记录

* **GIVEN** 存档加载过程中发生异常
* **WHEN** 异常被捕获
* **THEN** 系统应记录异常类型、消息和堆栈跟踪
* **AND** 应记录存档版本信息（如果可用）

## MODIFIED Requirements

### Requirement: GsonConfig 枚举容错配置

修改 `GsonConfig.kt`，为所有枚举类型注册容错反序列化器。

```kotlin
object GsonConfig {
    
    fun createGson(): Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .serializeNulls()
        .disableHtmlEscaping()
        .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
        .setExclusionStrategies(KotlinInternalFieldExclusionStrategy())
        .registerTypeAdapterFactory(EnumFallbackTypeAdapterFactory())
        .create()
}
```

### Requirement: ModelConverters 枚举转换器更新

更新所有枚举转换器，确保在遇到未知值时返回安全的默认值。

```kotlin
@TypeConverter
@JvmStatic
fun toDiscipleStatus(value: String): DiscipleStatus =
    runCatching { DiscipleStatus.valueOf(value) }
        .getOrDefault(DiscipleStatus.IDLE)
```

### Requirement: SaveManager 加载增强

在 `loadFromFile` 方法中添加更详细的错误处理和日志。

```kotlin
private fun loadFromFile(file: File): SaveData? {
    return try {
        // ... existing code ...
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Enum deserialization failed for ${file.name}, value: ${e.message}", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error while loading ${file.name}", e)
        null
    }
}
```

## REMOVED Requirements

无
