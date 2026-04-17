package com.xianxia.sect.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

private const val TAPTAP_SDK_PRIVACY_URL = "https://developer.taptap.cn/docs/sdk/start/agreement/"
private const val MMKV_URL = "https://github.com/Tencent/MMKV"
private const val PRIVACY_POLICY_URL = "https://hsmy7.github.io/index.html/"

private fun openUrlInBrowser(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PrivacyConsentScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val context = LocalContext.current
    var showFullPolicy by remember { mutableStateOf(false) }

    if (showFullPolicy) {
        FullPrivacyPolicyScreen(
            onBack = { showFullPolicy = false }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.PageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Text(
                text = "隐私政策",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "模拟宗门",
                fontSize = 14.sp,
                color = GameColors.TextTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, GameColors.Border)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    PrivacySummaryContent(
                        onPrivacyLinkClick = { showFullPolicy = true },
                        onTapTapSdkLinkClick = { openUrlInBrowser(context, TAPTAP_SDK_PRIVACY_URL) },
                        onMmkvLinkClick = { openUrlInBrowser(context, MMKV_URL) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = buildAnnotatedString {
                    append("点击\u201c同意\u201d即表示您已阅读并理解")
                    withStyle(style = SpanStyle(color = GameColors.SpiritBlue, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)) {
                        append("\u300a隐私政策\u300b")
                    }
                    append("\uff0c")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("同意我们按照上述内容处理您的个人信息")
                    }
                    append("\u3002如您不同意\uff0c请点击\u201c不同意\u201d退出应用\u3002")
                },
                fontSize = 12.sp,
                color = GameColors.TextSecondary,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clickable { showFullPolicy = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            GameButton(
                text = "同意",
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth(),
                height = 44.dp,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            GameButton(
                text = "不同意",
                onClick = onDisagree,
                modifier = Modifier.fillMaxWidth(),
                height = 44.dp,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "查看完整隐私政策",
                    fontSize = 13.sp,
                    color = GameColors.SpiritBlue,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { showFullPolicy = true }
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "在浏览器中打开",
                    fontSize = 13.sp,
                    color = GameColors.SpiritBlue,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openUrlInBrowser(context, PRIVACY_POLICY_URL) }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PrivacySummaryContent(
    onPrivacyLinkClick: () -> Unit = {},
    onTapTapSdkLinkClick: () -> Unit = {},
    onMmkvLinkClick: () -> Unit = {}
) {
    val sectionTitleStyle = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF333333))
    val bodyStyle = SpanStyle(fontSize = 13.sp, color = Color(0xFF444444))
    val linkStyle = SpanStyle(fontSize = 13.sp, color = GameColors.SpiritBlue, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)

    Text(
        text = buildAnnotatedString {
            withStyle(sectionTitleStyle) { append("重要提示") }
        },
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Text(
            text = "在您点击同意本隐私政策之前，本应用不会收集您的任何个人信息，不会申请任何可收集个人信息的权限，也不会初始化任何第三方SDK。",
            fontSize = 12.sp,
            color = Color(0xFF795548),
            modifier = Modifier.padding(10.dp),
            lineHeight = 18.sp
        )
    }

    Text(
        text = buildAnnotatedString {
            withStyle(sectionTitleStyle) { append("一、我们收集的信息") }
        },
        modifier = Modifier.padding(bottom = 6.dp, top = 4.dp)
    )

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("1. 账户信息") }
            append("\n")
            withStyle(bodyStyle) { append("当您选择TapTap登录时，收集：TapTap OpenID、UnionID、昵称、头像、电话号码（TapTap账户注册信息）、邮箱（TapTap账户注册信息）、认证令牌。电话号码和邮箱由TapTap平台在注册时收集，我们不会自行收集、存储或传输。用于账户识别、游戏存档关联、游戏内显示、账户安全和安全通信。") }
        },
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp),
        lineHeight = 19.sp
    )

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("2. 实名认证信息") }
            append("\n")
            withStyle(bodyStyle) { append("根据防沉迷规定，通过TapTap合规SDK进行实名认证。手动填写时需输入姓名、身份证号码和电话号码，用于完成国家要求的实名身份验证。电话号码属于个人敏感信息，由TapTap平台收集并提交至国家防沉迷系统验证，我们不会存储、查看或传输。建议优先使用快速认证（无需重新输入）。") }
        },
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp),
        lineHeight = 19.sp
    )

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("3. 设备标识符") }
            append("\n")
            withStyle(bodyStyle) { append("读取Android ID、设备硬件信息（品牌、型号等）、应用签名证书哈希。本应用仅用于本地加密密钥派生，以哈希形式参与加密运算，原始值不会传输到服务器。TapTap SDK也会独立收集Android ID和OAID（开放匿名设备标识符），用于设备系统兼容性、定位解决问题和广告效果分析。") }
        },
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp),
        lineHeight = 19.sp
    )

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("4. 网络状态信息") }
            append("\n")
            withStyle(bodyStyle) { append("检查网络连接状态，确保数据同步正常。不收集Wi-Fi SSID、BSSID或IP地址。") }
        },
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp),
        lineHeight = 19.sp
    )

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("5. 存储权限") }
            append("\n")
            withStyle(bodyStyle) { append("Android 9及以下版本请求外部存储读写权限，用于保存游戏存档。Android 10+使用分区存储，无需额外权限。") }
        },
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp),
        lineHeight = 19.sp
    )

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("6. 崩溃日志") }
            append("\n")
            withStyle(bodyStyle) { append("游戏异常退出时自动收集设备品牌、型号、产品名、设备代号、应用版本和异常堆栈，仅保存在设备本地，最多保留5份，不会自动上传。") }
        },
        modifier = Modifier.padding(bottom = 12.dp, start = 8.dp),
        lineHeight = 19.sp
    )

    Text(
        text = buildAnnotatedString {
            withStyle(sectionTitleStyle) { append("二、第三方SDK数据收集") }
        },
        modifier = Modifier.padding(bottom = 6.dp)
    )

    val tapTapAnnotatedString = buildAnnotatedString {
        withStyle(ParagraphStyle(lineHeight = 19.sp)) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("TapTap SDK\uff08v4.10.0\uff09") }
            append("\n")
            withStyle(bodyStyle) { append("仅在您同意本隐私政策后初始化。可能收集：设备信息（型号、系统版本、Android ID、OAID、CPU信息、内存信息）、网络信息、TapTap账户标识、电话号码（TapTap账户注册信息）、邮箱（TapTap账户注册信息）、实名认证数据。") }
            append("\n")
            pushStringAnnotation(tag = "URL", annotation = TAPTAP_SDK_PRIVACY_URL)
            withStyle(linkStyle) { append("TapTap SDK隐私政策 >") }
            pop()
        }
    }
    @Suppress("DEPRECATION") ClickableText(
        text = tapTapAnnotatedString,
        modifier = Modifier.padding(bottom = 4.dp, start = 8.dp),
        onClick = { offset: Int ->
            tapTapAnnotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { onTapTapSdkLinkClick() }
        }
    )

    val mmkvAnnotatedString = buildAnnotatedString {
        withStyle(ParagraphStyle(lineHeight = 19.sp)) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF333333))) { append("MMKV（腾讯）") }
            append("\n")
            withStyle(bodyStyle) { append("用于高性能本地键值存储，不收集或传输任何用户数据。") }
            append("\n")
            pushStringAnnotation(tag = "URL", annotation = MMKV_URL)
            withStyle(linkStyle) { append("MMKV开源地址 >") }
            pop()
        }
    }
    @Suppress("DEPRECATION") ClickableText(
        text = mmkvAnnotatedString,
        modifier = Modifier.padding(bottom = 12.dp, start = 8.dp),
        onClick = { offset: Int ->
            mmkvAnnotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { onMmkvLinkClick() }
        }
    )

    Text(
        text = buildAnnotatedString {
            withStyle(sectionTitleStyle) { append("三、信息使用目的") }
        },
        modifier = Modifier.padding(bottom = 6.dp)
    )

    listOf(
        "提供游戏核心功能：存档管理、TapTap登录、防沉迷合规",
        "保障本地数据安全：加密密钥派生、通信请求签名",
        "崩溃恢复和数据保护：异常退出后的游戏数据恢复",
        "安全防护：兑换码防刷、请求防伪造和防重放"
    ).forEach { item ->
        Text(
            text = "\u2022 $item",
            fontSize = 13.sp,
            color = Color(0xFF444444),
            modifier = Modifier.padding(bottom = 4.dp, start = 8.dp),
            lineHeight = 19.sp
        )
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = buildAnnotatedString {
            withStyle(sectionTitleStyle) { append("四、信息存储与保护") }
        },
        modifier = Modifier.padding(bottom = 6.dp)
    )

    Text(
        text = "\u2022 账户信息使用AES-256加密存储于设备本地\n\u2022 网络通信强制TLS 1.2/1.3加密传输\n\u2022 本应用读取的设备标识符仅以哈希形式参与加密运算，原始值不离开设备\n\u2022 网络请求中仅发送设备指纹的SHA-256摘要前8位\n\u2022 所有数据存储在中国境内，不存在跨境传输",
        fontSize = 13.sp,
        color = Color(0xFF444444),
        lineHeight = 19.sp,
        modifier = Modifier.padding(start = 8.dp)
    )
}

@Composable
fun FullPrivacyPolicyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.PageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameButton(
                    text = "返回",
                    onClick = onBack,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "完整隐私政策",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "在浏览器中打开",
                    fontSize = 12.sp,
                    color = GameColors.SpiritBlue,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openUrlInBrowser(context, PRIVACY_POLICY_URL) }
                )
            }

            HorizontalDivider(color = GameColors.Divider)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                Text(
                    text = "模拟宗门 隐私政策",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "更新日期：2026年4月17日 | 生效日期：2026年4月17日",
                    fontSize = 12.sp,
                    color = GameColors.TextTertiary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9F9)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "欢迎使用模拟宗门！【黑色毛衣工作室】（以下简称\u201c我们\u201d）系移动应用程序\u201c模拟宗门\u201d（以下简称\u201c本游戏\u201d）的运营者。我们深知个人信息对您的重要性，并将按照法律法规要求，采取相应安全保护措施来保护您的个人信息。在使用本应用前，请您仔细阅读并充分理解本隐私政策的全部内容。一旦您同意本隐私政策，即表示您已充分理解并同意我们按照本隐私政策处理您的相关信息。",
                        fontSize = 13.sp,
                        color = Color(0xFF555555),
                        modifier = Modifier.padding(12.dp),
                        lineHeight = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "重要提示：在您点击同意本隐私政策之前，本应用不会收集您的任何个人信息，不会申请任何可收集个人信息的权限，也不会初始化任何第三方SDK。只有在您明确同意本隐私政策后，本应用才会按照本隐私政策所述范围收集和使用您的个人信息。",
                        fontSize = 12.sp,
                        color = Color(0xFF795548),
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                FullPolicySection("〇、隐私同意机制") {
                    Text(
                        text = "本应用采用以下隐私同意机制，确保您的知情权和选择权：\n\n\u2022 首次启动弹窗：首次启动时弹出隐私政策同意窗口，您可以选择\"同意\"或\"不同意\"。\n\u2022 不同意即退出：选择\"不同意\"，本应用将立即退出，不会收集任何个人信息。\n\u2022 同意后初始化：只有在您明确同意后，本应用才会初始化第三方SDK（TapTap SDK）并开始收集个人信息。\n\u2022 应用内查看：您可以随时在应用内查看完整的隐私政策内容。\n\u2022 撤回同意：您可以随时退出 TapTap 登录来撤回对账户信息收集的同意。撤回同意不影响此前基于同意已进行的信息处理活动的效力。",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("一、我们收集的信息") {
                    FullPolicySubSection("1.1 账户信息") {
                        Text(
                            text = "当您选择 TapTap 登录时，我们通过 TapTap SDK 收集以下信息：",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "\u2022 用户标识：TapTap OpenID、UnionID \u2014 账户识别和游戏存档关联\n\u2022 个人资料：昵称、头像 \u2014 游戏内显示\n\u2022 联系方式：电话号码、邮箱（TapTap 账户注册信息）\u2014 TapTap 账户身份验证和账户安全\n\u2022 认证令牌：AccessToken（kid、tokenType、macKey、macAlgorithm）\u2014 安全通信和请求签名",
                            fontSize = 12.sp, color = Color(0xFF555555), lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "以上信息存储在设备本地，使用 AES-256 加密的 EncryptedSharedPreferences 保存。",
                            fontSize = 12.sp, color = Color(0xFF666666), lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "关于电话号码和邮箱：电话号码和邮箱由 TapTap 平台在您注册 TapTap 账户时收集，用于账户身份验证和安全保障。当您使用 TapTap 登录本游戏时，TapTap SDK 会读取上述信息以完成登录验证。我们不会自行收集、存储或传输您的电话号码和邮箱。如需管理您的电话号码和邮箱，请在 TapTap 平台操作。",
                                fontSize = 12.sp, color = Color(0xFF1565C0), lineHeight = 18.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    FullPolicySubSection("1.2 实名认证信息") {
                        Text(
                            text = "根据《关于防止未成年人沉迷网络游戏的通知》等国家防沉迷相关规定，我们通过 TapTap 合规 SDK 进行实名认证和年龄验证。\n\n\u2022 快速认证（推荐）：复用您在 TapTap 平台已完成的实名信息（姓名、身份证号），由 TapTap 平台处理。\n\u2022 手动填写认证：需输入姓名、身份证号码和电话号码。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "重要提示 - 关于电话号码收集：\n\n\u2022 收集目的：电话号码仅在您选择\"手动填写认证\"场景下收集，用于完成国家要求的实名身份验证，确保防沉迷政策有效执行。\n\u2022 收集方式：由 TapTap 合规 SDK 在实名认证界面中向您明示并征得同意后收集，我们不会自行设计或使用任何电话号码输入框。\n\u2022 处理方：电话号码由 TapTap 平台收集，并提交至国家新闻出版署网络游戏防沉迷实名认证系统进行验证。我们不会存储、查看或传输您的电话号码。\n\u2022 您的权利：您有权拒绝提供电话号码。如拒绝，将无法通过手动方式完成实名认证，可能导致无法正常使用游戏。建议优先使用\"快速认证\"方式，无需重新输入任何个人信息。\n\u2022 敏感信息性质：电话号码属于个人敏感信息。本条为针对电话号码的单独告知，请您仔细阅读并充分理解后再进行后续操作。",
                                fontSize = 12.sp, color = Color(0xFFC62828), lineHeight = 18.sp,
                                modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "实名认证数据由 TapTap 平台处理，我们不直接收集、存储或传输您的身份证号码等敏感身份信息。",
                                fontSize = 12.sp, color = Color(0xFF795548), lineHeight = 18.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    FullPolicySubSection("1.3 设备标识符") {
                        Text(
                            text = "我们读取以下设备信息，仅用于本地数据加密密钥派生和防止请求伪造：\n\n\u2022 Android ID（Settings.Secure.ANDROID_ID）\u2014 本地加密密钥派生\n\u2022 设备硬件信息（品牌、型号）\u2014 本地加密密钥派生和设备指纹\n\u2022 应用签名证书 SHA-256 哈希 \u2014 设备指纹增强\n\n上述设备标识符仅用于本地加密密钥派生，以哈希形式参与加密运算，原始值不会传输到我们的服务器。在网络请求中，仅发送设备指纹的前8位字符（SHA-256摘要前缀），用于防止请求伪造。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "关于 Android ID、OAID 与 TapTap SDK：除本应用自身读取 Android ID 用于本地加密密钥派生外，TapTap SDK（包括 tap-core、tap-login、tap-compliance 模块）也会独立收集 Android ID 和 OAID，用于确保设备系统兼容性、定位解决问题和广告效果分析。TapTap SDK 对 Android ID 和 OAID 的收集和处理受 TapTap SDK 隐私政策约束，详见下方第二节。",
                                fontSize = 12.sp, color = Color(0xFF1565C0), lineHeight = 18.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    FullPolicySubSection("1.4 网络状态信息") {
                        Text(
                            text = "我们检查网络连接状态，用于判断网络是否可用。我们不收集 Wi-Fi SSID、BSSID 或 IP 地址。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                    }

                    FullPolicySubSection("1.5 存储权限") {
                        Text(
                            text = "在 Android 9 及以下版本，我们请求外部存储读写权限，用于保存游戏存档数据到本地。在 Android 10 及以上版本，我们使用分区存储，无需请求额外权限。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                    }

                    FullPolicySubSection("1.6 崩溃日志") {
                        Text(
                            text = "当游戏异常退出时，我们自动收集设备品牌、型号、产品名、设备代号、Android版本、应用版本号和异常堆栈跟踪。崩溃日志仅保存在设备本地，最多保留5份，不会自动上传。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                    }

                    FullPolicySubSection("1.7 网络请求安全信息") {
                        Text(
                            text = "为保障通信安全，防止请求伪造和重放攻击，每个网络请求会携带以下安全头：应用版本号、平台标识、Android SDK级别、设备指纹前8位、HMAC-SHA256签名、时间戳、随机数、请求唯一标识和请求体哈希。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                    }

                    FullPolicySubSection("1.8 兑换码使用记录") {
                        Text(
                            text = "当您使用兑换码时，我们记录兑换码、使用时间、设备标识和玩家标识，用于防止兑换码重复使用和暴力枚举。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                    }
                }

                FullPolicySection("二、第三方 SDK 数据收集") {
                    FullPolicySubSection("2.1 TapTap SDK（v4.10.0）") {
                        Text(
                            text = "由易玩（上海）网络科技有限公司提供。仅在您同意本隐私政策后初始化。\n\n\u2022 tap-core：SDK核心功能 \u2014 可能收集设备信息（设备型号、操作系统版本、Android ID、OAID、CPU信息、内存信息）、网络信息（网络类型）\n\u2022 tap-login：账户登录 \u2014 收集TapTap账户标识、昵称、头像、电话号码（TapTap账户注册信息）、邮箱（TapTap账户注册信息）、Android ID、OAID、设备信息、网络信息\n\u2022 tap-common：公共组件 \u2014 收集设备基础信息\n\u2022 tap-compliance：防沉迷和实名认证 \u2014 收集实名认证数据、年龄信息、Android ID、OAID、设备信息、网络信息",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "TapTap SDK隐私政策",
                            fontSize = 13.sp,
                            color = GameColors.SpiritBlue,
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { openUrlInBrowser(context, TAPTAP_SDK_PRIVACY_URL) }
                        )
                    }

                    FullPolicySubSection("2.2 MMKV（腾讯）") {
                        Text(
                            text = "用于高性能本地键值存储，不收集或传输任何用户数据。",
                            fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "MMKV开源地址",
                            fontSize = 13.sp,
                            color = GameColors.SpiritBlue,
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { openUrlInBrowser(context, MMKV_URL) }
                        )
                    }
                }

                FullPolicySection("三、信息使用目的") {
                    Text(
                        text = "\u2022 提供游戏核心功能：存档管理、TapTap登录、防沉迷合规\n\u2022 保障本地数据安全：加密密钥派生、通信请求签名和完整性验证\n\u2022 崩溃恢复和数据保护：异常退出后的游戏数据恢复\n\u2022 安全防护：兑换码防刷、请求防伪造和防重放\n\n我们不会将您的信息用于上述目的以外的其他用途，也不会向任何第三方出售您的个人信息。",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("四、信息存储与保护") {
                    Text(
                        text = "\u2022 游戏存档和账户信息仅保存在您的设备本地\n\u2022 账户信息使用 AES-256 加密的 EncryptedSharedPreferences 存储\n\u2022 存档数据使用 AES-256-GCM 加密保护\n\u2022 网络通信强制使用 TLS 1.2/1.3 加密传输\n\u2022 启用证书固定防止中间人攻击\n\u2022 本应用读取的设备标识符仅以哈希形式参与加密运算，原始值不离开设备\n\u2022 网络请求中仅发送设备指纹的SHA-256摘要前8位\n\u2022 所有数据存储在中国境内，不存在跨境传输",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("五、信息共享") {
                    Text(
                        text = "我们不会与任何第三方共享您的个人信息，以下情况除外：\n\n\u2022 TapTap SDK：当您使用 TapTap 登录时，您的 TapTap 账户标识会与 TapTap 平台交互\n\u2022 法律要求：在法律法规要求或政府主管部门依法要求的情况下",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("六、数据保留期限") {
                    Text(
                        text = "\u2022 游戏存档数据：保留至您主动删除存档或卸载应用\n\u2022 账户登录信息：保留至您主动退出登录或卸载应用\n\u2022 崩溃日志：最多保留5份，超过后自动清理\n\u2022 兑换码使用记录：保留至游戏存档删除\n\u2022 加密密钥：保留至卸载应用",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("七、您的权利") {
                    Text(
                        text = "\u2022 查询和更正：您可以在游戏内查看您的登录信息\n\u2022 删除：您可以删除游戏存档、退出登录或卸载应用来清除数据\n\u2022 撤回同意：您可以随时退出 TapTap 登录来撤回对账户信息收集的同意\n\u2022 注销账户：如需注销 TapTap 账户，请在 TapTap 平台操作",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("八、未成年人保护") {
                    Text(
                        text = "我们严格遵守《关于防止未成年人沉迷网络游戏的通知》等相关规定，通过 TapTap 合规 SDK 对未成年人实施游戏时段和时长限制。未成年人仅可在周五、周六、周日及法定节假日的 20:00-21:00 进行游戏。我们不会主动收集未成年人的身份信息。",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("九、隐私政策更新") {
                    Text(
                        text = "我们可能会不时更新本隐私政策。更新后的政策将在应用内重新展示，您需要再次同意后方可继续使用。如果您不同意更新后的隐私政策，可以选择停止使用本应用。",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                FullPolicySection("十、联系我们") {
                    Text(
                        text = "如您对本隐私政策有任何疑问、意见或建议，可通过以下方式与我们联系：\n\n\u2022 邮箱：cp050923@126.com\n\u2022 通过 TapTap 平台的应用页面反馈\n\n我们将在15个工作日内回复您的请求。",
                        fontSize = 13.sp, color = Color(0xFF444444), lineHeight = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun FullPolicySection(
    title: String,
    content: @Composable () -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = GameColors.Divider)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1A1A1A)
    )
    Spacer(modifier = Modifier.height(8.dp))
    content()
}

@Composable
private fun FullPolicySubSection(
    title: String,
    content: @Composable () -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF333333)
    )
    Spacer(modifier = Modifier.height(4.dp))
    content()
}
