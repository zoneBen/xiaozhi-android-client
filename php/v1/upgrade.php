<?php

declare(strict_types=1);

/**
 * 接口③ 升级
 *
 * GET /v1/upgrade.php?deviceUid=…&versionCode=15&channel=default
 * 请求头：X-App-Key / X-Timestamp / X-Sign（见 common.php 顶部说明）
 *
 * ## 为什么响应是平铺的、而且没有 data 字段
 *
 * 客户端 AppUpdateManager.checkForUpdate() 读的是 `version.json` 那种平铺结构：
 * 顶层直接取 versionCode / versionName / downloadUrl / sha256 / releaseNotes /
 * forceUpdate。这里保持同样的形状，客户端那几行解析就一行都不用改 ——
 * 只换一个地址、加一层签名即可。
 *
 * 表里没有已发布版本时返回 versionCode = 0，客户端比较
 * `remoteVersionCode > currentVersionCode` 为假，自然得到「已是最新」。
 *
 * ## 强制升级的两条来源
 *
 *   force_update = 1                          → 运营手工按版本开
 *   请求的 versionCode < min_support_version  → 低于最低支持版本，自动兜底
 *
 * 服务端只负责给出 forceUpdate 这个事实，**不做**「要不要升级」的判断 ——
 * 那是客户端的 remoteVersionCode > currentVersionCode。
 */

require_once __DIR__ . '/../guard.php';
require_once __DIR__ . '/../common.php';

zx_require_method('GET', 'POST');
zx_require_sign();

$input = zx_input();

$deviceUid   = zx_str($input, 'deviceUid');
$versionCode = max(0, zx_int($input, 'versionCode'));

// channel 只允许 [a-z0-9_-]，避免被拿来当注入或塞奇怪的值进唯一键。
$channel = strtolower(zx_str($input, 'channel', 'default'));
if (preg_match('/^[a-z0-9_-]{1,32}$/', $channel) !== 1) {
    $channel = 'default';
}

// 客户端当前版本：上报上来只用于下面算 forceUpdate，不落库（心跳那边已经在维护了）。
$stmt = zx_db()->prepare(
    'SELECT `version_code`, `version_name`, `min_support_version`, `download_url`,
            `file_size`, `sha256`, `release_notes`, `force_update`, `published_at`
       FROM `xiaozhi_android_client_app_version`
      WHERE `platform` = ? AND `channel` = ? AND `enabled` = 1
      ORDER BY `version_code` DESC
      LIMIT 1'
);
$stmt->execute(['android', $channel]);
$latest = $stmt->fetch();

if ($latest === false) {
    // 一条已发布的版本都没有。回 0 而不是报错：对客户端来说「没有新版本」
    // 和「服务器还没有任何版本」是同一件事，都不该弹任何东西。
    zx_json_out(ZX_CODE_OK, 'ok', null, [
        'versionCode'       => 0,
        'versionName'       => '',
        'minSupportVersion' => 1,
        'downloadUrl'       => '',
        'fileSize'          => 0,
        'sha256'            => '',
        'releaseNotes'      => '',
        'forceUpdate'       => false,
        'publishedAt'       => '',
    ]);
}

$latestCode  = (int) $latest['version_code'];
$minSupport  = max(1, (int) $latest['min_support_version']);
$forceUpdate = ((int) $latest['force_update'] === 1) || ($versionCode < $minSupport);

// 上传的 APK 存的是相对路径，下发给客户端前拼成绝对地址。
// 手填外链时 zx_absolute_url 会原样返回。
$downloadUrl = zx_absolute_url((string) $latest['download_url']);

// 没有下载地址就等同于「没有新版本」：客户端拿到空 downloadUrl 也不会提示升级
// （见 AppUpdateManager 里的 downloadUrl.isNotBlank() 判断），这里就不假装有更新了。
if ($downloadUrl === '') {
    zx_json_out(ZX_CODE_OK, 'ok', null, [
        'versionCode'       => 0,
        'versionName'       => '',
        'minSupportVersion' => $minSupport,
        'downloadUrl'       => '',
        'fileSize'          => 0,
        'sha256'            => '',
        'releaseNotes'      => '',
        'forceUpdate'       => false,
        'publishedAt'       => '',
    ]);
}

zx_json_out(ZX_CODE_OK, 'ok', null, [
    'versionCode'       => $latestCode,
    'versionName'       => (string) $latest['version_name'],
    'minSupportVersion' => $minSupport,
    'downloadUrl'       => $downloadUrl,
    'fileSize'          => (int) ($latest['file_size'] ?? 0),
    'sha256'            => strtolower((string) ($latest['sha256'] ?? '')),
    'releaseNotes'      => (string) ($latest['release_notes'] ?? ''),
    'forceUpdate'       => $forceUpdate,
    'publishedAt'       => (string) ($latest['published_at'] ?? ''),
]);
