# Remove Realm Gap Multiplier Limits — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all upper and lower clamps from `calculateRealmGapMultiplier`, so the realm gap damage bonus/penalty scales linearly without any artificial caps.

**Architecture:** Remove the `coerceAtMost(MAX_REALM_GAP)` on `absGap` and the `coerceIn(MIN_DAMAGE_RATIO, MAX_DAMAGE_RATIO)` on the final ratio in `BattleCalculator.calculateRealmGapMultiplier`. Remove the now-unused constants from `GameConfig.Battle.RealmGap`. All player-disciple combat paths already route through `calculateCombatantDamage` → `calculateRealmGapMultiplier`, so no additional wiring is needed.

**Tech Stack:** Kotlin, JUnit 4

**Design Decision:** After removing limits, the penalty formula `1.0 - absGap * 0.5` can go negative for absGap > 2. However, `MIN_DAMAGE = 1` at the final damage level ensures at least 1 damage is always dealt. The bonus formula `1.0 + absGap * 0.5` will now scale without bound (e.g., realm-0 vs realm-9 = 5.5x).

---

## Existing Mechanism Coverage (verified — no changes needed)

All 5 production call sites for `calculateCombatantDamage` already use realm gap multiplier:

| Call Site | Combat Type |
|-----------|-------------|
| `BattleSystem.executeAttack:580` | Normal attack (world/scout/mission/cave) |
| `BattleSystem.executeSkill:592` | Skill attack (world/scout/mission/cave) |
| `AISectAttackManager.executeNormalAttackAction:598` | Sect war normal attack |
| `AISectAttackManager.executeSingleAttackAction:629` | Sect war single-target skill |
| `AISectAttackManager.executeAoeAttackAction:670` | Sect war AoE skill |

`BattleCalculator.calculateDamage` also uses it but has no production callers (tests only).

---

### Task 1: Remove limits from `calculateRealmGapMultiplier`

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/core/util/BattleCalculator.kt:109-121`

- [ ] **Step 1: Simplify the function**

Replace the current function (lines 109-121):

```kotlin
fun calculateRealmGapMultiplier(attackerRealm: Int, defenderRealm: Int): Double {
    val gap = attackerRealm - defenderRealm
    val absGap = kotlin.math.abs(gap).coerceAtMost(GameConfig.Battle.RealmGap.MAX_REALM_GAP)
    if (absGap == 0) return 1.0

    val ratio = if (gap < 0) {
        1.0 + absGap * GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_REALM
    } else {
        1.0 - absGap * GameConfig.Battle.RealmGap.DAMAGE_PENALTY_PER_REALM
    }

    return ratio.coerceIn(GameConfig.Battle.RealmGap.MIN_DAMAGE_RATIO, GameConfig.Battle.RealmGap.MAX_DAMAGE_RATIO)
}
```

With:

```kotlin
fun calculateRealmGapMultiplier(attackerRealm: Int, defenderRealm: Int): Double {
    val gap = attackerRealm - defenderRealm
    if (gap == 0) return 1.0

    val absGap = kotlin.math.abs(gap)

    return if (gap < 0) {
        1.0 + absGap * GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_REALM
    } else {
        (1.0 - absGap * GameConfig.Battle.RealmGap.DAMAGE_PENALTY_PER_REALM).coerceAtLeast(0.0)
    }
}
```

Note: The `coerceAtLeast(0.0)` on the penalty branch prevents negative multipliers (which would cause healing). Without it, a Qi-Refining(realm=9) disciple attacking an Immortal(realm=0) would have `1.0 - 9*0.5 = -3.5x` multiplier, producing negative base damage. `MIN_DAMAGE = 1` at the final damage level would still coerce the result to 1, but a 0.0 floor is cleaner.

- [ ] **Step 2: Compile check**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

Expected: BUILD SUCCESSFUL (or just the test compile errors in next task).

---

### Task 2: Remove unused RealmGap constants from GameConfig

**Files:**
- Modify: `android/app/src/main/java/com/xianxia/sect/core/GameConfig.kt:414-420`

- [ ] **Step 1: Remove the three now-unused constants**

Replace the RealmGap object:

```kotlin
object RealmGap {
    const val DAMAGE_BONUS_PER_REALM: Double = 0.50
    const val DAMAGE_PENALTY_PER_REALM: Double = 0.50
    const val MAX_REALM_GAP: Int = 5
    const val MIN_DAMAGE_RATIO: Double = 0.0
    const val MAX_DAMAGE_RATIO: Double = 3.0
}
```

With:

```kotlin
object RealmGap {
    const val DAMAGE_BONUS_PER_REALM: Double = 0.50
    const val DAMAGE_PENALTY_PER_REALM: Double = 0.50
}
```

- [ ] **Step 2: Verify no other file references the removed constants**

```bash
cd android && grep -r "MAX_REALM_GAP\|MIN_DAMAGE_RATIO\|MAX_DAMAGE_RATIO" --include="*.kt" --exclude-dir=build --exclude-dir=.worktrees app/src/
```

Expected: No results (only in GameConfig.kt definition, which we just removed).

- [ ] **Step 3: Compile check**

```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

Expected: BUILD SUCCESSFUL.

---

### Task 3: Update BattleCalculatorTest

**Files:**
- Modify: `android/app/src/test/java/com/xianxia/sect/core/util/BattleCalculatorTest.kt:276-302`

- [ ] **Step 1: Replace the clamping tests with unbounded tests**

Remove tests at lines 276-302 (five tests: same realm, higher vs lower, lower vs higher, max bonus clamped, max penalty clamped).

Replace with:

```kotlin
@Test
fun `calculateRealmGapMultiplier - same realm returns 1`() {
    val multiplier = BattleCalculator.calculateRealmGapMultiplier(5, 5)
    assertEquals(1.0, multiplier, 0.001)
}

@Test
fun `calculateRealmGapMultiplier - higher realm attacking lower gets bonus`() {
    val multiplier = BattleCalculator.calculateRealmGapMultiplier(0, 3)
    assertEquals(2.5, multiplier, 0.001)  // 1.0 + 3 * 0.5
}

@Test
fun `calculateRealmGapMultiplier - lower realm attacking higher gets penalty`() {
    val multiplier = BattleCalculator.calculateRealmGapMultiplier(5, 1)
    val expected = maxOf(0.0, 1.0 - 4 * 0.5)  // = 0.0 (clamped at 0)
    assertEquals(expected, multiplier, 0.001)
}

@Test
fun `calculateRealmGapMultiplier - full gap across all 10 realms (bonus)`() {
    val multiplier = BattleCalculator.calculateRealmGapMultiplier(0, 9)
    assertEquals(5.5, multiplier, 0.001)  // 1.0 + 9 * 0.5 = 5.5 (previously capped at 3.0)
}

@Test
fun `calculateRealmGapMultiplier - full gap across all 10 realms (penalty)`() {
    val multiplier = BattleCalculator.calculateRealmGapMultiplier(9, 0)
    assertEquals(0.0, multiplier, 0.001)  // 1.0 - 9 * 0.5 = -3.5 → clamped to 0.0
}

@Test
fun `calculateRealmGapMultiplier - penalty floors at zero not negative`() {
    val multiplier = BattleCalculator.calculateRealmGapMultiplier(8, 0)
    assertEquals(0.0, multiplier, 0.001)  // 1.0 - 8 * 0.5 = -3.0 → clamped to 0.0
}
```

- [ ] **Step 2: Run the updated tests**

```bash
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.util.BattleCalculatorTest"
```

Expected: All tests PASS.

---

### Task 4: Update BattleSystemTest

**Files:**
- Modify: `android/app/src/test/java/com/xianxia/sect/core/engine/BattleSystemTest.kt:200-214`

- [ ] **Step 1: Replace realm gap tests**

Remove tests at lines 200-214.

Replace with:

```kotlin
@Test
fun `calculateRealmGapMultiplier - same realm returns 1`() {
    val multiplier = battleSystem.calculateRealmGapMultiplier(5, 5)
    assertEquals(1.0, multiplier, 0.001)
}

@Test
fun `calculateRealmGapMultiplier - full 10-realm gap bonus no longer capped`() {
    val multiplier = battleSystem.calculateRealmGapMultiplier(0, 9)
    assertEquals(5.5, multiplier, 0.001)
}

@Test
fun `calculateRealmGapMultiplier - full 10-realm gap penalty floors at zero`() {
    val multiplier = battleSystem.calculateRealmGapMultiplier(9, 0)
    assertEquals(0.0, multiplier, 0.001)
}
```

- [ ] **Step 2: Run the updated tests**

```bash
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.engine.BattleSystemTest"
```

Expected: All tests PASS.

---

### Task 5: Full test suite

- [ ] **Step 1: Run all tests**

```bash
cd android && ./gradlew.bat test
```

Expected: All tests PASS.

---

### Task 6: Commit

- [ ] **Step 1: Commit all changes**

```bash
git add android/app/src/main/java/com/xianxia/sect/core/util/BattleCalculator.kt
git add android/app/src/main/java/com/xianxia/sect/core/GameConfig.kt
git add android/app/src/test/java/com/xianxia/sect/core/util/BattleCalculatorTest.kt
git add android/app/src/test/java/com/xianxia/sect/core/engine/BattleSystemTest.kt
git commit -m "$(cat <<'EOF'
feat: remove all caps from realm gap damage multiplier

Remove MAX_REALM_GAP (was 5), MIN_DAMAGE_RATIO (was 0.0), and
MAX_DAMAGE_RATIO (was 3.0) from calculateRealmGapMultiplier.
The bonus/penalty now scales linearly across all 10 realms —
Immortal vs Qi Refining now gives 5.5x instead of being capped at 3.0x.
Penalty branch floors at 0.0 to prevent negative multipliers.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Changelog

After implementation, update both:

- `android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt` — add entry describing the change
- `CHANGELOG.md` at project root — add version section

---

## Verification

1. **Compile**: `./gradlew.bat compileReleaseKotlin` passes
2. **Unit tests**: `./gradlew.bat test` passes
3. **Manual test**: Launch the game, enter a world battle with a high-realm disciple vs low-realm beast — damage should be significantly higher than before (previously capped at 3.0x, now can reach 5.5x for max gap)
4. **Manual test**: Low-realm disciple vs high-realm beast — damage should be minimal (1 per hit) due to penalty flooring at 0.0x multiplier + MIN_DAMAGE = 1
