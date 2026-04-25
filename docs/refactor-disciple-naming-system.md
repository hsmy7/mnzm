# 弟子命名系统重构方案

> 数据库版本：13 → 14 | 版本号：2.5.8 → 2.5.9

---

## 一、问题总览

项目中存在 **4套独立的弟子命名实现**，分散在不同文件中，各自维护自己的姓名池和命名逻辑，没有统一的命名服务。

| 来源 | 文件 | 姓氏池 | 名字池(男/女) | 名字长度 | 复姓支持 |
|------|------|--------|--------------|---------|---------|
| 玩家招收弟子 | DiscipleService.kt | 10 | 8/8 | 固定2字 | ❌ |
| 子嗣出生 | CultivationService.kt | 继承父姓 | 35/26 | 固定2字 | ❌(有bug) |
| 招募列表刷新 | CultivationService.kt | 40 | 36/36 | 固定2字 | ❌ |
| AI宗门弟子 | AISectDiscipleManager.kt | 30 | 30/30(单字) | 随机1-2字 | ❌ |
| 通用工具(未使用) | GameUtils.kt | 10+30 | 10+55 | 混合 | ✅ |

### 问题 1：命名逻辑重复且不一致

4处独立实现各自的姓氏池和名字池，池大小差异巨大（DiscipleService 仅8个男名，CultivationService.refreshRecruitList 有36个），风格也不统一。

### 问题 2：子嗣姓氏提取有严重缺陷

```kotlin
// CultivationService.kt:445
val fatherSurname = father.name.firstOrNull()?.toString() ?: ""
```

`firstOrNull()` 只取第一个字符。如果父亲是复姓（如"慕容逍遥"），子嗣会变成"慕逍遥"——姓氏被截断。而 GameUtils 的 xianxiaSurnames 中已包含慕容、上官、欧阳、司徒、南宫等复姓，一旦这些复姓弟子有子嗣，姓氏就会出错。

### 问题 3：Disciple 模型没有独立的姓氏字段

`name` 是一个 `String`，姓和名混在一起。这导致：
- 无法可靠地从全名中提取姓氏
- 子嗣继承姓氏只能靠猜测（取第一个字符）
- 无法按姓氏进行家族/宗族查询

### 问题 4：无重名检测

所有命名实现都不检查是否与现有弟子重名。

### 问题 5：AI宗门弟子名字风格与修仙世界观不符

名字池是"云、风、雷、电、剑、刀、枪、棍、拳、掌"这类单字，组合后会产生"李剑掌""王雷鲸"这类名字，缺乏修仙世界的文雅感。

### 问题 6：GameUtils.generateRandomName 未被弟子系统使用

虽然提供了最完善的命名工具（支持3种风格、包含复姓），但弟子招收/子嗣出生/AI弟子生成都没有调用它，仅兑换码系统使用了。

---

## 二、方案设计

### 核心思路

1. **统一命名服务**：创建 `NameService` 对象，合并所有命名逻辑
2. **姓氏独立存储**：在 `Disciple` 模型中增加 `surname` 字段
3. **复姓优先匹配**：提取姓氏时先检查复姓列表
4. **名字池扩充与质量统一**：合并去重后统一维护

### NameService 设计

```kotlin
object NameService {
    data class NameResult(val surname: String, val fullName: String)
    enum class NameStyle { COMMON, XIANXIA, FULL }

    fun generateName(gender: String, style: NameStyle, existingNames: Set<String>): NameResult
    fun inheritName(parentSurname: String, gender: String, existingNames: Set<String>): NameResult
    fun extractSurname(fullName: String): String
}
```

关键设计点：
- **复姓优先匹配**：`extractSurname` 先检查复姓列表，再回退到单字符
- **重名检测**：接收 `existingNames` 参数，最多尝试50次避免重名
- **名字长度多样性**：约75%概率双字名、25%概率单字名
- **风格参数**：`NameStyle` 控制姓氏池范围

### 数据库迁移策略

```kotlin
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE disciples ADD COLUMN surname TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE disciples_core ADD COLUMN surname TEXT NOT NULL DEFAULT ''")
    }
}
```

- `surname` 默认值为空字符串，旧存档兼容
- 旧弟子在首次被访问时，通过 `extractSurname(name)` 回填 surname

---

## 三、实施过程

### 3.1 新建 NameService

文件：`core/util/NameService.kt`

- 复姓列表：16个（慕容、上官、欧阳、司徒、南宫、诸葛、东方、西门、独孤、令狐、皇甫、公孙、轩辕、太史、端木、百里）
- 单姓列表：60个
- 男双字名：80个（逍遥、无忌、清风、明月、剑心、丹辰、怀瑾、景行、承宇等）
- 女双字名：80个（月华、紫烟、灵芸、清音、幽兰、寒梅、芷若、沐云、婉清等）
- 男单字名：20个（风、云、雷、剑、明、华、天、玄、龙、虎等）
- 女单字名：20个（月、雪、花、梅、兰、竹、玉、珠、霞、虹等）

### 3.2 模型层改动

| 模型 | 改动 |
|------|------|
| Disciple | 新增 `surname: String` 字段，更新 `copyWith` |
| DiscipleCore | 新增 `surname: String` 字段，更新 `fromDisciple` |
| DiscipleAggregate | 新增 `surname` 属性，更新 `toDisciple` |
| SerializableDisciple | 新增 `surname`（ProtoNumber(100)） |
| MutableDisciple (ObjectPool) | 新增 `surname`，更新 `reset/copyFrom/toDisciple` |

### 3.3 序列化层改动

- `SerializationModule.convertDisciple()`：序列化时写入 surname
- `SerializationModule.convertBackDisciple()`：反序列化时读取 surname（旧存档无此字段，默认空字符串）

### 3.4 各调用点替换

| 原位置 | 替换方式 |
|--------|---------|
| DiscipleService.recruitDisciple() | `NameService.generateName(gender, FULL)` |
| CultivationService.createChild() | `NameService.inheritName(fatherSurname, gender)` |
| CultivationService.refreshRecruitList() | `NameService.generateName(gender, FULL)` |
| AISectDiscipleManager.generateRandomDisciple() | `NameService.generateName(gender, XIANXIA)` |
| RedeemCodeManager.generateDisciple() | `NameService.generateName(gender, XIANXIA)` |
| GameUtils.generateRandomName() | 委托给 NameService，保留 API 兼容 |

### 3.5 子嗣姓氏提取修复

```kotlin
// 修复前
val fatherSurname = father.name.firstOrNull()?.toString() ?: ""

// 修复后
val fatherSurname = if (father.surname.isNotEmpty()) father.surname 
                    else NameService.extractSurname(father.name)
```

---

## 四、代码复查修复

复查发现并修复了3个问题：

1. **MutableDisciple.reset() 缺少 surname 重置**：对象池回收再分配时可能残留上一个弟子的姓氏
2. **singleSurnames 中 "萧" 重复**：替换为 "贺"
3. **femaleDoubleNames 中 "梦璃" 和 "灵犀" 重复**：替换为 "初雪" 和 "素锦"

---

## 五、验证结果

- **构建验证**：`compileDebugKotlin` 通过，无编译错误
- **数据库迁移**：版本 13 → 14，disciples 和 disciples_core 两表均添加 surname 列
- **旧存档兼容**：Protobuf 新增字段 ProtoNumber(100) 不与现有编号冲突，默认空字符串保证旧存档安全
- **版本号**：2.5.8 → 2.5.9（versionCode 2076 → 2077）

---

## 六、改动文件清单

### 新建
- `core/util/NameService.kt`

### 修改（14个）
- `core/model/Disciple.kt`
- `core/model/DiscipleCore.kt`
- `core/model/DiscipleAggregate.kt`
- `data/serialization/unified/SerializableSaveData.kt`
- `data/serialization/unified/SerializationModule.kt`
- `data/local/GameDatabase.kt`
- `core/engine/service/DiscipleService.kt`
- `core/engine/service/CultivationService.kt`
- `core/engine/AISectDiscipleManager.kt`
- `core/util/GameUtils.kt`
- `core/engine/RedeemCodeManager.kt`
- `core/util/DiscipleObjectPool.kt`
- `app/build.gradle`
- `CHANGELOG.md`
