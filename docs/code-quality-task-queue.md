# 超长函数拆分任务队列（P3）

> 来源：各模块 detekt-baseline.xml 中冻结的 LongMethod 违规。拆分一个函数后从对应模块 baseline 摘除一条（baseline 只缩不增）。

| 模块 | 文件 | 类/函数 |
|---|---|---|
| app | DiscipleObjectPool.kt | MutableDisciple
| app | DiscipleObjectPool.kt | MutableDisciple
| app | DiscipleObjectPool.kt | MutableDisciple
| app | GameActivity.kt | GameActivity
| app | GameMonitorManager.kt | GameMonitorManager
| app | GameStateStoreImpl.kt | GameStateStoreImpl
| app | GameStateStoreImpl.kt | GameStateStoreImpl
| app | MainActivity.kt | @Composable fun MainScreen( sessionManager: SessionManager, complianceDialogState: MutableState<MainActivity.ComplianceDialogState?>, tapTapReady: Boolean = false, onLoginSuccess: () -> Unit, onPrivacyAgreed: () -> Unit = {} )
| app | MainActivity.kt | MainActivity
| app | PrivacyConsentScreen.kt | @Composable fun FullPrivacyPolicyScreen( onBack: () -> Unit )
| app | PrivacyConsentScreen.kt | @Composable fun PrivacyConsentScreen( onAgree: () -> Unit, onDisagree: () -> Unit )
| app | PrivacyConsentScreen.kt | @Composable private fun PrivacySummaryContent( onPrivacyLinkClick: () -> Unit = {}, onTapTapSdkLinkClick: () -> Unit = {}, onMmkvLinkClick: () -> Unit = {}, onDirichletAdSdkLinkClick: () -> Unit = {} )
| app | SaveSelectScreen.kt | @Composable fun SaveSelectScreen( saveSlots: List<SaveSlot>, onLoadSlot: (Int) -> Unit, onNewGame: (Int, String) -> Unit, onDeleteSlot: (Int) -> Unit, onLogout: () -> Unit )
| app | SaveSelectScreen.kt | @Composable fun SaveSlotCard( slot: SaveSlot, dateFormat: SimpleDateFormat, onLoad: () -> Unit, onNewGame: () -> Unit, onDelete: () -> Unit )
| app | UiError.kt | UiError.Companion
| app | VulkanPolicy.kt | VulkanPolicy
| app | XianxiaApplication.kt | XianxiaApplication
| feature/game | ActivityDialog.kt | @Composable fun ActivityDialog( viewModel: ActivityViewModel, gameViewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | AlchemyDialog.kt | @Composable fun AlchemyDialog( buildingInstanceId: String = "", alchemySlots: List<AlchemySlot>, materials: List<Material>, herbs: List<Herb>, gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, productionViewModel: ProductionViewModel, alchemyViewModel: AlchemyViewModel, colors: com.xianxia.sect.ui.theme.XianxiaColorScheme, onDismiss: () -> Unit )
| feature/game | AlchemyDialog.kt | @Composable private fun PillDetailDialog( recipes: List<PillRecipeDatabase.PillRecipe>, herbs: List<Herb>, viewModel: GameViewModel? = null, onDismiss: () -> Unit )
| feature/game | AlchemyDialog.kt | @Composable private fun PillSelectionDialog( materials: List<Material>, herbs: List<Herb>, slotIndex: Int, viewModel: GameViewModel, productionViewModel: ProductionViewModel, alchemyViewModel: AlchemyViewModel, onDismiss: () -> Unit, onConfirmOverride: ((PillRecipeDatabase.PillRecipe) -> Unit)? = null )
| feature/game | AttackDiscipleDialog.kt | @Composable internal fun AttackDiscipleDialog( sectName: String, disciples: List<DiscipleAggregate>, gameData: GameData?, viewModel: GameViewModel, onAttack: (List<Pair<Int, DiscipleAggregate>>) -> Unit, onDismiss: () -> Unit )
| feature/game | AttackDiscipleDialog.kt | @Composable private fun AttackDiscipleSelectionDialog( disciples: List<DiscipleAggregate>, currentSlotDiscipleId: String? = null, alreadySelectedIds: Set<String> = emptySet(), viewModel: GameViewModel, onSelect: (DiscipleAggregate) -> Unit, onDismiss: () -> Unit )
| feature/game | AttackWarningDialog.kt | @Composable internal fun WarDeclarationDialog( warning: AttackWarning, currentSpiritStones: Long, onAppease: () -> Unit, onBecomeVassal: () -> Unit, onDismiss: () -> Unit, scrimEnabled: Boolean = true )
| feature/game | AutoBuyDialog.kt | @Composable fun AutoBuyDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | AutoBuyDialog.kt | @Composable fun AutoBuyItemSelectDialog( viewModel: GameViewModel, existingList: List<AutoBuyEntry>, onConfirm: (List<AutoBuyEntry>) -> Unit, onDismiss: () -> Unit )
| feature/game | AutoManagementDialog.kt | @Composable fun AutoManagementDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | AutoManagementDialog.kt | @Composable private fun AutoAssignSection( title: String, attrLabel: String, focused: Boolean, rootCounts: List<Int>, threshold: String, onFocusedToggle: () -> Unit, onRootToggle: (Int) -> Unit, onThresholdChange: (String) -> Unit )
| feature/game | BattleLogDialogs.kt | @Composable internal fun BattleLogDetailDialog( log: BattleLog, onDismiss: () -> Unit, scrimEnabled: Boolean = true )
| feature/game | BattleLogDialogs.kt | @Composable internal fun BattleLogListDialog( battleLogs: List<BattleLog>, yearlyReports: List<YearlyReport> = emptyList(), onDismiss: () -> Unit )
| feature/game | BattleLogDialogs.kt | @Composable internal fun BattleLogListItem( log: BattleLog, onClick: () -> Unit )
| feature/game | BattleLogDialogs.kt | @Composable private fun YearlyReportDetailDialog( report: YearlyReport, onDismiss: () -> Unit )
| feature/game | BattleResultDialog.kt | @Composable internal fun BattleResultDialog( resultData: BattleResultUIData, battleLog: BattleLog?, onConfirm: () -> Unit, onViewDetail: (BattleLog) -> Unit, onDismiss: () -> Unit, viewModel: GameViewModel? = null, scrimEnabled: Boolean = true )
| feature/game | BloodRefiningPoolDialog.kt | @Composable fun BloodRefiningPoolDialog( buildingInstanceId: String, viewModel: GameViewModel, bloodRefiningViewModel: BloodRefiningViewModel, gameData: GameData?, disciples: List<DiscipleAggregate>, materials: List<Material>, onDismiss: () -> Unit )
| feature/game | BloodRefiningPoolDialog.kt | @Composable private fun MaterialSelectorDialog( bloodMaterials: List<Pair<BeastMaterialDatabase.BeastMaterial, Int>>, viewModel: GameViewModel? = null, onDismiss: () -> Unit, onSelect: (BeastMaterialDatabase.BeastMaterial, Int) -> Unit )
| feature/game | BloodRefiningViewModel.kt | BloodRefiningViewModel
| feature/game | BuildingConstructionBar.kt | @Composable fun BuildingConstructionBar( buildingList: List<Pair<String, (GridBuildingData?) -> Unit>>, placedBuildings: List<GridBuildingData>, buildingCosts: Map<String, Long>, spiritStones: Long, onSelectBuilding: (String) -> Unit, modifier: Modifier = Modifier, getBuildingCount: (String) -> Int = { name -> placedBuildings.count { it.displayName == name } }, getBuildingMaxCount: (String) -> Int = { 1 }, /** 当前宗门等级（SectLevel 常量），用于检测中级建筑等级限制 */ currentSectLevel: Int = 0, /** 等级不足时的回调，在中级建筑被点击时触发 */ onSelectBuildingLevelRequirement: ((String) -> Unit)? = null )
| feature/game | BuildingFeatureBoot.kt | fun BuildingFeatureRegistry.registerDefaults()
| feature/game | BuildingsTab.kt | @Composable internal fun BuildingsTab( viewModel: GameViewModel, productionViewModel: ProductionViewModel, alchemyViewModel: AlchemyViewModel, forgeViewModel: ForgeViewModel, herbGardenViewModel: HerbGardenViewModel, spiritMineViewModel: SpiritMineViewModel, onDismiss: () -> Unit )
| feature/game | CloudSaveDialog.kt | @Composable fun CloudSaveDialog( saveLoadViewModel: SaveLoadViewModel, onDismiss: () -> Unit )
| feature/game | DailySignInDialog.kt | @Composable fun DailySignInPanel( viewModel: GameViewModel )
| feature/game | DailySignInDialog.kt | @OptIn(ExperimentalFoundationApi::class) @Composable private fun MilestoneRewardRow( milestone: MilestoneReward, isClaimed: Boolean, watchedKeys: Set<String> = emptySet(), isReached: Boolean, cardSize: Dp, cardHeight: Dp, labelSpacing: Dp, dayLabelWidth: Dp, nameFontSize: TextUnit, onLongPress: (Any) -> Unit )
| feature/game | DailySignInDialog.kt | @OptIn(ExperimentalFoundationApi::class) @Composable private fun SignInDayCard( dayOfMonth: Int, reward: DailySignInReward, state: SignInDayState, cellWidth: Dp, cellHeight: Dp, nameFontSize: TextUnit, watchedKeys: Set<String> = emptySet(), modifier: Modifier = Modifier, onLongPress: (Any) -> Unit = {} )
| feature/game | DaoCompanionManagementDialog.kt | @Composable fun DaoCompanionManagementDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | DetailActionButtons.kt | @Composable fun RelationsDialog( disciple: DiscipleAggregate, allDisciples: List<DiscipleAggregate>, onDismiss: () -> Unit )
| feature/game | DetailCultivationSection.kt | @Composable fun BasicInfoSection( disciple: DiscipleAggregate, allEquipment: List<EquipmentInstance> = emptyList(), allManuals: List<ManualInstance> = emptyList(), manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(), elderSlots: ElderSlots? = null, allDisciples: List<DiscipleAggregate> = emptyList(), sectPolicies: SectPolicies? = null, residenceSlots: List<ResidenceSlot> = emptyList(), placedBuildings: List<GridBuildingData> = emptyList(), viewModel: GameViewModel? = null, gameMonth: Int = 1, gameYear: Int = 1, gamePhase: Int = 0, gameSpeed: Int = 1 )
| feature/game | DetailCultivationSection.kt | @Composable fun BreakthroughDetailDialog( detail: DiscipleStatCalculator.BreakthroughBonusDetail, onDismiss: () -> Unit )
| feature/game | DetailCultivationSection.kt | @Composable fun HpMpBars( disciple: DiscipleAggregate, maxHpOverride: Int? = null, maxMpOverride: Int? = null, gameSpeed: Int = 1 )
| feature/game | DetailCultivationSection.kt | internal fun calculatePreachingBonusesForDisplay( disciple: DiscipleAggregate, elderSlots: ElderSlots?, allDisciples: List<DiscipleAggregate>, sectPolicies: SectPolicies? = null ): Triple<Double, Double, Double>
| feature/game | DetailEquipmentSection.kt | @Composable fun EquipmentSelectionDialog( slotType: String, allEquipment: List<EquipmentInstance>, equipmentStacks: List<EquipmentStack>, currentEquipmentId: String?, currentDiscipleId: String, discipleRealm: Int, selectedEquipmentId: String?, onSelect: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, viewModel: GameViewModel? = null )
| feature/game | DetailHeaderSection.kt | @Composable fun DetailRightPanel( disciple: DiscipleAggregate, allDisciples: List<DiscipleAggregate>, localDiscipleType: String, showDiscipleTypeDropdown: Boolean, onDiscipleTypeDropdownChange: (Boolean) -> Unit, onLocalDiscipleTypeChange: (String) -> Unit, actions: DetailActionCallbacks, viewModel: GameViewModel? )
| feature/game | DetailManualSection.kt | @Composable fun ManualSelectionDialog( manualStacks: List<ManualStack>, allManuals: List<ManualInstance>, currentManualIds: List<String>, discipleRealm: Int, maxManualSlots: Int, selectedManualId: String?, onSelect: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, viewModel: GameViewModel? = null )
| feature/game | DetailPillSection.kt | @Composable fun StorageBagDialog( items: List<StorageBagItem>, spiritStones: Long, disciple: DiscipleAggregate, viewModel: GameViewModel?, onDismiss: () -> Unit )
| feature/game | DetailPillSection.kt | @Composable private fun <T> RewardItemGrid( items: List<T>, selectedItem: RewardSelectedItem?, watchedKeys: Set<String> = emptySet(), onItemSelect: (RewardSelectedItem) -> Unit, onViewDetail: (Any) -> Unit = {} )
| feature/game | DetailPillSection.kt | @Composable private fun RewardAllItemsGrid( equipment: List<EquipmentStack>, manuals: List<ManualStack>, pills: List<Pill>, materials: List<Material>, herbs: List<Herb>, seeds: List<Seed>, selectedItem: RewardSelectedItem?, watchedKeys: Set<String> = emptySet(), onItemSelect: (RewardSelectedItem) -> Unit, onViewDetail: (Any) -> Unit = {} )
| feature/game | DetailPillSection.kt | @Composable private fun RewardBottomPanel( selectedItem: RewardSelectedItem?, rewardQuantity: Int, maxQuantity: Int, isRewarding: Boolean = false, onQuantityChange: (Int) -> Unit, onRewardClick: () -> Unit )
| feature/game | DetailPillSection.kt | @Composable private fun RewardItemsDialog( disciple: DiscipleAggregate, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | DiplomacyDialog.kt | @Composable fun DiplomacyDialog( gameData: GameData?, viewModel: GameViewModel, interactionViewModel: WorldMapInteractionViewModel, onDismiss: () -> Unit )
| feature/game | DiplomacyDialog.kt | @Composable internal fun DiplomacySectCard( sect: WorldSect, relation: Int, gameData: GameData?, isAlly: Boolean, onOpenDiplomacyDialogue: () -> Unit, onTrade: () -> Unit )
| feature/game | DiscipleComponents.kt | @Composable fun DiscipleSlot( disciple: DiscipleAggregate?, modifier: Modifier = Modifier, borderColor: Color = GameColors.Border, showActions: Boolean = false, onSlotClick: () -> Unit = {}, onEmptySlotClick: () -> Unit = {}, onDismiss: (() -> Unit)? = null, onSwap: (() -> Unit)? = null )
| feature/game | DiscipleComponents.kt | @Composable fun PortraitDiscipleCard( disciple: DiscipleAggregate, isSelected: Boolean = false, isCurrent: Boolean = false, showStatus: Boolean = true, extraAttributes: List<Pair<String, Int>> = emptyList(), customAttributes: @Composable (() -> Unit)? = null, actions: @Composable (() -> Unit)? = null, onClick: () -> Unit )
| feature/game | DiscipleDetailScreen.kt | @Composable fun DiscipleDetailDialog( disciple: DiscipleAggregate, allDisciples: List<DiscipleAggregate> = emptyList(), allEquipment: List<EquipmentInstance> = emptyList(), allManuals: List<ManualInstance> = emptyList(), manualStacks: List<ManualStack> = emptyList(), equipmentStacks: List<EquipmentStack> = emptyList(), manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(), viewModel: GameViewModel? = null, onDismiss: () -> Unit, onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null, scrimEnabled: Boolean = true )
| feature/game | DiscipleDetailScreen.kt | @Composable private fun ManualReplaceDialog( availableManualStacks: List<ManualStack>, selectedReplaceManualId: String?, watchedKeys: Set<String> = emptySet(), onSelectReplaceManual: (String) -> Unit, onViewReplaceDetail: (ManualStack) -> Unit, onConfirmReplace: () -> Unit, onDismissReplace: () -> Unit )
| feature/game | DiscipleFilterComponents.kt | @Composable @OptIn(ExperimentalLayoutApi::class) internal fun SpiritRootAttributeFilterBar( selectedSpiritRootFilter: Set<Int>, selectedAttributeSort: String?, selectedRealmFilter: Set<Int> = emptySet(), realmFilterOptions: List<Pair<Int, String>> = emptyList(), realmCounts: Map<Int, Int> = emptyMap(), spiritRootExpanded: Boolean, attributeExpanded: Boolean, realmExpanded: Boolean = false, spiritRootCounts: Map<Int, Int>, onSpiritRootFilterSelected: (Int) -> Unit, onSpiritRootFilterRemoved: (Int) -> Unit, onAttributeSortSelected: (String?) -> Unit, onRealmFilterSelected: (Int) -> Unit = {}, onRealmFilterRemoved: (Int) -> Unit = {}, onSpiritRootExpandToggle: () -> Unit, onAttributeExpandToggle: () -> Unit, onRealmExpandToggle: () -> Unit = {}, isCompact: Boolean = false, showAllCheckboxVisible: Boolean = false, showAllEnabled: Boolean = false, onShowAllToggle: () -> Unit = {} )
| feature/game | DiscipleManagementDialog.kt | @Composable fun DiscipleManagementDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | DiscipleSelectorDialog.kt | @Composable fun DiscipleSelectorDialog( config: DiscipleSelectorConfig, disciples: List<DiscipleAggregate>, onDismiss: () -> Unit, onConfirm: (List<DiscipleAggregate>) -> Unit, viewModel: GameViewModel? = null, showAllEnabled: Boolean = false, battleAndExplorationIds: Set<String> = emptySet(), scrimEnabled: Boolean = true )
| feature/game | DisciplesTab.kt | @Composable internal fun DirectDiscipleSelectionDialog( disciples: List<DiscipleAggregate>, requiredAttribute: Pair<String, String>?, elderSlots: ElderSlots, viewModel: GameViewModel, onDismiss: () -> Unit, onSelect: (String) -> Unit )
| feature/game | DisciplesTab.kt | @Composable internal fun DisciplesTab( gameData: GameData?, disciples: List<DiscipleAggregate>, equipment: List<EquipmentInstance>, manuals: List<ManualInstance>, manualStacks: List<ManualStack>, equipmentStacks: List<EquipmentStack>, viewModel: GameViewModel )
| feature/game | DisciplesTab.kt | @Composable internal fun ElderDiscipleSelectionDialog( disciples: List<DiscipleAggregate>, currentElderId: String?, requiredAttribute: Pair<String, String>?, elderSlots: ElderSlots, viewModel: GameViewModel, onDismiss: () -> Unit, onSelect: (String) -> Unit )
| feature/game | ForgeDialog.kt | @Composable fun ForgeDialog( buildingInstanceId: String = "", forgeSlots: List<ForgeSlot>, materials: List<Material>, gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, productionViewModel: ProductionViewModel, forgeViewModel: ForgeViewModel, colors: com.xianxia.sect.ui.theme.XianxiaColorScheme, onDismiss: () -> Unit )
| feature/game | ForgeDialog.kt | @Composable private fun EquipmentDetailDialog( recipe: ForgeRecipeDatabase.ForgeRecipe, materials: List<Material>, viewModel: GameViewModel? = null, onDismiss: () -> Unit )
| feature/game | ForgeDialog.kt | @Composable private fun EquipmentSelectionDialog( materials: List<Material>, slotIndex: Int, viewModel: GameViewModel, productionViewModel: ProductionViewModel, forgeViewModel: ForgeViewModel, onDismiss: () -> Unit, onConfirmOverride: ((ForgeRecipeDatabase.ForgeRecipe) -> Unit)? = null )
| feature/game | GameOverlayHost.kt | @Composable fun GameOverlayHost( vms: OverlayViewModels, callbacks: OverlayCallbacks )
| feature/game | GoldFingerOverlay.kt | @Composable internal fun GoldFingerSelectionOverlay( goldFingerState: GoldFingerState, cameraState: SectCameraState, tileSize: Int, goldenFingerBmp: ImageBitmap? )
| feature/game | GuideDialog.kt | @Composable fun GuideDialog( gameData: GameData, claimedRewardIds: Set<Int>, allTasks: List<GuideTask>, onClaimReward: (taskId: Int) -> Unit, onDismiss: () -> Unit, discipleTables: DiscipleTables? = null )
| feature/game | GuideDialog.kt | @Composable private fun TaskDetailColumn( selectedTask: GuideTask?, gameData: GameData, discipleTables: DiscipleTables?, modifier: Modifier = Modifier )
| feature/game | HeavenlyTrialBattleDialog.kt | @Composable fun HeavenlyTrialBattleDialog( levelIndex: Int, viewModel: HeavenlyTrialViewModel, gameViewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | HeavenlyTrialBattleDialog.kt | @Composable private fun EnemyInfoDetail( enemy: Combatant, gameViewModel: GameViewModel? = null )
| feature/game | HeavenlyTrialClearRewardDialog.kt | @Composable private fun ClearRewardRow( label: String, items: List<com.xianxia.sect.core.model.ClearRewardItem>, isCleared: Boolean, canClaim: Boolean, watchedKeys: Set<String> = emptySet(), onClaim: () -> Unit )
| feature/game | HeavenlyTrialCombatLogic.kt | internal fun resolveAIAction( actor: Combatant, ai: BattleAI.AIAction, actorIsPlayer: Boolean, players: List<Combatant>, enemies: List<Combatant>, rng: DeterministicRng = combatRng ): Pair<List<Combatant>, List<Combatant>>
| feature/game | HeavenlyTrialCombatScreen.kt | @Composable fun HeavenlyTrialCombatScreen( viewModel: HeavenlyTrialViewModel, onFinished: (won: Boolean) -> Unit )
| feature/game | HeavenlyTrialComponents.kt | @Composable internal fun CombatUnitCell( combatant: Combatant?, isCurrent: Boolean = false, isAllySelected: Boolean = false, isEnemySelected: Boolean = false, isShaking: Boolean = false, flightAnim: FlightAnimState = FlightAnimState(), modifier: Modifier = Modifier, onClick: () -> Unit )
| feature/game | HeavenlyTrialDiscipleDialog.kt | @Composable fun HeavenlyTrialDiscipleDialog( viewModel: HeavenlyTrialViewModel, gameViewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | HeavenlyTrialDiscipleDialog.kt | @Composable private fun DisciplePickerDialog( aliveDisciples: List<DiscipleAggregate>, currentSlotDiscipleId: String?, alreadySelectedIds: Set<String>, gameViewModel: GameViewModel, onSelect: (DiscipleAggregate) -> Unit, onDismiss: () -> Unit )
| feature/game | HeavenlyTrialPanel.kt | @Composable fun HeavenlyTrialPanel( viewModel: HeavenlyTrialViewModel, onOpenClearRewards: () -> Unit = {}, /** 是否绘制自带背景图（半屏嵌入时由宿主对话框提供背景，传 false 避免叠加） */ showBackground: Boolean = true )
| feature/game | ItemDetailDialog.kt | @Composable @Suppress("DEPRECATION") private fun ManualStatsContent( manual: ManualInstance, bonusMultiplier: Double, rarityColor: Color )
| feature/game | ItemDetailDialog.kt | @Composable fun ItemDetailDialog( item: Any, onDismiss: () -> Unit, viewModel: GameViewModel? = null, extraActions: @Composable (() -> Unit)? = null )
| feature/game | ItemDetailDialog.kt | @Composable fun LearnedManualDetailDialog( manual: ManualInstance, proficiencyData: ManualProficiencyData?, onForget: () -> Unit, onDismiss: () -> Unit, extraActions: @Composable (() -> Unit)? = null )
| feature/game | ItemDetailEffects.kt | @Suppress("DEPRECATION") internal fun getManualEffects(item: ManualInstance): List<String>
| feature/game | ItemDetailEffects.kt | @Suppress("DEPRECATION") internal fun getManualStackEffects(item: ManualStack): List<String>
| feature/game | ItemDetailEffects.kt | internal fun getPillEffects(item: Pill): List<String>
| feature/game | ItemDetailOtherEffects.kt | internal fun getMerchantItemEffects(item: MerchantItem): List<String>
| feature/game | ItemDetailOtherEffects.kt | internal fun getStorageBagItemEffects(item: StorageBagItem): List<String>
| feature/game | LawEnforcementHallDialog.kt | @Composable fun LawEnforcementHallDialog( disciples: List<DiscipleAggregate>, gameData: GameData?, viewModel: GameViewModel, productionViewModel: ProductionViewModel, onDismiss: () -> Unit )
| feature/game | LawEnforcementHallDialog.kt | @Composable private fun LawDisciplesSection( lawDisciples: List<DirectDiscipleSlot>, disciples: List<DiscipleAggregate>, onDiscipleClick: (Int) -> Unit, onDiscipleRemove: (Int) -> Unit, onDiscipleSwap: (Int) -> Unit = {} )
| feature/game | LevelDetailDialog.kt | @Composable fun LevelDetailDialog( level: MapItem.Level, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, onAttack: (List<String?>) -> Unit, onDismiss: () -> Unit )
| feature/game | LevelDetailDialog.kt | @Composable private fun LevelSlotSelectionDialog( disciples: List<DiscipleAggregate>, alreadySelectedIds: Set<String> = emptySet(), viewModel: GameViewModel, onSelect: (String) -> Unit, onDismiss: () -> Unit )
| feature/game | LibraryDialog.kt | @Composable private fun LibraryDiscipleSelectionDialog( disciples: List<DiscipleAggregate>, currentDiscipleId: String?, viewModel: GameViewModel, onSelect: (DiscipleAggregate) -> Unit, onDismiss: () -> Unit )
| feature/game | LoadingScreen.kt | @Composable private fun CustomGoldenProgressBar( progress: Float, modifier: Modifier = Modifier, borderColor: Color = Color(0xFFFFD700), progressColor: Color = Color(0xFFFFE55F) )
| feature/game | LoadingScreen.kt | @Composable private fun LoadingScreenContent( progress: Float, showProgress: Boolean, phaseText: String )
| feature/game | MailDialog.kt | @Composable fun MailDialog( viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | MailDialog.kt | @Composable private fun ClaimedAttachmentCard( attachment: MailAttachment, watchedKeys: Set<String> = emptySet() )
| feature/game | MailDialog.kt | @OptIn(ExperimentalLayoutApi::class) @Composable private fun MailDetailPanel( mail: MailEntity, viewModel: GameViewModel? = null, onClaim: () -> Unit )
| feature/game | MainGameScreen.kt | @Composable fun MainGameScreen( mapPreloadData: MapPreloadData, viewModel: GameViewModel, saveLoadViewModel: SaveLoadViewModel, productionViewModel: ProductionViewModel, alchemyViewModel: AlchemyViewModel, forgeViewModel: ForgeViewModel, herbGardenViewModel: HerbGardenViewModel, spiritMineViewModel: SpiritMineViewModel, patrolTowerViewModel: PatrolTowerViewModel, bloodRefiningViewModel: BloodRefiningViewModel, worldMapInteractionViewModel: WorldMapInteractionViewModel, worldMapGarrisonViewModel: WorldMapGarrisonViewModel, battleViewModel: BattleViewModel, onLogout: () -> Unit, onRestartGame: () -> Unit, limitAdTracking: Boolean = true, onLimitAdTrackingChanged: (Boolean) -> Unit = {}, /** 是否强制使用 Canvas 软件渲染（模拟器/Vulkan 不可用设备） */ forceSoftwareRendering: Boolean = false, /** Vulkan 初始化生命周期监听器（由 GameActivity 注入，驱动 CrashRecoveryEngine） */ vulkanInitListener: NativeSurfaceView.VulkanInitListener? = null, /** 用于后台 I/O 操作的协程调度器 */ dispatcher: CoroutineDispatcher = Dispatchers.IO )
| feature/game | MasterApprenticeSelectDialog.kt | @Composable fun MasterApprenticeSelectDialog( currentDisciple: DiscipleAggregate, allDisciples: List<DiscipleAggregate>, onDismiss: () -> Unit, onMasterSelected: (DiscipleAggregate) -> Unit )
| feature/game | MerchantDialog.kt | @Composable fun MerchantDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | MessageListContent.kt | @Composable fun MessageListContent( events: List<GameEventRecord>, modifier: Modifier = Modifier, scrollToBottomTrigger: Int = 0 )
| feature/game | MissionHallDialog.kt | @Composable fun MissionHallDialog( gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | MissionHallDialog.kt | @Composable private fun ActiveMissionCard( mission: ActiveMission, currentYear: Int, currentMonth: Int, onClick: () -> Unit )
| feature/game | MissionHallDialog.kt | @Composable private fun ActiveMissionDetailDialog( mission: ActiveMission, disciples: List<DiscipleAggregate>, currentYear: Int, currentMonth: Int, onDiscipleClick: (DiscipleAggregate?) -> Unit, onDismiss: () -> Unit )
| feature/game | MissionHallDialog.kt | @Composable private fun AvailableMissionCard( mission: Mission, onClick: () -> Unit )
| feature/game | MissionHallDialog.kt | @Composable private fun MissionDispatchDialog( mission: Mission, allDisciples: List<DiscipleAggregate>, busyDiscipleIds: Set<String>, gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | NativeSurfaceView.kt | NativeSurfaceView
| feature/game | NativeSurfaceView.kt | NativeSurfaceView.Companion
| feature/game | NativeSurfaceView.kt | NativeSurfaceView.RenderThread
| feature/game | OverlayDialogRouter.kt | @Composable internal fun OverlayDialogRoute( type: DialogType, vms: OverlayViewModels, callbacks: OverlayCallbacks, gameData: GameData, onDismiss: () -> Unit )
| feature/game | PatrolTowerDialog.kt | @Composable fun PatrolTowerDialog( buildingInstanceId: String = "", viewModel: GameViewModel, patrolTowerViewModel: PatrolTowerViewModel, gameData: GameData?, disciples: List<DiscipleAggregate>, onDismiss: () -> Unit )
| feature/game | PatrolTowerDialog.kt | @Composable private fun AttackRangeDialog( config: PatrolConfig, onSave: (PatrolConfig) -> Unit, onDismiss: () -> Unit )
| feature/game | PeakScreenComponents.kt | @Composable fun PeakDiscipleListSection( sectionTitle: String, emptyText: String, disciples: List<DiscipleAggregate>, maxHeightDp: Dp = 180.dp, truncateAt: Int? = 10 )
| feature/game | PeakScreenComponents.kt | @Composable fun PeakDiscipleSelectionDialog( title: String, disciples: List<DiscipleAggregate>, currentDiscipleId: String?, requirementText: String, viewModel: GameViewModel, onSelect: (DiscipleAggregate) -> Unit, onDismiss: () -> Unit, defaultSortAttribute: String? = null )
| feature/game | PlantingDialog.kt | @Composable fun PlantingDialog( seeds: List<Seed>, gameData: GameData, viewModel: GameViewModel, activeSectId: String, onDismiss: () -> Unit )
| feature/game | PlantingDialog.kt | @Composable private fun PlantingPagination( currentPage: Int, totalPages: Int, onFirstPage: () -> Unit, onPreviousPage: () -> Unit, onNextPage: () -> Unit, onLastPage: () -> Unit )
| feature/game | ProductionComponents.kt | @Composable fun ProductionDirectDiscipleSelectionDialog( theme: ProductionTheme, disciples: List<DiscipleAggregate>, elderSlots: ElderSlots, onDismiss: () -> Unit, onSelect: (String) -> Unit, viewModel: GameViewModel? = null, battleAndExplorationIds: Set<String> = emptySet(), )
| feature/game | ProductionComponents.kt | @Composable fun ProductionElderSelectionDialog( theme: ProductionTheme, disciples: List<DiscipleAggregate>, currentElderId: String?, elderSlots: ElderSlots, onDismiss: () -> Unit, onSelect: (String) -> Unit, viewModel: GameViewModel? = null, battleAndExplorationIds: Set<String> = emptySet(), )
| feature/game | ProductionComponents.kt | @Composable fun ProductionSlotItem( theme: ProductionTheme, productName: String?, isWorking: Boolean, isIdle: Boolean, remainingMonths: Int, index: Int, productRarity: Int = 1, totalDuration: Int = 1, isPill: Boolean = false, isHerb: Boolean = false, successRate: Double = 0.0, gamePhase: Int = 0, onCancel: (() -> Unit)? = null, onReplace: (() -> Unit)? = null, onClick: () -> Unit )
| feature/game | QingyunPeakDialog.kt | @Composable fun QingyunPeakDialog( disciples: List<DiscipleAggregate>, gameData: GameData?, viewModel: GameViewModel, productionViewModel: ProductionViewModel, onDismiss: () -> Unit, )
| feature/game | RecruitDialog.kt | @Composable fun RecruitDialog( recruitList: List<DiscipleAggregate>, gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | RecruitDialog.kt | @Composable private fun RecruitManagementDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | ReflectionCliffDialog.kt | @Composable fun ReflectionCliffDialog( disciples: List<DiscipleAggregate>, gameData: GameData?, onDismiss: () -> Unit, onExpelDisciple: (String) -> Unit = {}, onReleaseDisciple: (String) -> Unit = {} )
| feature/game | ResidenceDialog.kt | @Composable fun ResidenceDialog( buildingInstanceId: String, viewModel: GameViewModel, disciples: List<DiscipleAggregate>, gameData: GameData, onDismiss: () -> Unit )
| feature/game | RewardCardHost.kt | @Composable private fun AnimatedRewardCard( item: RewardCardItem, cardIndex: Int, totalCards: Int, showTopDivider: Boolean = false, onAllDone: () -> Unit )
| feature/game | SaveLoadLoadDelegate.kt | SaveLoadLoadDelegate
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | SaveLoadViewModel.kt | SaveLoadViewModel
| feature/game | ScoutDialog.kt | @Composable internal fun ScoutDialog( sectName: String, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, onScout: (List<String>) -> Unit, onDismiss: () -> Unit )
| feature/game | ScoutDialog.kt | @Composable private fun ScoutDiscipleSelectionDialog( disciples: List<DiscipleAggregate>, currentSlotDiscipleId: String? = null, alreadySelectedIds: Set<String> = emptySet(), viewModel: GameViewModel, onSelect: (DiscipleAggregate) -> Unit, onDismiss: () -> Unit )
| feature/game | SecretRealmBackpackDialog.kt | @Composable internal fun SecretRealmBackpackDialog( backpack: SecretRealmBackpack, onDismiss: () -> Unit )
| feature/game | SecretRealmDetailDialog.kt | @Composable fun SecretRealmDetailDialog( realm: MapItem.SecretRealm, gameData: GameData?, viewModel: SecretRealmViewModel, onStart: (memberIds: List<String>) -> Unit, onContinue: () -> Unit, onDismiss: () -> Unit )
| feature/game | SecretRealmExplorationScreen.kt | @Composable fun SecretRealmExplorationScreen( viewModel: SecretRealmViewModel, onExit: () -> Unit, onFinished: () -> Unit )
| feature/game | SectDiplomacyDialog.kt | @Composable internal fun SectDiplomacyDialog( sect: WorldSect, relation: Int, gameData: GameData?, disciples: List<DiscipleAggregate>, interactionViewModel: WorldMapInteractionViewModel, onDismiss: () -> Unit )
| feature/game | SectDiplomacyDialog.kt | @Composable private fun RightPanel( initialDialogueText: String, portraitRes: String, playerPortraitRes: String, sectName: String, isAlly: Boolean, isPlayerVassal: Boolean = false, canVassal: Boolean = true, hasGiftedThisYear: Boolean, relationLevel: SectRelationLevel, spiritStones: Long = 0, chatMessages: List<ChatMessage>, visibleCount: Int, isChatting: Boolean, isChatDone: Boolean, skipped: Boolean, showGiftOptions: Boolean = false, onAllianceClick: () -> Unit, onDissolveClick: () -> Unit, onVassalClick: () -> Unit = {}, onDissolveVassalClick: () -> Unit = {}, onSkipClick: () -> Unit, onGiftClick: () -> Unit, onGiftTierClick: (Int) -> Unit, onCancelGiftClick: () -> Unit, modifier: Modifier = Modifier )
| feature/game | SectInfoCard.kt | @Composable internal fun SectInfoCard( sectName: String, gameYear: Int, gameMonth: Int, gamePhase: Int, lowStones: Long, midStones: Long, highStones: Long, discipleCount: Int, combatPower: Long, sectLevel: Int = SectLevel.MEDIUM, showRewardBadge: Boolean = false, onSectIconClick: () -> Unit = {}, onSectNameClick: () -> Unit = {} )
| feature/game | SectLevelDetailDialog.kt | @Composable fun SectLevelDetailDialog( gameData: GameData, aliveDisciples: List<DiscipleAggregate>, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | SectLevelRewardDialog.kt | @Composable fun SectLevelRewardDialog( level: Int, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | SectManagementDialog.kt | @Composable fun SectManagementDialog( gameData: GameData?, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | SectMapEdgeOverlay.kt | @Composable fun SectMapEdgeOverlay( cameraState: SectCameraState, worldPixelWidth: Int, worldPixelHeight: Int, modifier: Modifier = Modifier )
| feature/game | SectMapViewport.kt | @Composable internal fun SectMapViewport( params: SectMapViewportParams, preview: MapPreviewState, commandBus: RenderCommandBus, onViewCreated: (NativeSurfaceView) -> Unit, modifier: Modifier = Modifier )
| feature/game | SectTradeDialog.kt | @Composable fun SectTradeDialog( sect: WorldSect?, gameData: GameData?, tradeItems: List<MerchantItem>, viewModel: GameViewModel, interactionViewModel: WorldMapInteractionViewModel, onDismiss: () -> Unit )
| feature/game | SettingsTab.kt | @Composable internal fun RedeemCodeDialog( viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | SettingsTab.kt | @Composable internal fun SaveSlotCard( slot: SaveSlot, isSelected: Boolean, onClick: () -> Unit )
| feature/game | SettingsTab.kt | @Composable internal fun SaveSlotDialog( viewModel: GameViewModel, saveLoadViewModel: SaveLoadViewModel, onDismiss: () -> Unit )
| feature/game | SettingsTab.kt | @Composable internal fun SettingsTab( viewModel: GameViewModel, saveLoadViewModel: SaveLoadViewModel, onLogout: () -> Unit, onDismiss: () -> Unit, limitAdTracking: Boolean = true, onLimitAdTrackingChanged: (Boolean) -> Unit = {} )
| feature/game | SettingsTab.kt | @Composable private fun ChangelogDialog(onDismiss: () -> Unit)
| feature/game | SoftwareCanvasBackend.kt | SoftwareCanvasBackend
| feature/game | SpiritMineDialog.kt | @Composable fun SpiritMineDialog( buildingInstanceId: String = "", viewModel: GameViewModel, productionViewModel: ProductionViewModel, spiritMineViewModel: SpiritMineViewModel, onDismiss: () -> Unit, spiritMineBaseOutput: Int = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER, spiritMineMiningThreshold: Int = GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD, spiritMineMiningBonusRate: Double = GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE )
| feature/game | TianshuHallDialog.kt | @Composable fun TianshuHallDialog( gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, productionViewModel: ProductionViewModel, onDismiss: () -> Unit )
| feature/game | TianshuHallDialog.kt | @Composable private fun SectPoliciesDialog( gameData: GameData?, viewModel: GameViewModel, productionViewModel: ProductionViewModel, onDismiss: () -> Unit )
| feature/game | WarehouseBulkSellDialog.kt | @Composable internal fun BulkSellDialog( viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | WarehouseDetailDialog.kt | @Composable private fun SellQuantitySelector( sellQuantity: Int, maxQuantity: Int, onQuantityChange: (Int) -> Unit )
| feature/game | WarehouseDialog.kt | @Composable fun WarehouseDialog( buildingInstanceId: String, gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, productionViewModel: ProductionViewModel, onDismiss: () -> Unit )
| feature/game | WarehouseDiscipleSelectDialog.kt | @Composable internal fun DiscipleSelectForRewardDialog( itemName: String, itemId: String, itemType: String, itemRarity: Int, viewModel: GameViewModel, onDismiss: () -> Unit )
| feature/game | WarehouseTab.kt | @Composable internal fun WarehousePagination( currentPage: Int, totalPages: Int, onPreviousPage: () -> Unit, onNextPage: () -> Unit, onFirstPage: () -> Unit, onLastPage: () -> Unit )
| feature/game | WarehouseTab.kt | @Composable internal fun WarehouseTab( viewModel: GameViewModel, showBulkSellDialog: Boolean = false, onBulkSellDismiss: () -> Unit = {}, onDismiss: () -> Unit = {} )
| feature/game | WenDaoPeakDialog.kt | @Composable fun WenDaoPeakDialog( disciples: List<DiscipleAggregate>, gameData: GameData?, viewModel: GameViewModel, productionViewModel: ProductionViewModel, onDismiss: () -> Unit, // ← NEW: use instead of viewModel.closeCurrentDialog() )
| feature/game | WorldMapDialog.kt | @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) @Composable internal fun WorldMapDialog( worldSects: List<WorldSect>, mapRenderData: WorldMapRenderData, gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, interactionViewModel: WorldMapInteractionViewModel, garrisonViewModel: WorldMapGarrisonViewModel, onDismiss: () -> Unit )
| feature/game | WorldMapScreen.kt | @Composable fun WorldMapScreen( items: List<MapItem>, cameraState: WorldCameraState = rememberWorldCamera( worldWidth = MapCoordinateSystem.WORLD_WIDTH, worldHeight = MapCoordinateSystem.WORLD_HEIGHT ), focusWorldX: Float? = null, focusWorldY: Float? = null, onBack: () -> Unit = {}, onSectClick: (MapItem.Sect) -> Unit = {}, onLevelClick: (MapItem.Level) -> Unit = {}, onSecretRealmClick: (MapItem.SecretRealm) -> Unit = {}, onUserInteraction: () -> Unit = {}, connectionEdges: List<MSTEdge> = emptyList() )
| feature/game | WorldMapSectDetailDialog.kt | @Composable internal fun WorldMapSectDetailDialog( sect: WorldSect, gameData: GameData?, disciples: List<DiscipleAggregate>, viewModel: GameViewModel, interactionViewModel: WorldMapInteractionViewModel, garrisonViewModel: WorldMapGarrisonViewModel, onDismiss: () -> Unit )
| feature/game | WorldMapSectDetailDialog.kt | @Composable private fun GarrisonDiscipleSelectionDialog( disciples: List<DiscipleAggregate>, garrisonedIds: Set<String> = emptySet(), viewModel: GameViewModel, onSelect: (DiscipleAggregate) -> Unit, onDismiss: () -> Unit )
| core/data | FunctionalWAL.kt | FunctionalWAL
| core/data | FunctionalWAL.kt | FunctionalWAL
| core/data | FunctionalWAL.kt | FunctionalWAL
| core/data | GameDatabaseMigrationSupport.kt | internal fun rebuildGameData( db: SupportSQLiteDatabase, oldSuffix: String, sourceColumns: List<String> )
| core/data | GameDatabaseMigrationsV11ToV20.kt | <no name provided>
| core/data | GameDatabaseMigrationsV21ToV30.kt | <no name provided>
| core/data | GameStateRepository.kt | GameStateRepository
| core/data | RoomMigrationTest.kt | RoomMigrationTest
| core/data | SecureKeyManager.kt | SecureKeyManager
| core/data | StorageEngine.kt | StorageEngine
| core/data | StorageEngine.kt | StorageEngine
| core/data | StorageEngine.kt | StorageEngine
| core/data | StorageEngine.kt | StorageEngine
| core/data | StorageSystemBenchmark.kt | StorageSystemBenchmark
| core/data | UiKeyRecoveryCallback.kt | UiKeyRecoveryCallback
| core/data | UnifiedSerializationEngine.kt | UnifiedSerializationEngine
| core/domain | DiscipleAggregate.kt | DiscipleAggregate
| core/domain | DiscipleSerializer.kt | DiscipleSerializer
| core/domain | DiscipleSerializer.kt | DiscipleSerializer
| core/domain | DiscipleTables.kt | DiscipleTables
| core/domain | DiscipleTables.kt | DiscipleTables
| core/domain | ItemDatabase.kt | ItemDatabase
| core/domain | ItemDatabase.kt | ItemDatabase
| core/domain | ItemDatabase.kt | ItemDatabase
| core/domain | PillRecipeDatabase.kt | PillRecipeDatabase
| core/domain | PillRecipeDatabase.kt | PillRecipeDatabase
| core/domain | PillRecipeDatabase.kt | PillRecipeDatabase
| core/domain | SectLevelRewardConfig.kt | SectLevelRewardConfig
| core/ui | ElderBonusInfoButton.kt | @Composable fun ElderBonusInfoDialog( bonusInfo: ElderBonusInfo, onDismiss: () -> Unit, @DrawableRes backgroundRes: Int = R.drawable.bg_horizontal, @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button )
| core/ui | GameDialog.kt | @Composable fun UnifiedGameDialog( onDismissRequest: () -> Unit, title: String, modifier: Modifier = Modifier, mode: DialogMode = DialogMode.Half, dismissOnBackPress: Boolean = true, dismissOnClickOutside: Boolean = true, scrimEnabled: Boolean = true, headerActions: @Composable (() -> Unit)? = null, headerContent: @Composable (() -> Unit)? = null, scrollableContent: Boolean = false, titleColor: Color = Color.Black, titleFontSize: TextUnit = AppTypography.Title, titleAlignment: Alignment = Alignment.Center, showCloseButton: Boolean = true, /** 是否渲染标题栏（false 时隐藏 header 且内容区零 padding，供全屏内容覆盖使用） */ showHeader: Boolean = true, @DrawableRes backgroundRes: Int = SpriteResRegistry.resolve("bg_horizontal") ?: R.drawable.bg_horizontal, @DrawableRes closeButtonRes: Int = SpriteResRegistry.resolve("ui_close_button") ?: R.drawable.ui_close_button, content: @Composable () -> Unit )
| core/ui | ItemCard.kt | @Composable fun UnifiedItemCard( data: ItemCardData, modifier: Modifier = Modifier, size: Dp = 60.dp, isSelected: Boolean = false, selectedBorderColor: Color = Color.White, isFollowed: Boolean = false, showQuantity: Boolean = true, showPrice: Boolean = false, craftable: Boolean = true, onClick: () -> Unit = {}, onLongPress: (() -> Unit)? = null, overlayButtonText: String? = null, onOverlayButtonClick: (() -> Unit)? = null, showPlaceholderText: Boolean = true, nameFontSize: androidx.compose.ui.unit.TextUnit = 9.sp )
| core/ui | SmallScreenDialog.kt | @Composable fun SmallScreenDialog( onDismissRequest: () -> Unit, title: String, titleColor: Color = Color.Black, dismissOnBackPress: Boolean = true, dismissOnClickOutside: Boolean = true, footer: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit )
| core/ui | StandardPromptDialog.kt | @Composable fun InlineStandardPromptDialog( onDismissRequest: () -> Unit, title: String, text: String? = null, confirmLabel: String = "确定", onConfirm: () -> Unit = onDismissRequest, dismissLabel: String? = null, onDismiss: (() -> Unit)? = null, customButtons: (@Composable RowScope.() -> Unit)? = null, dismissOnBackPress: Boolean = true, dismissOnClickOutside: Boolean = true, showCloseButton: Boolean = false, scrimEnabled: Boolean = true, titleColor: Color = Color.Black, @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box, @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button, @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button, content: @Composable (ColumnScope.() -> Unit) = {} )
| core/ui | StandardPromptDialog.kt | @Composable fun StandardPromptDialog( onDismissRequest: () -> Unit, title: String, text: String? = null, confirmLabel: String = "确定", onConfirm: () -> Unit = onDismissRequest, dismissLabel: String? = null, onDismiss: (() -> Unit)? = null, customButtons: (@Composable RowScope.() -> Unit)? = null, dismissOnBackPress: Boolean = true, dismissOnClickOutside: Boolean = true, showCloseButton: Boolean = false, scrimEnabled: Boolean = true, titleColor: Color = Color.Black, @DrawableRes dialogBackgroundRes: Int = R.drawable.dialog_box, @DrawableRes buttonBackgroundRes: Int = R.drawable.ui_button, @DrawableRes closeButtonRes: Int = R.drawable.ui_close_button, content: @Composable (ColumnScope.() -> Unit) = {} )
| core/engine | AISectAttackManager.kt | AISectAttackManager
| core/engine | AISectAttackManager.kt | AISectAttackManager
| core/engine | AISectAttackManager.kt | AISectAttackManager
| core/engine | AISectAttackManager.kt | AISectAttackManager
| core/engine | AISectBeastAttackProcessor.kt | AISectBeastAttackProcessor
| core/engine | AISectDiscipleManager.kt | AISectDiscipleManager
| core/engine | AISectDiscipleManager.kt | AISectDiscipleManager
| core/engine | AISectTeamComposer.kt | internal fun generateWarRewards(sectLevel: Int, itemCount: Int): WarRewards
| core/engine | BattleAI.kt | BattleAI
| core/engine | BattleCalculator.kt | BattleCalculator
| core/engine | BattleSystem.kt | BattleSystem
| core/engine | BattleSystem.kt | BattleSystem
| core/engine | BattleSystem.kt | BattleSystem
| core/engine | BootSequenceController.kt | BootSequenceController
| core/engine | BuildingConfigService.kt | BuildingConfigService
| core/engine | BuildingFeatureTestRegistration.kt | fun BuildingFeatureRegistry.registerTestFeatures()
| core/engine | BuildingService.kt | BuildingService
| core/engine | CaveExplorationProcessor.kt | CaveExplorationProcessor
| core/engine | CaveExplorationProcessor.kt | CaveExplorationProcessor
| core/engine | CaveExplorationSystem.kt | CaveExplorationSystem
| core/engine | CaveExplorationSystem.kt | CaveExplorationSystem
| core/engine | CombatService.kt | CombatService
| core/engine | CultivationCore.kt | CultivationCore
| core/engine | CultivationCoreRealtimeAutoPillsTest.kt | PillsRealtime
| core/engine | CultivationEventProcessor.kt | CultivationEventProcessor
| core/engine | CultivationEventProcessor.kt | CultivationEventProcessor
| core/engine | CultivationRateCalculator.kt | CultivationRateCalculator
| core/engine | CultivationRateEquivalenceTest.kt | CultivationRateEquivalenceTest
| core/engine | CustomVelocityTracker.kt | CustomVelocityTracker
| core/engine | DailySignInService.kt | DailySignInService
| core/engine | DiplomacyService.kt | DiplomacyService
| core/engine | DiplomacyService.kt | DiplomacyService
| core/engine | DiplomacyService.kt | DiplomacyService
| core/engine | DiscipleEquipmentService.kt | DiscipleEquipmentService
| core/engine | DiscipleFacadeImpl.kt | DiscipleFacadeImpl
| core/engine | DiscipleFacadeImpl.kt | DiscipleFacadeImpl
| core/engine | DiscipleFacadeImpl.kt | DiscipleFacadeImpl
| core/engine | DiscipleFactory.kt | DiscipleFactory
| core/engine | DiscipleLifecycleProcessor.kt | DiscipleLifecycleProcessor
| core/engine | DiscipleManualManager.kt | DiscipleManualManager
| core/engine | DiscipleServiceCrudTest.kt | DiscipleServiceCrudTest
| core/engine | DiscipleSlotManager.kt | DiscipleSlotManager
| core/engine | DiscipleStatusService.kt | DiscipleStatusService
| core/engine | DiscipleStatusService.kt | DiscipleStatusService.Companion
| core/engine | ElderManagementUseCase.kt | ElderManagementUseCase
| core/engine | EncounterBattleService.kt | EncounterBattleService
| core/engine | EncounterBattleService.kt | EncounterBattleService
| core/engine | EncounterBattleService.kt | EncounterBattleService
| core/engine | EnemyGenerator.kt | EnemyGenerator
| core/engine | ExplorationService.kt | ExplorationService
| core/engine | GameEngineAtomicAssign.kt | suspend fun GameEngine.assignPatrolAtomic( discipleId: String, globalIndex: Int ): DomainResult<Unit>
| core/engine | GameEngineAtomicAssign.kt | suspend fun GameEngine.assignToResidenceAtomic( buildingInstanceId: String, slotIndex: Int, discipleId: String ): DomainResult<Unit>
| core/engine | GameEngineAtomicAssign.kt | suspend fun GameEngine.autoAssignPatrolAtomic( assignments: List<Pair<Int, String>> ): DomainResult<Unit>
| core/engine | GameEngineAtomicAssign.kt | suspend fun GameEngine.swapPatrolAtomic( fromGlobalIndex: Int, toGlobalIndex: Int ): DomainResult<Unit>
| core/engine | GameEngineAtomicAssignTest.kt | GameEngineAtomicAssignTest
| core/engine | GameEngineBattleOps.kt | private suspend fun GameEngine.handleCaveLevelVictory(level: WorldLevel): List<BattleRewardItem>
| core/engine | GameEngineBattleOps.kt | suspend fun GameEngine.attackSect(sectId: String, attackSlots: List<Pair<Int, DiscipleAggregate>>)
| core/engine | GameEngineBattleOps.kt | suspend fun GameEngine.attackWorldLevel(levelId: String, discipleIds: List<String?>)
| core/engine | GameEngineBattleOps.kt | suspend fun GameEngine.scoutSect(sectId: String, memberIds: List<String>)
| core/engine | GameEngineCoordination.kt | private suspend fun GameEngine.applyMissionResult( result: MissionSystem.MissionResult, activeMission: ActiveMission, year: Int, month: Int, aliveDisciples: List<Disciple> )
| core/engine | GameEngineCoordination.kt | suspend fun GameEngine.loadData( gameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material> = emptyList(), herbs: List<Herb> = emptyList(), seeds: List<Seed> = emptyList(), teams: List<ExplorationTeam>, battleLogs: List<BattleLog> = emptyList(), alliances: List<Alliance> = emptyList(), productionSlots: List<ProductionSlot> = emptyList(), storageBags: List<StorageBag> = emptyList() )
| core/engine | GameEngineCore.kt | GameEngineCore
| core/engine | GameEngineCore.kt | GameEngineCore
| core/engine | GameEngineMarriageProposalTest.kt | GameEngineMarriageProposalTest
| core/engine | GameEngineSectLevelOps.kt | suspend fun GameEngine.claimSectLevelReward(level: Int): SectLevelClaimResult
| core/engine | GiftService.kt | GiftService
| core/engine | HeavenlyTrialService.kt | HeavenlyTrialService
| core/engine | HeavenlyTrialService.kt | HeavenlyTrialService
| core/engine | HeavenlyTrialService.kt | HeavenlyTrialService
| core/engine | InventoryFacadeImpl.kt | InventoryFacadeImpl
| core/engine | InventoryFacadeImpl.kt | InventoryFacadeImpl
| core/engine | InventoryFacadeImpl.kt | InventoryFacadeImpl
| core/engine | LawEnforcementProcessor.kt | LawEnforcementProcessor
| core/engine | MailService.kt | MailService
| core/engine | ManualDatabase.kt | ManualDatabase
| core/engine | ManualDatabaseTest.kt | ManualDatabaseTest
| core/engine | MissionSystem.kt | MissionSystem
| core/engine | PatrolBattleSystem.kt | PatrolBattleSystem
| core/engine | ProductionCoordinator.kt | ProductionCoordinator
| core/engine | ProductionCoordinator.kt | ProductionCoordinator
| core/engine | ProductionProcessor.kt | ProductionProcessor
| core/engine | ProductionProcessorAutoAlchemyTest.kt | ProductionProcessorAutoAlchemyTest
| core/engine | ProductionTransactionManager.kt | ProductionTransactionManager
| core/engine | ProductionTransactionManager.kt | ProductionTransactionManager
| core/engine | RecruitService.kt | RecruitService.Companion
| core/engine | RedeemCodeManager.kt | RedeemCodeManager
| core/engine | RedeemCodeManager.kt | RedeemCodeManager
| core/engine | RedeemCodeManager.kt | RedeemCodeManager
| core/engine | RedeemCodeService.kt | RedeemCodeService
| core/engine | SectMapTouchEngine.kt | SectMapTouchEngine
| core/engine | ThermalController.kt | ThermalController
| core/engine | VassalService.kt | VassalService
