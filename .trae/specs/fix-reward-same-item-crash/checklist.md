# Checklist

## 防重复点击机制验证
- [ ] GameViewModel 中添加了 rewardingDiscipleIds 集合
- [ ] rewardItemsToDisciple 函数开始时检查弟子ID是否在集合中
- [ ] 重复点击时直接返回不执行操作
- [ ] 赏赐完成后（无论成功与否）从集合中移除弟子ID

## UI 状态管理验证
- [ ] DiscipleDetailScreen 中使用 LaunchedEffect 管理赏赐异步操作
- [ ] isRewarding 状态在协程完成后才重置为 false
- [ ] 添加了错误处理确保状态能够正确重置

## 数据一致性验证
- [ ] processDiscipleItemInstantlyInternal 使用正确的数据快照
- [ ] transactionMutex 锁保护确保数据一致性

## 功能测试验证
- [ ] 单次赏赐功能正常工作
- [ ] 快速连续点击赏赐按钮不会导致崩溃
- [ ] 赏赐相同道具多次不会崩溃
- [ ] 赏赐装备、丹药、功法、材料、草药、种子等不同类型的道具都正常
- [ ] 赏赐后道具正确转移到弟子储物袋
- [ ] 赏赐后宗门仓库中道具数量正确减少
