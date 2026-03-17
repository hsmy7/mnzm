# 修复无法存档问题 Spec

## Why

用户报告游戏存档时会闪退，经诊断发现是 Gson 序列化循环引用导致的 StackOverflowError。

## What Changes

* 修复 Gson 配置，排除 `@Transient` 注解标记的字段

* 防止循环引用导致的序列化崩溃

* **同步修改**：同时修复 `SaveManager.kt` 和 `ModelConverters.kt` 中的 Gson 配置

## Impact

* Affected specs: 存档系统

* Affected code:

  * SaveManager.kt (Gson 配置)

  * ModelConverters.kt (Room TypeConverter Gson 配置)

  * GameData.kt (WorldSect.connectedSects 字段)

## 根本原因分析

### 问题定位

1. `WorldSect` 数据类中有 `@Transient val connectedSects: List<WorldSect>` 字段
2. Gson 默认不识别 `@Transient` 注解，会尝试序列化所有字段
3. `connectedSects` 是 `List<WorldSect>` 类型，包含 `WorldSect` 对象
4. 序列化时形成循环引用：WorldSect → connectedSects → WorldSect → ...
5. 导致无限递归，最终 StackOverflowError 崩溃

### 验证

```kotlin
// GameData.kt 第 340 行
@Transient val connectedSects: List<WorldSect> = emptyList(),
```

这个 `@Transient` 注解在 Room 中有效（不持久化到数据库），但在 Gson 中默认无效。

## 向后兼容性分析

### 对旧存档的影响：**无影响**

1. **旧存档加载**：

   * 旧存档中没有 `connectedSects` 字段（因为它是运行时计算的临时字段）

   * 加载后 `connectedSects` 使用默认值 `emptyList()`

   * `GameEngine.loadData()` 中会调用 `initializeWorldMap()` 重新计算 `connectedSects`

2. **新存档保存**：

   * 修改后，`connectedSects` 字段不会被序列化

   * 存档文件结构与之前预期一致

   * 不会增加存档文件大小

3. **数据完整性**：

   * `connectedSects` 是派生数据，可以从 `connectedSectIds` 和 `worldMapSects` 重新计算

   * 不需要持久化存储

## ADDED Requirements

### Requirement: Gson 排除 transient 字段

Gson 配置应排除带有 `@Transient` 注解的字段，防止循环引用。

#### Scenario: 序列化时排除 transient 字段

* **WHEN** 序列化包含 `@Transient` 字段的对象

* **THEN** Gson 应跳过这些字段，不进行序列化

#### Scenario: 反序列化时排除 transient 字段

* **WHEN** 反序列化对象

* **THEN** Gson 应跳过 `@Transient` 字段，使用默认值

## MODIFIED Requirements

### Requirement: SaveManager Gson 配置

修改 SaveManager 中的 Gson 配置，添加 transient 字段排除策略。

```kotlin
private val gson: Gson = GsonBuilder()
    .setDateFormat("yyyy-MM-dd HH:mm:ss")
    .serializeNulls()
    .disableHtmlEscaping()
    .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
    .create()
```

### Requirement: ModelConverters Gson 配置

修改 ModelConverters 中的 Gson 配置，添加 transient 字段排除策略。

```kotlin
private val gson: Gson = GsonBuilder()
    .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT)
    .create()
```

## REMOVED Requirements

无
