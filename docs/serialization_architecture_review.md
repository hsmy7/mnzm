# XianxiaSect 序列化架构分析评估与重构建议

> 分析日期：2026-05-30
> 分析范围：`data/serialization/`、`data/compression/`、`data/engine/`、`data/model/`
> 参照标准：游戏行业序列化设计实践（Unity/Godot/Unreal 存档系统、ProtoBuf Schema Evolution、FlatBuffers 零拷贝模式）

---

## 一、当前架构总览

### 1.1 序列化流水线

```
SaveData (业务模型)
    ↓ SaveDataConverter.toSerializable()
SerializableSaveData (ProtoBuf 序列化模型)
    ↓ UnifiedSerializationEngine.serialize()
ByteArray (二进制: Header + Checksum + Compressed Payload)
    ↓ SaveFileHandler.writeAtomically()
.sav 文件 (磁盘持久化)
```

反序列化沿反方向执行，附加版本迁移步骤。

### 1.2 核心文件及职责

| 层次 | 文件 | 行数 | 职责 |
|------|------|------|------|
| 业务模型 | `data/model/SaveData.kt` | 46 | 定义运行时数据结构 |
| 序列化模型 | `data/serialization/unified/SerializableSaveData.kt` | 930 | ProtoBuf 注解的数据类 |
| 转换器 + 引擎 + 迁移 | `data/serialization/unified/SerializationModule.kt` | 2845 | 双向映射、版本迁移、序列化/压缩/校验/配额 |
| 压缩 | `data/compression/DataCompressor.kt` | 412 | LZ4/ZSTD/GZIP |
| 存储 | `data/engine/StorageEngine.kt` | 809 | Room + 文件双写 + 缓存 |

### 1.3 二进制格式

```
| Magic (2B) | Version (1B) | Format (1B) | Compression (1B) | HasChecksum (1B) | OriginalSize (4B) |
| Checksum (32B, 可选) | Compressed Payload (变长) |
```

- Magic: `0x5853`
- Format: `1` = PROTOBUF
- Compression: `0` = NONE, `1` = LZ4, `2` = ZSTD
- Checksum: SHA-256

---

## 二、当前架构优点

### 2.1 格式选择合理

选用 `kotlinx-serialization` + ProtoBuf 编码，相比 JSON 更紧凑（体积减少 60%+），相比原生 Java 序列化更安全（无反射漏洞），对移动端存档场景是正确选择。

### 2.2 压缩策略分层

| 场景 | 算法 | 特点 |
|------|------|------|
| 自动存档（高频） | LZ4 | ~500MB/s，低延迟 |
| 完整存档（压缩比优先） | ZSTD | 3-5x 压缩率 |
| 云上传（减少带宽） | ZSTD | 同上 |
| Legacy 兼容 | GZIP | 旧格式兼容 |

### 2.3 完整性校验

SHA-256 checksum 嵌入二进制 header，反序列化时自动验证，能检测数据损坏。

### 2.4 分级配额系统

`SerializationQuota` 按域限制（core 64KB / disciple 10MB / inventory 5MB / combat 20MB / total 200MB），防止单模块膨胀撑爆存档。

### 2.5 版本迁移框架

`VersionMigrator` 接口 + 链式迁移（V1→V2→V3→V4），基本框架已搭好。

### 2.6 双写冗余

Room 数据库 + `.sav` 文件双写，提供灾难恢复能力。加载时依次尝试：缓存 → Room → .sav 文件。

---

## 三、关键问题识别

### 问题 1：双模型映射的巨大维护成本 [严重度：高]

**现状**

`SaveData`（业务模型）与 `SerializableSaveData`（序列化模型）是两套完全独立的数据类体系，通过 `SaveDataConverter` 的 1800+ 行手工映射代码连接。

**行业对比**

主流游戏引擎（Unity、Godot、Unreal）的存档系统普遍采用**单一数据模型 + 注解驱动序列化**，避免双模型映射。Unity 的 `[SerializeField]`、Godot 的 `[Export]` 都是让业务模型直接声明序列化语义。

**具体风险**

- 每新增一个字段，需修改 3 处：业务模型、序列化模型、Converter 双向映射
- `convertBackStorageBagItem` 中存在一个 300+ 字符的条件表达式来判断 effect 是否为空，这是典型的维护灾难
- `SerializableDisciple` 有 80 个字段，映射代码中大量 `?: 0`、`?: ""`、`?: false` 默认值散落，极易遗漏或写错
- 任何字段类型变更（如 Int → Long）需要同步修改两处模型 + 两处映射

**证据**

```kotlin
// SaveDataConverter.kt L1438 — 判断 effect 是否为空的条件
val effect = data.effect.takeIf {
    it.cultivationSpeedPercent != 0.0 || it.skillExpSpeedPercent != 0.0 ||
    it.nurtureSpeedPercent != 0.0 || it.breakthroughChance != 0.0 ||
    it.targetRealm != 0 || it.cultivationAdd != 0 || it.skillExpAdd != 0 ||
    it.nurtureAdd != 0 || it.healMaxHpPercent != 0.0 || it.mpRecoverMaxMpPercent != 0.0 ||
    it.hpAdd != 0 || it.mpAdd != 0 || it.extendLife != 0 ||
    it.physicalAttackAdd != 0 || it.magicAttackAdd != 0 ||
    it.physicalDefenseAdd != 0 || it.magicDefenseAdd != 0 || it.speedAdd != 0 ||
    it.critRateAdd != 0.0 || it.critEffectAdd != 0.0 || it.intelligenceAdd != 0 ||
    it.charmAdd != 0 || it.loyaltyAdd != 0 || it.comprehensionAdd != 0 ||
    it.artifactRefiningAdd != 0 || it.pillRefiningAdd != 0 ||
    it.spiritPlantingAdd != 0 || it.teachingAdd != 0 || it.moralityAdd != 0 ||
    it.revive || it.clearAll || it.duration != 0 || it.minRealm != 9 ||
    it.pillCategory.isNotEmpty() || it.pillType.isNotEmpty()
}?.let { convertBackItemEffect(it) }
```

---

### 问题 2：ProtoNumber 编号冲突与混乱 [严重度：高]

**现状**

`SerializableGameData` 中的 ProtoNumber 编号存在重复：

```kotlin
@ProtoNumber(36) val residenceSlots: List<SerializableResidenceSlot> = emptyList(),
@ProtoNumber(36) val forgeSlots: List<SerializableBuildingSlot> = emptyList(),  // 重复编号！
```

编号跳跃严重：1-37 之后跳到 42、45-54、87，中间有注释掉的编号（29、44、55）。

**行业对比**

ProtoBuf 规范要求：
- 字段编号一旦分配不可复用、不可修改
- 删除的字段应标记 `reserved` 而非注释掉
- 重复编号会导致序列化数据损坏（两个字段共享同一 wire tag）

**影响**

重复的 `@ProtoNumber(36)` 意味着 `residenceSlots` 和 `forgeSlots` 在序列化时会写入相同的字段编号，反序列化时后写入的会覆盖先写入的，导致数据丢失。

---

### 问题 3：PillEffect 用 Map<String, Double> 编码，类型安全丧失 [严重度：高]

**现状**

`convertPill` 将 `PillEffect` 的所有字段打包成 `Map<String, Double>`：

```kotlin
effects = mapOf(
    "breakthroughChance" to pill.effects.breakthroughChance,
    "targetRealm" to pill.effects.targetRealm.toDouble(),
    "isAscension" to if (pill.effects.isAscension) 1.0 else 0.0,
    "duration" to pill.effects.duration.toDouble(),
    "cannotStack" to if (pill.effects.cannotStack) 1.0 else 0.0,
    // ... 30+ 个字段
)
```

反序列化时从 Map 中取值并反向转换：

```kotlin
isAscension = (data.effects["isAscension"] ?: 0.0) > 0.5,
duration = (data.effects["duration"] ?: 0.0).toInt(),
```

**行业对比**

ProtoBuf 的核心优势是强类型 schema。用 `Map<String, Double>` 编码结构化数据等于放弃了这个优势。正确做法是为 PillEffect 定义独立的 `@Serializable` 数据类。

**风险**

- 字段名拼写错误只能在运行时发现
- 布尔值用 `> 0.5` 判断，`Double` 精度问题可能导致误判
- Int ↔ Double 转换存在溢出和精度丢失风险
- 新增字段时无法通过编译器检查遗漏

---

### 问题 4：HashedSaveData 的哈希计算不完整 [严重度：中]

**现状**

`HashedSaveData.computeHash()` 只对部分字段计算哈希：

```kotlin
digest.update(data.version.toByteArray(Charsets.UTF_8))
digest.update(data.timestamp.toString().toByteArray(Charsets.UTF_8))
data.gameData?.let { gd ->
    digest.update(gd.sectName.toByteArray(Charsets.UTF_8))
    digest.update(gd.currentSlot.toString().toByteArray(Charsets.UTF_8))
    digest.update(gd.spiritStones.toString().toByteArray(Charsets.UTF_8))
}
digest.update(data.disciples.size.toString().toByteArray(Charsets.UTF_8))
```

只覆盖了 version、timestamp、sectName、currentSlot、spiritStones 和弟子数量。装备、功法、丹药、战斗日志等大量数据完全未纳入。

**行业对比**

完整性校验应对整个序列化后的字节流计算哈希，而非选择性字段。当前 `UnifiedSerializationEngine` 中的 `computeChecksum(rawData)` 才是正确的做法——对完整 ProtoBuf 字节流计算 SHA-256。`HashedSaveData` 的部分哈希反而提供了虚假的安全感。

---

### 问题 5：版本迁移机制脆弱 [严重度：中]

**现状**

- 迁移器用字符串版本号匹配（`"1.0"` → `"2.0"` → `"3.0"` → `"4.0"`），不支持跳版本
- `V2ToV3Migrator` 的迁移逻辑是空操作（只改版本号）
- `V3ToV4Migrator` 使用启发式判断（`duration > 0 && <= 12` 则乘以 30），边界情况会出错
- 迁移只在 `SaveDataMigrator` 中定义，但 `SerializationModule.deserializeSaveData` 并未调用迁移器

**行业对比**

成熟游戏的存档迁移通常：
- 使用整数版本号，支持从任意旧版本直接迁移到最新版本
- 每个迁移步骤是纯函数，有明确的输入输出契约
- 迁移在反序列化后自动执行
- 有迁移失败的回滚机制
- 迁移器注册在 DI 容器中，可独立测试

---

### 问题 6：巨型文件违反单一职责 [严重度：中]

**现状**

`SerializationModule.kt` 一个文件 2845 行，包含：

| 组件 | 估算行数 |
|------|----------|
| `SerializationModule`（门面） | ~85 |
| `UnifiedSerializationEngine`（引擎） | ~560 |
| `SaveDataConverter`（转换器） | ~1800 |
| `SaveDataMigrator` + 3 个版本迁移器 | ~150 |
| 枚举定义（SerializationFormat、CompressionType、DataType） | ~50 |
| 数据类（SerializationContext、SerializationResult 等） | ~200 |

**行业对比**

Kotlin 惯例是一个类一个文件。这种"上帝文件"使代码审查、测试、版本控制都变得困难。Git blame/blame 几乎无法追踪单个类的变更历史。

---

### 问题 7：数据丢失——序列化/反序列化 roundtrip 不一致 [严重度：高]

**现状**

多处转换代码丢弃数据：

```kotlin
// AICaveTeam — 序列化和反序列化都丢弃弟子列表
private fun convertAICaveTeam(team: AICaveTeam): SerializableAICaveTeam {
    return SerializableAICaveTeam(
        disciples = emptyList(),  // 丢弃！
    )
}

// CultivatorCave — 丢弃弟子和资源
private fun convertCultivatorCave(cave: CultivatorCave): SerializableCultivatorCave {
    return SerializableCultivatorCave(
        disciples = emptyList(),   // 丢弃！
        resources = emptyMap(),    // 丢弃！
    )
}

// BattleLogAction — 丢弃 actorId 和 targetId
private fun convertBattleLogAction(action: BattleLogAction): SerializableBattleLogAction {
    return SerializableBattleLogAction(
        actorId = "",    // 丢弃！
        targetId = "",   // 丢弃！
    )
}
```

**行业对比**

存档系统的基本契约是：`deserialize(serialize(data)) == data`。任何数据丢失都违反了这个契约。

---

### 问题 8：ZSTD fallback 的压缩格式歧义 [严重度：低]

**现状**

当 ZSTD 压缩 fallback 到 GZIP 时，`SerializationModule.buildFinalData` 仍将 header 中的 compression 字段标记为 ZSTD。解压时先尝试 ZSTD 解压失败再 fallback GZIP，增加了不必要的错误路径。

**风险**

- 解压路径上产生虚假的错误日志
- 如果未来 ZSTD 库版本变更导致解压行为变化，可能误将 GZIP 数据当作损坏的 ZSTD 数据
- 压缩格式应从数据本身可确定，不应依赖外部状态

---

## 四、重构级建议

### 建议 1：消除双模型，统一为注解驱动的单一模型 [优先级：P3，长期]

**目标**

让业务模型直接支持 ProtoBuf 序列化，消除 SaveDataConverter。

**方案**

```
当前：SaveData → SaveDataConverter → SerializableSaveData → ProtoBuf
目标：SaveData → ProtoBuf（直接序列化）
```

具体步骤：

1. 在 `SaveData` 及其嵌套类型上添加 `@Serializable` 和 `@ProtoNumber` 注解
2. 将 `Disciple`、`EquipmentInstance` 等核心模型的嵌套结构（`CombatAttributes`、`PillEffects`、`EquipmentSet` 等）也添加注解
3. 使用 `@SerialName` 处理枚举序列化，替代当前的 `.name` 手动转换
4. 逐步迁移，保留旧格式的反序列化兼容层
5. 迁移完成后删除 `SerializableSaveData.kt` 和 `SaveDataConverter`

**收益**

- 消除 1800+ 行映射代码
- 新增字段只需修改一处
- 编译器自动检查字段遗漏
- 消除映射逻辑中的默认值不一致风险

**风险**

- 需要确保新格式的 ProtoNumber 编号与旧格式兼容
- 需要为旧格式保留反序列化路径
- 实施周期长，建议在数据模型稳定后推进

---

### 建议 2：修复 ProtoNumber 编号冲突 [优先级：P0，立即]

**目标**

修复编号冲突，建立可持续的编号分配策略。

**方案**

1. **立即修复** `@ProtoNumber(36)` 重复：将 `forgeSlots` 改为 `@ProtoNumber(39)` 或其他未使用编号（注意：`@Deprecated` 字段仍需保留原编号以兼容旧存档）
2. 为已删除字段添加注释标记：`// @ProtoNumber(29) RESERVED - unlockedDungeons removed in v3.0.x`
3. 新字段从各消息类型的最大编号 +1 开始分配，不再复用空隙
4. 建立编号分配表，记录每个编号的用途和变更历史

**编号分配表示例**

| 消息类型 | 编号 | 字段 | 状态 |
|----------|------|------|------|
| SerializableGameData | 1-28 | 基础字段 | 活跃 |
| SerializableGameData | 29 | (unlockedDungeons) | 已删除，reserved |
| SerializableGameData | 36 | residenceSlots | 活跃 |
| SerializableGameData | 36 | forgeSlots | **冲突，需修改** |
| SerializableGameData | 87 | spiritMineExpansions | 活跃 |

---

### 建议 3：将 PillEffect 改为独立的 Serializable 数据类 [优先级：P2]

**目标**

恢复类型安全，消除 `Map<String, Double>` 的反模式。

**方案**

```kotlin
@Serializable
data class SerializablePillEffect(
    @ProtoNumber(1) val breakthroughChance: Double = 0.0,
    @ProtoNumber(2) val targetRealm: Int = 0,
    @ProtoNumber(3) val isAscension: Boolean = false,
    @ProtoNumber(4) val cultivationSpeedPercent: Double = 0.0,
    @ProtoNumber(5) val duration: Int = 0,
    @ProtoNumber(6) val cannotStack: Boolean = true,
    @ProtoNumber(7) val cultivationAdd: Int = 0,
    @ProtoNumber(8) val extendLife: Int = 0,
    @ProtoNumber(9) val physicalAttackAdd: Int = 0,
    @ProtoNumber(10) val magicAttackAdd: Int = 0,
    @ProtoNumber(11) val physicalDefenseAdd: Int = 0,
    @ProtoNumber(12) val magicDefenseAdd: Int = 0,
    @ProtoNumber(13) val hpAdd: Int = 0,
    @ProtoNumber(14) val mpAdd: Int = 0,
    @ProtoNumber(15) val speedAdd: Int = 0,
    @ProtoNumber(16) val critRateAdd: Double = 0.0,
    @ProtoNumber(17) val critEffectAdd: Double = 0.0,
    @ProtoNumber(18) val revive: Boolean = false,
    @ProtoNumber(19) val clearAll: Boolean = false,
    // ... 其余字段
)

@Serializable
data class SerializablePill(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val rarity: Int,
    @ProtoNumber(5) val effects: SerializablePillEffect,  // 替代 Map<String, Double>
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val quantity: Int = 1,
)
```

**兼容性**

此变更会破坏旧存档的 Pill 数据兼容性，需要：
- 在版本迁移器中添加从 `Map<String, Double>` 到 `SerializablePillEffect` 的转换
- 或保留 `Map<String, Double>` 作为 `@ProtoNumber(5)` 的旧格式，新增 `@ProtoNumber(20)` 作为新格式

---

### 建议 4：统一完整性校验 [优先级：P1]

**目标**

确保完整性校验覆盖全部数据。

**方案**

1. 保留 `UnifiedSerializationEngine` 中的 `computeChecksum(rawData)`（对完整字节流的 SHA-256）作为主要校验机制
2. 废弃 `HashedSaveData.computeHash()` 的部分字段哈希，标记为 `@Deprecated`
3. 如果需要应用层级别的数据签名，应在序列化后的字节流上计算，而非在业务对象上选择字段计算
4. 在反序列化路径中，checksum 不匹配时应明确报告错误而非静默返回 null

---

### 建议 5：重构版本迁移系统 [优先级：P2]

**目标**

支持健壮的跨版本迁移。

**方案**

```kotlin
data class SchemaVersion(val major: Int, val minor: Int = 0) : Comparable<SchemaVersion> {
    override fun compareTo(other: SchemaVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor })
}

interface VersionMigrator {
    val fromVersion: SchemaVersion
    val toVersion: SchemaVersion
    fun migrate(data: SerializableSaveData): SerializableSaveData
}

class SaveDataMigrator @Inject constructor(
    private val migrators: Set<@JvmSuppressWildcards VersionMigrator>
) {
    fun migrate(data: SerializableSaveData, targetVersion: SchemaVersion): MigrationResult {
        var current = data
        var maxSteps = 20  // 防止无限循环
        while (current.schemaVersion < targetVersion && maxSteps-- > 0) {
            val migrator = migrators
                .filter { it.fromVersion == current.schemaVersion }
                .maxByOrNull { it.toVersion }  // 选择跳转最远的迁移器
                ?: return MigrationResult.Failed(
                    IllegalStateException("No migrator from ${current.schemaVersion}"),
                    current
                )
            current = migrator.migrate(current)
        }
        return MigrationResult.Success(current)
    }
}
```

关键改进：
- 整数版本号替代字符串
- 支持跳版本（选择最远可达的迁移器）
- 最大步数限制防止无限循环
- 迁移器通过 DI 注入，可独立测试
- 在 `SerializationModule.deserializeSaveData` 中自动调用

---

### 建议 6：拆分巨型文件 [优先级：P1]

**目标**

每个类独立文件，符合 Kotlin 惯例。

**方案**

```
serialization/unified/
├── SerializationModule.kt          (~85行)  门面
├── UnifiedSerializationEngine.kt   (~560行) 引擎
├── SaveDataConverter.kt            (~1800行) 转换器
├── SaveDataMigrator.kt             (~150行)  迁移器
├── V1ToV2Migrator.kt               (~20行)   迁移步骤
├── V2ToV3Migrator.kt               (~20行)   迁移步骤
├── V3ToV4Migrator.kt               (~50行)   迁移步骤
├── SerializableSaveData.kt         (~930行)  序列化模型
├── SerializationTypes.kt           (~250行)  枚举+数据类
└── SerializationQuota.kt           (~180行)  配额系统
```

---

### 建议 7：修复数据丢失问题 [优先级：P0，立即]

**目标**

确保序列化/反序列化的往返一致性（roundtrip fidelity）。

**方案**

1. 为 `AICaveTeam.disciples` 和 `CultivatorCave.disciples/resources` 添加完整的序列化支持
2. 为 `BattleLogAction.actorId/targetId` 添加序列化字段
3. 建立自动化 roundtrip 测试：

```kotlin
@Test
fun `roundtrip - all data types preserved`() {
    val original = createFullyPopulatedSaveData()
    val serialized = serializationModule.serializeAndCompressSaveData(original)
    val deserialized = serializationModule.deserializeSaveData(serialized)
    assertSaveDataEquals(original, deserialized)
}
```

4. 覆盖所有数据类型，特别关注当前丢失的字段

---

### 建议 8：修复 ZSTD fallback 的压缩格式歧义 [优先级：P1]

**目标**

确保压缩格式可从数据本身确定，不依赖外部状态。

**方案**

方案 A（推荐）：当 ZSTD fallback 到 GZIP 时，在 header 中记录实际使用的压缩算法：

```kotlin
val (compressedData, compressionType) = if (rawData.size >= context.compressThreshold) {
    val result = dataCompressor.compress(rawData, algo)
    val actualAlgo = if (algo == CompressionAlgorithm.ZSTD && !isZstdAvailable()) {
        CompressionType.GZIP  // 记录实际算法
    } else {
        context.compression
    }
    result.data to actualAlgo
} else {
    // ...
}
```

方案 B：在 `CompressedData` 中增加 `actualAlgorithm` 字段，与 `requestedAlgorithm` 区分。

方案 C（长期）：如果 ZSTD 库不可用，直接从 `CompressionType` 枚举中移除 ZSTD 选项，避免运行时 fallback 的不确定性。

---

## 五、优先级排序与实施路径

| 优先级 | 建议 | 影响范围 | 实施难度 | 建议时间线 |
|--------|------|----------|----------|------------|
| **P0** | 修复 ProtoNumber(36) 重复 | 数据损坏风险 | 低 | 立即 |
| **P0** | 修复 AICaveTeam/Cave/Action 数据丢失 | 存档数据丢失 | 低 | 立即 |
| **P1** | 拆分巨型文件 | 可维护性 | 低 | 1 周内 |
| **P1** | 统一完整性校验 | 安全性 | 中 | 1 周内 |
| **P1** | 修复 ZSTD fallback 歧义 | 数据一致性 | 中 | 1 周内 |
| **P2** | PillEffect 改为强类型 | 类型安全 | 中 | 2-4 周 |
| **P2** | 重构版本迁移系统 | 长期可维护性 | 高 | 2-4 周 |
| **P3** | 消除双模型统一注解 | 架构根本优化 | 很高 | 数据模型稳定后 |

### 实施路径建议

```
Phase 1（立即）：修复 P0 问题
  ├── 修复 ProtoNumber(36) 重复
  └── 修复数据丢失字段

Phase 2（1 周内）：P1 改进
  ├── 拆分 SerializationModule.kt
  ├── 统一完整性校验
  └── 修复 ZSTD fallback

Phase 3（2-4 周）：P2 改进
  ├── PillEffect 强类型化
  └── 版本迁移系统重构

Phase 4（长期）：P3 架构优化
  └── 消除双模型，统一注解驱动序列化
```

---

## 六、行业参考

### 6.1 序列化格式对比

| 特性 | ProtoBuf (当前) | FlatBuffers | JSON | CBOR |
|------|-----------------|-------------|------|------|
| 零拷贝读取 | ❌ | ✅ | ❌ | ❌ |
| 编码效率 | 高 | 中 | 低 | 中 |
| 解码速度 | 快 | 最快 | 慢 | 快 |
| Schema 演进 | ✅ 强类型 | ✅ 强类型 | ❌ | ❌ |
| Kotlin 支持 | ✅ kotlinx-serialization | 需代码生成 | ✅ kotlinx-serialization | ✅ kotlinx-serialization |
| 适合场景 | 存档持久化 | 实时通信 | 调试/配置 | 嵌入式 |

当前选择 ProtoBuf 是正确的。FlatBuffers 的零拷贝优势在存档场景下不明显（存档需要完整反序列化），但若未来需要实时同步游戏状态（如多人模式），可考虑 FlatBuffers。

### 6.2 游戏行业存档设计模式

| 模式 | 描述 | 当前项目是否采用 |
|------|------|-----------------|
| 增量存档 | 只保存变化的部分 | ❌ 每次全量保存 |
| 分块存档 | 按模块独立保存/加载 | ❌ 单一 SaveData |
| Schema 版本号 | 存档中嵌入 schema 版本 | ✅ version 字段 |
| 校验和 | 防篡改/防损坏 | ✅ SHA-256 |
| 压缩 | 减少磁盘占用 | ✅ LZ4/ZSTD |
| 备份轮转 | 保留多个历史版本 | ✅ StorageBackup |
| 原子写入 | 防止写入中断导致损坏 | ✅ writeAtomically |
| WAL | 预写日志保证一致性 | ✅ WALProvider |

**未采用但建议关注**：

- **增量存档**：当前每次保存全量数据。对于弟子数量多的后期存档，增量保存可显著减少写入时间。但实现复杂度高，建议在性能成为瓶颈时再考虑。
- **分块存档**：将 SaveData 拆分为 Core/Disciple/Inventory/World 等独立模块，按需加载。可减少内存占用，但增加了模块间一致性管理的复杂度。

---

## 七、总结

当前序列化架构在基础选型（ProtoBuf + LZ4/ZSTD + SHA-256）上是合理的，基础设施（校验、压缩、配额、备份）也比较完善。核心问题集中在**双模型映射的维护成本**和**映射代码中的数据丢失/类型安全缺陷**上。

P0 问题（ProtoNumber 冲突、数据丢失）应立即修复，这些是可能导致用户存档损坏的硬 bug。P1 问题（文件拆分、校验统一、ZSTD 歧义）影响可维护性和一致性，应尽快处理。P2/P3 问题（类型安全、迁移系统、架构统一）是长期技术债务，建议按阶段推进。
