# 统一 Room 单存储架构方案

> 日期：2026-05-30
> 目标：消除 Room + .sav 双写架构，统一为 Room 唯一存储，削减 ~4200 行维护代码

---

## 一、现状

```
保存：GameData(Entity) → SaveDataConverter → SerializableSaveData → ProtoBuf → .sav
                                  ↘
                                  Room DB（双写）
加载：.sav → ProtoBuf → SerializableSaveData → SaveDataConverter → GameData(Entity)
       Room DB → GameData(Entity)（fallback）
```

每次保存同时写 Room 数据库和 `.sav` 文件，两套格式不同、模型不同、迁移路径独立。SaveDataConverter 1800 行手动映射代码是双架构的直接产物。

## 二、行业参照

单机手游存档方案调研：

- **Room / WCDB** 为主存储，WAL（Write-Ahead Log）提供崩溃安全，SQLite 自带事务回滚
- 备份用**整文件拷贝**或**Google Drive 云同步**，不另建一套序列化格式
- 双写冗余在游戏存档领域少见——更常见于金融/支付系统（监管合规要求），游戏无此需求
- ProtoBuf 设计目标是跨平台网络传输（C/S 通信），本地持久化属于杀鸡用牛刀
- 主流单机游戏（Stardew Valley、Terraria、Minecraft PE）的存档均采用单一格式 + 简单文件备份，无序列化转换层

## 三、目标架构

```
保存：GameData(Entity) → Room DB（唯一存储，WAL 模式）
加载：Room DB → GameData(Entity)
备份：Room DB 文件 → zip → 备份目录 / 云端
```

## 四、可移除的代码

| 模块 | 文件 | 估算行数 |
|------|------|----------|
| 序列化模型 | `data/serialization/unified/SerializableSaveData.kt` | 930 |
| 转换器 | `data/serialization/unified/SaveDataConverter.kt` | 1800 |
| 序列化引擎 | `data/serialization/unified/UnifiedSerializationEngine.kt` | 560 |
| 版本迁移器 | `data/serialization/unified/SaveDataMigrator.kt` + 4个Migrator | 200 |
| 序列化门面 | `data/serialization/unified/SerializationModule.kt` | 85 |
| 类型/配额 | `data/serialization/unified/SerializationTypes.kt` / `SerializationQuota.kt` | 200 |
| 压缩层 | `data/compression/DataCompressor.kt`（存档压缩部分） | 200 |
| 文件存储 | `data/engine/SaveFileHandler.kt` | 150 |
| 文件备份 | `data/engine/FileBackupManager.kt` | 150 |

**预计削减 ~4200 行代码**。`data/serialization/` 目录可整体移除。

## 五、需要保留和调整的部分

**保留不变：**
- `GameData.kt` + 所有 `@Entity` 类
- `GameDatabase.kt` + Migration 链（成为唯一的版本迁移路径）
- Room DAO 层

**需要调整：**
- `StorageEngine.kt` — 删除文件双写和文件回退加载逻辑
- `StorageFacade` — 简化保存/加载路径，只走 Room
- DI 模块（`StorageModule.kt`）— 移除序列化相关 Provider

## 六、风险与应对

| 风险 | 应对 |
|------|------|
| Room DB 文件损坏 | WAL checkpoint 前先做文件快照；利用 Android AutoBackup |
| 旧 `.sav` 存档无法读取 | 保留一次性迁移工具：启动时检测老 `.sav` → 转 Room → 删除 `.sav` |
| 云存档格式不兼容 | 改为上传 Room DB 文件（可 zip 压缩），版本号嵌入文件名 |
| 实施周期长 | 分四期推进，每期独立可测试 |

## 七、实施分期

```
Phase 1：保存改为只写 Room，.sav 停止写入（但保留读取兼容）
Phase 2：.sav → Room 一次性迁移工具（老用户启动时自动执行一次）
Phase 3：删除 SerializationModule / Converter / ProtoBuf / 压缩层
Phase 4：云存档切换为 Room DB 文件上传
```

每期独立提交，中间版本均可正常运行。

## 八、收益总结

- 删 4200 行维护代码，消除双模型映射
- 加字段只需改 `@Entity` + 一条 `ALTER TABLE ADD COLUMN` migration
- 保存速度提升（跳过 ProtoBuf 序列化 + 压缩）
- 不再有双路径数据不一致的风险
- 新人理解存档系统只需看 Room 层，无需学习 ProtoBuf
