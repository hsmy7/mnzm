# AI宗门互相进攻机制检查清单

## 数据结构
- [x] AIBattleTeam数据类定义完整，包含所有必要字段
  - ✅ 已验证：[GameData.kt:494-511](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt#L494-L511)
  - 包含所有必要字段：id, attackerSectId, attackerSectName, defenderSectId, defenderSectName, disciples, currentX/Y, targetX/Y, moveProgress, status, route, currentRouteIndex, startYear, startMonth
- [x] GameData新增aiBattleTeams字段正确添加
  - ✅ 已验证：[GameData.kt:133](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt#L133)
- [x] WorldSect新增isUnderAttack、attackerSectId、occupierSectId字段正确添加
  - ✅ 已验证：[GameData.kt:332-334](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt#L332-L334)
- [x] 数据库迁移文件正确更新
  - ✅ 已验证：[DatabaseMigrations.kt:1278-1297](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/local/DatabaseMigrations.kt#L1278-L1297) (MIGRATION_43_44)

## 进攻决策逻辑
- [x] 进攻条件检查正确（好感度<20、弟子数>=10、非盟友）
  - ✅ 已验证：[AISectAttackManager.kt:61-83](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L61-L83)
- [x] 路线相连判断正确（包括占领的宗门算自己的宗门）
  - ✅ 已验证：[AISectAttackManager.kt:85-97](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L85-L97)
- [x] 正邪对立加成正确计算（+10%）
  - ✅ 已验证：[AISectAttackManager.kt:120-122](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L120-L122)
- [x] 实力对比评估正确计算
  - ✅ 已验证：[AISectAttackManager.kt:99-107](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L99-L107) (calculatePowerScore)
- [x] 进攻概率计算正确（基础3% + 好感度惩罚 + 正邪加成，最高20%）
  - ✅ 已验证：[AISectAttackManager.kt:109-125](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L109-L125)

## 进攻队伍组建
- [x] 进攻队伍人数固定为10人
  - ✅ 已验证：[AISectAttackManager.kt:25](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L25), [134](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L134)
- [x] 弟子选择优先高境界
  - ✅ 已验证：[AISectAttackManager.kt:130](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L130)
- [x] 进攻队伍继承弟子真实属性
  - ✅ 已验证：[AISectAttackManager.kt:350-371](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L350-L371) (convertToCombatant使用getBaseStats)

## 进攻移动逻辑
- [x] 移动速度与探查队伍一致
  - ✅ 已验证：[AISectAttackManager.kt:265-266](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L265-L266)
- [x] 到达检测正确
  - ✅ 已验证：[AISectAttackManager.kt:288-290](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L288-L290)

## 进攻战斗处理
- [x] AI防守队伍组建正确（固定10人）
  - ✅ 已验证：[AISectAttackManager.kt:156-161](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L156-L161)
- [x] 战斗使用现有战斗系统
  - ✅ 已验证：[AISectAttackManager.kt:292-348](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L292-L348) (executeAIBattleTurn)
- [x] 战斗结果判定正确（胜利/失败/平局）
  - ✅ 已验证：[AISectAttackManager.kt:314-318](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L314-L318)

## 进攻结果处理
- [x] 战斗死亡弟子正确从宗门aiDisciples列表移除
  - ✅ 已验证：[GameEngine.kt:12019](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L12019), [12031](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L12031)
- [x] 进攻/防守队伍使用真实弟子数据
  - ✅ 已验证：使用AISectDisciple的完整数据和getBaseStats()
- [x] 宗门占领处理正确（化神及以上弟子全部死亡即可被占领）
  - ✅ 已验证：[AISectAttackManager.kt:337-346](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L337-L346)
- [x] 占领后的宗门可作为新的进攻起点
  - ✅ 已验证：[AISectAttackManager.kt:88-94](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L88-L94) (isRouteConnected检查occupierSectId)

## 事件通知
- [x] 宗门灭亡事件正确生成
  - ✅ 已验证：[AISectAttackManager.kt:540-542](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L540-L542), [GameEngine.kt:12052](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L12052)

## 结盟联动
- [x] 盟友被攻击支援逻辑正确（每月3.2%概率，好感度>90，必须路线相连，支援队伍固定10人）
  - ✅ 已验证：[AISectAttackManager.kt:186-220](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt#L186-L220)

## 玩家宗门进攻
- [x] 进攻玩家宗门条件与AI宗门相同（必须路线相连）
- [x] 玩家防守队伍组建正确（10人，空闲状态，高境界优先）
- [x] 玩家防守失败后果正确（仓库掠夺30-50个道具）
  - 注：IDLE和REFLECTING是互斥的枚举值，filter { it.status == IDLE } 已自动排除思过崖弟子

## 玩家进攻目标选择
- [x] 可进攻目标计算正确（玩家宗门+玩家占领宗门的connectedSectIds）
  - ✅ 已验证：[GameViewModel.kt:3293-3300](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt#L3293-L3300)
  - 使用AISectAttackManager.isRouteConnected检查路线连接
- [x] 高光显示只显示路线相连的AI宗门
  - ✅ 已验证：通过getMovableTargetSectIds()方法实现
- [x] 移动按钮逻辑正确修改
  - ✅ 已验证：[GameEngine.kt:701](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L701)
  - 使用AISectAttackManager.isRouteConnected检查路线连接

## GameEngine集成
- [x] 年度事件中进攻决策检查正确触发
  - ✅ 已验证：[GameEngine.kt:1161](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L1161) (月度事件中调用)
- [x] 月度事件中进攻队伍移动更新正确
  - ✅ 已验证：[GameEngine.kt:493](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L493) (每日事件中调用)
- [x] 好感度变化联动正确
  - ✅ 已验证：[GameEngine.kt:12055-12067](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt#L12055-L12067)
  - 战斗后双方好感度降低10点

## UI显示
- [x] AI进攻队伍移动动画正确显示
  - ✅ 已验证：[WorldMapScreen.kt:350-364](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/WorldMapScreen.kt#L350-L364)
- [x] 被进攻宗门战斗标记正确显示
  - ✅ 已验证：[WorldMapScreen.kt:366-376](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/WorldMapScreen.kt#L366-L376)

---

## 验证总结

### 已完成功能 (100%)

- ✅ 数据结构定义完整
- ✅ 进攻决策逻辑正确
- ✅ 进攻队伍组建和移动逻辑正确
- ✅ 战斗处理和结果处理正确
- ✅ 结盟联动逻辑正确
- ✅ AI宗门进攻玩家宗门逻辑正确
- ✅ 玩家进攻目标选择功能正确
- ✅ GameEngine集成正确
- ✅ 好感度变化联动正确
- ✅ UI显示正确

### 实现的核心文件

1. **数据模型**：[GameData.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/GameData.kt)
   - AIBattleTeam数据类
   - WorldSect扩展字段

2. **核心逻辑**：[AISectAttackManager.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/AISectAttackManager.kt)
   - 进攻决策逻辑
   - 战斗处理逻辑
   - 结果处理逻辑

3. **游戏引擎集成**：[GameEngine.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt)
   - 月度进攻决策检查
   - 每日移动更新
   - 战斗触发处理

4. **UI显示**：[WorldMapScreen.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/WorldMapScreen.kt)
   - AI进攻队伍移动动画
   - 战斗标记显示
