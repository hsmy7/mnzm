# Checklist

## 数据模型验证
- [ ] GameConfig.Buildings 包含任务阁建筑配置
- [ ] TaskData 数据类定义完整，包含所有必要字段
- [ ] TaskReward 数据类定义完整
- [ ] TaskType 枚举包含所有任务类型
- [ ] GameData 包含 taskHallTasks 字段
- [ ] GameData 包含 taskHallLastRefreshYear 和 taskHallLastRefreshMonth 字段
- [ ] DiscipleStatus 枚举包含 ON_MISSION 状态

## 任务刷新机制验证
- [ ] 游戏时间每三个月自动刷新任务列表
- [ ] 首次进入任务阁时正确生成任务
- [ ] 任务过期后正确清理

## 任务派遣验证
- [ ] 只能派遣空闲状态弟子
- [ ] 派遣后弟子状态正确变为 ON_MISSION
- [ ] 派遣后任务状态正确变为 ongoing
- [ ] 成功率计算正确，考虑弟子属性和境界

## 任务结算验证
- [ ] 任务成功时正确发放奖励
- [ ] 任务失败时无奖励
- [ ] 结算后弟子状态恢复为 IDLE
- [ ] 弟子死亡时任务正确中断

## UI界面验证
- [ ] 任务阁入口正确显示在建筑列表
- [ ] 任务列表正确显示所有任务信息
- [ ] 弟子选择对话框正确筛选空闲弟子
- [ ] 进行中任务正确显示进度和剩余时间
- [ ] 预计成功率正确显示
