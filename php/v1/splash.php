<?php

declare(strict_types=1);

/**
 * 接口② 开屏
 *
 *   GET  /v1/splash.php   拉取开屏素材
 *   POST /v1/splash.php   上报曝光/点击
 *
 * 请求头：X-App-Key / X-Timestamp / X-Sign（见 common.php 顶部说明）
 *
 * ## 一个和计划不同的地方：用 revision 而不是 lastId 做条件请求
 *
 * 原计划是客户端把缓存素材的 id 传回来、相同则回 notModified。但 id 相同不代表内容
 * 相同 —— 后台改一张已有素材的图，id 不变，客户端就会一直用旧图。
 * 改成下发 `revision`（生效素材集合里最大的 updated_at，unix 秒），任何一次编辑都会
 * 让它变，客户端拿这个值做条件请求才是对的。
 *
 * 另外客户端只把 revision 当「要不要重新拉清单」用；「要不要重新下图」由每条的
 * imageSha256 决定。
 */

require_once __DIR__ . '/../guard.php';
require_once __DIR__ . '/../common.php';

zx_require_sign();

$method = zx_method();
if ($method === 'GET') {
    zx_handle_fetch();
} elseif ($method === 'POST') {
    zx_handle_report();
} else {
    zx_fail(ZX_CODE_BAD_REQUEST, '不支持的请求方法，仅接受 GET/POST');
}

// ----------------------------------------------------------------------------

function zx_handle_fetch(): void
{
    $input = zx_input();

    $deviceUid = zx_str($input, 'deviceUid');
    if ($deviceUid === '' || strlen($deviceUid) > 64) {
        zx_fail(ZX_CODE_BAD_REQUEST, 'deviceUid 缺失或不合法');
    }

    // width/height 目前不参与筛选（一条素材只有一张图），收下是为了将来做
    // 多分辨率时不用再改客户端的调用点。
    $lastRevision = max(0, zx_int($input, 'lastRevision'));

    $ttlSec = max(60, zx_setting_int('splash_ttl_sec', 600));

    // 总开关关掉时，明确回一个 enabled=false 让客户端清掉缓存 ——
    // 这是运营唯一能让已经在客户端上的旧图下线的办法。
    if (zx_setting_int('splash_enabled', 1) !== 1) {
        zx_ok([
            'enabled'      => false,
            'ttlSec'       => $ttlSec,
            'revision'     => 0,
            'notModified'  => false,
            'items'        => [],
        ]);
    }

    $db  = zx_db();
    $now = zx_now();

    $where = '`enabled` = 1
              AND (`start_at` IS NULL OR `start_at` <= ?)
              AND (`end_at`   IS NULL OR `end_at`   >= ?)';

    // revision：生效集合里最大的 updated_at。任何一次编辑都会让它变。
    $stmt = $db->prepare(
        "SELECT COALESCE(UNIX_TIMESTAMP(MAX(`updated_at`)), 0)
           FROM `xiaozhi_android_client_splash` WHERE {$where}"
    );
    $stmt->execute([$now, $now]);
    $revision = (int) $stmt->fetchColumn();

    if ($revision === 0) {
        zx_ok([
            'enabled'     => true,
            'ttlSec'      => $ttlSec,
            'revision'    => 0,
            'notModified' => false,
            'items'       => [],
        ]);
    }

    if ($lastRevision === $revision) {
        // 客户端手里的清单就是最新的，连 items 都不用给。
        zx_ok([
            'enabled'     => true,
            'ttlSec'      => $ttlSec,
            'revision'    => $revision,
            'notModified' => true,
            'items'       => [],
        ]);
    }

    $limit = max(1, min(10, zx_setting_int('splash_max_items', 3)));

    $stmt = $db->prepare(
        "SELECT `id`, `title`, `subtitle`, `image_url`, `image_sha256`, `image_width`, `image_height`,
                `link_url`, `duration_ms`, `weight`
           FROM `xiaozhi_android_client_splash`
          WHERE {$where}
          ORDER BY `weight` DESC, `id` DESC
          LIMIT ?"
    );
    $stmt->bindValue(1, $now, PDO::PARAM_STR);
    $stmt->bindValue(2, $now, PDO::PARAM_STR);
    $stmt->bindValue(3, $limit, PDO::PARAM_INT);
    $stmt->execute();

    $items = [];
    foreach ($stmt->fetchAll() as $row) {
        $items[] = [
            'id'           => (int) $row['id'],
            'title'        => (string) $row['title'],
            'subtitle'     => (string) $row['subtitle'],
            'imageUrl'     => zx_absolute_url((string) $row['image_url']),
            'imageSha256'  => (string) ($row['image_sha256'] ?? ''),
            'imageWidth'   => (int) ($row['image_width'] ?? 0),
            'imageHeight'  => (int) ($row['image_height'] ?? 0),
            'linkUrl'      => (string) $row['link_url'],
            'durationMs'   => max(500, min(15000, (int) $row['duration_ms'])),
            'weight'       => (int) $row['weight'],
        ];
    }

    zx_ok([
        'enabled'     => true,
        'ttlSec'      => $ttlSec,
        'revision'    => $revision,
        'notModified' => false,
        'items'       => $items,
    ]);
}

// ----------------------------------------------------------------------------

function zx_handle_report(): void
{
    $input = zx_input();

    $deviceUid = zx_str($input, 'deviceUid');
    if ($deviceUid === '' || strlen($deviceUid) > 64) {
        zx_fail(ZX_CODE_BAD_REQUEST, 'deviceUid 缺失或不合法');
    }

    $splashId = zx_int($input, 'splashId');
    if ($splashId <= 0) {
        zx_fail(ZX_CODE_BAD_REQUEST, 'splashId 不合法');
    }

    $action = zx_enum($input, 'action', ['show', 'click'], '');
    if ($action === '') {
        zx_fail(ZX_CODE_BAD_REQUEST, 'action 仅接受 show / click');
    }

    // 素材可能刚被删掉，外键约束不存在，这里显式确认一下，
    // 免得统计表里攒下一堆指向不存在素材的行。
    $stmt = zx_db()->prepare('SELECT COUNT(*) FROM `xiaozhi_android_client_splash` WHERE `id` = ?');
    $stmt->execute([$splashId]);
    if ((int) $stmt->fetchColumn() === 0) {
        zx_fail(ZX_CODE_NOT_FOUND, '素材不存在');
    }

    $now = zx_now();
    zx_db()->prepare(
        'INSERT INTO `xiaozhi_android_client_splash_stat`
           (`splash_id`, `device_uid`, `action`, `stat_date`, `created_at`)
         VALUES (?, ?, ?, ?, ?)'
    )->execute([$splashId, $deviceUid, $action, substr($now, 0, 10), $now]);

    zx_ok();
}
