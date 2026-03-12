# Game Code Optimization Roadmap

## Completed in this round
- Wrapped `GameRepository.saveAll()` in a Room transaction.
- Wrapped `GameRepository.clearAllData()` in a Room transaction.
- Switched `saveAll()` event/log/team writes to batch inserts.
- Added `BattleLogDao.insertAll(logs)` for batch persistence.
- Updated DI wiring in `AppModule` to inject `GameDatabase` into `GameRepository`.
- Reworked `SaveManager`:
  - Added shared helpers for slot validation and key building.
  - Replaced async `apply()` writes with `commit()` for save/delete/autosave operations.
  - Centralized save JSON parsing and error handling.
  - Removed duplicate slot-empty object construction.
- Hardened `GameUtils`:
  - Fixed random-range edge cases (`Int`/`Long` inclusive bounds and overflow handling).
  - Normalized `randomChance()` input and made weighted-random robust to invalid weights.
  - Added overflow-safe experience calculation and safe truncate behavior.
- Refined `SessionManager`:
  - Consolidated `SharedPreferences` writes via a single `edit {}` helper.
  - Changed `clearSession()` to clear auth fields only and preserve privacy-agreement state.
- Added unit tests:
  - Introduced `GameUtilsTest` and enabled JUnit dependency in app module.

## Current architecture observations
- `core/engine/GameEngine.kt` is a very large god-class (6k+ lines).
- `ui/game/GameViewModel.kt` is also overloaded (1k+ lines, many mixed responsibilities).
- `data` layer is now in better shape for transaction-safe persistence.

## Next optimization steps
1. Split `GameEngine` by domain systems:
   - `SectSystem`, `ExplorationSystem`, `EconomySystem`, `CombatSystem`, etc.
2. Reduce `GameViewModel` responsibilities:
   - Move save/tick/domain actions to use-cases/controllers.
3. Unify save paths:
   - Manual save, auto save, and exit save should share one serialization path.
4. Add focused regression tests:
   - `saveAll`, `clearAllData`, save-load consistency.

## Validation status
- `:app:compileDebugKotlin` passes after these changes.
- `:app:testDebugUnitTest` passes.
