package me.xiaozhi.androidclient.camera

import java.io.File

/**
 * 每次拍照落一张**独立**文件，供对话里回看。
 *
 * ## 为什么不复用调试用的 `last_capture.jpg`
 *
 * 那个文件每拍一次就被覆盖，只留最新一帧——它服务的是"开发期核对画面方向"。
 * 而对话里的照片是要**核对历史**的：覆盖之后，前面几条气泡会全部变成同一张图，
 * 而且看起来"很对"，不会报错。
 *
 * ## 为什么要裁剪
 *
 * 不裁的话这台设备会一直攒照片（一张 ≈140KB，1280x720）。对话本身只留最近 100 条
 * （见 `MainViewModel.addChatMessage`），所以留 [MAX_KEPT_PHOTOS] 张足够回看，
 * 又不会无限占存储。
 */
object PhotoStore {

    /** 最多留多少张。比对话的 100 条少一些：只为回看，不必跟对话等长。 */
    const val MAX_KEPT_PHOTOS = 40

    /** 文件名带上时间戳：既保证唯一，也让人一眼看出先后。 */
    fun photoFileName(timestampMillis: Long): String = "photo_$timestampMillis.jpg"

    /**
     * 只留最新的 [keep] 张，其余删掉，返回实际删掉几张。
     *
     * 按 `lastModified` 排序而不是解析文件名里的时间戳：外部往这个目录里丢过别的
     * 文件时解析会失败，而 `lastModified` 对任何文件都成立。
     */
    fun pruneOldPhotos(dir: File, keep: Int = MAX_KEPT_PHOTOS): Int {
        if (keep < 0) return 0
        val photos = dir.listFiles { file -> file.isFile && file.name.endsWith(".jpg") }
            ?: return 0
        return photos.sortedByDescending { it.lastModified() }
            .drop(keep)
            .count { it.delete() }
    }
}
