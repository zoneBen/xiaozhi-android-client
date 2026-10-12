package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.xiaozhi.androidclient.R
import me.xiaozhi.androidclient.ui.theme.Brand
import me.xiaozhi.androidclient.ui.theme.BrandSoft
import me.xiaozhi.androidclient.ui.theme.DangerRed
import me.xiaozhi.androidclient.ui.theme.Paper

/**
 * 设置页共用的小组件。
 *
 * 原来它们以 `private` 住在 [me.xiaozhi.androidclient.MainActivity] 里，设置分页之后
 * 各个子页都要用，就集中到了这里。**只放"长相"，不放"知道什么"**——
 * 每个组件只接收已经算好的值，不读 `UiState`、不做判断，这样它们能同时服务
 * 角色页、设备页、邮件页和定时任务页。
 */

/**
 * 本机没有对应硬件时，设置页那一行降到的透明度。
 *
 * 0.45 是"明显比别处淡、但还读得清副标题里那句原因"的位置——**原因必须还读得清**，
 * 否则用户看到的只是一个点不动的开关，猜不出为什么。
 */
private const val DISABLED_ROW_ALPHA = 0.45f

/** 设置页每一块的白色圆角底。 */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 一屏设置的外壳：底色、系统栏留白、上下滚动、卡片间距。
 *
 * 每一屏都用它，是为了让「返回时页面不跳」这件事自动成立——只要所有页的外边距
 * 都由同一个函数给出，就不可能出现在某一页上多了 18dp 的情况。
 */
@Composable
internal fun SettingsPageScaffold(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/**
 * 设置首页的一行：图标 + 名称 + 当前值摘要 + `›`。
 *
 * [summary] 不是装饰：设置首页把每一项现在的状态摊开，用户才不必为了确认
 * 「拍照到底关没关」而挨个点进去看。留空则不占位。
 */
@Composable
internal fun SettingsNavRow(
    icon: String,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 只读的一行：图标 + 标题 + 说明。用于展示结论（比如硬件自检结果）。 */
@Composable
internal fun SettingsStatus(
    icon: String,
    title: String,
    subtitle: String,
    /**
     * 副标题的颜色。默认是次要文字色；**失败结果传 [DangerRed]**。
     *
     * 用 `Color?` 而不是让默认值直接引用 `MaterialTheme`：默认参数在调用点求值，
     * 传 null 时再取主题色，语义更直白，也不会把一个 Composable 调用藏进签名里。
     */
    subtitleColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一个带标题的输入框（邮件设置那一屏要用七个）。
 *
 * 标题放在框**外面**而不是用 `OutlinedTextField` 自带的 `label`：label 在焦点态会浮到
 * 边框上，一屏七个框同时有一两个浮着，读起来比"标题始终在框上方"乱得多。
 * 副标题（[helper]）用于写"这个字段填什么才不算白填"这类只在框外说得清的话。
 */
@Composable
internal fun SettingsTextField(
    icon: String,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    helper: String = "",
    /** 密码/授权码：显示成圆点。**只是显示**——它照样明文存在本机（见 MailSettingsStore）。 */
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = if (placeholder.isBlank()) null else {
                    { Text(placeholder, style = MaterialTheme.typography.bodySmall) }
                },
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (helper.isNotBlank()) {
                Text(
                    text = helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一行「说明 + 右边一个按钮」，用于触发一次性的动作（比如重新自检）。 */
@Composable
internal fun SettingsAction(
    icon: String,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    /**
     * false 表示**现在没有可做的事**（比如历史已经是 0 条了，没什么可清空的）。
     * 按钮变灰而不是整行消失：行消失了用户会以为功能没做，灰着则能看出"是这个条件不满足"。
     */
    enabled: Boolean = true,
    /** 破坏性动作（清空历史）用它：红色文字按钮，和普通动作区分开。 */
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (danger) {
            TextButton(onClick = onClick, enabled = enabled) {
                Text(
                    text = actionLabel,
                    color = if (enabled) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(actionLabel) }
        }
    }
}

/**
 * 一行开关。
 *
 * [enabled] 传 false 表示**本机没这个硬件**：整行变淡、开关也点不动。
 * 副标题要写明为什么，否则用户只会看到一个按不动的开关。
 */
@Composable
internal fun SettingsSwitch(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 置灰靠整行降透明度 + 把 enabled 透传给 Switch：
            // Switch(enabled = false) 自带正确的不可点语义和无障碍信息，
            // 只把它画淡而不传 enabled 的话，读屏软件仍然会念成"可切换"。
            .alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 一行多选一（目前用于"用前置还是后置摄像头"）。
 *
 * 项目里没有 FilterChip / SegmentedButton / RadioButton 可用，而 `RoleProfileRow` 那套
 * "整行可点 + 「当前」标记"是给长列表用的。几个选项摆成一对胶囊更直白：
 * 选中那个用品牌浅蓝底 + 品牌蓝字，未选中那个留白。
 *
 * 只在**真的有得选**的时候才显示（调用方在选项少于两个时直接不渲染这一行）——
 * 一个只有一个选项的选择器是在浪费用户的时间。
 *
 * 几个胶囊用 [FlowRow] 排、**不是普通 `Row`**：普通 Row 会把剩下的宽度发给最后一个
 * 孩子，宽度不够时那个胶囊的文字就自己折行——真机上「Gmail」被挤成了竖排的
 * 「G / m / ail」，「不加密」也折成两行。FlowRow 是整颗胶囊换行，读起来才正常。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsChoice(
    icon: String,
    title: String,
    subtitle: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    SettingsChoiceRow(icon, title, subtitle, options, onChipClick = onSelect)
}

/**
 * 一行多选（目前用于"每周哪几天"）。
 *
 * 和 [SettingsChoice] 长得一模一样、只差一个字的语义——**这不是重复，是分工**：
 * 单选点第二下没反应，多选点第二下要取消。共用一个函数名的话，调用方就得从
 * 上下文猜自己是哪种，而猜错的表现是"点一下把别的选了/取消不掉"，很难一眼看出来。
 * 所以两个名字、一份长相（[SettingsChoiceRow]）。
 */
@Composable
internal fun SettingsMultiChoice(
    icon: String,
    title: String,
    subtitle: String,
    options: List<Pair<String, Boolean>>,
    onToggle: (Int) -> Unit,
) {
    SettingsChoiceRow(icon, title, subtitle, options, onChipClick = onToggle)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceRow(
    icon: String,
    title: String,
    subtitle: String,
    options: List<Pair<String, Boolean>>,
    onChipClick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEachIndexed { index, (label, selected) ->
                    ChoiceChip(label = label, selected = selected) { onChipClick(index) }
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** [SettingsChoice] 里的单个胶囊。 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) BrandSoft else Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (selected) Brand else Paper),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Brand else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * 一行下拉选择。选中的那一项显示在右边，点整行弹出菜单。
 *
 * 和 [SettingsChoice] 的分工：胶囊适合"两三个选项、每个都该一眼看见"；
 * 选项多起来之后一排胶囊会挤着换行（真机上四个就把这一行撑成两排），
 * 而这类选择的选项还会随着适配的增长继续加。下拉把"当前值"和"全部选项"
 * 分开——平时只占一行，要看别的才展开。
 *
 * 下拉箭头用 [R.drawable.ic_chevron_right] 旋转 90° 得到，**不引入 down 图标的资源**：
 * 两个形状完全一样，多一个文件只会让"箭头到底有几个"变成需要查的事。
 */
@Composable
internal fun SettingsDropdown(
    icon: String,
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = icon)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = options.getOrNull(selectedIndex).orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = Brand,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(90f),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Brand else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

/** 一枚只读的小胶囊，用于「当前」「Session 1a2b3c」这类标记。 */
@Composable
internal fun StatusTag(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.9f),
        shape = CircleShape,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
