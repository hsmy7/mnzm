# 规则：代码质量标准

**本文件即时生效**（2026-08-04 起）。与 CLAUDE.md 的关系：CLAUDE.md 0.1-0.4 铁律与 1-13 章（语言规范/模块架构/规模限制）保留；本文件承载**质量标准 + 坏味道清单 + 审查门禁 + 量化指标 + 跨平台可移植性**，两者交叉引用不重复。游戏未来要做 iOS 端，本文件含跨平台可移植性要求。

---

## 1. 代码质量标准

### 1.1 命名规范（对标 Google Kotlin 风格指南，S 级来源）

| 类别 | 规范 | 示例 |
|------|------|------|
| 类型（类/接口/枚举） | PascalCase | `CultivationService`、`AdPurpose` |
| 函数/方法 | camelCase，动词短语 | `checkpointAllProduction()` |
| 常量 | UPPER_SNAKE_CASE，标量必须 `const` | `MAX_THEFT_PER_YEAR = 3` |
| 后备属性 | 下划线前缀 | `private var _slots` |
| 测试类 | 被测试类名开头 + `Test` 结尾 | `InventorySystemConsolidateTest` |
| 测试函数 | camelCase 或反引号句子 | `` `addEquipmentStack - empty name returns INVALID_NAME` `` |

**禁止**：`mName`/`s_name` 等特殊前缀后缀（标识符仅用 ASCII 字母数字）、通配符导入（`import xxx.*`，detekt `WildcardImport` 已启用）、中文标识符。

### 1.2 坏味道清单（🔴 出现即修）

| 坏味道 | 判定 | 处置 |
|--------|------|------|
| 长函数 | >60 行（CLAUDE.md 3.3） | 拆分为私有辅助函数 |
| 深嵌套 | >4 层（CLAUDE.md 审查清单） | 早返回/卫语句 |
| 重复代码 | DRY 违反（同逻辑 ≥2 处） | 提取公共函数（先评估是否值得抽象，避免过度设计） |
| 注释撒谎 | 注释与代码行为不一致 | 改代码或改注释，禁止两者并存 |
| 无效注释 | 纯复述代码（`// 加 1`） | 删除 |
| 字符串硬编码 | UI 文案直接字面量 | 提取到文案字典（为未来多语言/iOS 做准备）；禁止散落硬编码提示文案 |
| 魔法数字 | 有业务含义的数字（CLAUDE.md 0.4） | 命名常量（`const val` / `companion object`） |
| 孤儿代码 | 无调用方的函数/参数/字段 | 提交前删除（发现他人死代码 → 报告，不擅自删） |

### 1.3 设计原则（🟡 评审标准）

- **SOLID**：单职责（类名反映唯一职责，禁 Manager/Handler/Utils 模糊后缀——CLAUDE.md 3.5）、开闭原则、里氏替换、接口隔离、依赖反转（依赖注入优先硬编码——CLAUDE.md 0.2）
- **KISS / YAGNI**：最小化代码解决问题，不写投机性代码、不添加需求之外的抽象
- **组合优于继承**：行为复用走接口/委托
- **可测试性**：依赖注入、纯函数优先、边界三态（空/极值/异常——CLAUDE.md 0.1）、无全局可变状态

### 1.4 扩展友好性（🔴 呼应"为未来扩展做准备"）

- **开闭原则**：新功能优先扩展注册表/策略/枚举（`SpriteResRegistry`、`SlotCategory`、`AdPurpose`、`RngPartition`、`SaveValidationRuleRegistry` 等），避免修改既有 when 分支。例外：`DialogType` 穷举 when 分支（编译期穷举保证不漏，有渲染覆盖守卫测试）
- **API 只增不改**：对外接口（Facade/UseCase）签名变更必须评估所有调用方；退役用 `@Deprecated` + 废弃周期，禁止直接删除
- **模块边界不可穿越**（CLAUDE.md 2.1 依赖方向表）：core:domain 零 Android 依赖

### 1.5 iOS 跨平台可移植性（🔴 游戏未来做 iOS 端）

> 现有基线 vs 未来约束：见 `docs/knowledge-base.md#扩展性现状盘点` 的 iOS 可移植性基线表。**本节的未来约束只约束新代码，不用于审查现有代码**。

| 约束 | 要求 | 参照模式 |
|------|------|---------|
| core 层禁 Android 独占 API | `:core:domain` / `:core:engine` 禁止使用 `android.os`/`Context`/`Build`/`Toast`/`Log(android.util)` 等 Android SDK 类 | `RemoteConfigProvider` / `AdService` 接口 + 平台实现模式 |
| 平台能力接口抽象 | 新增平台能力（时间/存储/网络/加密/通知/支付/广告/分享）一律接口抽象 + Android/iOS 双平台实现，方案中必须给出 iOS 对等实现 | `ThermalReader` → `AndroidThermalReader` 先例 |
| 渲染双路径 | Vulkan 为 Android 独占（iOS 用 Metal 或软件渲染）；新渲染特性必须同时有 `SoftwareCanvasBackend` 等价实现（CLAUDE.md 审查清单已有，此处为可移植性约束） | `VulkanPolicy` + `SoftwareCanvasBackend` |
| 存储选型 | Room 为 iOS 迁移风险点；新增数据层组件优先评估跨平台方案（SQLDelight/原生 SQLite）或保持接口抽象 | core:data Repository 层 |
| 序列化一致性 | ProtoBuf + kotlinx.serialization 跨平台一致；文件路径/分隔符/编码禁止硬编码平台细节（已有 UTF-8 强制） | `SaveData` 序列化单路径 |
| API 守卫 | `Build.VERSION.SDK_INT` / `Build.SOC_*` 访问必须有 API 守卫（Android 专用，iOS 无需） | CLAUDE.md 审查清单已有条目 |

---

## 2. 审查与门禁机制

### 2.1 代码审查流程（🔴）

1. **提交前自审**：CLAUDE.md 13.3 审查清单逐条过（提交前必跑 `compileReleaseKotlin lintRelease`——rules/build-quality.md）
2. **PR/变更审查**：对齐用户全局 code-review.md 三层（安全 / 质量 / 性能），CRITICAL 级必须修复后才可合并
3. **对抗性审查**：新玩法模块（>100 行）/玩家资源系统/网络与存档系统强制（用户全局 first-principles-adversarial.md，3 角色恶意代理并行）
4. **守卫测试**：新增枚举/注册表项必须运行对应守卫测试（CLAUDE.md 9.5 三要素：枚举驱动 + 故意排除项 + 错误消息带操作指引）

### 2.2 CI 门禁（🔴）

- `compileReleaseKotlin` BUILD SUCCESSFUL + `lintRelease` 无新增严重问题 + `assembleRelease` 无新增 `^w:` 警告（rules/build-quality.md）
- `detekt` 无新增违规（detekt-baseline.xml 只缩不增——CLAUDE.md 13.2）
- `testReleaseUnitTest` 全绿 + Kover 覆盖率达标（engine ≥80%）
- 新增枚举/注册表变更跑对应守卫测试（build-quality.md 2026-08-04 补充）

### 2.3 技术债管理（🟡）

- 技术债登记：`docs/architecture.md` 待决策清单（T1-T16 模式）持续维护，新债必须登记编号 + 描述 + 触发条件
- **债主回报制**：任务中发现预存问题/无用代码/可优化代码必须报告（CLAUDE.md 第 12 条），禁止静默负债
- 清债时机：同主题重构时一并处理，禁止"后续再说"式无限拖延

---

## 3. 量化指标（🔴 阈值 + 检测方式）

| 指标 | 阈值 | 检测方式 |
|------|------|---------|
| 单文件行数 | ≤2000 行（生成代码除外） | 人工/detekt |
| 单函数行数 | ≤60 行 | 人工/detekt `FunctionNaming` 辅助 |
| 单行长度 | ≤120 字符（import/KDoc/URL 除外） | detekt `MaxLineLength` |
| **圈复杂度** | 单函数 cyclomaticComplexity **≤15**（新增约束；超限必须拆分并说明） | detekt `ComplexMethod` |
| 嵌套深度 | ≤4 层 | detekt/人工 |
| 覆盖率 | engine ≥80% 行覆盖 | Kover |
| 构造参数 | 类 ≤7 / Composable ≤6 | detekt `LongParameterList` |
| TODO/FIXME | 提交前清零或登记为技术债 | 人工/grep |
| 重复代码 | 无整段重复（DRY） | 人工/detekt |
| 循环依赖 | 模块间 DAG（Konsist 检查） | Konsist |
| 新增警告 | 0 个新增 `^w:` / lint 严重问题 | build-quality.md 流程 |
| 硬编码字符串 | UI 文案零硬编码（字典化） | 人工/grep |

> 注意：detekt `ComplexMethod` 阈值若需配置调整，属**代码变更**，需在 android/config/detekt/detekt.yml 修改后跑 `./gradlew.bat detekt` 验证，不在纯文档修订范围。

---

## 行业依据

- Google Kotlin 风格指南（Android 官方）：https://developer.android.com/kotlin/style-guide
- Android Developers《Kotlin 指南变更记录》（2024-07）：https://developer.android.com/kotlin/guides-changelog
- 其余依据见各扩展规则文件（rules/expansion-playbook.md 等）的行业依据章节
