package me.xiaozhi.androidclient.backend

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 接口签名。服务端口径见 `php/common.php` 的 `zx_sign()`：
 *
 *     X-Sign = HMAC-SHA256(
 *         "$方法\n$路径\n$appKey\n$时间戳\n" + SHA256($请求体),
 *         $appSecret
 *     )
 *
 * 结果一律小写十六进制。
 *
 * 刻意做成无 Android 依赖的纯函数：客户端和服务端算出来的值必须逐字节一致，
 * 而这条链路只能在单测里钉死 —— 真出问题时客户端拿到的是一个 401，
 * 它既不告诉你是哪一段不一样，也没法在设备上调试。
 */
internal object BackendSigner {

    /**
     * [rawBody] 收的是**即将发出去的字节**，不是字符串：服务端算的是
     * `hash('sha256', $rawBody)`，也就是它真正收到的那串字节。客户端多一次
     * String → ByteArray 的转换就多一处可能对不上的地方（编码、BOM、换行）。
     * 让同一份字节既当请求体又当签名输入，这条缝就没了。
     */
    fun sign(
        method: String,
        path: String,
        appKey: String,
        timestampSeconds: Long,
        rawBody: ByteArray,
        appSecret: String,
    ): String {
        val canonical = listOf(
            method.uppercase(Locale.US),
            path,
            appKey,
            timestampSeconds.toString(),
            sha256Hex(rawBody),
        ).joinToString(separator = "\n")

        return hmacSha256Hex(appSecret, canonical)
    }

    fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex()

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * 十六进制编码。带 Locale.US 是**防守性**的：`%02x` 目前（实测 JDK 17 / Android
     * 的 Formatter）不做本地化，阿拉伯语、波斯语、天城文、泰文数字等区域设置下输出
     * 都一样，所以今天去掉 Locale 也仍然算得对。但签名要和 PHP 逐字节比对，
     * 而 `%d` 一类转换**确实**会本地化（实测会吐出阿拉伯-印度数字）——
     * 也就是说这里离「静默全线 401」只差一次转换符的改动。钉死 Locale 的代价是零，
     * 项目里其它地方（AppPreferences.pseudoMacFromSeed、DeviceIdentityStore.sha256Hex）
     * 出于同样的防守理由也钉着。
     */
    private fun ByteArray.toHex(): String = buildString(size * 2) {
        // 必须写 `for (byte in this@toHex)`，不能写裸的 `forEach {}`：`buildString` 的
        // 接收者是 StringBuilder，也就是 CharSequence，于是 `forEach` 会解析到
        // `CharSequence.forEach`（走那个空串）、`it` 变成 Char —— 结果是一个空字符串，
        // 而且只报一条 deprecation 警告，签名全线 401 却看不出是哪一层的错。
        for (byte in this@toHex) {
            append("%02x".format(Locale.US, byte.toInt() and 0xFF))
        }
    }
}
