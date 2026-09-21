package com.xianxia.sect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.GameButton

/**
 * 存档初始化失败阻断屏（SR-3，审计 §12-J 修复）。
 *
 * StorageFacade 初始化 3 次全败后替代 LoadingScreen：如实展示失败原因与影响
 * （文案模式感知，见 [storageInitFailureMessage]），提供「重试」入口——
 * 不再静默放行进主菜单（旧实现的空缓存会话会静默丢玩家进度）。
 */
@Composable
fun StorageInitErrorScreen(
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠",
                fontSize = 40.sp,
                color = Color(0xFFE53935)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "存档初始化失败",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color(0xFF555555),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            GameButton(
                text = "重试",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
