# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Test / Lint

All commands run from the `android/` directory using the Gradle wrapper:

```bash
# Compile check (fast feedback — do this after every change)
cd android && ./gradlew.bat compileReleaseKotlin

# Build release APK
cd android && ./gradlew.bat assembleRelease

# Build debug APK
cd android && ./gradlew.bat assembleDebug

# Run all unit tests (Robolectric + JUnit)
cd android && ./gradlew.bat test

# Run a single test class
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.engine.BattleSystemTest"

# Lint
cd android && ./gradlew.bat lintRelease

# Clean build (when KSP incremental cache breaks with NoSuchFileException *_Impl.java)
cd android && ./gradlew.bat clean
```

Tests live in `android/app/src/test/`. They use JUnit 4, Mockito, Robolectric, and `kotlinx-coroutines-test`. Robolectric tests need `includeAndroidResources = true`.

## Tech Stack

- **Language**: Kotlin 2.0.21, JVM target 17
- **UI**: Jetpack Compose with Material3 (BOM 2025.02.00), no XML layouts
- **DI**: Hilt 2.56 (`@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`)
- **Database**: Room 2.6.1 with KSP annotation processing; single shared DB file (`xianxia_sect.db`) for all save slots
- **Serialization**: Kotlinx Serialization (JSON + Protobuf + CBOR)
- **Storage**: MMKV (fast K-V), DataStore (preferences), LZ4/Zstd (compression)
- **Network**: Retrofit + OkHttp with Gson
- **Auth**: TapTap SDK (login, compliance, analytics)
- **Build**: AGP 8.8.0, Gradle with Aliyun mirrors for China

## Architecture: Two-Layer State Model

```
┌──────────────────────────────────────────────────┐
│ Layer 2: UI (ViewModel + Compose)                │
│   - Subscribes to GameStateStore.unifiedState    │
│   - Dialogs managed by DialogStateManager        │
├──────────────────────────────────────────────────┤
│ Layer 1: GameEngineCore + GameEngine             │
│   - EngineCore: game loop (200ms tick)           │
│   - Engine: business logic (cultivation, battle, │
│     production, diplomacy, exploration, etc.)    │
│   - Writes to GameStateStore._state MutableFlow  │
└──────────────────────────────────────────────────┘
```

### Data Flow

```
User Action (Compose UI)
  → ViewModel calls GameEngine method
    → GameEngine delegates to Service (e.g., CombatService)
      → Service reads from / writes to GameStateStore._state
        → StateFlow emits new UnifiedGameState
          → ViewModel.collectAsState() triggers recomposition
            → Compose UI renders updated state
```

- **GameEngine** is the single entry point for all state mutations from the UI layer. ViewModels never write to `GameStateStore` directly.
- **GameEngineCore** runs on a 200ms tick via `SystemManager`, driving autonomous systems (TimeSystem, BuildingSubsystem, etc.) that also write to `GameStateStore`.
- **GameStateStore** is the single source of truth — one `MutableStateFlow<UnifiedGameState>` containing all game state. Individual `StateFlow` projections are derived via `.map {}`.

### Key Source Directories

**Core — Game logic, state, and static data**
| Directory | Purpose |
|-----------|---------|
| `core/engine/` | Game loop (200ms tick), services, systems, production, scheduling |
| `core/engine/service/` | Per-domain services (Disciple, Combat, Cultivation, Diplomacy, Building, Event, Exploration, etc.) |
| `core/engine/system/` | ECS-like systems: Inventory, Building, Time, SystemManager |
| `core/model/` | Data classes: GameData (Room Entity), Disciple, Items, Equipment, etc. |
| `core/state/` | GameStateStore (central state), UnifiedGameState, UnifiedGameStateManager |
| `core/registry/` | Static game data: Equipment, Manuals, Herbs, ForgeRecipes, Items |
| `core/config/` | JSON-driven config: buildings, gifts, diplomatic events, inventory |

**Data — Storage, serialization, and persistence**
| Directory | Purpose |
|-----------|---------|
| `data/` | Storage layer: Room DB, serialization, compression, encryption, WAL, backup |
| `data/facade/` | StorageFacade — single external API for save/load/delete |
| `data/engine/` | StorageEngine — internal storage orchestration |
| `data/local/` | Room database, DAOs, migrations, type converters |

**UI — Compose screens, components, and theming**
| Directory | Purpose |
|-----------|---------|
| `ui/game/` | Game screens, ViewModels (one per feature), dialogs |
| `ui/game/tabs/` | Tab content: Disciples, Buildings, Warehouse, Settings |
| `ui/game/map/` | World map (Compose Canvas), markers, camera |
| `ui/components/` | Shared Compose components (GameButton, ItemCard, DialogManager) |
| `ui/theme/` | Colors, typography, shapes, button sizes |

**Infrastructure — DI, networking, and third-party SDKs**
| Directory | Purpose |
|-----------|---------|
| `di/` | Hilt modules: AppModule, CoreModule, RepositoryModule, StorageModule |
| `network/` | Retrofit API interfaces |
| `taptap/` | TapTap SDK wrappers (auth, compliance) |

### Key Classes

- **`GameEngineCore`** — Game loop controller. `start()`/`stop()`/`tick()` at 200ms intervals. Delegates to `SystemManager` which runs registered systems (TimeSystem, BuildingSubsystem, etc.) in priority order.
- **`GameEngine`** — Facade over all game logic. Injected into ViewModels. Orchestrates services and writes results to `GameStateStore`.
- **`GameStateStore`** — Single `MutableStateFlow<UnifiedGameState>`. All game state (disciples, items, events, etc.) lives in one `UnifiedGameState` object. Individual `StateFlow` projections derived via `.map {}`.
- **`GameViewModel`** — Primary ViewModel (Hilt). Bridges UI to engine. Owns `DialogStateManager`.
- **`MainGameScreen`** — Tab-based layout: OVERVIEW, DISCIPLES, BUILDINGS, WAREHOUSE, SETTINGS. No Jetpack Navigation — everything is in one screen with dialog overlays.
- **`GameData`** — Room `@Entity` for the core save row. Primary keys: `(id, slot_id)`.

### Navigation Pattern

No `NavHost` is used for the main game. `MainGameScreen` switches content via `MainTab` enum. Feature screens (Alchemy, Forge, HerbGarden, etc.) are dialogs opened via `DialogStateManager.openDialog(DialogType, params)`. The two actual Activity transitions are:

1. `MainActivity` → `GameActivity` (in-game)
2. `MainActivity` → `SaveSelectScreen` (save select)

### ViewModel Conventions

- ViewModels extend `BaseViewModel` which provides `showError()`, `showSuccess()`, `showInfo()`, and `withLoading()`.
- Each feature gets its own ViewModel (e.g., `AlchemyViewModel`, `ForgeViewModel`, `ProductionViewModel`, `DiscipleViewModel`).
- ViewModels read from `GameStateStore.unifiedState` via `collectAsState()` or direct `.value` reads for snapshots.
- Mutations go through `GameEngine` methods, never directly to `GameStateStore` from the UI layer.

## Style Guide

### Text Colors — Black Only

All in-game text **must** use `Color.Black` exclusively. No gray, white, colored, or tinted text. This applies to:

- All `Text()` composable `color` parameters
- `GameColors.TextPrimary`, `TextSecondary`, `TextTertiary`, `TextOnPrimary` all resolve to `Color(0xFF000000)`
- Any literal `Color(0xFF666666)`, `Color(0xFF999999)`, `Color.White` etc. used as text color → use `Color.Black`

**Enforcement**: When adding or modifying any `Text()` composable, use `color = Color.Black`. When reviewing code, flag any non-black text color.

## Database Migration Requirements

Before modifying ANY `@Entity` class (especially `GameData`), read `rules/database-migration.md`. The #1 cause of "all saves empty + new game doesn't run" is changing entity fields without a corresponding Migration. When in doubt, keep the old field AND add the new one with `@Ignore` — never remove a column without a Migration.

- **NEVER use `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0+ required, not guaranteed on any Android version. To drop columns, use `db.safeDropColumns("table", "col1", "col2")` (defined in `GameDatabase.kt`). It rebuilds the table via PRAGMA, works on all API levels.
- **v3.1.60 起不再有 .sav 双写** — Room 是唯一本地存储。修改 `@Entity` 只需一条 DB Migration，无需同步修改 `SerializableSaveData`。序列化层仅在 `SavMigrator`（读旧 .sav）和未来联机通信时使用。
- **ProtoBuf 只支持 `List`，不支持 `Set`/`Map` 等集合类型** — 所有通过 `ProtobufConverters` 直接序列化的 `@Serializable` 数据类（如 `SectPolicies`、`GameData` 内的嵌套对象），字段必须用 `List`，禁止 `Set`、`Map`。需要去重语义时在业务层用 `.toSet()` 转换。忽略此条会导致序列化静默失败，**存档变空**（v3.1.74 血泪教训）。

## 设计方案规则

出方案或做设计决策时，**必须**先使用 `/deep-research` skill 并结合网络搜索，调研同游戏行业的先进设计，给出对标分析后再出最优方案。禁止凭经验直接写代码。

**方案必须是可长期维护的成熟方案，禁止分阶段/渐进式交付。** 设计方案应当一次性完整覆盖所有影响点（包括 UI、存储、测试、旧数据兼容），不允许遗留"后续优化"。方案本身即为最终态，执行者照单实施即可，不应需要自行补充或二次设计。

**硬性指标：**
- 行业参考来源 **不得少于 20 条**，且必须来自权威渠道
- 所有参考数据必须是 **近 2 年内** 的最新数据（以当前日期为准），禁止引用过时资料
- 每条参考必须标注来源 URL 和发布日期，无法确认发布日期的来源不得使用
- 调研报告中必须包含参考来源清单及每条的核心摘要

**参考来源权威等级（优先采信高等级来源，低等级来源不计入 20 条配额）：**

| 等级 | 来源类型 | 计入配额 | 示例 |
|------|----------|----------|------|
| **S 级** | 官方文档 / 白皮书 | ✅ | Unity/Unreal 官方文档、Apple HIG、Google Material Design |
| **S 级** | 行业权威报告 | ✅ | Newzoo、Sensor Tower、data.ai、伽马数据、腾讯研究院 |
| **S 级** | 顶会演讲 / 论文 | ✅ | GDC Vault、SIGGRAPH、ACM/ IEEE 论文 |
| **A 级** | 头部产品官方技术博客 | ✅ | 米哈游 Tech Blog、Supercell 技术分享、Epic 技术博客 |
| **A 级** | 知名开发者 / 团队公开复盘 | ✅ | Riot Games 技术博客、Digital Foundry 分析 |
| **B 级** | 高质量社区技术文章 | ✅ | Medium 高赞（>500 claps）、知名技术博主 |
| **C 级** | 个人博客 / 论坛帖子 | ❌ 不计入 | 仅作补充参考 |

**来源优先级：官方文档 > 行业报告 > 顶会演讲 > 头部产品技术博客 > 知名团队复盘 > 社区文章。20 条配额中至少 12 条来自 S 级或 A 级来源。**

流程：
1. 明确需求 → 列出待调研的设计问题
2. 使用 `Skill` 工具调用 `deep-research` + `WebSearch` 搜索行业做法
3. 对标头部产品（原神、星铁、网易、腾讯系、米哈游系、莉莉丝、鹰角、叠纸等）的设计模式
4. 确保收集 ≥20 条有效参考后，输出对比分析报告，标注推荐方案和理由
5. 报告末尾附完整的参考来源清单（标题 + URL + 发布日期）
6. 用户确认后再执行

## 代码质量规范

### 简洁优先
- **用最简单的方式解决问题**，不为单次使用建抽象，不添加未被要求的"灵活性"
- 新增功能优先复用现有模式（参考周边代码风格），不引入第二套架构
- 写完自审：能否删掉一半代码仍实现同样功能？能就删

### 模块化隔离
- **修改一个功能不能影响其他功能**。新增模块通过接口/服务层与现有系统交互，不直接耦合
- 数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore。不反向引用
- 异常必须就地捕获或有明确的全局兜底，**不能让一个模块的异常导致整个应用崩溃**
- 新增 Room Entity 时：独立表 + 独立 DAO，不影响 game_data 主表
- 修改 GameData 字段时：必须加 `@SettlementStrategy` 注解 + DB Migration，**缺一不可**

### 防御性编程
- 所有对外部系统的调用（网络请求、DB 操作）必须包裹 try-catch
- StateFlow 数据流确保有初始值，不在 UI 层做空值合并
- 新功能上线前自测：正常流程 + 边界条件 + 异常恢复

### 架构文档同步
- 写代码时**必须同步更新** `CODE_WIKI.md`，保持架构文档与代码一致
- 新增模块：补充架构分层图、数据流说明、核心类/接口列表
- 修改现有模块：更新对应章节的用法说明、注意事项
- 发现技术债务或优化方向：追加到文档末尾的「后续优化项」清单
- 文档内容包含：**架构设计 + 使用方式 + 关键配置 + 后续优化项**

## Changelog Requirements

After implementing any feature or bug fix, update BOTH:
- **In-game**: `android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt` — add/append `ChangelogEntry` to `entries` list
- **External**: `CHANGELOG.md` at project root — add version section at top

Both must be updated before marking any task complete. Changes described in Chinese from the player's perspective.

## Version Release

When releasing, update in `android/app/build.gradle`:
- `versionCode` — increment by 1
- `versionName` — three-segment format `x.x.xx` (two-digit last segment, zero-padded). E.g., `2.6.09` → `2.6.10`, `2.6.99` → `2.7.00`. Never `2.6.1` (missing zero-pad).

See `rules/version-release.md` for the full release checklist.

## Android SDK / Encoding

- `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`
- All Java/Kotlin compilation is forced to UTF-8 to prevent Chinese character corruption
- Uses Aliyun Maven mirrors for Gradle plugin and dependency resolution
