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
