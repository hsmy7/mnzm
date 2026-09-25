package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun RelationsDialog(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit
) {
    val discipleMap = remember(allDisciples) { allDisciples.associateBy { it.id } }

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
            RelationsContent(master = master, apprentices = apprentices)
        }
    }
}

/** 关系列表内容：师徒类别 + 无关系空态 */
@Composable
private fun RelationsContent(
    master: DiscipleAggregate?,
    apprentices: List<DiscipleAggregate>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = DialogDefaults.CommonMaxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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

        if (master == null && apprentices.isEmpty()) {
            Text(
                text = "无关系",
                fontSize = 12.sp,
                color = Color.Black
            )
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
