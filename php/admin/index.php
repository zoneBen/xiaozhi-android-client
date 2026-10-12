<?php

declare(strict_types=1);

/**
 * 设备列表。
 *
 * 页面顶上那行「设备总数 / 最近 5 分钟有心跳」其实是本项目最重要的一块看板：客户端在
 * 服务器挂掉时是**静默降级**的（继续用本地缓存、不弹任何东西），所以服务端一旦出事，
 * 只有这里能看出来。把「最近 5 分钟有心跳」放到最显眼的位置，就是为了让这个数字掉到 0
 * 时一眼可见。
 *
 * 「在线」的判定口径：last_seen_at 落在 3 个心跳周期内。用 3 倍而不是 1 倍，是为了容忍
 * 单次心跳丢包和时钟抖动；心跳间隔从 config 表读，改间隔不用改代码。
 */

require_once __DIR__ . '/lib.php';

admin_require_login();

$db = zx_db();

// ---- 写操作（全部 POST + CSRF，改完重定向回列表） --------------------------

if (admin_is_post()) {
    admin_require_csrf();

    $action = is_string($_POST['action'] ?? null) ? (string) $_POST['action'] : '';
    $id     = (int) ($_POST['id'] ?? 0);
    $page   = max(1, (int) ($_POST['page'] ?? 1));

    if ($id > 0 && $action === 'status') {
        $status = ((int) ($_POST['status'] ?? 0) === 1) ? 1 : 0;
        $stmt   = $db->prepare(
            'UPDATE `xiaozhi_android_client_device` SET `status` = ?, `updated_at` = ? WHERE `id` = ?'
        );
        $stmt->execute([$status, zx_now(), $id]);
        admin_flash($status === 1
            ? '设备已启用。'
            : '设备已停用：它下次心跳会收到 403，客户端随即停止上报。');
    } elseif ($id > 0 && $action === 'remark') {
        $remark = is_string($_POST['remark'] ?? null) ? trim((string) $_POST['remark']) : '';
        $remark = admin_clip($remark, 255);
        $stmt   = $db->prepare(
            'UPDATE `xiaozhi_android_client_device` SET `remark` = ?, `updated_at` = ? WHERE `id` = ?'
        );
        $stmt->execute([($remark === '' ? null : $remark), zx_now(), $id]);
        admin_flash('备注已保存。');
    }

    // POST-redirect-GET：刷新页面不会重复提交，且保留当前页码。
    admin_redirect('index.php?page=' . $page);
}

// ---- 读操作 ----------------------------------------------------------------

$intervalSec = max(60, zx_setting_int('heartbeat_interval_sec', 900));
$onlineSec   = $intervalSec * 3;                       // 在线判定窗口
$alarmSec    = 300;                                    // 顶部看板固定用 5 分钟
$onlineAt    = date('Y-m-d H:i:s', time() - $onlineSec);
$alarmAt     = date('Y-m-d H:i:s', time() - $alarmSec);

// 顶部看板：总数 + 最近 5 分钟有心跳的台数。
$totalCount = (int) $db->query('SELECT COUNT(*) FROM `xiaozhi_android_client_device`')->fetchColumn();
$stmt = $db->prepare('SELECT COUNT(*) FROM `xiaozhi_android_client_device` WHERE `last_seen_at` >= ?');
$stmt->execute([$alarmAt]);
$recentCount = (int) $stmt->fetchColumn();

// ---- 分页 ------------------------------------------------------------------

$perPage = 50;
$page    = max(1, (int) ($_GET['page'] ?? 1));
$pages   = max(1, (int) ceil($totalCount / $perPage));
$page    = min($page, $pages);
$offset  = ($page - 1) * $perPage;

// 排序：在线的排前面，其次按最后心跳倒序。在线与否用 last_seen_at 与阈值比较现算。
$sql = <<<SQL
SELECT `id`, `device_uid`, `brand`, `model`, `os_version`, `sdk_int`, `app_version_name`,
       `last_ip`, `last_seen_at`, `heartbeat_count`, `status`, `remark`,
       (`last_seen_at` >= ?) AS `is_online`
  FROM `xiaozhi_android_client_device`
 ORDER BY `is_online` DESC, `last_seen_at` DESC
 LIMIT ? OFFSET ?
SQL;
$stmt = $db->prepare($sql);
$stmt->bindValue(1, $onlineAt, PDO::PARAM_STR);
$stmt->bindValue(2, $perPage, PDO::PARAM_INT);
$stmt->bindValue(3, $offset, PDO::PARAM_INT);
$stmt->execute();
$rows = $stmt->fetchAll();

$csrf    = admin_csrf_token();
$allOnline = ($recentCount === 0 && $totalCount > 0);   // 一台都没心跳 → 大概是服务端出事了

admin_page_header('设备列表');
?>
<h1 class="page">设备<span class="hint">在线判定 = 最近 <?= (int) $intervalSec ?> 秒 × 3 = <?= (int) $onlineSec ?> 秒内有心跳</span></h1>

<div class="panel alarm <?= $allOnline ? 'alert' : '' ?>">
  <div class="stat"><b><?= e((string) $totalCount) ?></b><span>设备总数</span></div>
  <div class="stat"><b><?= e((string) $recentCount) ?></b><span>最近 5 分钟有心跳</span></div>
  <?php if ($allOnline): ?>
    <div class="stat"><b style="color:#b42318">!</b><span>5 分钟内没有任何设备上报，先检查服务端/数据库是否还活着</span></div>
  <?php endif; ?>
</div>

<div class="panel">
  <?php if ($rows === []): ?>
    <p class="empty">还没有任何设备。客户端首次心跳上报后这里就会出现。</p>
  <?php else: ?>
    <table>
      <thead>
        <tr>
          <th>device_uid</th>
          <th>备注</th>
          <th>型号</th>
          <th>系统</th>
          <th>应用版本</th>
          <th>IP</th>
          <th>最后心跳</th>
          <th>心跳次数</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <?php foreach ($rows as $row): ?>
          <?php
            $id       = (int) $row['id'];
            $online   = ((int) $row['is_online']) === 1;
            $enabled  = ((int) $row['status']) === 1;
            $brandModel = trim((string) $row['brand'] . ' ' . (string) $row['model']);
          ?>
          <tr>
            <td class="mono"><?= e($row['device_uid']) ?></td>
            <td>
              <form class="inline" method="post" action="index.php">
                <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
                <input type="hidden" name="action" value="remark">
                <input type="hidden" name="id" value="<?= $id ?>">
                <input type="hidden" name="page" value="<?= (int) $page ?>">
                <input type="text" name="remark" maxlength="255" value="<?= e($row['remark']) ?>"
                       style="width:9rem" placeholder="如：展厅 3 号机">
                <button class="btn" type="submit">存</button>
              </form>
            </td>
            <td><?= e($brandModel === '' ? '—' : $brandModel) ?></td>
            <td><?= e(($row['os_version'] ?? '') === '' ? '—' : $row['os_version']) ?>
                <?= ((int) ($row['sdk_int'] ?? 0) > 0) ? '<span class="mono">(API ' . e($row['sdk_int']) . ')</span>' : '' ?></td>
            <td><?= e(($row['app_version_name'] ?? '') === '' ? '—' : $row['app_version_name']) ?></td>
            <td class="mono"><?= e(($row['last_ip'] ?? '') === '' ? '—' : $row['last_ip']) ?></td>
            <td>
              <div class="mono"><?= e($row['last_seen_at']) ?></div>
              <div class="note"><?= e(admin_relative_time((string) $row['last_seen_at'])) ?></div>
            </td>
            <td class="mono"><?= e((string) $row['heartbeat_count']) ?></td>
            <td>
              <?php if ($online): ?>
                <span class="badge on">在线</span>
              <?php else: ?>
                <span class="badge off">离线</span>
              <?php endif; ?>
              <?php if (!$enabled): ?>
                <span class="badge warn">已停用</span>
              <?php endif; ?>
            </td>
            <td>
              <form class="inline" method="post" action="index.php">
                <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
                <input type="hidden" name="action" value="status">
                <input type="hidden" name="id" value="<?= $id ?>">
                <input type="hidden" name="page" value="<?= (int) $page ?>">
                <input type="hidden" name="status" value="<?= $enabled ? 0 : 1 ?>">
                <?php if ($enabled): ?>
                  <button class="btn danger" type="submit">停用</button>
                <?php else: ?>
                  <button class="btn" type="submit">启用</button>
                <?php endif; ?>
              </form>
            </td>
          </tr>
        <?php endforeach; ?>
      </tbody>
    </table>

    <div class="pager">
      <span>第 <?= (int) $page ?> / <?= (int) $pages ?> 页 · 共 <?= (int) $totalCount ?> 台</span>
      <?php if ($page > 1): ?>
        <a class="btn" href="index.php?page=<?= (int) ($page - 1) ?>">上一页</a>
      <?php endif; ?>
      <?php if ($page < $pages): ?>
        <a class="btn" href="index.php?page=<?= (int) ($page + 1) ?>">下一页</a>
      <?php endif; ?>
    </div>
  <?php endif; ?>
</div>
<?php
admin_page_footer();
