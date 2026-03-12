# Tasks

- [x] Task 1: 数据结构扩展
  - [x] SubTask 1.1: 在GameData.kt中添加Alliance数据类和alliances字段，增加envoyDiscipleId字段
  - [x] SubTask 1.2: 在WorldSect数据类中添加allianceId、allianceStartYear字段
  - [x] SubTask 1.3: 在ModelConverters中添加Alliance列表的TypeConverter

- [x] Task 2: 游说系统实现
  - [x] SubTask 2.1: 实现calculatePersuasionSuccessRate方法（计算游说成功率）
  - [x] SubTask 2.2: 实现selectEnvoyDisciple方法（选择游说弟子，检查境界要求）
  - [x] SubTask 2.3: 实现游说任务执行逻辑（弟子状态变为忙碌）
  - [x] SubTask 2.4: 实现游说结果判定（成功/失败）

- [x] Task 3: 核心结盟逻辑实现
  - [x] SubTask 3.1: 在GameEngine中实现requestAlliance方法（玩家请求结盟，好感度>=90）
  - [x] SubTask 3.2: 实现checkAllianceConditions方法（检查结盟条件）
  - [x] SubTask 3.3: 实现dissolveAlliance方法（解除结盟）

- [x] Task 4: AI结盟逻辑实现
  - [x] SubTask 4.1: 在GameEngine年度结算中添加AI结盟检查逻辑
  - [x] SubTask 4.2: 实现tryAIAlliance方法（AI尝试结盟，好感度>=90）
  - [x] SubTask 4.3: 实现calculateAIAllianceProbability方法（计算AI结盟概率）

- [x] Task 5: 盟约期限机制实现
  - [x] SubTask 5.1: 实现盟约到期检查逻辑（3年有效期）
  - [x] SubTask 5.2: 实现盟约到期自动解散（不扣好感度）
  - [x] SubTask 5.3: 实现盟约续签功能（重新游说流程）

- [x] Task 6: 结盟效果系统实现
  - [x] SubTask 6.1: 修改交易系统，应用结盟交易优惠（10%）
  - [x] SubTask 6.2: 修改商品稀有度限制，应用结盟加成（+1）

- [x] Task 7: 跨宗门道侣系统实现
  - [x] SubTask 7.1: 实现跨宗门道侣检查逻辑（概率1.2%）
  - [x] SubTask 7.2: 实现跨宗门生育子嗣逻辑（概率0.6%，1年冷却）
  - [x] SubTask 7.3: 实现子嗣归属男方宗门的逻辑
  - [x] SubTask 7.4: 实现解散联盟时子嗣处理（不改变归属）

- [x] Task 8: 求援系统实现
  - [x] SubTask 8.1: 实现calculateRequestSupportSuccessRate方法（计算求援成功率，与游说算法一致）
  - [x] SubTask 8.2: 实现selectRequestDisciple方法（选择求援弟子，检查境界金丹及以上）
  - [x] SubTask 8.3: 实现求援任务执行逻辑（弟子状态变为忙碌）
  - [x] SubTask 8.4: 实现求援结果判定（成功/失败）
  - [x] SubTask 8.5: 实现calculateSupportTeamSize方法（支援队伍规模=5+(好感度-90)*5，最大55）
  - [x] SubTask 8.6: 实现generateAllySupportTeam方法（生成盟友支援队伍）

- [x] Task 9: 结盟与战争联动
  - [x] SubTask 9.1: 修改战争发起逻辑，检查是否攻击盟友
  - [x] SubTask 9.2: 实现攻击盟友后好感度降为0的逻辑
  - [x] SubTask 9.3: 实现被攻击时求援触发逻辑

- [x] Task 10: 好感度联动与自动解除
  - [x] SubTask 10.1: 实现好感度低于90自动解除结盟
  - [x] SubTask 10.2: 实现好感度变化监听

- [x] Task 11: ViewModel层实现
  - [x] SubTask 11.1: 在GameViewModel中添加结盟相关状态流
  - [x] SubTask 11.2: 实现requestAlliance方法调用GameEngine
  - [x] SubTask 11.3: 实现dissolveAlliance方法调用GameEngine
  - [x] SubTask 11.4: 实现游说弟子选择相关方法（含境界过滤）
  - [x] SubTask 11.5: 实现求援弟子选择相关方法（含境界过滤）

- [x] Task 12: UI界面实现
  - [x] SubTask 12.1: 创建AllianceDialog组件（结盟确认弹窗，白色背景）
  - [x] SubTask 12.2: 创建EnvoyDiscipleSelectDialog组件（游说弟子选择弹窗，米色按钮深棕色边框）
  - [x] SubTask 12.3: 创建RequestSupportDialog组件（求援弟子选择弹窗）
  - [x] SubTask 12.4: 在宗门详情界面添加结盟状态显示（含盟约剩余年限）
  - [x] SubTask 12.5: 添加"结盟"/"解除结盟"按钮及交互（米色背景深棕色边框）

- [x] Task 13: 编译检查
  - [x] SubTask 13.1: 修复编译错误
  - [x] SubTask 13.2: 确认编译成功

# Task Dependencies

- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1], [Task 2]
- [Task 4] depends on [Task 1]
- [Task 5] depends on [Task 3]
- [Task 6] depends on [Task 3]
- [Task 7] depends on [Task 3]
- [Task 8] depends on [Task 3]
- [Task 9] depends on [Task 3], [Task 8]
- [Task 10] depends on [Task 3]
- [Task 11] depends on [Task 2], [Task 3], [Task 4], [Task 5], [Task 6], [Task 7], [Task 8], [Task 9], [Task 10]
- [Task 12] depends on [Task 11]
- [Task 13] depends on [Task 1] - [Task 12]
