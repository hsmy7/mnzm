# Checklist

## 数据结构
- [x] SectPolicies 包含 alchemyIncentive 字段（丹道激励）
- [x] SectPolicies 包含 forgeIncentive 字段（锻造激励）
- [x] SectPolicies 包含 herbCultivation 字段（灵药培育）
- [x] SectPolicies 包含 cultivationSubsidy 字段（修行津贴）
- [x] SectPolicies 包含 manualResearch 字段（功法研习）

## ViewModel 函数
- [x] toggleAlchemyIncentive() 函数正常工作
- [x] toggleForgeIncentive() 函数正常工作
- [x] toggleHerbCultivation() 函数正常工作
- [x] toggleCultivationSubsidy() 函数正常工作
- [x] toggleManualResearch() 函数正常工作

## 副宗主智力加成
- [x] calculateViceSectMasterPolicyBonus() 函数实现正确
- [x] 智力50为基准，无加成
- [x] 每多5点智力增加1%政策效果
- [x] 无副宗主时无加成

## GameEngine 效果逻辑 - 资源生产类
- [x] 丹道激励：炼丹成功率+10%（含副宗主智力加成）
- [x] 锻造激励：锻造成功率+10%（含副宗主智力加成）
- [x] 灵药培育：灵药生长速度+20%（含副宗主智力加成）

## GameEngine 效果逻辑 - 修炼提升类
- [x] 修行津贴：化神境以下弟子修炼速度+15%（含副宗主智力加成）
- [x] 功法研习：功法修炼速度+20%（含副宗主智力加成）

## 现有政策修改
- [x] 灵矿增产：添加副宗主智力加成（无灵石消耗）
- [x] 增强治安：效果改为+20%，消耗改为3000灵石，添加副宗主智力加成

## 月度政策消耗
- [x] 增强治安：每月消耗3000灵石
- [x] 丹道激励：每月消耗3000灵石
- [x] 锻造激励：每月消耗3000灵石
- [x] 灵药培育：每月消耗3000灵石
- [x] 修行津贴：每月消耗3000灵石
- [x] 功法研习：每月消耗4000灵石
- [x] 灵石不足时政策自动关闭

## UI界面
- [x] 灵矿增产政策描述更新正确（含副宗主智力加成说明，无灵石消耗）
- [x] 增强治安政策描述更新正确（效果+20%，消耗3000灵石，副宗主智力加成说明）
- [x] 丹道激励政策开关显示正确
- [x] 锻造激励政策开关显示正确
- [x] 灵药培育政策开关显示正确
- [x] 修行津贴政策开关显示正确
- [x] 功法研习政策开关显示正确
- [x] 各政策描述文字清晰准确
- [x] 政策开关可正常切换
