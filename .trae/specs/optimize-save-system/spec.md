# 存档系统优化 Spec

## Why
用户反馈游戏数据一多就无法读档和存档。当前存档系统使用 SharedPreferences 存储整个游戏数据的 JSON 字符串，而 SharedPreferences 有大小限制（约 1-2MB），当弟子数量、物品、事件等数据增多时，JSON 字符串会超出限制导致存档失败。

## What Changes
- 将存档存储方式从 SharedPreferences 迁移到文件存储（内部存储）
- 实现数据压缩（GZIP）减少存储空间占用
- 实现异步保存机制，避免阻塞主线程
- 添加存档数据清理机制，自动清理过期数据
- 添加存档错误处理和恢复机制
- **BREAKING**: 存档格式变更，需要实现数据迁移

## Impact
- Affected specs: 存档系统、游戏数据持久化
- Affected code: 
  - `SaveManager.kt` - 核心存档逻辑
  - `GameViewModel.kt` - 存档调用
  - `SaveData.kt` - 存档数据模型

## ADDED Requirements

### Requirement: 文件存储替代 SharedPreferences
系统应使用文件存储替代 SharedPreferences 来保存游戏数据，以支持更大的数据量。

#### Scenario: 大数据量存档成功
- **WHEN** 游戏数据量超过 2MB（如 500+ 弟子、大量物品等）
- **THEN** 存档操作应成功完成，不因数据大小而失败

### Requirement: 数据压缩
系统应对存档数据进行 GZIP 压缩，减少存储空间占用。

#### Scenario: 压缩存档
- **WHEN** 保存游戏数据时
- **THEN** 数据应被压缩后存储，压缩率应达到 50% 以上

### Requirement: 异步保存机制
系统应使用异步方式保存数据，避免阻塞主线程。

#### Scenario: 异步保存不阻塞 UI
- **WHEN** 用户触发存档操作
- **THEN** 存档应在后台线程执行，UI 线程不被阻塞

### Requirement: 存档数据清理
系统应自动清理过期的存档数据，如旧战斗日志、旧事件。

#### Scenario: 自动清理旧数据
- **WHEN** 战斗日志超过 100 条或游戏事件超过 50 条
- **THEN** 系统应自动清理最旧的数据

### Requirement: 存档错误恢复
系统应在存档失败时提供错误信息和恢复选项。

#### Scenario: 存档失败处理
- **WHEN** 存档操作失败
- **THEN** 系统应显示错误信息，并保留上一次成功的存档

### Requirement: 数据迁移
系统应支持从旧版 SharedPreferences 存档格式迁移到新的文件存储格式。

#### Scenario: 旧存档迁移
- **WHEN** 用户首次启动新版本应用
- **THEN** 系统应自动检测并迁移旧存档数据到新格式

## MODIFIED Requirements

### Requirement: SaveManager 存储实现
SaveManager 应使用文件存储实现存档功能。

**原实现**: 使用 SharedPreferences 存储 JSON 字符串
**新实现**: 使用内部存储文件 + GZIP 压缩

### Requirement: 存档槽位信息
存档槽位信息应从存档文件元数据读取，而非解析完整存档。

**原实现**: 解析完整 JSON 获取槽位信息
**新实现**: 使用独立的元数据文件或文件属性存储槽位信息
