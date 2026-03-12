# 探索队伍血条实时同步修复 - 验证清单

- [x] 检查血条显示逻辑是否正确读取 `disciple.statusData["currentHp"]`
- [x] 检查 `GameEngine.kt` 中的 `triggerBattleEvent` 方法是否正确更新 `statusData["currentHp"]`
- [x] 验证代码编译无错误
- [x] 测试战斗后血条是否立即更新为当前生命值
- [x] 验证血条显示与实际生命值一致
- [x] 验证在不同战斗场景下血条同步功能是否正常
- [x] 确认修改符合现有代码风格和架构
- [x] 确认修改最小化，未引入新的bug