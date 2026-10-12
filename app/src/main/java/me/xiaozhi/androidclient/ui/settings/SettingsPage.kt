package me.xiaozhi.androidclient.ui.settings

/**
 * 设置里有哪几屏。
 *
 * 用 `enum` 而不是带参数的 sealed class：`rememberSaveable` 能直接存取 enum，
 * 换成 sealed class 就得额外写一个 `Saver`，而这里每一屏都不需要携带数据。
 *
 * [title] 是顶栏显示的名字。**首页与子页共用同一个顶栏**，标题跟着这个值走，
 * 所以子页自己不要再画一个标题——那会变成一屏两个标题。
 */
enum class SettingsPage(val title: String) {
    HOME("设置"),
    ROLE("角色设置"),
    DEVICE("设备控制"),
    CONVERSATION("对话与历史"),
    EMAIL("邮件设置"),
    ABOUT("关于与更新"),
}
