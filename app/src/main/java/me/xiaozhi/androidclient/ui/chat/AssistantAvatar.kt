package me.xiaozhi.androidclient.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * 角色头像：有图显示图，没图显示名字最后一个字。
 *
 * 原先私有在 `MainActivity` 里，顶栏精简时挪到这里——[ChatHeaderRow] 要 28dp 的小头像、
 * 消息气泡要 36dp 的，两处都得用它，留在 `MainActivity` 就得把可见性放开、
 * 让 `ui/chat` 反向依赖那个三千行的大文件。
 *
 * 尺寸由调用方通过 `modifier` 决定：**这个组件自己不给默认尺寸**，
 * 因为它在两个地方的差异（28 / 36dp）正好是这个组件唯一会变的东西。
 */
@Composable
internal fun AssistantAvatar(
    avatarPath: String,
    fallbackText: String = "智",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val containerModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Surface(
        modifier = containerModifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
    ) {
        if (avatarPath.isNotBlank() && File(avatarPath).exists()) {
            AsyncImage(
                model = File(avatarPath),
                contentDescription = "小智头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = fallbackText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
