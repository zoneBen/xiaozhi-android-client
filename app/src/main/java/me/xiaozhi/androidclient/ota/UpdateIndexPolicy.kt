package me.xiaozhi.androidclient.ota

import java.net.URI

/**
 * 更新索引地址「是不是一个真的配了的地方」。
 *
 * 更新源已经接上自建后端（[AppUpdateManager.DEFAULT_UPDATE_INDEX_URL]），但
 * [AppUpdateManager.PLACEHOLDER_UPDATE_INDEX_URL] 这个不可达占位仍然要留着 ——
 * 客户端没配 `appSecret` 时 `MainViewModel` 会用它，因为往自建接口发一个不带签名的
 * 请求只会拿到 401。没有这层判断时会发生三件都不好的事：
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

/**
 * 更新索引地址的候选列表，按尝试顺序返回。
 *
 * ## 为什么不能无条件套镜像前缀
 *
 * 镜像（ghfast.top / gh-proxy.com）的存在意义只有一个：`raw.githubusercontent.com`
 * 这类 GitHub 域名在国内部分网络下不是快速失败、而是直接挂住，所以先试着从国内
 * 可达的代理绕过去。
 *
 * 但原来的写法是把前缀无条件拼在**任何**配置地址前面。接入自建后端之后，
 * 配置地址变成了 `http://api.mrcong.cn:300/apps/xiaozhi_android_client/v1/upgrade.php`，
 * 拼出来就是 `https://ghfast.top/http://api.mrcong.cn:300/...` —— 一个必然失败的
 * 地址。而 [AppUpdateManager.checkForUpdate] 是**顺序尝试、失败才轮到下一个**的，
 * 于是每次检查更新（包括每次冷启动的静默检查）都要先白等两轮
 * `configHttpClient` 的超时（8 秒连接 + 12 秒读），才轮到那个本该第一个打的真实地址。
 *
 * 判定放在这里、而不是在 AppUpdateManager 里内联一个 `if`：纯函数、无 Android 依赖，
 * 可以直接单测。和 [isUpdateIndexConfigured] 是同一个理由。
 */
internal fun buildIndexCandidates(rawUrl: String): List<String> {
    val url = rawUrl.trim()
    if (url.isEmpty()) return emptyList()

    // 只有 GitHub 系域名才值得走镜像。判定用 host 而不是 `contains("github.com")`：
    // 后者会把 `https://example.com/github.com/version.json` 这种路径误判成 GitHub。
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase().orEmpty()
    val isGitHub = host == "github.com" ||
        host == "raw.githubusercontent.com" ||
        host == "objects.githubusercontent.com" ||
        host == "githubusercontent.com" ||
        host.endsWith(".githubusercontent.com") ||
        host.endsWith(".github.com")

    if (!isGitHub) return listOf(url)

    // 顺序很重要：**镜像优先，GitHub 原链放最后**。
    // raw.githubusercontent.com 在部分网络下不是快速失败而是直接挂住，
    // 把它排在第一个，用户就要先白等一个超时才轮到能用的镜像。
    return listOf(
        "https://ghfast.top/$url",
        "https://gh-proxy.com/$url",
        url,
    )
}
