package com.xianxia.sect.core.perf

/**
 * FlatBuffers 评估报告
 *
 * ## 结论：不建议采用 FlatBuffers，当前 kotlinx.serialization Protobuf 方案已足够
 *
 * 核心判断依据：
 * 1. 本项目的序列化瓶颈不在反序列化速度，而在 Room 数据库 I/O 和 Base64 编码开销
 * 2. FlatBuffers 的零拷贝优势在 Room + SQLite 架构下无法兑现（Room 必须将数据读入内存后才能交给 TypeConverter）
 * 3. 迁移成本极高（需维护 .fbs schema、手写 Kotlin 访问层、双系统共存期），收益不匹配
 * 4. 更有效的优化路径已存在且部分已实施（GameHeavyData 分表、Disciple 多表拆分、增量保存）
 *
 * ---
 *
 * ## 当前序列化开销
 *
 * ### 架构概览
 * - 序列化框架：kotlinx.serialization Protobuf（非 Google Protobuf）
 * - Room TypeConverter：[ProtobufConverters] 将复杂字段序列化为 ByteArray → Base64 → TEXT 列
 * - NullSafeProtoBuf：两套配置（encodeDefaults=true 用于存档，false 用于 Room）
 * - 重型数据分离：GameHeavyData 表存储 aiSectDisciples / sectDetails / exploredSects / scoutInfo / manualProficiencies
 *
 * ### ProtobufConverters 覆盖范围
 * - 基本类型转换器：6 个（List<String>, Map<String,String>, Map<String,Int>, Map<Int,Int>, Map<String,Double>, Map<Int,Boolean>, Set<Int>）
 * - 枚举转换器：9 个（EquipmentSlot, ManualType, PillCategory, MaterialCategory, DiscipleStatus, TeamStatus, SlotStatus, RecipeType, BattleType, BattleResult）
 * - 复杂对象转换器：5 个（GameSettingsData, ElderSlots, SectPolicies, SectScoutInfo, EquipmentNurtureData）
 * - 复杂列表转换器：25+ 个（List<Disciple>, List<BuildingSlot>, List<WorldSect>, List<BattleLogRound> 等）
 * - 复杂 Map 转换器：5 个（Map<String,ExploredSectInfo>, Map<String,SectScoutInfo>, Map<String,List<ManualProficiencyData>>, Map<String,SectDetail>）
 *
 * ### 开销热点分析
 * 1. **Base64 编码膨胀**：ByteArray → Base64 String 导致体积膨胀约 33%，这是 Room TEXT 列兼容性的代价
 * 2. **全量反序列化**：每次读取 game_data 行时，所有 Protobuf 列都会被 TypeConverter 反序列化，
 *    即使业务层只需要其中 1-2 个字段。这是 Room 的固有行为，FlatBuffers 无法改变这一点
 * 3. **重型列表字段**：List<Disciple>（aiSectDisciples）、Map<String,SectDetail>（sectDetails）
 *    是最大的序列化开销来源，已通过 GameHeavyData 分表缓解
 * 4. **Disciple 多表拆分**：Disciple 已拆分为 DiscipleCore / DiscipleCombatStats / DiscipleEquipment /
 *    DiscipleExtended / DiscipleAttributes 五张表 + DiscipleCompact 轻量表，大幅减少了单行序列化压力
 *
 * ### 自动保存对序列化的调用频率
 * - 游戏循环 ~60fps，但自动保存按月触发（非每帧）
 * - 自动保存间隔可配置（默认 3 个月游戏时间）
 * - 保存走 SavePipeline 异步队列，不阻塞主线程
 * - 因此序列化频率约为每数秒一次（取决于游戏速度），而非每帧一次
 *
 * ---
 *
 * ## FlatBuffers 优势
 *
 * ### 1. 零拷贝反序列化
 * - FlatBuffers 不需要解析/反序列化步骤，直接从 ByteBuffer 读取字段
 * - 理论上比 Protobuf 快 10-100x 的反序列化速度
 * - 内存零分配（不需要创建中间对象）
 *
 * ### 2. 随机访问
 * - 可以只读取需要的字段，跳过不需要的部分
 * - 对于大型嵌套结构（如 WorldSect 含 GarrisonSlot 列表），可以只读取 id/name 而不解析全部
 *
 * ### 3. 更小的二进制体积
 * - 不需要解析元数据，schema 信息不在数据中
 * - 比 Protobuf 略小，比 JSON 小很多
 *
 * ---
 *
 * ## FlatBuffers 劣势
 *
 * ### 1. 零拷贝在 Room 架构下无法兑现（关键问题）
 * - Room 从 SQLite 读取数据时，必须将 TEXT/BLOB 列内容加载到 Java/Kotlin 的 String/ByteArray
 * - TypeConverter 接收的已经是 String 参数，FlatBuffers 的"直接从 ByteBuffer 读取"优势完全丧失
 * - 要真正利用零拷贝，需要绕过 Room 直接操作 SQLite cursor + BLOB 列，这等于重写整个数据层
 *
 * ### 2. Kotlin 支持差
 * - FlatBuffers 官方只生成 Java 代码，无 Kotlin 扩展
 * - 生成的代码是 mutable builder 模式，与 Kotlin 的 data class / immutable 惯例严重冲突
 * - 需要手写大量 Kotlin ↔ FlatBuffer 互转的适配层
 * - 项目当前有 40+ 个 @Serializable data class，每个都需要双向转换代码
 *
 * ### 3. Schema 维护成本
 * - 需要维护 .fbs schema 文件，与 Kotlin data class 双向同步
 * - 当前 kotlinx.serialization 直接从 @Serializable 注解生成 serializer，零额外维护
 * - FlatBuffers 的 schema 演进比 Protobuf 更脆弱（字段删除/重命名更危险）
 *
 * ### 4. 构建工具链复杂度
 * - 需要集成 flatc 编译器到 Gradle 构建流程
 * - 当前项目使用 KSP + kotlinx.serialization，无需额外构建步骤
 * - 跨平台构建（Windows/Mac/Linux）需要维护不同平台的 flatc 二进制
 *
 * ### 5. 调试困难
 * - FlatBuffers 二进制不可读，无法像 JSON/Protobuf 文本那样直接查看
 * - 当前 Base64 编码的 Protobuf 数据虽然不可读，但 decode 后可通过 Protobuf 工具解析
 * - FlatBuffers 的调试工具链远不如 Protobuf 成熟
 *
 * ### 6. 写入性能更差
 * - FlatBuffers 的构建过程比 Protobuf 序列化更慢更复杂
 * - 需要预先计算偏移量、反向填充
 * - 本项目的保存操作（写入）比加载操作（读取）更频繁（自动保存每数秒一次）
 * - 写入性能退化是不可接受的
 *
 * ---
 *
 * ## 适用场景分析
 *
 * ### 不适合 FlatBuffers 的场景（本项目全部命中）
 * - 数据存储在 SQLite/Room 中（无法利用零拷贝）
 * - 使用 Kotlin data class 作为核心模型（需大量适配代码）
 * - 写入频率高于读取频率（自动保存 > 加载）
 * - Schema 频繁演进（游戏仍在活跃开发，模型持续变化）
 * - 需要完整的对象图（业务逻辑直接操作 data class，不是只读部分字段）
 *
 * ### 适合 FlatBuffers 的场景（本项目不具备）
 * - 网络协议层：高频传输、低延迟要求、只读部分字段
 * - 资源文件：只读、不修改、启动时加载一次
 * - 实时通信：每帧发送/接收消息，零拷贝反序列化有实际收益
 * - C++ 共享内存：跨语言零拷贝访问
 *
 * ---
 *
 * ## 迁移成本
 *
 * ### 工作量估算
 * | 项目 | 估算 |
 * |------|------|
 * | 编写 .fbs schema（40+ 模型） | 3-5 天 |
 * | 集成 flatc 到 Gradle | 1-2 天 |
 * | 编写 Kotlin ↔ FlatBuffer 适配层 | 5-8 天 |
 * | 修改 ProtobufConverters | 2-3 天 |
 * | 数据迁移（旧存档兼容） | 2-3 天 |
 * | 测试 + 修复 | 3-5 天 |
 * | **总计** | **16-26 天** |
 *
 * ### 风险
 * - 双系统共存期：Protobuf 和 FlatBuffers 并存，增加维护复杂度
 * - 数据丢失风险：迁移过程中序列化/反序列化 bug 可能导致存档损坏
 * - 性能回退风险：FlatBuffers 写入更慢，可能导致自动保存延迟增加
 * - Schema 同步风险：.fbs 和 @Serializable data class 可能不一致
 *
 * ---
 *
 * ## 替代方案（推荐）
 *
 * ### 方案 A：消除 Base64 中间层（预估提升 30-40% 序列化性能）
 * - 将 Room 列类型从 TEXT 改为 BLOB，直接存储 ByteArray
 * - 消除 Base64 编码/解码的 CPU 开销和 33% 体积膨胀
 * - 修改量：ProtobufConverters 的编码方法 + Room Entity 的 @ColumnInfo(typeAffinity = BLOB)
 * - 风险：需要数据库迁移（ALTER TABLE 无法改列类型，需重建表）
 * - 可行性：高，这是当前架构下性价比最高的优化
 *
 * ### 方案 B：继续深化分表策略（已部分实施）
 * - GameHeavyData 分表已实施，将 5 个重型字段独立存储
 * - Disciple 多表拆分已实施（6 张子表 + 1 张轻量表）
 * - 可继续将 game_data 中的其他重型字段拆出：
 *   - worldMapSects → WorldMapStateEntity（已实施）
 *   - alliances / sectRelations → DiplomacyState（已实施）
 *   - spiritFieldPlants / unlockedRecipes → ProductionState（已实施）
 *   - patrolSlots / patrolConfig → PatrolState（已实施）
 * - 效果：每次只需读取/写入变化的子表，减少全量序列化
 *
 * ### 方案 C：选择性反序列化（预估减少 50-70% 无效反序列化）
 * - Room TypeConverter 的固有问题是全量反序列化
 * - 可通过 @Ignore + 手动查询实现按需加载
 * - 对于重型字段（如 aiSectDisciples），只在 UI 需要时才反序列化
 * - 已通过 GameHeavyData + loadHeavyData 参数部分实现
 *
 * ### 方案 D：缓存序列化结果（预估减少 80%+ 重复序列化）
 * - 对未变化的字段，缓存上次的 Base64 编码结果
 * - GameData 的 SettlementStrategy 机制已实现增量保存
 * - 可进一步在 TypeConverter 层面加入 dirty flag 缓存
 *
 * ### 方案 E：使用 kotlinx.serialization Cbor 替代 Protobuf
 * - Cbor 是自描述格式，不需要 Base64 中间层（可直接存 BLOB）
 * - 与现有 @Serializable 注解完全兼容，零迁移成本
 * - 体积比 Protobuf 略大但比 JSON 小，且支持 schema 演进
 * - 可行性：中等，需要评估与现有 Protobuf 存档的兼容性
 *
 * ---
 *
 * ## 总结
 *
 * | 维度 | FlatBuffers | 当前 Protobuf | 替代方案 A（BLOB） |
 * |------|-------------|---------------|-------------------|
 * | 反序列化速度 | 极快（零拷贝） | 中等 | 中等（省去 Base64） |
 * | 序列化速度 | 慢 | 快 | 快（省去 Base64） |
 * | Room 兼容性 | 差（需绕过） | 好 | 好 |
 * | Kotlin 支持 | 差（Java only） | 好（原生） | 好 |
 * | Schema 演进 | 脆弱 | 稳健 | 稳健 |
 * | 迁移成本 | 极高（16-26天） | 无 | 低（3-5天） |
 * | 实际收益 | 极低（Room 限制） | - | 中等（30-40%） |
 *
 * FlatBuffers 的核心优势（零拷贝）在本项目的 Room + SQLite 架构下无法兑现。
 * 建议优先实施方案 A（消除 Base64 中间层），这是当前架构下性价比最高的序列化优化。
 */
object FlatBuffersEvaluation
