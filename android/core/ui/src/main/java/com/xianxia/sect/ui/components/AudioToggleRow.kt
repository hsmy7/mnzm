package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 音频设置两行勾选组件。
 *
 * 在登录界面右上角、主菜单头像下方、游戏设置界面共用。
 *
 * @param soundEnabled 音效开关状态
 * @param musicEnabled 音乐开关状态
 * @param onSoundToggle 音效切换回调
 * @param onMusicToggle 音乐切换回调
 */
@Composable
fun AudioToggleRow(
    soundEnabled: Boolean,
    musicEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    onMusicToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "音乐",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.width(4.dp))
            CircularCheckbox(
                checked = musicEnabled,
                onToggle = { onMusicToggle(!musicEnabled) }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "音效",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.width(4.dp))
            CircularCheckbox(
                checked = soundEnabled,
                onToggle = { onSoundToggle(!soundEnabled) }
            )
        }
    }
}
