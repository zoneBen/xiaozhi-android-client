<?php

declare(strict_types=1);

/**
 * 开屏素材列表。
 *
 * 曝光/点击来自 xiaozhi_android_client_splash_stat（一次聚合查询整页取完，避免按行 N+1）。
 *
 * 删除一条素材时必须连它的统计行一起删：stat 表对 splash_id 没有外键，留着就会攒下
 * 一堆指向不存在素材的孤儿行。两句话放在同一个事务里，避免删了一半。
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
        $stmt    = $db->prepare(
            'UPDATE `xiaozhi_android_client_splash` SET `enabled` = ?, `updated_at` = ? WHERE `id` = ?'
        );
        $stmt->execute([$enabled, zx_now(), $id]);
        admin_flash($enabled === 1 ? '素材已启用。' : '素材已停用。');
    } elseif ($id > 0 && $action === 'delete') {
        // 先取出图片路径：删完行就查不到了，而删文件要用到它。
        $stmt = $db->prepare('SELECT `image_url` FROM `xiaozhi_android_client_splash` WHERE `id` = ? LIMIT 1');
        $stmt->execute([$id]);
        $imageUrl = $stmt->fetchColumn();

        $db->beginTransaction();
        try {
            $db->prepare('DELETE FROM `xiaozhi_android_client_splash_stat` WHERE `splash_id` = ?')->execute([$id]);
            $db->prepare('DELETE FROM `xiaozhi_android_client_splash` WHERE `id` = ?')->execute([$id]);
            $db->commit();
        } catch (Throwable $e) {
            $db->rollBack();
            throw $e;
        }

        // 只有我们自己传上去的图才删，外链一律不动。
        if ($imageUrl !== false) {
            admin_delete_uploaded_file((string) $imageUrl);
        }
        admin_flash('素材及其统计已删除。');
    }

    admin_redirect('splash_list.php');
}

// ---- 读操作 ----------------------------------------------------------------

$rows = $db->query(
    'SELECT `id`, `title`, `subtitle`, `image_url`, `link_url`, `start_at`, `end_at`,
            `duration_ms`, `weight`, `enabled`, `updated_at`
       FROM `xiaozhi_android_client_splash`
      ORDER BY `enabled` DESC, `weight` DESC, `id` DESC'
)->fetchAll();

// 一次取完整页的曝光/点击，按 splash_id + action 汇总。
$stats = [];
foreach ($db->query(
    'SELECT `splash_id`, `action`, COUNT(*) AS `c`
       FROM `xiaozhi_android_client_splash_stat`
      GROUP BY `splash_id`, `action`'
)->fetchAll() as $statRow) {
    $stats[(int) $statRow['splash_id']][(string) $statRow['action']] = (int) $statRow['c'];
}

$csrf = admin_csrf_token();

admin_page_header('开屏素材');
?>
<div class="toolbar">
  <h1 class="page" style="margin:0">开屏素材</h1>
  <a class="btn primary" href="splash_edit.php">新增素材</a>
</div>

<div class="panel">
  <?php if ($rows === []): ?>
    <p class="empty">还没有开屏素材。<a href="splash_edit.php">去新增一条</a>。</p>
  <?php else: ?>
    <table>
      <thead>
        <tr>
          <th>预览</th>
          <th>ID</th>
          <th>标题 / 副标题</th>
          <th>生效时段</th>
          <th>展示时长</th>
          <th>权重</th>
          <th>状态</th>
          <th>曝光 / 点击</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <?php foreach ($rows as $row): ?>
          <?php
            $id      = (int) $row['id'];
            $enabled = ((int) $row['enabled']) === 1;
            $shows   = $stats[$id]['show']  ?? 0;
            $clicks  = $stats[$id]['click'] ?? 0;
            $start   = (string) ($row['start_at'] ?? '');
            $end     = (string) ($row['end_at'] ?? '');
            $period  = ($start === '' && $end === '')
                ? '不限'
                : (($start === '' ? '不限' : $start) . ' ~ ' . ($end === '' ? '不限' : $end));
          ?>
          <tr>
            <td>
              <img class="thumb" alt="素材预览"
                   src="<?= e(zx_absolute_url((string) $row['image_url'])) ?>"
                   loading="lazy">
            </td>
            <td class="mono"><?= $id ?></td>
            <td>
              <div><?= e(($row['title'] ?? '') === '' ? '（无标题）' : $row['title']) ?></div>
              <div class="note"><?= e((string) $row['subtitle']) ?></div>
            </td>
            <td class="note"><?= e($period) ?></td>
            <td class="mono"><?= e((string) $row['duration_ms']) ?> ms</td>
            <td class="mono"><?= e((string) $row['weight']) ?></td>
            <td>
              <?php if ($enabled): ?>
                <span class="badge on">启用</span>
              <?php else: ?>
                <span class="badge off">停用</span>
              <?php endif; ?>
            </td>
            <td class="mono"><?= e((string) $shows) ?> / <?= e((string) $clicks) ?></td>
            <td>
              <a class="btn" href="splash_edit.php?id=<?= $id ?>">编辑</a>
              <form class="inline" method="post" action="splash_list.php">
                <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
                <input type="hidden" name="action" value="toggle">
                <input type="hidden" name="id" value="<?= $id ?>">
                <input type="hidden" name="enabled" value="<?= $enabled ? 0 : 1 ?>">
                <button class="btn" type="submit"><?= $enabled ? '停用' : '启用' ?></button>
              </form>
              <form class="inline" method="post" action="splash_list.php"
                    data-confirm="删除素材 #<?= (int) $id ?> 及其统计？此操作不可撤销。">
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
