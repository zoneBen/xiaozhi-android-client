<?php

declare(strict_types=1);

/**
 * 新增 / 编辑 开屏素材。
 *
 * 图片有两种来源：上传文件（优先），或者直接填一个外链 URL。上传时：
 *   - 用 finfo 按**文件内容**判类型，只收 jpeg / png / webp，不信任扩展名和 $_FILES['type']；
 *   - 存成随机文件名（绝不沿用用户原名，避免 .php 这类伪装和路径穿越）；
 *   - 落库的是**相对路径**（uploads/splash/xxx），下发时由 zx_absolute_url 拼成绝对地址。
 * 替换图片时旧文件会被删掉 —— 但只删「我们自己传的」（路径在 uploads/splash/ 下），
 * 外链一律不动。
 *
 * 是否替换、替换成什么，由「这次有没有传文件 / 有没有填外链」决定；两者都没动就保留原图。
 */

require_once __DIR__ . '/lib.php';

admin_require_login();

$db = zx_db();
$id = max(0, (int) ($_GET['id'] ?? 0));

$existing = null;
if ($id > 0) {
    $stmt = $db->prepare(
        'SELECT `id`, `title`, `subtitle`, `image_url`, `image_sha256`, `image_width`, `image_height`,
                `link_url`, `start_at`, `end_at`, `duration_ms`, `weight`, `enabled`, `remark`
           FROM `xiaozhi_android_client_splash` WHERE `id` = ? LIMIT 1'
    );
    $stmt->execute([$id]);
    $existing = $stmt->fetch();
    if ($existing === false) {
        admin_error('素材不存在，可能已被删除。', 404);
    }
}

$isEdit = ($existing !== null);

// 表单初值：编辑时来自数据库，新增时用默认值。
$form = [
    'title'        => (string) ($existing['title'] ?? ''),
    'subtitle'     => (string) ($existing['subtitle'] ?? ''),
    'image_url'    => (string) ($existing['image_url'] ?? ''),
    'link_url'     => (string) ($existing['link_url'] ?? ''),
    'start_at'     => (string) ($existing['start_at'] ?? ''),
    'end_at'       => (string) ($existing['end_at'] ?? ''),
    'duration_ms'  => (string) ($existing['duration_ms'] ?? '3000'),
    'weight'       => (string) ($existing['weight'] ?? '100'),
    'enabled'      => (int) ($existing['enabled'] ?? 1),
    'remark'       => (string) ($existing['remark'] ?? ''),
];

$errors = [];
$formAction = $isEdit ? ('splash_edit.php?id=' . $id) : 'splash_edit.php';

if (admin_is_post()) {
    admin_require_csrf();

    $form['title']       = admin_clip(trim((string) ($_POST['title'] ?? '')), 64);
    $form['subtitle']    = admin_clip(trim((string) ($_POST['subtitle'] ?? '')), 128);
    $form['link_url']    = admin_clip(trim((string) ($_POST['link_url'] ?? '')), 512);
    $form['remark']      = admin_clip(trim((string) ($_POST['remark'] ?? '')), 255);
    $form['duration_ms'] = (string) max(0, (int) ($_POST['duration_ms'] ?? 0));
    $form['weight']      = (string) (int) ($_POST['weight'] ?? 100);
    $form['enabled']     = ((int) ($_POST['enabled'] ?? 0) === 1) ? 1 : 0;
    $form['start_at']    = admin_parse_datetime($_POST['start_at'] ?? null) ?? '';
    $form['end_at']      = admin_parse_datetime($_POST['end_at'] ?? null) ?? '';
    $externalUrl         = admin_clip(trim((string) ($_POST['image_external'] ?? '')), 512);

    $durationMs = (int) $form['duration_ms'];
    if ($durationMs < 500 || $durationMs > 15000) {
        $errors[] = '展示时长需在 500 ~ 15000 毫秒之间（客户端也会按这个范围钳制）。';
    }

    // 图片来源：上传优先于外链；两者都没提供时，编辑态保留原图、新增态报错。
    $finalImageUrl = (string) $form['image_url'];
    $finalSha      = $existing['image_sha256'] ?? null;
    $finalWidth    = isset($existing['image_width']) ? (int) $existing['image_width'] : null;
    $finalHeight   = isset($existing['image_height']) ? (int) $existing['image_height'] : null;
    $oldImageToDelete = null;
    $newlyUploadedRel = null;

    $file    = admin_file_entry('image');
    $fileErr = ($file !== null) ? (int) ($file['error'] ?? UPLOAD_ERR_NO_FILE) : UPLOAD_ERR_NO_FILE;

    if ($file !== null && $fileErr !== UPLOAD_ERR_NO_FILE) {
        if ($fileErr !== UPLOAD_ERR_OK) {
            $errors[] = admin_upload_error_message($fileErr);
        } else {
            $result = admin_store_splash_image((string) ($file['tmp_name'] ?? ''), (int) ($file['size'] ?? 0));
            if ($result['error'] !== '') {
                $errors[] = $result['error'];
            } else {
                $finalImageUrl    = $result['path'];
                $finalSha         = $result['sha256'];
                $finalWidth       = $result['width'];
                $finalHeight      = $result['height'];
                $oldImageToDelete = (string) $form['image_url'];
                $newlyUploadedRel = $result['path'];
            }
        }
    } elseif ($externalUrl !== '') {
        $finalImageUrl    = $externalUrl;
        $finalSha         = null;   // 外链我们量不到 sha/尺寸，留空即可
        $finalWidth       = null;
        $finalHeight      = null;
        $oldImageToDelete = (string) $form['image_url'];
    }

    if ($finalImageUrl === '') {
        $errors[] = '必须上传一张图片，或填写图片外链地址。';
    }

    $form['image_url'] = $finalImageUrl;

    if ($errors === []) {
        $now = zx_now();
        if ($isEdit) {
            $stmt = $db->prepare(
                'UPDATE `xiaozhi_android_client_splash`
                    SET `title` = ?, `subtitle` = ?, `image_url` = ?, `image_sha256` = ?,
                        `image_width` = ?, `image_height` = ?, `link_url` = ?, `start_at` = ?,
                        `end_at` = ?, `duration_ms` = ?, `weight` = ?, `enabled` = ?, `remark` = ?,
                        `updated_at` = ?
                  WHERE `id` = ?'
            );
            $stmt->execute([
                $form['title'], $form['subtitle'], $finalImageUrl, $finalSha,
                $finalWidth, $finalHeight, $form['link_url'],
                ($form['start_at'] === '' ? null : $form['start_at']),
                ($form['end_at'] === '' ? null : $form['end_at']),
                $durationMs, (int) $form['weight'], $form['enabled'],
                ($form['remark'] === '' ? null : $form['remark']),
                $now, $id,
            ]);
        } else {
            $stmt = $db->prepare(
                'INSERT INTO `xiaozhi_android_client_splash`
                    (`title`, `subtitle`, `image_url`, `image_sha256`, `image_width`, `image_height`,
                     `link_url`, `start_at`, `end_at`, `duration_ms`, `weight`, `enabled`, `remark`,
                     `created_at`, `updated_at`)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
            );
            $stmt->execute([
                $form['title'], $form['subtitle'], $finalImageUrl, $finalSha,
                $finalWidth, $finalHeight, $form['link_url'],
                ($form['start_at'] === '' ? null : $form['start_at']),
                ($form['end_at'] === '' ? null : $form['end_at']),
                $durationMs, (int) $form['weight'], $form['enabled'],
                ($form['remark'] === '' ? null : $form['remark']),
                $now, $now,
            ]);
        }

        // 数据库写成功后再清理旧图，避免写失败却把文件删了。
        if ($oldImageToDelete !== null && $oldImageToDelete !== $finalImageUrl) {
            admin_delete_uploaded_file($oldImageToDelete);
        }

        admin_flash($isEdit ? '素材已更新。' : '素材已新增。');
        admin_redirect('splash_list.php');
    }

    // 能走到这里说明没重定向（校验没过）：刚传上来的文件还没有任何记录引用它，删掉，
    // 免得在 uploads/ 里留下一个没人指向的孤儿文件。
    if ($newlyUploadedRel !== null) {
        admin_delete_uploaded_file($newlyUploadedRel);
    }
}

$csrf = admin_csrf_token();

/**
 * 校验并落盘一张开屏图片，返回结果数组。错误以字符串返回而不是抛异常 —— 这是常规的
 * 用户输入失败，走「回到表单显示提示」比整页报错体验好。
 *
 * @return array{error:string,path:string,sha256:?string,width:?int,height:?int}
 */
function admin_store_splash_image(string $tmpName, int $size): array
{
    $fail = static fn(string $msg): array => ['error' => $msg, 'path' => '', 'sha256' => null, 'width' => null, 'height' => null];

    $maxBytes = 5 * 1024 * 1024;
    if ($size <= 0 || $size > $maxBytes) {
        return $fail('图片大小必须在 1 字节 ~ 5 MB 之间。');
    }
    if ($tmpName === '' || !is_uploaded_file($tmpName)) {
        return $fail('上传的临时文件无效。');
    }

    // 按内容判类型：扩展名和 $_FILES['type'] 都是客户端可伪造的。
    $allowed = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'];
    $finfo   = new finfo(FILEINFO_MIME_TYPE);
    $mime    = (string) $finfo->file($tmpName);
    if (!isset($allowed[$mime])) {
        return $fail('只允许 jpeg / png / webp 图片，检测到的类型是：' . $mime);
    }

    $info = @getimagesize($tmpName);
    if ($info === false) {
        return $fail('这个文件不是有效的图片。');
    }

    $dir = admin_ensure_upload_dir('splash');
    $rel = 'uploads/splash/' . admin_random_filename($allowed[$mime]);
    $dest = $dir . '/' . basename($rel);

    if (!@move_uploaded_file($tmpName, $dest)) {
        return $fail('图片保存失败，请检查 uploads/splash 目录的权限。');
    }
    @chmod($dest, 0644);

    $sha = hash_file('sha256', $dest);

    return [
        'error'  => '',
        'path'   => $rel,
        'sha256' => ($sha === false) ? null : strtolower($sha),
        'width'  => (int) $info[0],
        'height' => (int) $info[1],
    ];
}

admin_page_header($isEdit ? '编辑开屏素材' : '新增开屏素材', 'splash');
?>
<h1 class="page"><?= $isEdit ? '编辑开屏素材 #' . $id : '新增开屏素材' ?></h1>

<?php if ($errors !== []): ?>
  <div class="flash err">
    <ul style="margin:0;padding-left:1.1rem">
      <?php foreach ($errors as $msg): ?><li><?= e($msg) ?></li><?php endforeach; ?>
    </ul>
  </div>
<?php endif; ?>

<form class="panel" method="post" action="<?= e($formAction) ?>" enctype="multipart/form-data">
  <input type="hidden" name="csrf" value="<?= e($csrf) ?>">

  <div class="grid2">
    <label class="field">
      <span>主标题（≤64）</span>
      <input type="text" name="title" maxlength="64" value="<?= e($form['title']) ?>">
    </label>
    <label class="field">
      <span>副标题（≤128）</span>
      <input type="text" name="subtitle" maxlength="128" value="<?= e($form['subtitle']) ?>">
    </label>
  </div>

  <label class="field">
    <span>点击跳转链接（留空则整块不可点，≤512）</span>
    <input type="text" name="link_url" maxlength="512" value="<?= e($form['link_url']) ?>" placeholder="https://…">
  </label>

  <?php if ($form['image_url'] !== ''): ?>
    <div class="field">
      <span class="note" style="display:block;margin-bottom:.35rem">当前图片</span>
      <img class="thumb" style="width:160px;height:90px" alt="当前图片"
           src="<?= e(zx_absolute_url($form['image_url'])) ?>">
      <div class="note mono" style="margin-top:.35rem"><?= e($form['image_url']) ?></div>
    </div>
  <?php endif; ?>

  <div class="grid2">
    <label class="field">
      <span>上传图片（jpeg / png / webp，≤5 MB）。上传会替换上面的图</span>
      <input type="file" name="image" accept="image/jpeg,image/png,image/webp">
    </label>
    <label class="field">
      <span>或者：图片外链 URL</span>
      <input type="text" name="image_external" maxlength="512" value=""
             placeholder="留空则不动；填了就用外链">
    </label>
  </div>

  <div class="grid2">
    <label class="field">
      <span>生效开始（留空 = 不限）</span>
      <input type="datetime-local" name="start_at" value="<?= e(admin_datetime_input_value($form['start_at'])) ?>">
    </label>
    <label class="field">
      <span>生效结束（留空 = 长期）</span>
      <input type="datetime-local" name="end_at" value="<?= e(admin_datetime_input_value($form['end_at'])) ?>">
    </label>
  </div>

  <div class="grid2">
    <label class="field">
      <span>展示时长（毫秒，500 ~ 15000）</span>
      <input type="number" name="duration_ms" min="500" max="15000" value="<?= e($form['duration_ms']) ?>">
      <small>客户端会把它钳到 <b>2000 ~ 5000</b>：头屏是「进主界面前的一段等待」，
        它本来有多长不由广告决定。填得比 2 秒短等于白放（会被拉到 2 秒），
        填得比 5 秒长也只占 5 秒。填 0 表示这一条不放广告。</small>
    </label>
    <label class="field">
      <span>权重（多条命中时大的优先）</span>
      <input type="number" name="weight" value="<?= e($form['weight']) ?>">
    </label>
  </div>

  <label class="field">
    <span>备注（仅后台可见，≤255）</span>
    <input type="text" name="remark" maxlength="255" value="<?= e($form['remark']) ?>">
  </label>

  <label class="field">
    <span>状态</span>
    <select name="enabled">
      <option value="1" <?= $form['enabled'] === 1 ? 'selected' : '' ?>>启用</option>
      <option value="0" <?= $form['enabled'] !== 1 ? 'selected' : '' ?>>停用</option>
    </select>
  </label>

  <div class="form-actions">
    <button class="btn primary" type="submit"><?= $isEdit ? '保存' : '新增' ?></button>
    <a class="btn" href="splash_list.php">返回列表</a>
  </div>
</form>
<?php
admin_page_footer();
