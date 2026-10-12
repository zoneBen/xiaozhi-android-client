<?php

declare(strict_types=1);

/**
 * 版本发布列表。
 *
 * 列表里的每条对应 xiaozhi_android_client_app_version 的一行，客户端拉最新版时取
 * platform+channel 下 enabled=1 的 MAX(version_code)（见 v1/upgrade.php）。
 *
 * 「发布 / 撤下」就是切 enabled：撤下（enabled=0）就等于这条版本对客户端不可见，
 * 客户端会回落到更低的已发布版本 —— 所以撤下前先确认下面还有能用的版本。
 */

require_once __DIR__ . '/lib.php';

admin_require_login();

$db = zx_db();

// ---- 写操作 ----------------------------------------------------------------

if (admin_is_post()) {
    admin_require_csrf();

    $action = is_string($_POST['action'] ?? null) ? (string) $_POST['action'] : '';
    $id     = (int) ($_POST['id'] ?? 0);

    if ($id > 0 && $action === 'toggle') {
        $enabled = ((int) ($_POST['enabled'] ?? 0) === 1) ? 1 : 0;
        if ($enabled === 1) {
            // 发布前的最后一道闸门：sha256 为空的记录，客户端会**把整个包下载完再拒绝安装**
            // （AppUpdateManager.verifyDownloadedApk 把空哈希当作「拒绝」，不是「跳过」）。
            // 编辑页保存时已经拦过一次，但这里是一键发布，绕得过去，所以两边都要拦。
            $stmt = $db->prepare('SELECT `sha256` FROM `xiaozhi_android_client_app_version` WHERE `id` = ? LIMIT 1');
            $stmt->execute([$id]);
            $sha256 = $stmt->fetchColumn();
            if (!is_string($sha256) || trim($sha256) === '') {
                admin_flash('这条版本没有 sha256，客户端下载后会拒绝安装，所以不能发布。请先「编辑」补上 sha256。', 'err');
                admin_redirect('version_list.php');
            }

            // 首次发布补上发布时间；已发布过的保留原时间。
            $stmt = $db->prepare(
                'UPDATE `xiaozhi_android_client_app_version`
                    SET `enabled` = 1, `published_at` = COALESCE(`published_at`, ?), `updated_at` = ?
                  WHERE `id` = ?'
            );
            $stmt->execute([zx_now(), zx_now(), $id]);
        } else {
            $stmt = $db->prepare(
                'UPDATE `xiaozhi_android_client_app_version` SET `enabled` = 0, `updated_at` = ? WHERE `id` = ?'
            );
            $stmt->execute([zx_now(), $id]);
        }
        admin_flash($enabled === 1 ? '版本已发布。' : '版本已撤下。');
    } elseif ($id > 0 && $action === 'delete') {
        $stmt = $db->prepare('SELECT `download_url` FROM `xiaozhi_android_client_app_version` WHERE `id` = ? LIMIT 1');
        $stmt->execute([$id]);
        $downloadUrl = $stmt->fetchColumn();

        $db->prepare('DELETE FROM `xiaozhi_android_client_app_version` WHERE `id` = ?')->execute([$id]);

        // 删 APK 前确认没有别的版本行还在引用同一个文件。
        if ($downloadUrl !== false && is_string($downloadUrl) && str_starts_with($downloadUrl, 'uploads/')) {
            $stmt = $db->prepare('SELECT COUNT(*) FROM `xiaozhi_android_client_app_version` WHERE `download_url` = ?');
            $stmt->execute([$downloadUrl]);
            if ((int) $stmt->fetchColumn() === 0) {
                admin_delete_uploaded_file($downloadUrl);
            }
        }
        admin_flash('版本记录已删除。');
    }

    admin_redirect('version_list.php');
}

// ---- 读操作 ----------------------------------------------------------------

$rows = $db->query(
    'SELECT `id`, `platform`, `channel`, `version_code`, `version_name`, `min_support_version`,
            `download_url`, `file_size`, `sha256`, `force_update`, `enabled`, `published_at`
       FROM `xiaozhi_android_client_app_version`
      ORDER BY `platform` ASC, `channel` ASC, `version_code` DESC'
)->fetchAll();

$csrf = admin_csrf_token();

admin_page_header('版本发布');
?>
<div class="toolbar">
  <h1 class="page" style="margin:0">版本发布</h1>
  <a class="btn primary" href="version_edit.php">新增版本</a>
</div>

<div class="panel">
  <?php if ($rows === []): ?>
    <p class="empty">还没有版本记录。<a href="version_edit.php">去新增一条</a>。</p>
  <?php else: ?>
    <table>
      <thead>
        <tr>
          <th>平台 / 渠道</th>
          <th>版本</th>
          <th>最低支持</th>
          <th>强制升级</th>
          <th>状态</th>
          <th>文件大小</th>
          <th>SHA256</th>
          <th>下载地址</th>
          <th>发布时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <?php foreach ($rows as $row): ?>
          <?php
            $id        = (int) $row['id'];
            $enabled   = ((int) $row['enabled']) === 1;
            $force     = ((int) $row['force_update']) === 1;
            $sha       = (string) ($row['sha256'] ?? '');
            $download  = (string) $row['download_url'];
            $size      = (int) ($row['file_size'] ?? 0);
          ?>
          <tr>
            <td><?= e($row['platform']) ?> / <span class="mono"><?= e($row['channel']) ?></span></td>
            <td>
              <div class="mono"><?= e($row['version_name']) ?></div>
              <div class="note">code <?= e((string) $row['version_code']) ?></div>
            </td>
            <td class="mono"><?= e((string) $row['min_support_version']) ?></td>
            <td><?= $force ? '<span class="badge warn">强制</span>' : '<span class="note">否</span>' ?></td>
            <td>
              <?php if ($enabled): ?>
                <span class="badge on">已发布</span>
              <?php else: ?>
                <span class="badge off">草稿</span>
              <?php endif; ?>
              <?php if ($sha === ''): ?>
                <span class="badge warn" title="客户端拿不到校验值，下载完会拒绝安装">缺 sha256</span>
              <?php endif; ?>
            </td>
            <td class="mono"><?= e($size > 0 ? admin_human_bytes($size) : '—') ?></td>
            <td class="mono">
              <?php if ($sha === ''): ?>
                <span class="note">—</span>
              <?php else: ?>
                <span title="<?= e($sha) ?>"><?= e(substr($sha, 0, 12)) ?>…</span>
              <?php endif; ?>
            </td>
            <td>
              <?php if ($download === ''): ?>
                <span class="note">—</span>
              <?php else: ?>
                <a href="<?= e(zx_absolute_url($download)) ?>" target="_blank" rel="noopener">下载</a>
              <?php endif; ?>
            </td>
            <td class="note"><?= e(($row['published_at'] ?? '') === '' || $row['published_at'] === null ? '—' : $row['published_at']) ?></td>
            <td>
              <a class="btn" href="version_edit.php?id=<?= $id ?>">编辑</a>
              <form class="inline" method="post" action="version_list.php">
                <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
                <input type="hidden" name="action" value="toggle">
                <input type="hidden" name="id" value="<?= $id ?>">
                <input type="hidden" name="enabled" value="<?= $enabled ? 0 : 1 ?>">
                <button class="btn" type="submit"><?= $enabled ? '撤下' : '发布' ?></button>
              </form>
              <form class="inline" method="post" action="version_list.php"
                    data-confirm="删除版本 <?= e((string) $row['version_name']) ?>（code <?= (int) $row['version_code'] ?>）？">
                <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
                <input type="hidden" name="action" value="delete">
                <input type="hidden" name="id" value="<?= $id ?>">
                <button class="btn danger" type="submit">删除</button>
              </form>
            </td>
          </tr>
        <?php endforeach; ?>
      </tbody>
    </table>
  <?php endif; ?>
</div>
<?php
admin_page_footer();
