# 全部界面横屏适配设计

## Context

上一轮已将主界面改为横屏，但游戏内27个界面仍为竖屏布局。本次统一所有界面为横屏，以 RecruitScreen / MerchantScreen 为模板。

## 统一模板（23个界面）

所有中小型弹窗统一使用：

```
HalfScreenDialog (85%宽 × 90%高，已带bg_horizontal背景)
└── Column(fillMaxSize)
    ├── Header Row(fillMaxWidth, SpaceBetween)
    │   ├── Column: 标题(14.sp Bold Black) + 副标题(11.sp TextSecondary)
    │   └── Row: 操作按钮 + CloseButton
    └── Content区: 可滚动
```

**改造方式：** 外层包装从 `CommonDialog`/`ProductionCommonDialog`/`PeakDialog`/原始`Dialog` 统一改为 `HalfScreenDialog`，内部 Header 统一为标准格式，内容区保持现有逻辑不变。

### 界面清单

| 文件 | 当前包装 | 改造动作 |
|------|---------|---------|
| AlchemyScreen.kt | ProductionCommonDialog | → HalfScreenDialog |
| ForgeScreen.kt | ProductionCommonDialog | → HalfScreenDialog |
| HerbGardenScreen.kt | CommonDialog | → HalfScreenDialog |
| SpiritMineScreen.kt | CommonDialog | → HalfScreenDialog |
| LibraryScreen.kt | CommonDialog | → HalfScreenDialog |
| WenDaoPeakScreen.kt | PeakDialog | → HalfScreenDialog |
| QingyunPeakScreen.kt | PeakDialog | → HalfScreenDialog |
| TianshuHallScreen.kt | Dialog | → HalfScreenDialog |
| LawEnforcementHallScreen.kt | Dialog | → HalfScreenDialog |
| MissionHallScreen.kt | CommonDialog | → HalfScreenDialog |
| ReflectionCliffScreen.kt | CommonDialog | → HalfScreenDialog |
| InventoryScreen.kt | HalfScreenDialog | 调整Header |
| SalaryConfigScreen.kt | HalfScreenDialog | 调整Header |
| OuterTournamentResultDialog.kt | HalfScreenDialog | 调整Header |
| WorldMapSectDetailDialog | AlertDialog | → HalfScreenDialog |
| DiplomacyDialog | Dialog | → HalfScreenDialog |
| SectTradeDialog | Dialog | → HalfScreenDialog |
| BattleTeamDialog | AlertDialog | → HalfScreenDialog |
| LevelDetailDialog | Dialog | → HalfScreenDialog |
| RewardDialog.kt | Dialog | → HalfScreenDialog |
| AllianceDialog.kt | Dialog | → HalfScreenDialog |
| GiftDialog.kt | Dialog | → HalfScreenDialog |
| BuildingsTab.kt | GameBackground | → HalfScreenDialog |

## 全屏界面（4个，已在 MainGameScreen 以 fullMaxSize Dialog 打开）

| 界面 | 文件 | 现状 |
|------|------|------|
| 弟子列表 | tabs/DisciplesTab.kt | 已全屏，内部保持 |
| 仓库 | tabs/WarehouseTab.kt | 已全屏，内部保持 |
| 设置 | tabs/SettingsTab.kt | 已全屏，内部保持 |

## 弟子详情重新设计（DiscipleDetailScreen.kt）

全屏 Dialog，左右分栏：

```
Dialog(fillMaxSize)
└── Surface(fillMaxSize)
    └── Row(fillMaxSize)
        ├── 左侧 40%: Column
        │   ├── 弟子人物图（统一素材 bg_horizontal）
        │   └── 名称/境界等基本信息
        ├── 竖线分隔(1.dp)
        └── 右侧 60%: Row
            ├── 内容区(weight 按标签切换)
            └── 右侧标签列(4按钮竖排): 信息/属性/装备/功法
```

人物素材：`D:\模拟宗门美术素材` 中的人物图只有一张，所有弟子共用。

## 不改动

- WorldMapDialog — 自定义全屏画布地图
- MainGameScreen — 已改造
- RecruitScreen / MerchantScreen — 已是横屏模板

## 验证

1. `./gradlew.bat compileReleaseKotlin` 通过
2. 逐个打开23个弹窗，确认尺寸统一、 Header 正确、内容完整可见
3. 弟子详情：左右分栏比例正确、标签切换正常
4. 全文字颜色均为 `Color.Black`
