package com.xianxia.sect.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SmallScreenDialog 窗口级 overlay 槽位测试（2026-08-08 物品详情售卖弹窗不可见根治）：
 * - overlay 槽位内内联覆盖层可见（修复结构守卫）
 * - 可滚动内容区尾部渲染不可见（旧缺陷回归文档：无限高度约束落视口外）
 * - overlay 默认 null 时内容正常
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmallScreenDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `overlay 槽位内内联覆盖层可见 - 修复结构守卫`() {
        composeRule.setContent {
            SmallScreenDialog(
                title = "物品详情",
                onDismissRequest = {},
                overlay = {
                    InlineStandardPromptDialog(
                        onDismissRequest = {},
                        title = "确认出售",
                        confirmLabel = "确定",
                        dismissLabel = "取消"
                    ) {
                        Text("确认以当前价格出售该物品吗？")
                    }
                }
            ) {
                Text("详情内容")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认出售").assertIsDisplayed()
        composeRule.onNodeWithText("确认以当前价格出售该物品吗？").assertIsDisplayed()
    }

    @Test
    fun `可滚动内容区尾部渲染内联覆盖层不可见 - 旧缺陷回归文档`() {
        composeRule.setContent {
            SmallScreenDialog(
                title = "物品详情",
                onDismissRequest = {}
            ) {
                // 复刻修复前 ItemDetailDialog 结构：overlay 渲染在可滚动内容区尾部，
                // 前序内容撑满视口 → 覆盖层落到可见视口外（fillMaxSize 无法全屏）
                Box(Modifier.fillMaxWidth().height(1000.dp)) {}
                InlineStandardPromptDialog(
                    onDismissRequest = {},
                    title = "确认出售",
                    confirmLabel = "确定",
                    dismissLabel = "取消"
                ) {
                    Text("确认以当前价格出售该物品吗？")
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认出售").assertIsNotDisplayed()
    }

    @Test
    fun `overlay 默认 null 时内容正常渲染`() {
        composeRule.setContent {
            SmallScreenDialog(
                title = "物品详情",
                onDismissRequest = {}
            ) {
                Text("详情内容")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("详情内容").assertIsDisplayed()
    }
}
