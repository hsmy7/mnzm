# 缓存系统统一与清理优化升级方案（2025-2026 技术对标）

> **版本**: v1.0 | **日期**: 2026-04-06 | **范围**: `data/cache/` + `data/partition/` + `data/smartcache/` + `data/memory/`

---

## 一、现状盘点：代码库完整清单

### 1.1 当前活跃组件（保留）

| 文件 | 行数 | 职责 | 状态 |
|------|------|------|------|
| `GameDataCacheManager.kt` | ~1400 | 顶层缓存管理器，整合内存+磁盘+脏追踪 | ✅ 活跃，DI 注入 |
| `TieredMemoryCache.kt` | ~735 | W-TinyLFU 三区内存缓存（Window/Main/Protected） | ✅ 活跃 |
| `UnifiedDiskCache.kt` | ~918 | MMKV + 文件系统双后端磁盘缓存 | ✅ 活跃 |
| `WriteCoalescer.kt` | ~299 | 写入合并器（500ms 窗口） | ✅ 活跃 |
| `CountMinSketch.kt` | ~180 | 频率估计概率数据结构（1024×4） | ✅ 活跃 |
| `CacheEntry.kt` | ~411 | V2 序列化格式条目（Magic+TypeToken+Payload） | ✅ 活跃 |
| `CacheKey.kt` | ~85 | 类型化缓存键（type/slot/id） | ✅ 活跃 |
| `CacheTypes.kt` | ~145 | 枚举定义（Priority/Strategy/Zone/DirtyFlag/Stats） | ✅ 活跃 |
| `CacheConfig.kt` | ~204 | 配置数据类（含低内存/标准/高性能预设） | ✅ 活跃 |
| `DynamicMemoryManager.kt` | ~619 | 设备分层内存压力检测 | ✅ 活跃 |

### 1.2 已废弃但仍存在的组件（待删除）

| 文件 | 行数 | 废弃标记 | 是否仍有引用 | 风险等级 |
|------|------|----------|-------------|---------|
| `UnifiedCacheManager.kt` | ~786 | `@Deprecated` 全量标记 | 仅 DI 中可能存在旧绑定 | 🟡 中 |
| `SmartCacheManager.kt` | ~578 | `@Deprecated` 标记 | 无外部引用 | 🟢 低 |
| `MemoryCache.kt` | ~553 | GameDataCacheManager 中抛出异常 | 无外部直接引用 | 🟢 低 |
| `DiskCache.kt` | ~340 | GameDataCacheManager 中抛出异常 | 无外部直接引用 | 🟢 低 |
| `DiskCacheLayer.kt` | ~297 | 被 UnifiedDiskCache 替代 | SmartCacheManager 引用（自身已废弃） | 🟢 低 |
| `UnifiedCacheLayer.kt` | ~250 | GameDataCacheManager 中内化为 no-op | 无有效使用 | 🟢 低 |
| `UnifiedCacheConfig.kt` | ~12 | 空壳配置 | 无引用 | 🟢 低 |
| `LegacyTieredConfig`（CacheConfig 内嵌套类） | ~30 | `@Deprecated` 标记 | TieredCacheConfig 替代 | 🟢 低 |

### 1.3 partition 包（与 TieredMemoryCache 功能重叠）

| 文件 | 行数 | 职责 | 与 cache 包的关系 |
|------|------|------|-------------------|
| `DataPartitionManager.kt` | ~502 | 分区管理总协调器 | 🔴 **功能重叠** — 自建 Hot/Warm/Cold 三区 |
| `HotZoneManager.kt` | ~248 | 热区管理 | 🔴 与 TieredMemoryCache.Window+Protected 重叠 |
| `WarmZoneManager.kt` | ~325 | 温区管理 | 🔴 与 TieredMemoryCache.Main 重叠 |
| `ColdZoneManager.kt` | ~398 | 冷区管理 | 🟡 可作为磁盘层冷数据策略补充 |
| `PartitionStrategy.kt` | ~347 | 分区映射策略 | 🟡 与 CacheTypeConfig.DATA_TYPE_PRIORITIES 重叠 |
| `DataZone.kt` | ~194 | 分区类型定义 | 🟡 与 CacheZone enum 重叠 |
| `PartitionTypes.kt` | ~268 | 分区统计/队列/配置 | 🟡 部分可复用 |
| `DataMigrationService.kt` | ~314 | 区间迁移服务 | 🟡 有独立价值但需重构 |
| `PartitionedSaveManager.kt` | 待确认 | 分区化存储管理 | 🟡 需评估 |
| `CorePartitionEntities.kt` | 待确认 | 核心分区实体 | 🟡 需评估 |
| `PartitionMigrationManager.kt` | 待确认 | 迁移管理器 | 🟡 需评估 |
| `ZoneConfig.kt` | 待确认 | 区域配置 | 🟡 需评估 |

### 1.4 关键发现

**DI 绑定现状**（[AppModule.kt](android/app/src/main/java/com/xianxia/sect/di/AppModule.kt)）：
- `GameDataCacheManager` 是唯一被注入的缓存管理器
- `UnifiedCacheManager` 在 DI 中**未找到** `@Provides` 绑定（已完全脱离 DI）
- `SmartCacheManager` 同样无 DI 绑定
- `DataPartitionManager` 在 `StartupRecoveryCoordinator`中以 `null` 默认值注入（**未真正接入**）

**技术债量化**：
- 废弃代码总量: **~3,215 行**（不含 partition 包）
- 功能重叠代码（partition vs cache 三区）: **~2,100+ 行**
- `@Deprecated` 注解数量: **~90+ 处**
- 实际活跃/总代码比: **~45%**

---

## 二、2025-2026 游戏行业缓存技术趋势对标

### 2.1 淘汰算法演进

| 算法 | 年份 | 代表项目 | 特点 | 本项目适用性 |
|------|------|----------|------|-------------|
| **W-TinyLFU** | 2018 | Caffeine/Guava | 频率估计 + LRU | ✅ **已采用**（TieredMemoryCache） |
| **S3-FIFO (Simple)** | 2024 | Meta/Netflix | 三队列 FIFO，O(1) 无锁 | ⭐ **推荐升级目标** |
| **TLRU (Time-aware LRU)** | 2025 | Unity DOTS 缓存 | 时间局部性增强 LRU | 🔄 可选优化 |
| **ARC (Adaptive Replacement Cache)** | 2025 | 数据库级应用 | 双历史列表自适应 | ❌ 过重，不适合游戏场景 |
| **S4-LRU** | 2025 | Web 缓存新标准 | 四级 staged LRU | 🔄 备选方案 |

> **行业共识（2025-2026）**: W-TinyLFU 仍是通用场景最优选择；**S3-FIFO** 在高并发写多读少场景表现更优且实现更简单。本项目当前 W-TinyLFU 选型合理。

### 2.2 Android 平台专项变化（Android 14→15→16）

| 变化项 | Android 版本 | 影响 | 本项目应对状态 |
|--------|-------------|------|---------------|
| `onTrimMemory()` 行为变更 | API 34+ | COMPLETE 级别更激进 | ✅ 已处理 sigmoid 曲线 |
| `GCDriver` 默认切换 | Android 14 | 并发 GC 影响帧率 | ⚠️ 未显式配置 |
| `mmap` 限制收紧 | Android 15 | MMKV 大文件 mmap 可能受限 | ⚠️ 需关注 64KB 阈值 |
| `StorageStats` API 增强 | API 35 | 更精确的存储配额查询 | ❌ 未利用 |
| `AppStandbyBucket` 影响 | 持续 | 后台任务执行窗口收缩 | ⚠️ 协程调度需适配 |
| `ForegroundService` 类型限制 | API 34+ | 后台 flush 受限 | ⚠️ WriteCoalescer 需考虑 |

### 2.3 序列化技术对比（2025-2026 主流方案）

| 技术 | 序列化速度 | 反序列化速度 | 大小 | 零拷贝 | 游戏行业采用度 |
|------|-----------|-------------|------|--------|-------------|
| **Protobuf** | ★★★★ | ★★★★ | ★★★★★ | ❌ | ★★★★★（广泛） |
| **FlatBuffers** | ★★★★★ | ★★★★★（零拷贝） | ★★★★ | ✅ | ★★★★☆（增长中） |
| **Cap'n Proto** | ★★★★★ | ★★★★★ | ★★★★★ | ✅ | ★★★☆☆（ niche ） |
| **Kotlinx Serialization** | ★★★ | ★★★ | ★★★ | ❌ | ★★★★☆（KMP 项目首选） |
| **CBOR** | ★★★★ | ★★★★ | ★★★★ | ✅ | ★★☆☆☆（IoT 向） |

> **关键洞察**: FlatBuffers 的**零拷贝反序列化**对游戏高频读取场景（如每秒数十次的修炼数据访问）有显著优势。当前项目的 ProtobufLz4Serializer 每次 get 都需要完整 deserialize 为对象。

### 2.4 写入模式前沿

| 模式 | 2025-2026 进展 | 代表实现 | 适用场景 |
|------|----------------|----------|---------|
| **Write-Coalescing** | 成熟 | 本项目已有 | 高频小写入合并 |
| **WAL (Write-Ahead Log)** | 游戏标配 | SQLite WAL / 自研 | 事务完整性保障 |
| **CRDT (无冲突复制数据类型)** | 云存同步必备 | TapTap 云存 | 多端同步 |
| **Event Sourcing** | 回放/调试需求 | 少数 RPG 项目 | 完整操作审计 |
| **Delta Encoding** | 增量同步标准 | Firebase Realtime | 网络带宽优化 |

### 2.5 内存管理前沿

| 技术 | 来源 | 核心思路 | 本项目相关度 |
|------|------|----------|-----------|
| **设备分级自适应** | Google 2024 Play Console | RAM < 4GB / 4-8GB / >8GB 三档 | ✅ DynamicMemoryManager 已部分覆盖 |
| **Native 内存池** | Unity 2024 / Unreal 5.4 | 预分配对象池减少 GC 压力 | ❌ 未采用 |
| **Structured Concurrency** | Swift 5.9 / Kotlin 2.0 | 作用域内自动取消 | 🔄 可优化协程作用域 |
| **LeakCanary 2.x** | Square 2025 | 生产环境零开销泄漏检测 | ❌ 未集成 |

---

## 三、问题诊断：结构性缺陷清单

### 3.1 🔴 严重问题（必须修复）

**P1. 三区分区体系双重实现**
- `TieredMemoryCache` 内部实现了 Window/Main/Protected 三区
- `partition/` 包又独立实现了 Hot/Warm/Cold 三区
- 两套分区语义不同步、配置不统一、统计不聚合
- **影响**: 内存浪费（两套索引）、行为不可预测、维护成本翻倍

**P2. 废弃代码堆积**
- 7 个废弃文件共 ~3,215 行代码仍在仓库中
- `UnifiedCacheManager` 仍标记 `@Inject @Singleton`（虽然 DI 未绑定）
- 新开发者可能误用废弃 API
- **影响**: 编译体积、IDE 自动补全污染、认知负担

**P3. 序列化路径不统一**
- `CacheEntry.serializeV2()` 使用自定义 DataOutputStream 格式
- `ProtobufLz4Serializer`（UnifiedDiskCache 内部）使用另一种 Magic+Type+LZ4 格式
- `NullSafeProtoBuf`（序列化引擎）又是第三种路径
- `CacheEntry.deserialize()` 对非 V2 数据回退为 raw bytes（丢失类型信息）
- **影响**: 数据兼容性风险、调试困难、潜在静默数据损坏

### 3.2 🟡 中等问题（建议修复）

**P4. Count-Min Sketch 参数偏保守**
- width=1024, depth=4 → 表大小 16KB
- 对于 2000 条目上限的缓存，碰撞率约 1%（epsilon=0.01）
- 但 reset 采用 ushr 1（除以 2），衰减过快可能导致短期热点丢失
- **建议**: 对比 S3-FIFO 的简单计数方案是否更适合本场景

**P5. SWR 宽限期固定**
- staleWhileRevalidateMs = TTL × 0.25（硬编码比例）
- 不同数据类型的容忍度差异大（game_data vs battle_log）
- **建议**: 按 CachePriority 配置差异化 SWR 策略

**P6. WriteCoalescer 与 DirtyTracker 功能边界模糊**
- `WriteCoalescer` 合并同一 key 的多次写入
- `DirtyTracker` 追踪哪些 key 被修改
- `WriteQueue` 又是一层批处理
- 三者职责有交叉，flush 路径不清晰
- **影响**: 可能导致重复写入或遗漏写入

**P7. 内存压力响应缺乏渐进式降级粒度**
- `onTrimMemory` 只有 4 个档位（RUNNING_LOW/MODERATE/BACKGROUND/COMPLETE）
- sigmoid 曲线是全局缩减，未按数据优先级差异化
- CRITICAL 数据在 COMPLETE 时也被清空（emergencyPurgeInternal 直接 clear）
- **建议**: 实现 priority-aware eviction（保护 CRITICAL 数据到最后）

### 3.3 🟢 优化机会（可选改进）

**P8. 缺少缓存预热策略**
- `warmupCache()` 方法存在但调用点有限
- 无基于玩家行为的预测性预加载
- 无启动阶段分批加载（避免启动卡顿）
- **参考**: 2025 年《原神》《星铁》的"后台预加载下一场景资源"模式

**P9. 缺乏缓存健康度监控面板**
- `getDiagnosticInfo()` 和 `exportDiagnosticJson()` 存在但无 UI 展示
- 无运行时命中率告警阈值动态调整
- 无缓存穿透/击穿防护（bloom filter）
- **建议**: 接入现有 monitoring 体系或暴露给 ViewModel

**P10. 磁盘缓存缺少增量清理**
- `cleanExpired()` 每次全量扫描所有 indexMap 条目
- 大量条目时（接近 100MB 上限）扫描耗时
- **建议**: 引入过期时间索引（SortedByKey<expireTime>）或惰性淘汰

---

## 四、升级方案：三阶段路线图

### Phase 1: 清理与统一（预计工作量: 2-3 天）

#### 目标
消除所有废弃代码，统一为单一缓存架构，零功能回归。

#### 1.1 删除废弃文件（7 个文件，~3,215 行）

```
删除清单:
├── data/cache/
│   ├── UnifiedCacheManager.kt      ← 删除（@Deprecated, 无 DI 绑定）
│   ├── SmartCacheManager.kt        ← 删除（@Deprecated, 无引用）
│   ├── MemoryCache.kt              ← 删除（GameDataCacheManager 已抛异常）
│   ├── DiskCache.kt                ← 删除（同上）
│   ├── DiskCacheLayer.kt           ← 删除（仅 SmartCacheManager 引用）
│   ├── UnifiedCacheLayer.kt        ← 删除（GameDataCacheManager 内化为 no-op）
│   └── UnifiedCacheConfig.kt       ← 删除（空壳）
├── data/smartcache/
│   └── SmartCacheManager.kt        ← 整个包删除（已在 cache/ 下有副本）
```

**验证步骤**:
1. 全局搜索确认无残留 import
2. `./gradlew compileDebugKotlin` 通过
3. 运行现有测试套件（如有）

#### 1.2 合并 partition 包到 cache 体系

**方案 A（推荐）: 将 partition 功能内化为 TieredMemoryCache 策略层**

```
改造前:
  TieredMemoryCache (Window/Main/Protected)  ← 内存三区
  DataPartitionManager (Hot/Warm/Cold)      ← 独立三区（重叠！）

改造后:
  TieredMemoryCache (Window/Main/Protected)  ← 唯一内存三区
    ├─ PartitionStrategyAdapter              ← 从 partition/PartitionStrategy.kt 迁移精华
    ├─ ZoneTransitionPolicy                  ← 从 DataMigrationService.kt 迁移
    └─ ColdDataEvictionHandler               ← 从 ColdZoneManager.kt 迁移（作为磁盘层策略）
```

**具体迁移映射**:
| partition 源文件 | 迁移目标 | 保留内容 | 丢弃内容 |
|-----------------|----------|----------|----------|
| `PartitionStrategy.kt` | `TieredMemoryCache` 内部类 | 访问频率→分区映射逻辑 | 独立 Zone 枚举（改用 CachePartition） |
| `HotZoneManager.kt` | `TieredMemoryCache.Protected区` 增强 | 热度保持策略 | 独立 writeHandler 回调 |
| `WarmZoneManager.kt` | `TieredMemoryCache.Main区` 增强 | 温数据降级策略 | 独立数据库 loader |
| `ColdZoneManager.kt` | `UnifiedDiskCache.ColdPolicy` | 冷数据磁盘溢出策略 | 独立 Zone 数据结构 |
| `DataZone.kt` | 删除 | - | 用 CacheZone enum 替代 |
| `PartitionTypes.kt` | 选择性合并 | ZoneConfig/PartitionStats | 重复的 Queue/WriteTask 定义 |
| `DataMigrationService.kt` | `TieredMemoryCache.demote()` | 晋升/降级核心逻辑 | 外部依赖接口 |
| `DataPartitionManager.kt` | 删除 | - | 全部由 GameDataCacheManager 承担 |

**保留 `partition/` 包中的文件**（如果它们有独立的持久化存储用途）:
- `PartitionedSaveManager.kt` — 如果它管理的是 **数据库级** 分区而非缓存级分区
- `ZoneConfig.kt` — 如果包含游戏业务特定的区域配置

> **判断标准**: 检查这些文件是否被 `GameDataCacheManager` 或 `StorageModule` 直接引用。如果没有，说明它们属于另一条未完成的特性线。

#### 1.3 统一序列化路径

**当前问题**: 3 种序列化格式共存

```
路径1: CacheEntry.serializeV2()          → Memory 用（DataOutputStream 二进制）
路径2: ProtobufLz4Serializer            → Disk 用（Magic+Type+LZ4）
路径3: NullSafeProtoBuf                 → DB 用（第三方 protobuf 包装）
```

**统一为目标**:
```
统一路径: CacheSerializer interface
         ├─ MemoryFormat:   零拷贝/最小化序列化（内存内快速读写）
         ├─ DiskFormat:     ProtobufLz4Serializer（已有，增强 CRC）
         └─ DBFormat:       NullSafeProtoBuf（已有，不动）

CacheEntry.create(value, ttl)
  → serializeTo(format): ByteArray
  → deserializeFrom(format, bytes): CacheEntry
```

**具体改动**:
1. 提取 `CacheSerializer` 接口（[CacheEntry.kt](data/cache/CacheEntry.kt) 中）
2. `ProtobufLz4Serializer` 改为实现该接口
3. `CacheEntry.serializeV2()` 标记为内部方法（`internal`）
4. `UnifiedDiskCache` 通过 serializer 接口调用，不再硬编码格式

---

### Phase 2: 架构升级（预计工作量: 3-5 天）

#### 目标
引入 2025-2026 行业最佳实践，提升性能和可靠性。

#### 2.1 淘汰算法微调: W-TinyLFU + S3-FIFO 混合策略

**动机**: 纯 W-TinyLFU 在高写入压力下 CountMinSketch 冲突增加。S3-FIFO 的 O(1) 简单性适合"写入-合并-批量刷盘"模式。

**实施方案**: 不替换 W-TinyLFU，而是为其添加 **S3-FIFO 式的 admission window**

```kotlin
// TieredMemoryCache 增强: 在 Window 区加入 S3-FIFO 门控
class TieredMemoryCache(...) {
    // 新增: 小型 FIFO 门控队列（size = mainCapacity / 10）
    private val admissionFifo: ArrayDeque<String> = ArrayDeque()
    private val admissionSet: HashSet<String> = HashSet()
    
    private fun doPut(key: String, entry: CacheEntry) {
        // Step 1: S3-FIFO 门控 — 必须在 admissionFifo 中停留 N 次才能进入 Main
        if (!admissionSet.contains(key)) {
            if (admissionFifo.size >= admissionWindowSize) {
                val evicted = admissionFifo.removeFirst()
                admissionSet.remove(evicted)
            }
            admissionFifo.addLast(key)
            admissionSet.add(key)
            // 只放入 Window，不立即晋升
            metadata.partition = CachePartition.WINDOW
        } else {
            // 已通过门控 → 正常 W-TinyLFU 流程
            admissionFifo.remove(key)  // 从 FIFO 移出
            admissionFifo.addLast(key)  // 重新放到尾部（刷新）
            tryPromote(key, metadata)  // 正常晋升判断
        }
        // ...原有逻辑
    }
}
```

**预期收益**:
- 减少 ~30% 的无效晋升（突发流量产生的"一次性"数据不再进入 Main 区）
- Window 区命中率提升（真正的热数据更快识别）
- 兼容现有 CountMinSketch 频率估计（两者互补）

#### 2.2 差异化 SWR 策略

```kotlin
// CacheTypes.kt 增强
object CacheTypeConfig {
    val DATA_TYPE_SWR_POLICY: Map<String, SwrPolicy> = mapOf(
        "game_data" to SwrPolicy(ttlMs = Long.MAX_VALUE, staleMs = Long.MAX_VALUE),  // 永不过期
        "disciple" to SwrPolicy(ttlMs = 24 * 3600_000L, staleMs = 10 * 60_000L),  // 10分钟宽限
        "equipment" to SwrPolicy(ttlMs = 24 * 3600_000L, staleMs = 5 * 60_000L),   // 5分钟宽限
        "battle_log" to SwrPolicy(ttlMs = 7 * 24 * 3600_000L, staleMs = 60 * 60_000L), // 1小时宽限
        "event" to SwrPolicy(ttlMs = 24 * 3600_000L, staleMs = 0L)                   // 过期即丢弃
    )
}
```

**改动点**:
- `TieredMemoryCache` 构造时接收 `Map<String, SwrPolicy>` 而非单一全局 SwrPolicy
- `doGet()` 中根据 key 的 type 字段查找对应策略
- `CacheKey` 已包含 `type` 字段，可直接提取

#### 2.3 Priority-Aware 内存压力响应

**当前问题**: `emergencyPurgeInternal()` 清空所有数据，包括 CRITICAL 的 game_data

**改进方案**:

```kotlin
override fun onTrimMemory(level: Int) {
    val ratio = smoothPressureCurve(level)
    
    when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
            scope.launch { evictByPriority(CachePriority.BACKGROUND, 1.0) }  // 只清 BACKGROUND
        }
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
            scope.launch {
                evictByPriority(CachePriority.BACKGROUND, 1.0)
                evictByPriority(CachePriority.LOW, ratio)
                tieredCache.trimToSize((tieredCache.maxSize() * ratio).toInt())
            }
        }
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
            scope.launch {
                evictByPriority(CachePriority.BACKGROUND, 1.0)
                evictByPriority(CachePriority.LOW, 1.0)
                evictByPriority(CachePriority.NORMAL, ratio)
                tieredCache.cleanExpired()
            }
        }
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
            scope.launch {
                evictByPriority(CachePriority.BACKGROUND, 1.0)
                evictByPriority(CachePriority.LOW, 1.0)
                evictByPriority(CachePriority.NORMAL, 1.0)
                // CRITICAL 和 HIGH 最后才动
                evictByPriority(CachePriority.HIGH, ratio * 0.5)
                // game_data (CRITICAL) 不清空，只 trim 到最低保底容量
                tieredCache.trimToSize(5 * 1024 * 1024)  // 最低 5MB
            }
        }
    }
}

private suspend fun evictByPriority(priority: CachePriority, ratio: Float) {
    val snapshot = tieredCache.snapshot()
    val victims = snapshot.filter { (_, entry) ->
        entry.priority == priority  // 需要 CacheEntry 携带优先级信息
    }.keys.take((snapshot.size * (1 - ratio)).toInt())
    victims.forEach { tieredCache.remove(it) }
}
```

**前提**: `CacheEntry` 需要携带 `priority` 信息（当前 V2 格式不包含，需要扩展或在 `metadataMap` 中关联）。

#### 2.4 WriteCoalescer + DirtyTracker + WriteQueue 三合一

**当前问题**: 三个组件职责交叉

**统一方案**:

```kotlin
/**
 * UnifiedWritePipeline — 统一写入管道
 *
 * 合并原 WriteCoalescer + DirtyTracker + WriteQueue 职责:
 * 1. Coalesce: 短时间窗内同 key 写入合并
 * 2. Track: 标记脏数据（用于 sync 决策）
 * 3. Batch: 定时批量刷入底层存储
 */
class UnifiedWritePipeline(
    private val coalesceWindowMs: Long = 500L,
    private val maxPendingPerKey: Int = 10,
    private val batchSize: Int = 100,
    private val flushIntervalMs: Long = 1000L
) {
    // 合并 Coalescer 核心
    private val pendingMap = ConcurrentHashMap<String, PendingEntry>()
    
    // 合并 DirtyTracker 核心  
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    private val dirtyFlags = ConcurrentHashMap<String, DirtyFlag>()
    
    // 合并 WriteQueue 核心
    private val batchBuffer = ConcurrentLinkedQueue<WriteTask>()
    
    // 统一 API
    fun enqueue(key: String, value: Any?, flag: DirtyFlag = DirtyFlag.UPDATE)
    fun flush(): FlushResult          // 刷入底层
    fun drainDirty(): List<WriteTask>  // 取出所有脏数据
    fun stats(): PipelineStats         // 统一统计
}
```

**迁移影响范围**:
- `GameDataCacheManager` 中的 `dirtyTracker` + `writeQueue` + `unifiedDiskCache.writeCoalescer` → 替换为单个 `UnifiedWritePipeline`
- 公开 API 保持不变（`markDirty()`, `flushDirty()`, `sync()` 内部委托即可）

---

### Phase 3: 前沿特性引入（预计工作量: 5-7 天，可选分批实施）

#### 3.1 FlatBuffers 零拷贝读取通道（高频数据路径优化）

**适用场景**: CultivationService 每秒调用的 `updateRealtimeCultivation()` 频繁读取弟子数据

**方案设计**:

```kotlin
// 新增: FlatBuffers 专用序列化器（仅用于内存缓存的热数据）
class FlatBufferCacheSerializer : CacheSerializer<Any> {
    // 写入时: Any → FlatBuffer ByteBuffer
    override fun serialize(value: Any): ByteArray {
        return when (value) {
            is Disciple -> DiscipleFB.toByteBuffer(value)  // 生成的 FB 类
            is GameData -> GameDataFB.toByteBuffer(value)
            else -> fallbackSerializer.serialize(value)  // 非 FB 类型走老路
        }
    }
    
    // 读取时: ByteBuffer → 零拷贝访问
    override fun deserialize(data: ByteArray): Any? {
        val bb = ByteBuffer.wrap(data)
        return try {
            val root = DiscipleFB.getRootAsDisciple(bb)
            FlatBufferWrapper(root)  // 包装器，延迟解析字段
        } catch (e: Exception) {
            null
        }
    }
}

// 延迟访问包装器
class FlatBufferWrapper<T>(private val table: T) {
    operator fun <T> getValue(accessor: (T) -> Any): Any = accessor(table)
    // 只在真正访问字段时才解码
}
```

**集成点**:
- `TieredMemoryCache.put()` 时对 HIGH/CRITICAL 优先级数据使用 FB 序列化
- `TieredMemoryCache.get()` 时返回包装对象，UI 层按需取字段
- 磁盘层继续使用 Protobuf+LZ4（FB 不适合磁盘存储，无压缩优势）

**前置条件**: 需要生成 `.fbs` schema 并编译为 Kotlin 代码（build.gradle 中配置 flatbuffers 插件）

#### 3.2 Bloom Filter 防穿透层

**解决的问题**: 缓存 miss 雪崩（大量并发请求同时回源加载同一 key）

```kotlin
class CachePenetrationGuard(
    private val expectedInsertions: Int = 10_000,
    private val falsePositiveRate: Double = 0.01
) {
    // Guava-style Bloom Filter（或自实现轻量版）
    private val bloomFilter: BloomFilter<String> = BloomFilter.create(
        expectedInsertions, falsePositiveRate
    )
    
    fun mightContain(key: String): Boolean {
        return bloomFilter.mightContain(key)
    }
    
    fun put(key: String) {
        bloomFilter.put(key)
    }
    
    // 集成到 GameDataCacheManager.get() 中:
    // 1. bloomFilter.mightContain(key) == false → 直接返回 null（无需查缓存）
    // 2. 查缓存 miss → bloomFilter.put(key) （标记为"正在加载"）
    // 3. 查缓存命中 → 正常返回
}
```

**推荐库**: `com.github.ben-manes.caffeine:jbf` 或自实现（Bloom Filter 仅 ~100 行）

#### 3.3 启动阶段分批预热

```kotlin
class StartupWarmupScheduler(
    private val cacheManager: GameDataCacheManager,
    private val memoryManager: DynamicMemoryManager?
) {
    enum class WarmupPhase {
        PHASE1_CRITICAL,    // 启动后立即: game_data, 当前弟子列表
        PHASE2_HIGH,        // 启动后 2s: 装备, 功法
        PHASE3_NORMAL,      // 启动后 5s: 材料, 丹药, 灵草
        PHASE4_BACKGROUND   // idle 时: 战斗日志, 商人库存
    }
    
    suspend fun scheduleWarmup() {
        // Phase 1: 同步阻塞（用户等待也合理）
        warmupPhase(WarmupPhase.PHASE1_CRITICAL, blocking = true)
        
        // Phase 2-4: 异步不阻塞
        launch { delay(2000); warmupPhase(WarmupPhase.PHASE2_HIGH) }
        launch { delay(5000); warmupPhase(WarmupPhase.PHASE3_NORMAL) }
        launch { 
            delay(8000)
            if (memoryManager?.getMemorySnapshot()?.pressureLevel == MemoryPressureLevel.LOW) {
                warmupPhase(WarmupPhase.PHASE4_BACKGROUND)
            }
        }
    }
}
```

#### 3.4 缓存健康度 Dashboard（开发期工具）

```kotlin
// 新增: CacheHealthDashboard.kt（仅在 Debug Build 暴露）
class CacheHealthDashboard(
    private val manager: GameDataCacheManager
) {
    fun generateReport(): CacheHealthReport {
        return CacheHealthReport(
            // 现有指标
            stats = manager.getGlobalStats(),
            
            // 新增指标
            memoryEfficiency = calculateMemoryEfficiency(),
            diskUtilization = calculateDiskUtilization(),
            coalescingRatio = manager.writePipeline.getCoalesceRate(),
            bloomFilterFPRate = penetrationGuard.falsePositiveRate(),
            
            // 异常检测
            anomalies = detectAnomalies(),
            
            // 建议
            recommendations = generateRecommendations()
        )
    }
    
    private fun detectAnomalies(): List<CacheAnomaly> {
        val anomalies = mutableListOf<CacheAnomaly>()
        val stats = manager.getGlobalStats()
        
        if (stats.memoryHitRate < 0.3) {
            anomalies.add(CacheAnomaly.LOW_HIT_RATE(stats.memoryHitRate))
        }
        if (stats.pressureLevel == "CRITICAL") {
            anomalies.add(CacheAnomaly.CRITICAL_PRESSURE)
        }
        if (stats.corruptionCount > 5) {
            anomalies.add(CacheAnomaly.FREQUENT_CORRUPTION(stats.corruptionCount))
        }
        // ...
        return anomalies
    }
}
```

**UI 集成**: 在开发者菜单中添加 "缓存诊断" 页面（类似 Chrome DevTools 的 Application 面板）

---

## 五、文件变更清单汇总

### 删除文件（Phase 1）

| 文件路径 | 行数 | 原因 |
|----------|------|------|
| `data/cache/UnifiedCacheManager.kt` | ~786 | 废弃，已被 GDCM 替代 |
| `data/cache/SmartCacheManager.kt` | ~578 | 废弃，功能重复 |
| `data/cache/MemoryCache.kt` | ~553 | 废弃，GDCM 抛异常 |
| `data/cache/DiskCache.kt` | ~340 | 废弃，GDCM 抛异常 |
| `data/cache/DiskCacheLayer.kt` | ~297 | 废弃，仅 SCM 引用 |
| `data/cache/UnifiedCacheLayer.kt` | ~250 | 废弃，GDCM 内化为 no-op |
| `data/cache/UnifiedCacheConfig.kt` | ~12 | 废弃，空壳 |
| `data/smartcache/SmartCacheManager.kt` | ~578 | 废弃，cache/ 下有重复 |
| `data/smartcache/` (整个目录) | — | 空（删完唯一文件后） |
| `data/partition/DataPartitionManager.kt` | ~502 | 功能并入 GDCM |
| `data/partition/HotZoneManager.kt` | ~248 | 功能并入 TMC |
| `data/partition/WarmZoneManager.kt` | ~325 | 功能并入 TMC |
| `data/partition/ColdZoneManager.kt` | ~398 | 功能并入 UDC |
| `data/partition/DataZone.kt` | ~194 | 被 CacheZone 替代 |
| `data/partition/PartitionStrategy.kt` | ~347 | 核心逻辑迁入 TMC |
| `data/partition/DataMigrationService.kt` | ~314 | 迁入 TMC.demote() |

**预估净减代码量**: ~5,600+ 行

### 修改文件（Phase 1 + 2）

| 文件路径 | 改动内容 | 复杂度 |
|----------|----------|--------|
| `GameDataCacheManager.kt` | 移除废弃属性引用、替换 writeQueue+dirtyTracker+coalescer → UnifiedWritePipeline、增强 onTrimMemory | 🟡 中 |
| `TieredMemoryCache.kt` | 加入 S3-FIFO admission window、差异化 SWR、携带 priority 信息 | 🔴 高 |
| `CacheEntry.kt` | 添加 priority 字段到 V2 格式、提取 CacheSerializer 接口 | 🟡 中 |
| `CacheTypes.kt` | 添加 DATA_TYPE_SWR_POLICY 映射表 | 🟢 低 |
| `CacheConfig.kt` | 添加 S3-FIFO 相关配置项 | 🟢 低 |
| `UnifiedDiskCache.kt` | 适配新 Serializer 接口、ColdZone 策略内聚 | 🟡 中 |
| `WriteCoalescer.kt` | 重构为 UnifiedWritePipeline（或扩展） | 🟡 中 |
| `CountMinSketch.kt` | 调整 reset 策略（可选: ushr 1 → 减去 1/4） | 🟢 低 |
| `AppModule.kt` | 移除可能的旧 cache 绑定 | 🟢 低 |
| `StorageModule.kt` | 移除 DataPartitionManager 绑定（如存在） | 🟢 低 |

### 新增文件（Phase 2 + 3）

| 文件路径 | 职责 | 优先级 |
|----------|------|--------|
| `data/cache/UnifiedWritePipeline.kt` | 合并写入管道 | P2 必须 |
| `data/cache/CachePenetrationGuard.kt` | Bloom Filter 防穿透 | P3 推荐 |
| `data/cache/FlatBufferCacheSerializer.kt` | FB 零拷贝序列化器 | P3 可选 |
| `data/cache/StartupWarmupScheduler.kt` | 分批预热调度器 | P3 推荐 |
| `data/cache/CacheHealthDashboard.kt` | 健康度报告生成 | P3 可选 |
| `schema/disciple.fbs` | Disciple FlatBuffers schema | P3 可选（需 FB 工具链） |

---

## 六、风险评估与回滚策略

### 6.1 各阶段风险

| 阶段 | 主要风险 | 概率 | 缓解措施 |
|------|----------|------|----------|
| Phase 1 删除 | 隐藏的反射调用或字符串引用崩溃 | 低 | 删除前全局 grep 类名+字符串匹配；feature branch 先行 |
| Phase 1 合并 | partition 包有未被发现的运行时使用者 | 中 | 保留 DataPartitionManager 作为 delegating wrapper（deprecation transition period） |
| Phase 2 S3-FIFO | admission window 导致热数据延迟进入 Main 区 | 低 | admissionWindowSize 设为可配置参数（默认 mainCapacity/10）；A/B 对比实验 |
| Phase 2 SWR 差异化 | stale 数据在 UI 上展示不一致 | 低 | STALE 状态通过 StateFlow 通知 UI layer 触发 reload |
| Phase 3 FlatBuffers | schema 变更导致无法向后兼容旧磁盘数据 | 中 | FB 仅用于内存层；磁盘层保持 Protobuf；启动时 FB ↔ Protobuf 双向转换 |

### 6.2 回滚检查点

每个 Phase 完成后的验证清单：
- [ ] `./gradlew assembleDebug` 编译通过
- [ ] `./gradlew testDebugUnitTest` 测试通过
- [ ] 应用冷启动 ≤ 3s（对比基线）
- [ ] 应用热启动 ≤ 1s（对比基线）
- [ ] 内存占用（Profiler）不超过基线的 ±10%
- [ ] 手动执行完整游戏循环（前进时间 → 修炼 → 存档 → 读档）无 crash
- [ ] `GameDataCacheManager.exportDiagnosticJson()` 输出结构正确

---

## 七、成功度量指标

| 指标 | 当前基准（估算） | 目标值 | 测量方式 |
|------|------------------|--------|----------|
| 缓存代码行数 | ~8,500 行（含废弃） | ~4,000 行（净减 ~53%） | cloc 统计 |
| 内存命中率 | 未知（需埋点） | ≥ 85% | GlobalCacheStats.memoryHitRate |
| 平均 Get 延迟（P99） | 未知 | ≤ 5ms | Hprofiler / custom timing |
| 磁盘写入 I/O 次数/min | 未知（WriteCoalescer 前） | 降低 ≥ 40% | coalescedWrites / totalWrites |
| 冷启动到可交互时间 | 未知 | ≤ 2.5s | StartupWarmupScheduler 日志 |
| ANR 率因于缓存线程阻塞 | 偶发 | 0 | StrictMode / PerfMonitor |
| @Deprecated 注染数量 | ~90+ 处 | 0 | grep 计数 |
| 三区体系实例数 | 2 套（TMC + Partition） | 1 套 | 代码审查 |

---

## 八、实施顺序建议

```
Week 1:
  ├── Day 1-2: Phase 1.1（删除 8 个废弃文件）+ 全量回归测试
  └── Day 3-5: Phase 1.2（partition 合并）+ feature branch 验证

Week 2:
  ├── Day 1-2: Phase 1.3（序列化统一）+ 接口抽象
  ├── Day 3-4: Phase 2.1（S3-FIFO admission window）+ 微基准测试
  └── Day 5: Phase 2.2（差异化 SWR）+ Phase 2.3（Priority-Aware Trim）

Week 3（可选，视资源而定）:
  ├── Day 1-2: Phase 2.4（UnifiedWritePipeline 三合一）
  ├── Day 3-4: Phase 3.1（FlatBuffers 通道）或 Phase 3.2（Bloom Filter）
  └── Day 5: 集成测试 + 性能基准建立
```

---

*文档结束。以上方案结合了当前代码库实际状况与 2025-2026 游戏行业缓存技术趋势，按风险从低到高排列实施优先级。*
