# 妖兽基础战斗数值降低10%

## 背景

当前妖兽在所有境界（含小层）的战斗数值偏高，需要整体降低10%以改善战斗平衡。

## 目标

将 `GameConfig.Beast.REALM_STATS` 中所有境界的基础战斗数值（hp/mp/attack/defense/speed）降低10%，四舍五入取整。

## 不调整的部分

| 项目 | 说明 |
|------|------|
| 暴击率 | `critRate = 0.05 + realmIndex * 0.01`，不属于基础战斗数值 |
| 技能倍率 | `damageMultiplier`，不属于基础战斗数值 |
| 首领倍率 | `bossMultiplier = 2.5`，独立乘区，基础值降10%后首领自动降10% |
| 妖兽类型修正 | `hpMod/atkMod/defMod/speedMod`，类型差异化修正 |
| 人型敌人 | `GameConfig.Enemy.REALM_STATS`，不在本次调整范围 |
| 计算公式 | `BattleSystem.createBeast()` 和 `CaveExplorationSystem.createGuardian()` 的公式不变 |

## 变更详情

### GameConfig.kt — Beast.REALM_STATS 数值替换

将 `REALM_STATS` 中每个值 ×0.9 后四舍五入取整。

**修改前**：
```kotlin
val REALM_STATS = mapOf(
    9  to RealmStats(hp=538,    mp=207,    attack=49,     defense=36,     speed=25),
    8  to RealmStats(hp=1344,   mp=517,    attack=120,    defense=91,     speed=64),
    7  to RealmStats(hp=3493,   mp=1344,   attack=310,    defense=234,    speed=166),
    6  to RealmStats(hp=9137,   mp=3514,   attack=815,    defense=609,    speed=432),
    5  to RealmStats(hp=24184,  mp=9302,   attack=2158,   defense=1612,   speed=1145),
    4  to RealmStats(hp=59116,  mp=22737,  attack=5275,   defense=3941,   speed=2798),
    3  to RealmStats(hp=139729, mp=53742,  attack=12468,  defense=9315,   speed=6614),
    2  to RealmStats(hp=311704, mp=119886, attack=27814,  defense=20780,  speed=14755),
    1  to RealmStats(hp=644840, mp=248040, attack=57545,  defense=42994,  speed=30528),
    0  to RealmStats(hp=1343418,mp=516750, attack=119886, defense=89570,  speed=63600)
)
```

**修改后**：
```kotlin
val REALM_STATS = mapOf(
    9  to RealmStats(hp=484,    mp=186,    attack=44,     defense=32,     speed=23),
    8  to RealmStats(hp=1210,   mp=465,    attack=108,    defense=82,     speed=58),
    7  to RealmStats(hp=3144,   mp=1210,   attack=279,    defense=211,    speed=149),
    6  to RealmStats(hp=8223,   mp=3163,   attack=734,    defense=548,    speed=389),
    5  to RealmStats(hp=21766,  mp=8372,   attack=1942,   defense=1451,   speed=1031),
    4  to RealmStats(hp=53204,  mp=20463,  attack=4748,   defense=3547,   speed=2518),
    3  to RealmStats(hp=125756, mp=48368,  attack=11221,  defense=8384,   speed=5953),
    2  to RealmStats(hp=280534, mp=107897, attack=25033,  defense=18702,  speed=13280),
    1  to RealmStats(hp=580356, mp=223236, attack=51791,  defense=38695,  speed=27475),
    0  to RealmStats(hp=1209076,mp=465075, attack=107897, defense=80613,  speed=57240)
)
```

## 数值对照表

| 境界 | 属性 | 原值 | ×0.9 取整 | 降幅 |
|------|------|------|-----------|------|
| 9(炼气) | hp | 538 | 484 | -10.0% |
| | mp | 207 | 186 | -10.1% |
| | attack | 49 | 44 | -10.2% |
| | defense | 36 | 32 | -11.1% |
| | speed | 25 | 23 | -8.0% |
| 8(筑基) | hp | 1344 | 1210 | -10.0% |
| | mp | 517 | 465 | -10.1% |
| | attack | 120 | 108 | -10.0% |
| | defense | 91 | 82 | -9.9% |
| | speed | 64 | 58 | -9.4% |
| 7(金丹) | hp | 3493 | 3144 | -10.0% |
| | mp | 1344 | 1210 | -10.0% |
| | attack | 310 | 279 | -10.0% |
| | defense | 234 | 211 | -9.8% |
| | speed | 166 | 149 | -10.2% |
| 6(元婴) | hp | 9137 | 8223 | -10.0% |
| | mp | 3514 | 3163 | -10.0% |
| | attack | 815 | 734 | -9.9% |
| | defense | 609 | 548 | -10.0% |
| | speed | 432 | 389 | -10.0% |
| 5(化神) | hp | 24184 | 21766 | -10.0% |
| | mp | 9302 | 8372 | -10.0% |
| | attack | 2158 | 1942 | -10.0% |
| | defense | 1612 | 1451 | -10.0% |
| | speed | 1145 | 1031 | -9.9% |
| 4(合体) | hp | 59116 | 53204 | -10.0% |
| | mp | 22737 | 20463 | -10.0% |
| | attack | 5275 | 4748 | -10.0% |
| | defense | 3941 | 3547 | -10.0% |
| | speed | 2798 | 2518 | -10.0% |
| 3(大乘) | hp | 139729 | 125756 | -10.0% |
| | mp | 53742 | 48368 | -10.0% |
| | attack | 12468 | 11221 | -10.0% |
| | defense | 9315 | 8384 | -10.0% |
| | speed | 6614 | 5953 | -9.9% |
| 2(渡劫) | hp | 311704 | 280534 | -10.0% |
| | mp | 119886 | 107897 | -10.0% |
| | attack | 27814 | 25033 | -10.0% |
| | defense | 20780 | 18702 | -10.0% |
| | speed | 14755 | 13280 | -10.0% |
| 1(仙人) | hp | 644840 | 580356 | -10.0% |
| | mp | 248040 | 223236 | -10.0% |
| | attack | 57545 | 51791 | -10.0% |
| | defense | 42994 | 38695 | -10.0% |
| | speed | 30528 | 27475 | -10.0% |
| 0(真仙) | hp | 1343418 | 1209076 | -10.0% |
| | mp | 516750 | 465075 | -10.0% |
| | attack | 119886 | 107897 | -10.0% |
| | defense | 89570 | 80613 | -10.0% |
| | speed | 63600 | 57240 | -10.0% |

> 注：低境界小数值因四舍五入，实际降幅在 8%~11% 之间浮动，高境界数值降幅精确为10%。

## 影响路径

`REALM_STATS` 是妖兽基础值的唯一来源，两个创建入口均通过 `getRealmStats()` 读取：

1. `BattleSystem.createBeast()` — 任务/秘境妖兽，自动生效
2. `CaveExplorationSystem.createGuardian()` — 洞府守护兽，自动生效

洞府首领最终值 = 基础值 × 小层倍率 × (1 + 类型修正 + 方差) × 2.5，基础值降10%后首领也降10%。

无需修改任何计算公式。

## 涉及文件

| 文件 | 变更类型 |
|------|---------|
| `GameConfig.kt` | 替换 `Beast.REALM_STATS` 的10条数据 |
| `ChangelogData.kt` | 添加变更记录 |
| `CHANGELOG.md` | 添加变更记录 |
