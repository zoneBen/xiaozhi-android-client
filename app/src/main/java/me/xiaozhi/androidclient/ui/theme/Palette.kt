package me.xiaozhi.androidclient.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 全项目共享的色值，**只在这里定义一次**。
 *
 * 建这个文件的直接原因：设置页要分页，几个原本私有在 `MainActivity` 里的色
 * （品牌蓝、选中态浅底、页面底色）跟着搬到了 `ui/settings`，而聊天页顶栏那侧也要用。
 * 同一个品牌蓝在三处各写一遍 `0xFF3E7BFA`，谁改了一处另外两处不会跟着变，
 * 而"两个地方的蓝不一样"肉眼几乎看不出来，只会觉得界面某处不对劲。
 *
 * 主题（[XiaozhiClientTheme]）里的那份色板也搬到了这里——它本来就是同一批颜色，
 * 之前只是各写各的。
 */

// —— 品牌色 ——

internal val Brand = Color(0xFF3E7BFA)
internal val BrandSoft = Color(0xFFDCE8FF)
internal val BrandDeep = Color(0xFF1E4FB8)
internal val Teal = Color(0xFF3FA2B8)
internal val Mint = Color(0xFF3F8F7A)

// —— 中性色 ——

/** 聊天页与设置页共用的页面底色。 */
internal val Paper = Color(0xFFF1F5FA)
internal val Stone = Color(0xFFE3EAF3)
internal val Ink = Color(0xFF16202E)
internal val Night = Color(0xFF10161F)

// —— 语义色 ——

/** 「打断」按钮。比 Material 默认的 error 更饱和——它要在"正在播报"的瞬间被一眼找到。 */
internal val DangerRed = Color(0xFFE53935)

/** 「麦克风已就绪 ✓」与「待机中」。 */
internal val ReadyGreen = Color(0xFF16A34A)

/** 「讲话中」。 */
internal val PendingAmber = Color(0xFFF59E0B)

/** 「连接中 / 未连接 / 待激活」这一族：都是"等着就行"，所以不给颜色。 */
internal val MutedGray = Color(0xFF9CA3AF)

/** 聊天页顶栏那条浅蓝底。 */
internal val ChatHeaderTint = Color(0xFFE8F0FC)
