<?php

declare(strict_types=1);

/**
 * 接口① 心跳
 *
 * POST /v1/heartbeat.php
 * 请求头：X-App-Key / X-Timestamp / X-Sign（见 common.php 顶部说明）
 *
 * 客户端把设备唯一码和各项元数据报上来，服务端 UPSERT 进 device 表，
 * 并下发心跳间隔、开屏开关等运行时参数 —— 这样改频率只要改数据库，不用重新发包。
 *
 * 被停用的设备（device.status = 0）返回 403，客户端据此停止上报。
 */

require_once __DIR__ . '/../guard.php';
require_once __DIR__ . '/../common.php';

zx_require_method('POST');
zx_require_sign();

$input = zx_input();

$deviceUid = zx_str($input, 'deviceUid');
if ($deviceUid === '') {
    zx_fail(ZX_CODE_BAD_REQUEST, 'deviceUid 不能为空');
}
if (strlen($deviceUid) > 64) {
    zx_fail(ZX_CODE_BAD_REQUEST, 'deviceUid 过长');
}

/** 空字符串统一转成 null，配合下面的 COALESCE(NULLIF(...)) 保证不会用空值覆盖已有数据。 */
$nullable = static fn(string $value): ?string => ($value === '') ? null : $value;

$source = zx_enum($input, 'source', ['app', 'worker', 'boot'], 'app');
$isBoot = ($source === 'boot');
$now    = zx_now();
$ip     = zx_client_ip();
$db     = zx_db();

// ---- 停用检查 --------------------------------------------------------------
//
// 放在 UPSERT 之前：被停用的设备连 last_seen_at 都不该刷新，
// 否则后台一眼看不出它其实已经失联了。
$stmt = $db->prepare('SELECT `status` FROM `xiaozhi_android_client_device` WHERE `device_uid` = ? LIMIT 1');
$stmt->execute([$deviceUid]);
$existingStatus = $stmt->fetchColumn();

if ($existingStatus !== false && (int) $existingStatus === 0) {
    zx_fail(ZX_CODE_FORBIDDEN, '设备已被停用');
}

// ---- UPSERT ----------------------------------------------------------------
//
// 元数据一律「新值非空才覆盖」：客户端某次只发了部分字段（或换了个版本没带全）
// 时，不能把之前攒下来的型号、SN 这些信息抹掉。
$sql = <<<SQL
INSERT INTO `xiaozhi_android_client_device`
  (`device_uid`, `client_id`, `android_id`, `mac_address`, `serial_number`, `install_id`,
   `brand`, `manufacturer`, `model`, `device_name`, `os_version`, `sdk_int`,
   `app_version_code`, `app_version_name`, `screen_width`, `screen_height`,
   `net_type`, `last_ip`, `first_seen_at`, `last_seen_at`,
   `heartbeat_count`, `app_open_count`, `status`, `created_at`, `updated_at`)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `client_id`        = COALESCE(NULLIF(VALUES(`client_id`), ''), `client_id`),
  `android_id`       = COALESCE(NULLIF(VALUES(`android_id`), ''), `android_id`),
  `mac_address`      = COALESCE(NULLIF(VALUES(`mac_address`), ''), `mac_address`),
  `serial_number`    = COALESCE(NULLIF(VALUES(`serial_number`), ''), `serial_number`),
  `install_id`       = COALESCE(NULLIF(VALUES(`install_id`), ''), `install_id`),
  `brand`            = COALESCE(NULLIF(VALUES(`brand`), ''), `brand`),
  `manufacturer`     = COALESCE(NULLIF(VALUES(`manufacturer`), ''), `manufacturer`),
  `model`            = COALESCE(NULLIF(VALUES(`model`), ''), `model`),
  `device_name`      = COALESCE(NULLIF(VALUES(`device_name`), ''), `device_name`),
  `os_version`       = COALESCE(NULLIF(VALUES(`os_version`), ''), `os_version`),
  `sdk_int`          = IF(VALUES(`sdk_int`) > 0, VALUES(`sdk_int`), `sdk_int`),
  `app_version_code` = IF(VALUES(`app_version_code`) > 0, VALUES(`app_version_code`), `app_version_code`),
  `app_version_name` = COALESCE(NULLIF(VALUES(`app_version_name`), ''), `app_version_name`),
  `screen_width`     = IF(VALUES(`screen_width`) > 0, VALUES(`screen_width`), `screen_width`),
  `screen_height`    = IF(VALUES(`screen_height`) > 0, VALUES(`screen_height`), `screen_height`),
  `net_type`         = COALESCE(NULLIF(VALUES(`net_type`), ''), `net_type`),
  `last_ip`          = COALESCE(NULLIF(VALUES(`last_ip`), ''), `last_ip`),
  `last_seen_at`     = VALUES(`last_seen_at`),
  `heartbeat_count`  = `heartbeat_count` + 1,
  `app_open_count`   = `app_open_count` + VALUES(`app_open_count`),
  `updated_at`       = NOW()
SQL;

$db->prepare($sql)->execute([
    $deviceUid,
    $nullable(zx_str($input, 'clientId')),
    $nullable(zx_str($input, 'androidId')),
    $nullable(zx_str($input, 'mac')),
    $nullable(zx_str($input, 'serialNumber')),
    $nullable(zx_str($input, 'installId')),
    $nullable(zx_str($input, 'brand')),
    $nullable(zx_str($input, 'manufacturer')),
    $nullable(zx_str($input, 'model')),
    $nullable(zx_str($input, 'deviceName')),
    $nullable(zx_str($input, 'osVersion')),
    max(0, zx_int($input, 'sdkInt')),
    max(0, zx_int($input, 'appVersionCode')),
    $nullable(zx_str($input, 'appVersionName')),
    max(0, zx_int($input, 'screenWidth')),
    max(0, zx_int($input, 'screenHeight')),
    $nullable(zx_str($input, 'netType')),
    $nullable($ip),
    $now,
    $now,
    $isBoot ? 1 : 0,
]);

// ---- 明细日志 --------------------------------------------------------------
//
// 写失败不影响主流程：明细是给人看的，主表那行才是权威数据。
try {
    $db->prepare(
        'INSERT INTO `xiaozhi_android_client_heartbeat_log`
           (`device_uid`, `app_version_code`, `net_type`, `ip`, `source`, `created_at`)
         VALUES (?, ?, ?, ?, ?, ?)'
    )->execute([
        $deviceUid,
        max(0, zx_int($input, 'appVersionCode')),
        $nullable(zx_str($input, 'netType')),
        $nullable($ip),
        $source,
        $now,
    ]);
} catch (Throwable $e) {
    error_log('[xiaozhi] heartbeat_log 写入失败：' . $e->getMessage());
}

// ---- 下发运行时参数 --------------------------------------------------------

$intervalSec = max(60, min(3600, zx_setting_int('heartbeat_interval_sec', 900)));
$splashTtl   = max(60, zx_setting_int('splash_ttl_sec', 600));

// 最新版本号，让心跳顺带成为一次「是否有新版本」的低成本探测。
$latestVersionCode = 0;
try {
    $stmt = $db->prepare(
        'SELECT MAX(`version_code`) FROM `xiaozhi_android_client_app_version`
          WHERE `platform` = ? AND `channel` = ? AND `enabled` = 1'
    );
    $stmt->execute(['android', 'default']);
    $latestVersionCode = (int) $stmt->fetchColumn();
} catch (Throwable $e) {
    error_log('[xiaozhi] 查询最新版本失败：' . $e->getMessage());
}

zx_ok([
    'intervalSec'       => $intervalSec,
    'deviceStatus'      => 1,
    'splashEnabled'     => zx_setting_int('splash_enabled', 1) === 1,
    'splashTtlSec'      => $splashTtl,
    'latestVersionCode' => $latestVersionCode,
]);
