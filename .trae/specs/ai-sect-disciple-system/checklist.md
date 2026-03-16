# Checklist

## 数据模型
- [x] AISectDisciple数据类包含所有必要字段（ID、姓名、境界、境界层数、修为、灵根、年龄、寿命、战斗属性浮动、天赋、功法、装备、悟性）
- [x] AISectDisciple包含境界名称、修为上限等计算属性
- [x] AISectDisciple包含战斗属性计算方法，正确应用属性浮动
- [x] AISectDisciple包含突破概率计算方法
- [x] AISectDisciple包含修炼速度计算方法

## 数据结构修改
- [x] WorldSect包含aiDisciples字段
- [x] WorldSect的discipleCountByRealm字段从aiDisciples自动计算境界分布

## 弟子管理器
- [x] AISectDiscipleManager能生成随机AI弟子
- [x] AI弟子包含1-3个天赋（可能包含负面）
- [x] AI弟子包含1-5本功法
- [x] AI弟子包含1-4种装备
- [x] AI弟子包含随机悟性属性
- [x] AISectDiscipleManager能为所有AI宗门招募新弟子
- [x] 新弟子境界为炼气期，灵根随机生成
- [x] 招募数量统一为1-10人
- [x] 月度修炼正确计算修炼速度
- [x] 修炼速度 = 基础速度(5.0) × 灵根加成 × 悟性加成 × 功法加成 × 天赋加成
- [x] 灵根加成：单灵根4.0倍，双灵根3.0倍，三灵根1.5倍，四灵根1.0倍，五灵根0.8倍
- [x] 悟性加成：高于50每点+4%，低于50每点-2%
- [x] 月度修为增加 = 修炼速度 × 5 × 30
- [x] 突破逻辑正确实现
- [x] 年龄增长和死亡逻辑正确实现
- [x] 功法品质不超过境界限制
- [x] 装备品质不超过境界限制

## GameEngine集成
- [x] WorldMapGenerator初始化AI宗门时生成初始弟子
- [x] 年度事件正确调用AI弟子招募
- [x] 月度事件正确调用AI弟子修炼和突破
- [x] 年度事件正确处理AI弟子年龄增长

## 战斗队伍生成
- [x] AICaveTeamGenerator从真实弟子中选择成员
- [x] 使用弟子的真实属性、天赋、装备和功法
- [x] 战斗后正确处理弟子状态

## 侦查系统
- [x] 侦查返回真实弟子信息
- [x] 境界分布统计正确
