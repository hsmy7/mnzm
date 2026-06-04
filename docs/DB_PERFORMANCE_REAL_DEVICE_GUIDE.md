# 真机投影查询 + 索引审查操作指南

## 前置准备

1. 手机连电脑，USB 调试已开启
2. `adb devices` 确认设备在线
3. 安装 debug 包：`./gradlew.bat assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## 一、索引审查 — EXPLAIN QUERY PLAN

### 原理

SQLite 的 `EXPLAIN QUERY PLAN` 告诉你查询走的是 `SEARCH TABLE USING INDEX`（命中索引）还是 `SCAN TABLE`（全表扫描）。全表扫描在万行级数据下会显著拖慢。

### 步骤

#### 1. 找到数据库文件

```bash
# 进入设备 shell
adb shell

# 找到包名对应的数据库目录
run-as com.xianxia.sect
cd databases
ls -la
# 应该看到 xianxia_sect.db 和 xianxia_sect.db-wal
```

#### 2. 打开 sqlite3

```bash
sqlite3 xianxia_sect.db
```

> 如果设备没有 sqlite3，先退出 run-as，用 `adb pull` 拉取到电脑：
> ```bash
> adb shell "run-as com.xianxia.sect cat databases/xianxia_sect.db" > xianxia_sect.db
> # 然后在电脑上用 sqlite3 工具打开
> ```

#### 3. 逐个验证高频查询

```sql
-- 验证 1：弟子列表（最高频，每 tick 都可能调用）
EXPLAIN QUERY PLAN
SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1;

-- 预期结果: SEARCH TABLE disciples USING INDEX index_disciple_slot_id_isAlive
-- 如果显示 SCAN TABLE → 索引未命中

-- 验证 2：按境界筛选弟子
EXPLAIN QUERY PLAN
SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1 AND realm >= 3;

-- 预期: 使用 index_disciple_isAlive_realm 或组合

-- 验证 3：按名称搜索
EXPLAIN QUERY PLAN
SELECT * FROM disciples WHERE slot_id = 1 AND name LIKE '%张三%' AND isAlive = 1;

-- 预期: LIKE '%xxx%' 无法使用索引（前后都模糊），会 SCAN TABLE
-- 这是可接受的——模糊搜索天然需要全表扫描

-- 验证 4：按忠诚度查询
EXPLAIN QUERY PLAN
SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1 AND loyalty < 30;

-- 验证 5：按年龄查询
EXPLAIN QUERY PLAN
SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1 AND age >= 60;

-- 验证 6：装备查询
EXPLAIN QUERY PLAN
SELECT * FROM equipment_stacks WHERE slot_id = 1 AND rarity >= 3;

-- 验证 7：物品按类型查询
EXPLAIN QUERY PLAN
SELECT * FROM pills WHERE slot_id = 1 AND rarity >= 3;
```

#### 4. 判断标准

| EXPLAIN 输出 | 含义 | 行动 |
|-------------|------|------|
| `SEARCH TABLE ... USING INDEX ...` | ✅ 命中索引 | 无需处理 |
| `SEARCH TABLE ... USING COVERING INDEX ...` | ✅✅ 最佳（覆盖索引，无需回表） | 无需处理 |
| `SCAN TABLE ...` | ❌ 全表扫描 | 需加索引 |
| `USE TEMP B-TREE` | ❌ 排序用临时表 | 加排序字段索引 |

### 常见需要关注的 SCAN TABLE 场景

1. **复合查询缺少联合索引**：`WHERE slot_id = 1 AND isAlive = 1 AND realm >= 3` 如果没有 `(slot_id, isAlive, realm)` 联合索引，SQLite 可能只用 `slot_id` 索引然后过滤
2. **`slot_id` 缺失的查询**：所有实体表的主键是 `(id, slot_id)`，如果查询不带 `slot_id`，会扫全表

---

## 二、投影查询 — 只选需要的列

### 原理

Disciple 的 `SELECT *` 返回 50+ 字段（含装备、战斗属性等大字段），每秒可能查询 N 次。如果调用处只需要 `id, name, realm, realmLayer, status` 5 个字段，仍拉回全部 50+ 列就浪费 I/O。

### 测量当前查询耗时

在 App 中临时加日志（或用 Android Studio Profiler 的 Database Inspector）：

```kotlin
// 在调用最高频的 DAO 方法处加临时测量
val start = System.nanoTime()
val disciples = discipleDao.getAliveDisciples(slotId)
val elapsed = (System.nanoTime() - start) / 1_000_000f
if (elapsed > 10f) Log.w("DB_PERF", "getAliveDisciples: ${elapsed}ms, count=${disciples.size}")
```

建议在以下方法处加测量：
- `discipleDao.getAliveDisciples(slotId)` — 总览/弟子列表
- `discipleDao.getDisciplesBySlotId(slotId)` — 全量弟子
- `gameDataDao.getBySlotId(slotId)` — 游戏数据加载

### 只在以下情况做投影

> ⚠️ Room 的投影查询必须返回完整 Entity 或创建专门的数据类，不能"只选部分字段映射到原 Entity"。

**值得改的场景**（数据量大 + 调用频率高）：

1. **弟子列表总览**（只显示名字、境界、状态，不需要战斗属性、装备等大字段）
2. **自动存档元数据**（只需要 slot_id、sectName、gameYear 等摘要字段——已经有投影查询 `getSaveSlotsSummary` ✅）

**不值得改的场景**：
- 调用频率低的单条查询
- 确实需要完整 Entity 的场景（如弟子详情）

### 实施示例

```kotlin
// 新建投影数据类
data class DiscipleSummary(
    val id: String,
    val name: String,
    val realm: Int,
    val realmLayer: Int,
    val status: DiscipleStatus,
    val isAlive: Boolean,
    val discipleType: DiscipleType
)

// DAO 新增投影查询
@Query("""
    SELECT id, name, realm, realmLayer, status, isAlive, discipleType
    FROM disciples
    WHERE slot_id = :slotId AND isAlive = 1
    ORDER BY realm DESC, cultivation DESC
""")
suspend fun getAliveDiscipleSummaries(slotId: Int): List<DiscipleSummary>
```

> 注意：`DiscipleStatus` 和 `DiscipleType` 是枚举，需要 Room TypeConverter 支持。查询返回的是基础类型（Int/String），需要确认 TypeConverter 配置。

---

## 三、快速检查清单

```bash
# 1. 拉取数据库
adb shell "run-as com.xianxia.sect cat databases/xianxia_sect.db" > xianxia_sect.db

# 2. 看表大小
sqlite3 xianxia_sect.db "SELECT COUNT(*) AS cnt FROM disciples;"
sqlite3 xianxia_sect.db "SELECT COUNT(*) AS cnt FROM equipment_stacks;"

# 3. 批量 EXPLAIN 所有索引使用情况
sqlite3 xianxia_sect.db <<'EOF'
.timer on
EXPLAIN QUERY PLAN SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1;
EXPLAIN QUERY PLAN SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1 AND realm >= 3;
EXPLAIN QUERY PLAN SELECT * FROM disciples WHERE slot_id = 1 AND name LIKE '%张三%';
EXPLAIN QUERY PLAN SELECT * FROM equipment_stacks WHERE slot_id = 1;
EXPLAIN QUERY PLAN SELECT * FROM equipment_instances WHERE slot_id = 1 AND ownerId = 'xxx';
EXPLAIN QUERY PLAN SELECT * FROM pills WHERE slot_id = 1;
EXPLAIN QUERY PLAN SELECT * FROM materials WHERE slot_id = 1;
EXPLAIN QUERY PLAN SELECT * FROM herbs WHERE slot_id = 1;
EXPLAIN QUERY PLAN SELECT * FROM exploration_teams WHERE slot_id = 1;
EXPLAIN QUERY PLAN SELECT * FROM battle_logs WHERE slot_id = 1;
.quit
EOF

# 4. 看实际查询耗时
sqlite3 xianxia_sect.db <<'EOF'
.timer on
SELECT * FROM disciples WHERE slot_id = 1 AND isAlive = 1;
SELECT * FROM equipment_instances WHERE slot_id = 1;
SELECT * FROM pills WHERE slot_id = 1;
.quit
EOF
```

---

## 四、预期结论

基于当前代码审查：

- **已有 40+ 索引**，Disciple 表覆盖了 name、realm、isAlive、status、discipleType、loyalty、age 等常见查询字段
- **game_data 存档摘要已有投影查询** ✅
- **Disciple 的 `SELECT *`** 在弟子数量 <500 时影响不大（单个 Disciple 序列化后约 2-5KB，500 弟子 ≈ 1-2.5MB）。但弟子接近 1000 上限时值得做摘要投影
- **最可能的瓶颈**：`LIKE '%keyword%'` 搜索天然全表扫描，如果关键词搜索慢，考虑用 FTS（全文搜索）或用前缀匹配 `LIKE 'keyword%'` 替代
