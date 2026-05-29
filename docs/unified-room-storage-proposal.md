# 存档架构重构方案（单机→联机演进路线）

> 日期：2026-05-30
> 目标：消除本地 .sav + Room 双写冗余，保留 ProtoBuf 为未来联机通信协议做准备

---

## 一、现状

```
保存：GameData(Entity) → SaveDataConverter → SerializableSaveData → ProtoBuf → .sav
                                  ↘
                                  Room DB（双写）
加载：.sav → ProtoBuf → SerializableSaveData → SaveDataConverter → GameData(Entity)
       Room DB → GameData(Entity)（fallback）
```

问题是**本地写了两份独立格式**。Room DB 和 .sav 是同一份游戏数据的两种存储，维护 1800 行 Converter 映射 + 两套独立的版本迁移。

## 二、目标架构（考虑联机演进）

### 当前阶段（单机）：砍掉 .sav 双写

```
保存：GameData(Entity) → Room DB（唯一本地存储）
加载：Room DB → GameData(Entity)
备份：Room DB 文件 → zip → 云备份
```

### 联机阶段：ProtoBuf 成为网络协议

```
客户端                           服务端
GameData(Entity) ↔ SaveDataConverter ↔ SerializableSaveData ↔ ProtoBuf ↔ 网络
       ↕
    Room DB（本地缓存 + 离线兜底）
```

ProtoBuf 序列化层**保留**，但职责从本地持久化转为 C/S 通信协议。

## 三、各层的最终定位

| 层 | 当前用途 | 重构后 |
|------|----------|--------|
| Room DB | 双写之一 | **唯一本地存储**，WAL 崩溃安全 |
| ProtoBuf / SerializableSaveData | .sav 文件序列化 | **网络协议 schema**，C/S 数据交换 |
| SaveDataConverter | 双模型手工映射 | **保留**，Entity ↔ 网络协议转换 |
| DataCompressor | .sav 压缩 | **保留**，网络传输压缩（省带宽） |
| SaveDataMigrator | 无（之前死代码，刚接入） | **保留**，协议版本迁移（联机兼容多客户端版本） |
| SaveFileHandler | .sav 文件读写 | **删除** |
| FileBackupManager | .sav 文件备份 | **删除** |

## 四、本地不再双写后可删除的代码

| 模块 | 文件 | 估算行数 |
|------|------|----------|
| 文件存储 | `data/engine/SaveFileHandler.kt` | 150 |
| 文件备份 | `data/engine/FileBackupManager.kt` | 150 |
| 文件回退加载 | `StorageEngine.kt` 中的 `.sav` load fallback | 50 |
| 文件序列化入口 | `SerializationModule.kt` 中的 save to file / load from file | 50 |
| 存档配额 | `SerializationQuota.kt`（文件大小限制） | 去掉，本地 DB 不设配额 |

**预计削减 ~400 行**，消除文件 I/O 相关代码。

## 五、哪些保留（联机需要）

| 保留 | 原因 |
|------|------|
| `SerializableSaveData.kt` 全部 | 网络协议 schema |
| `SaveDataConverter.kt` | Entity ↔ ProtoBuf 双向桥接 |
| `SerializationModule.kt` 核心 | 封装序列化/反序列化为 `ByteArray`，供网络层使用 |
| `DataCompressor.kt` | 网络传输压缩 |
| `SaveDataMigrator.kt` | 协议版本兼容，多客户端版本共存 |
| `SerializationTypes.kt` | ProtoBuf 相关枚举和类型 |

## 六、实施分期

```
Phase 1：保存改为只写 Room，.sav 停止写入（保留读取兼容）
Phase 2：.sav → Room 一次性迁移（老用户启动自动执行）
Phase 3：删除 SaveFileHandler / FileBackupManager
         SerializationModule 去掉文件读写入参(c/s 接口保留)
Phase 4（联机时）：SerializableSaveData 直接作为网络协议使用
```

## 七、收益

- 本地不再维护两套独立存储，加字段只走 Room Migration
- ProtoBuf 序列化能力保留，联机时直接复用
- 文件 I/O 代码删除，简化加载流程
- 架构上为联机做好铺垫：数据格式（ProtoBuf）、压缩、版本迁移均已就位
