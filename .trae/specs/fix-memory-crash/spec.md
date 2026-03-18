# 修复长时间运行崩溃问题 Spec

## Why

部分手机在游戏长时间运行后会出现闪退，主要原因是内存管理不当导致的内存泄漏和内存压力累积。

## What Changes

* 添加内存压力监控和主动释放机制
* 优化 StateFlow 的订阅策略，减少不必要的内存占用
* 添加低内存时的自动保存和状态保护
* 优化游戏循环的资源使用
* 添加内存警告处理机制

## Impact

* Affected specs: 游戏核心引擎、内存管理
* Affected code:
  * GameEngine.kt (内存管理)
  * GameViewModel.kt (StateFlow 优化)
  * GameActivity.kt (低内存处理)
  * XianxiaApplication.kt (全局内存监控)

## 根本原因分析

### 问题定位

1. **GameEngine 作为单例持有大量数据**
   - GameEngine 是 Singleton，持有所有游戏数据在内存中
   - 包含多个大型列表：disciples, equipment, manuals, pills, materials, herbs, seeds, teams, buildingSlots, events, battleLogs, alchemySlots
   - 这些数据在游戏运行期间持续增长

2. **StateFlow 使用 SharingStarted.Eagerly**
   - 所有 StateFlow 使用 Eagerly 策略，立即启动并持续运行
   - 即使没有订阅者也会保持活跃状态
   - 导致不必要的内存和 CPU 占用

3. **游戏循环持续运行**
   - 游戏循环每 TICK_INTERVAL 毫秒执行一次
   - 每次循环创建临时对象，累积导致 GC 压力
   - 没有内存使用监控机制

4. **缺少低内存处理**
   - 没有响应系统低内存警告 (onTrimMemory / onLowMemory)
   - 没有在内存紧张时自动保存和释放资源

5. **Compose 状态管理**
   - 大量使用 `remember` 和 `collectAsState`
   - 部分状态在组件销毁后可能未被正确清理

### 验证

```kotlin
// GameViewModel.kt - StateFlow 使用 Eagerly 策略
val gameData: StateFlow<GameData> = gameEngine.gameData
    .stateIn(viewModelScope, SharingStarted.Eagerly, gameEngine.gameData.value)

// GameEngine 作为单例
@Provides
@Singleton
fun provideGameEngine(): GameEngine = GameEngine()
```

## ADDED Requirements

### Requirement: 内存压力监控

系统应监控内存使用情况，在内存压力过大时采取保护措施。

#### Scenario: 内存使用过高时触发警告
* **WHEN** 应用内存使用超过阈值
* **THEN** 系统应记录警告日志并尝试释放非必要资源

#### Scenario: 内存严重不足时自动保存
* **WHEN** 系统报告低内存状态
* **THEN** 系统应自动保存游戏进度并释放可释放的资源

### Requirement: 低内存响应机制

应用应响应系统的低内存警告，执行相应的内存释放操作。

#### Scenario: 收到 TRIM_MEMORY_UI_HIDDEN 时释放 UI 资源
* **WHEN** 系统发送 TRIM_MEMORY_UI_HIDDEN 信号
* **THEN** 应用应释放 UI 相关的缓存资源

#### Scenario: 收到 TRIM_MEMORY_MODERATE 时释放中等优先级资源
* **WHEN** 系统发送 TRIM_MEMORY_MODERATE 信号
* **THEN** 应用应释放非关键数据缓存

#### Scenario: 收到 TRIM_MEMORY_RUNNING_CRITICAL 时紧急处理
* **WHEN** 系统发送 TRIM_MEMORY_RUNNING_CRITICAL 信号
* **THEN** 应用应立即保存游戏状态并释放尽可能多的内存

### Requirement: StateFlow 优化

优化 StateFlow 的订阅策略，减少内存占用。

#### Scenario: 使用 WhileSubscribed 替代 Eagerly
* **WHEN** StateFlow 没有订阅者时
* **THEN** 应停止上游 Flow 的收集，释放相关资源

### Requirement: 游戏循环内存优化

优化游戏循环的资源使用，减少内存分配。

#### Scenario: 复用对象减少 GC 压力
* **WHEN** 游戏循环执行时
* **THEN** 应复用临时对象而非频繁创建新对象

## MODIFIED Requirements

### Requirement: XianxiaApplication 添加内存监控

在 Application 类中添加全局内存监控和低内存响应。

```kotlin
class XianxiaApplication : Application(), ComponentCallbacks2 {
    override fun onCreate() {
        super.onCreate()
        registerComponentCallbacks(this)
    }
    
    override fun onTrimMemory(level: Int) {
        when (level) {
            TRIM_MEMORY_UI_HIDDEN -> { }
            TRIM_MEMORY_MODERATE -> { }
            TRIM_MEMORY_RUNNING_CRITICAL -> { }
        }
    }
}
```

### Requirement: GameActivity 添加低内存处理

在 GameActivity 中添加 onLowMemory 回调处理。

```kotlin
override fun onLowMemory() {
    super.onLowMemory()
    viewModel.performEmergencySave()
}
```

### Requirement: GameViewModel 内存管理增强

添加内存状态管理和主动释放机制。

```kotlin
fun onMemoryPressure(level: Int) {
    when (level) {
        TRIM_MEMORY_MODERATE -> { }
        TRIM_MEMORY_RUNNING_CRITICAL -> {
            performAutoSave()
        }
    }
}
```

## REMOVED Requirements

无
