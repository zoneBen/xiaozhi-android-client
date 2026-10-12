package me.xiaozhi.androidclient.ui.settings

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.xiaozhi.androidclient.R
import me.xiaozhi.androidclient.model.UiState

/**
 * 「关于」卡片：作者 / 微信 / 交流群 / 开源许可。
 *
 * 署名是**双署名**，不是客套：本项目是 MIT 的衍生物，MIT 的条件是
 * 「The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software」。只写本定制版作者、抹掉上游，
 * 等于直接违反许可证。所以上游项目、上游版权、本定制版修改三者都要在。
 */
private const val AUTHOR_NAME = "小葱（zoneben）"
private const val UPSTREAM_PROJECT = "xiaozhi-android-client"
private const val UPSTREAM_URL = "https://github.com/fenghui12/xiaozhi-android-client"
private const val UPSTREAM_COPYRIGHT = "Copyright (c) 2026 luo785859020"
private const val FORK_COPYRIGHT = "Copyright (c) 2026 小葱 (zoneben) — modifications"

/** 交流 QQ 群号。原来是需求里点名要放的那个群。 */
private const val QQ_GROUP = "973359785"

private const val LICENSE_SUMMARY =
    "本软件以 MIT 许可证开源：可自由使用、修改、分发和商用，" +
        "前提是保留版权声明与许可证全文。软件按「原样」提供，作者不对任何使用后果承担责任。"

/**
 * 完整 MIT 正文。刻意写成 Kotlin 常量而不是塞进 `strings.xml`——
 * 那个文件现在只有 2 条，是给 AndroidManifest 和头屏读屏用的；
 * 本项目的惯例是界面文案内联在 Compose 里。
 *
 * 正文本身会随副本一起分发（这是 MIT 的要求），所以**不要**改成指向仓库文件的链接。
 */
private val MIT_LICENSE_TEXT = """
    MIT License

    $UPSTREAM_COPYRIGHT
    $FORK_COPYRIGHT

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
""".trimIndent()

/**
 * 「关于与更新」页：版本与更新在最上，人与许可在下面。
 *
 * 合并成**一屏**而不是两屏：它们都是「关于这个 App 本身」的信息，用户想找作者的时候
 * 往往正顺手想知道自己装的是哪一版。分成两屏反而要多点一次。
 */
@Composable
internal fun AboutScreen(
    state: UiState,
    padding: PaddingValues,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
) {
    SettingsPageScaffold(padding) {
        AppUpdateCard(
            state = state,
            onCheckForUpdate = onCheckForUpdate,
            onStartUpdate = onStartUpdate,
        )
        AboutCard()
    }
}

/**
 * 版本与「检查更新」。
 *
 * 从 [me.xiaozhi.androidclient.MainActivity] 原样搬来，逻辑一行没动。
 * 版本号读的是 `BuildConfig` 透出来的 [UiState.appVersionName] / [UiState.appVersionCode]，
 * **不是字面量**——XiaozhiModels.kt 里记着一次漏改版本号的事故，不要重蹈。
 */
@Composable
private fun AppUpdateCard(
    state: UiState,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "系统与软件更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "当前版本：v${state.appVersionName} (Build ${state.appVersionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onCheckForUpdate,
                    enabled = !state.isCheckingUpdate && !state.isDownloadingUpdate,
                ) {
                    Text(if (state.isCheckingUpdate) "检查中..." else "检查更新")
                }
            }

            if (state.updateCheckStatus.isNotBlank()) {
                Text(
                    text = state.updateCheckStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.availableUpdate != null) {
                Surface(
                    color = Color(0xFFF0FDF4),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "🎉 发现新版本 v${state.availableUpdate.versionName}",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF166534),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.availableUpdate.releaseNotes.isNotBlank()) {
                            Text(
                                text = state.availableUpdate.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF15803D),
                            )
                        }

                        if (state.isDownloadingUpdate) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { state.downloadProgressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "下载更新中 ${state.downloadProgressPercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF166534),
                                )
                            }
                        } else {
                            Button(
                                onClick = onStartUpdate,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("立即下载并安装更新")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    var showWechat by remember { mutableStateOf(false) }
    var showFullLicense by remember { mutableStateOf(false) }

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            AboutInfoRow(label = "作者", value = AUTHOR_NAME)
            AboutInfoRow(
                label = "微信",
                value = "扫码添加 ›",
                onClick = { showWechat = true },
            )
            AboutInfoRow(
                label = "交流 QQ 群",
                value = "$QQ_GROUP · 点击复制",
                onClick = { copyQqGroup(context) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "开源许可",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = LICENSE_SUMMARY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "本定制版作者：$AUTHOR_NAME",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "原项目：$UPSTREAM_PROJECT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = UPSTREAM_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = UPSTREAM_COPYRIGHT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = FORK_COPYRIGHT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(onClick = { showFullLicense = !showFullLicense }) {
                    Text(if (showFullLicense) "收起完整许可证" else "查看完整许可证")
                }

                if (showFullLicense) {
                    Text(
                        text = MIT_LICENSE_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }

    if (showWechat) {
        WechatQrDialog(onDismiss = { showWechat = false })
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    Row(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 微信二维码大图。
 *
 * **三条不能改的约束**（改任意一条都会让二维码扫不出来）：
 *
 *  1. 图放在 `res/drawable-nodpi/`，且解码时 `inScaled = false`。放任何 density 桶都会被
 *     系统按屏幕密度重采样——668px 在低密度面板上会被缩到 200 多 px，插值一糊就识别不了。
 *  2. 渲染时 `filterQuality = FilterQuality.None`。默认的双线性插值会把黑白边缘抹成灰。
 *  3. **不要走 `util/ImageSampling` 的降采样**。那是为「大图别拖垮整机」写的，用途正好相反：
 *     全尺寸 668×665 解码约 1.7MB，只在一个 `remember` 缓存的对话框里，代价可以接受，
 *     而降采样对二维码是有害的。
 */
@Composable
private fun WechatQrDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember {
        runCatching {
            BitmapFactory.decodeResource(
                context.resources,
                R.drawable.wechat_qrcode,
                BitmapFactory.Options().apply { inScaled = false },
            )?.asImageBitmap()
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加作者微信") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "$AUTHOR_NAME 的微信二维码",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    )
                } else {
                    Text(
                        text = "二维码图片加载失败。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "$AUTHOR_NAME · 使用微信扫描上方二维码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun copyQqGroup(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("QQ 群", QQ_GROUP))
    // Android 13 起系统自己会弹「已复制」的提示，我们再弹一次就是双重提示。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制 QQ 群号 $QQ_GROUP", Toast.LENGTH_SHORT).show()
    }
}
