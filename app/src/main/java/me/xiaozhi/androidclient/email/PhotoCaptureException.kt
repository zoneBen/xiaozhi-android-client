package me.xiaozhi.androidclient.email

/**
 * 「照片没拍成」。
 *
 * 存在的唯一理由是把「拍照失败」和「发信失败」分开——这两件事在中文字面上都是
 * 「失败了」，但对用户的意义完全不同：一个是摄像头没打开/被别的应用占着，
 * 一个是账号授权码错了。混在一起记进执行记录，用户照着「发信失败」去翻 SMTP 设置，
 * 永远修不好。
 *
 * 所以它是一个**独立的类型**而不是靠消息文本去猜。
 */
class PhotoCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)
