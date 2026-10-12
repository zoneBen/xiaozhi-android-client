package me.xiaozhi.androidclient.ota

import java.net.URI

/**
 * 更新索引地址「是不是一个真的配了的地方」。
 *
 * 本定制版**没有做在线更新功能**，[AppUpdateManager.DEFAULT_UPDATE_INDEX_URL] 是一个
 * 刻意留的不可达占位。没有这层判断时会发生三件都不好的事：
 *
 * 1. 每次检查要连着打**三个**永远失败的请求（两个 gh-proxy 镜像 + 原始地址），
 *    白白等一轮超时；
 * 2. 启动时的静默检查（`MainViewModel.init`）也会照打一遍，冷启动白白多几个网络往返；
 * 3. 用户点「检查更新」看到的是一句英文的
 *    `Unable to resolve host "update.example.invalid": No address associated with hostname`
 *    ——那不是「这个版本没这个功能」，而是「你的网络坏了」，会把人引到错误的方向去排查。
 *
 * 判定只看 `.invalid` 这个顶级域：RFC 2606 / RFC 6761 把它保留为**保证永不解析**，
 * 所以它不是「当前这个占位字符串」的硬编码比对，而是一条有含义的规则——将来真要接入
 * 更新服务，换个真实域名进来就自动生效，不需要回来改这里。
 *
 * 与 [me.xiaozhi.androidclient.network.canReuseLiveConnection] 同类：纯函数、无 Android 依赖，
 * 就是为了能直接单测。
 */
internal fun isUpdateIndexConfigured(rawUrl: String?): Boolean {
    val url = rawUrl?.trim().orEmpty()
    if (url.isEmpty()) return false
    // 解析不出 host（空串、相对路径、「不是个 URL」）都算没配。
    val host = runCatching { URI(url).host }.getOrNull()?.trim().orEmpty()
    if (host.isEmpty()) return false
    val normalized = host.lowercase()
    // 两种写法都算占位：`update.example.invalid`（子域）与 `invalid`（顶级域本身）。
    // RFC 6761 保留的是 `invalid.` 这个名字，子域判定要连点上——所以给裸顶级域单独判一次。
    return normalized != "invalid" && !normalized.endsWith(".invalid")
}
