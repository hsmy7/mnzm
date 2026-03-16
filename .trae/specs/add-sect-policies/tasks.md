# Tasks

- [x] Task 1: 扩展 SectPolicies 数据结构
  - [x] SubTask 1.1: 在 GameData.kt 中为 SectPolicies 添加5个新政策字段（alchemyIncentive, forgeIncentive, herbCultivation, cultivationSubsidy, manualResearch）

- [x] Task 2: 实现 GameViewModel 政策切换函数
  - [x] SubTask 2.1: 添加 toggleAlchemyIncentive() 函数 - 丹道激励
  - [x] SubTask 2.2: 添加 toggleForgeIncentive() 函数 - 锻造激励
  - [x] SubTask 2.3: 添加 toggleHerbCultivation() 函数 - 灵药培育
  - [x] SubTask 2.4: 添加 toggleCultivationSubsidy() 函数 - 修行津贴
  - [x] SubTask 2.5: 添加 toggleManualResearch() 函数 - 功法研习

- [x] Task 3: 实现副宗主智力加成计算函数
  - [x] SubTask 3.1: 在 GameEngine 中添加 calculateViceSectMasterPolicyBonus() 函数
  - [x] SubTask 3.2: 逻辑：以智力50为基准，每多5点返回1%加成

- [x] Task 4: 实现 GameEngine 政策效果逻辑 - 资源生产类
  - [x] SubTask 4.1: 丹道激励 - 在炼丹成功率计算中添加+10%加成（含副宗主智力加成）
  - [x] SubTask 4.2: 锻造激励 - 在锻造成功率计算中添加+10%加成（含副宗主智力加成）
  - [x] SubTask 4.3: 灵药培育 - 在灵药园生长周期计算中添加-20%时间效果（含副宗主智力加成）

- [x] Task 5: 实现 GameEngine 政策效果逻辑 - 修炼提升类
  - [x] SubTask 5.1: 修行津贴 - 化神境以下弟子修炼速度+15%（含副宗主智力加成）
  - [x] SubTask 5.2: 功法研习 - 功法修炼速度+20%（含副宗主智力加成）

- [x] Task 6: 修改现有政策
  - [x] SubTask 6.1: 灵矿增产 - 添加副宗主智力加成（无灵石消耗）
  - [x] SubTask 6.2: 增强治安 - 效果从+30%改为+20%，消耗从5000改为3000灵石，添加副宗主智力加成

- [x] Task 7: 实现月度政策消耗处理
  - [x] SubTask 7.1: 添加 processPolicyCosts() 函数处理所有政策的月度灵石消耗
  - [x] SubTask 7.2: 增强治安：每月消耗3000灵石
  - [x] SubTask 7.3: 丹道激励：每月消耗3000灵石
  - [x] SubTask 7.4: 锻造激励：每月消耗3000灵石
  - [x] SubTask 7.5: 灵药培育：每月消耗3000灵石
  - [x] SubTask 7.6: 修行津贴：每月消耗3000灵石
  - [x] SubTask 7.7: 功法研习：每月消耗4000灵石
  - [x] SubTask 7.8: 在 processMonthlyEvents 中调用政策消耗处理
  - [x] SubTask 7.9: 灵石不足时政策自动关闭

- [x] Task 8: 更新 MainGameScreen 政策UI界面
  - [x] SubTask 8.1: 更新灵矿增产政策描述（含副宗主智力加成说明，无灵石消耗）
  - [x] SubTask 8.2: 更新增强治安政策描述（效果+20%，消耗3000灵石，副宗主智力加成说明）
  - [x] SubTask 8.3: 添加丹道激励政策开关UI
  - [x] SubTask 8.4: 添加锻造激励政策开关UI
  - [x] SubTask 8.5: 添加灵药培育政策开关UI
  - [x] SubTask 8.6: 添加修行津贴政策开关UI
  - [x] SubTask 8.7: 添加功法研习政策开关UI

# Task Dependencies
- Task 2 依赖 Task 1（需要数据结构先定义）
- Task 3 无依赖（可并行）
- Task 4-7 依赖 Task 1 和 Task 3（需要数据结构和智力加成函数）
- Task 8 依赖 Task 2（需要切换函数先实现）
- Task 4-6 可并行执行
