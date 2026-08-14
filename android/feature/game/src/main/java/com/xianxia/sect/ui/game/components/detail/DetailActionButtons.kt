package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.theme.GameColors

/** 特质名称格子目标宽度（dp）：单行容纳 4 个汉字加粗名称（10sp×4≈40dp，字体缩放 1.3 时约 52dp，含 8dp 内边距与余量） */
private const val TRAIT_CELL_TARGET_WIDTH_DP = 64

/** 新增入口（+ 号）图标尺寸（dp，与灵根洗炼入口一致） */
private const val ADD_BUTTON_SIZE_DP = 18

/**
 * 按实际容器宽度动态计算特质格子列数（1~5 列自适应）：
 * 每格宽度 ≥ TRAIT_CELL_TARGET_WIDTH_DP，保证 4 字名称单行容纳；容器宽则列多、窄则列少。
 */
private fun BoxWithConstraintsScope.traitCellColumnCount(): Int =
    maxOf(1, (maxWidth / TRAIT_CELL_TARGET_WIDTH_DP.dp).toInt())

/**
 * 特质栏标题行：标题左侧 + 新增入口（+ 号）右侧。
 *
 * 新增（消耗玉符玩法）入口：未达上限（[GameConfig.TraitAdd.MAX_TRAITS_PER_CATEGORY]）时
 * 显示 + 号按钮，点击打开"新增XX"弹窗（样式与灵根洗炼入口一致）。
 */
@Composable
private fun SectionHeader(
    title: String,
    showAddButton: Boolean,
    addDescription: String,
    onAddClick: (() -> Unit)?,
    dismissDropdown: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.weight(1f))
        if (showAddButton && onAddClick != null) {
            SpriteImage(
                name = "ui_add_button",
                contentDescription = addDescription,
                modifier = Modifier
                    .size(ADD_BUTTON_SIZE_DP.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { dismissDropdown(); onAddClick() }),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

@Composable
fun TalentsSection(
    talents: List<Talent>,
    statusData: Map<String, String> = emptyMap(),
    onTalentClick: (Talent) -> Unit = {},
    onAddClick: (() -> Unit)? = null
) {
    val dismissDropdown = LocalDismissDropdown.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = "天赋",
            showAddButton = talents.size < GameConfig.TraitAdd.MAX_TRAITS_PER_CATEGORY,
            addDescription = "新增天赋",
            onAddClick = onAddClick,
            dismissDropdown = dismissDropdown
        )

        if (talents.isEmpty()) {
            Text(
                text = "无天赋",
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columnCount = traitCellColumnCount()
                talents.chunked(columnCount).forEach { rowTalents ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowTalents.forEach { talent ->
                            val rarityColor = getTalentRarityColor(talent.rarity)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, rarityColor, RoundedCornerShape(4.dp))
                                    .clickable { dismissDropdown(); onTalentClick(talent) }
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = talent.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rarityColor,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        repeat(columnCount - rowTalents.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhysiquesSection(
    physiques: List<Physique>,
    onPhysiqueClick: (Physique) -> Unit = {},
    onAddClick: (() -> Unit)? = null
) {
    val dismissDropdown = LocalDismissDropdown.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = "体质",
            showAddButton = physiques.size < GameConfig.TraitAdd.MAX_TRAITS_PER_CATEGORY,
            addDescription = "新增体质",
            onAddClick = onAddClick,
            dismissDropdown = dismissDropdown
        )

        if (physiques.isEmpty()) {
            Text(
                text = "无体质",
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columnCount = traitCellColumnCount()
                physiques.chunked(columnCount).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowItems.forEach { physique ->
                            val rarityColor = getTalentRarityColor(physique.rarity)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, rarityColor, RoundedCornerShape(4.dp))
                                    .clickable { dismissDropdown(); onPhysiqueClick(physique) }
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = physique.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rarityColor,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        repeat(columnCount - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AffixesSection(
    affixes: List<Affix>,
    onAffixClick: (Affix) -> Unit = {},
    onAddClick: (() -> Unit)? = null
) {
    val dismissDropdown = LocalDismissDropdown.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = "词条",
            showAddButton = affixes.size < GameConfig.TraitAdd.MAX_TRAITS_PER_CATEGORY,
            addDescription = "新增词条",
            onAddClick = onAddClick,
            dismissDropdown = dismissDropdown
        )

        if (affixes.isEmpty()) {
            Text(
                text = "无词条",
                fontSize = 12.sp,
                color = Color.Black
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columnCount = traitCellColumnCount()
                affixes.chunked(columnCount).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowItems.forEach { affix ->
                            val rarityColor = getTalentRarityColor(affix.rarity)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, rarityColor, RoundedCornerShape(4.dp))
                                    .clickable { dismissDropdown(); onAffixClick(affix) }
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = affix.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rarityColor,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        repeat(columnCount - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RelationsDialog(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit
) {
    val discipleMap = remember(allDisciples) { allDisciples.associateBy { it.id } }
    val partner = remember(disciple.partnerId, allDisciples) {
        disciple.partnerId?.let { id -> discipleMap[id] }
    }

    val parent1 = remember(disciple.parentId1, allDisciples) {
        disciple.parentId1?.let { id -> discipleMap[id] }
    }

    val parent2 = remember(disciple.parentId2, allDisciples) {
        disciple.parentId2?.let { id -> discipleMap[id] }
    }

    val children = remember(disciple.id, allDisciples) {
        allDisciples.filter { it.parentId1 == disciple.id || it.parentId2 == disciple.id }
    }

    val siblings = remember(disciple.parentId1, disciple.parentId2, allDisciples) {
        if (disciple.parentId1 == null && disciple.parentId2 == null) {
            emptyList()
        } else {
            allDisciples.filter {
                it.id != disciple.id &&
                (it.parentId1 == disciple.parentId1 || it.parentId2 == disciple.parentId2 ||
                 it.parentId1 == disciple.parentId2 || it.parentId2 == disciple.parentId1)
            }
        }
    }

    val master = remember(disciple.masterId, allDisciples) {
        disciple.masterId?.let { id -> discipleMap[id] }
    }

    val apprentices = remember(disciple.id, allDisciples) {
        allDisciples.filter { it.masterId == disciple.id }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "关系",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DialogDefaults.CommonMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (parent1 != null || parent2 != null) {
                    RelationCategory("父母") {
                        parent1?.let { RelationItem("父亲", it) }
                        parent2?.let { RelationItem("母亲", it) }
                    }
                }

                if (partner != null) {
                    RelationCategory("道侣") {
                        RelationItem("道侣", partner)
                    }
                }

                if (children.isNotEmpty()) {
                    RelationCategory("子嗣") {
                        children.forEach { child ->
                            val relation = if (child.gender == "male") "子" else "女"
                            RelationItem(relation, child)
                        }
                    }
                }

                if (siblings.isNotEmpty()) {
                    RelationCategory("兄弟姐妹") {
                        siblings.forEach { sibling ->
                            val relation = if (sibling.gender == "male") "兄弟" else "姐妹"
                            RelationItem(relation, sibling)
                        }
                    }
                }

                if (master != null) {
                    RelationCategory("师父") {
                        RelationItem("师父", master)
                    }
                }

                if (apprentices.isNotEmpty()) {
                    RelationCategory("徒弟") {
                        apprentices.forEach { apprentice ->
                            RelationItem("徒弟", apprentice)
                        }
                    }
                }

                if (parent1 == null && parent2 == null && partner == null && children.isEmpty() && siblings.isEmpty() && master == null && apprentices.isEmpty()) {
                    Text(
                        text = "无关系",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun RelationCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        content()
    }
}

@Composable
fun RelationItem(relation: String, disciple: DiscipleAggregate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = relation,
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = disciple.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}
