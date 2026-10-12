package me.xiaozhi.androidclient.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.xiaozhi.androidclient.model.UiState

/** = MainActivity 的 `HeaderTint`（见那边的别名，两处是同一个值）。 */
internal val ChatHeaderTint = Color(0xFFE8F0FC)

/**
 * 聊天页顶栏：**单行**。
 *
 * ## 为什么从卡片缩成一行
 *
 * 原来这里是 `ChatHeaderCard`——一张约 100dp 高的卡片，里面装着 56dp 的头像、
 * 醒目的「语音 AI 交互」标题，以及一句没有颜色、不随状态变化的文字。三个问题：
 *
 *  1. 「语音 AI 交互」这句话的信息量是零。这是语音助手，用户点开就知道；
 *     而角色名（「小智」）本身就说明了在跟谁说话。整句删掉损失为零；
 *  2. 状态只有文字、没有颜色，而这个界面**唯一**会变的就是状态。
 *     隔着一臂距离看，「讲话中」和「未连接」长得一模一样；
 *  3. 这 100dp 是直接从消息列表里扣的。这台设备的显示屏是 1272×2800，
 *     看起来高，但加上输入框和「当前任务」面板之后，能看的消息并没几条。
 *
 * 缩到 48dp 之后，省下的空间全部还给消息列表。设置入口**位置不动**——它本来就在右上角。
 */
@Composable
internal fun ChatHeaderRow(
    state: UiState,
    onOpenSettings: () -> Unit,
) {
    val status = chatStatusOf(state)

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChatHeaderTint)
                // 48dp 正好是 IconButton 的最小可点尺寸，再高就白占地方、再矮就点不准。
                .height(48.dp)
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AssistantAvatar(
                avatarPath = state.activeRoleAvatarPath,
                fallbackText = state.activeRoleName.takeLast(1).ifBlank { "智" },
                modifier = Modifier.size(28.dp),
            )

            Text(
                text = state.activeRoleName.ifBlank { "小智" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                // 角色名是用户自己起的，可能很长（"小智助手二号机"）。不截断的话
                // 它会把状态灯挤出去——而状态灯比名字重要得多。
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            StatusLamp(status = status)

            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "打开设置")
            }
        }
    }
}

/** 一个圆点 + 一句状态文字。数字人画面上用的也是它，两处的状态因此必然一致。 */
@Composable
internal fun StatusLamp(
    status: ChatStatus,
    /** 画在数字人视频上时点深色底：底色不透明、文字转白，否则读不出来。 */
    onDarkBackdrop: Boolean = false,
) {
    // 圆点**始终**用状态色。它在深底上一样醒目，而且是这两处唯一承载"语义"的东西——
    // 把点也刷成白色，数字人页就退回到"只有一句话、分不出轻重"的老样子了。
    val dotColor = chatStatusColor(status)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .alpha(breathingAlpha(status))
                .background(dotColor, CircleShape),
        )
        Text(
            text = chatStatusLabel(status),
            style = MaterialTheme.typography.labelLarge,
            color = if (onDarkBackdrop) Color.White else dotColor,
            maxLines = 1,
        )
    }
}

/**
 * 正在说话 / 正在听时才呼吸，其余状态常亮。
 *
 * **只在这两个状态上建无限动画，不能图省事放在外面一直跑。** 这台设备（TV5701）
 * 是低端 32 位面板，一个每帧都要求重组的无限动画会一直占着 choreographer；
 * 而且一个常亮的呼吸灯很快就变成背景噪音，反而没人看它了。
 *
 * 只动 `alpha`——不动尺寸、不动物理位置，所以不会引起重新布局。
 */
@Composable
private fun breathingAlpha(status: ChatStatus): Float {
    val shouldBreath = status == ChatStatus.SPEAKING || status == ChatStatus.LISTENING
    if (!shouldBreath) return 1f

    val transition = rememberInfiniteTransition(label = "statusLampBreath")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusLampAlpha",
    ).value
}
