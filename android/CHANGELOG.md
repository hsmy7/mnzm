## v2.6.22 (2026-05-03)
- 宗门地图改为预加载：登录加载阶段即完成地图贴图解码和地形生成，进入游戏后地图即刻完整显示

## v2.6.21 (2026-05-01)
- 修复处于探索/队伍中的弟子无法修炼的问题（移除CultivationService中对IN_TEAM状态的修炼过滤，弟子在任何状态下都能修炼）
- 修复长老/副宗主任命后槽位仍为空的竞态：updateElderSlots改用updateGameDataDirect绕过transactionMutex同步更新
- 修复亲传弟子任命/卸任同样的竞态问题
- 修复问道峰/青云峰弟子选择对话框空状态不显示筛选列表的问题（始终显示筛选栏）
- 修复所有建筑任命后槽位仍空白+弟子列表不显示：ViewModel改用gameDataSnapshot/discipleAggregatesSnapshot直接读取_state.value，绕过stateIn(Dispatchers.Default)的跨线程调度延迟
- 建筑放置实现网格吸附系统：拖拽时建筑始终对齐最近网格、越界/重叠红色预警、确认按钮仅在合法位置可用
