<?php

declare(strict_types=1);

/**
 * 新增 / 编辑 版本发布。
 *
 * 下载地址有两种来源：上传 APK（优先），或者填外链 URL。
 *
 * ## 关于 200MB 的 APK 上传
 *
 * 大文件上传能不能成，取决于三处都调够：
 *   - php.ini 的 upload_max_filesize（单文件上限）
 *   - php.ini 的 post_max_size（整个 POST body 上限，必须 ≥ upload_max_filesize）
 *   - nginx 的 client_max_body_size（默认才 1m，最容易被漏掉）
 * 任何一处太小，PHP 会在**解析完请求之前**就截断 body：$_FILES 变成一个空数组，
 * 或者带着 UPLOAD_ERR_INI_SIZE 进来，页面看起来就像「什么都没传」。所以这里不假设
 * 有文件，而是显式检查 $_FILES['apk']['error']，把 UPLOAD_ERR_INI_SIZE 翻译成人话。
 *
 * ## APK 的签名
 *
 * APK 是 ZIP，所以只按扩展名判不可靠 —— 这里额外读文件头，要求是 PK\x03\x04，
 * 能挡住「改个后缀就传上来」。但**签名**这关服务端管不了：客户端 pin 了
 * EXPECTED_SIGNER_SHA256，用别的证书签的包一律拒装。这个必须靠人工在上传前保证，
 * 页面上也给了醒目提示。
 */

require_once __DIR__ . '/lib.php';

admin_require_login();

$db = zx_db();
$id = max(0, (int) ($_GET['id'] ?? 0));

$existing = null;
if ($id > 0) {
    $stmt = $db->prepare(
        'SELECT `id`, `platform`, `channel`, `version_code`, `version_name`, `min_support_version`,
                `download_url`, `file_size`, `sha256`, `release_notes`, `force_update`, `enabled`, `published_at`
           FROM `xiaozhi_android_client_app_version` WHERE `id` = ? LIMIT 1'
    );
    $stmt->execute([$id]);
    $existing = $stmt->fetch();
    if ($existing === false) {
        admin_error('版本记录不存在，可能已被删除。', 404);
    }
}

$isEdit = ($existing !== null);

$form = [
    'platform'            => (string) ($existing['platform'] ?? 'android'),
    'channel'             => (string) ($existing['channel'] ?? 'default'),
    'version_code'        => (string) ($existing['version_code'] ?? ''),
    'version_name'        => (string) ($existing['version_name'] ?? ''),
    'min_support_version' => (string) ($existing['min_support_version'] ?? '1'),
    'download_url'        => (string) ($existing['download_url'] ?? ''),
    'release_notes'       => (string) ($existing['release_notes'] ?? ''),
    'force_update'        => (int) ($existing['force_update'] ?? 0),
    'enabled'             => (int) ($existing['enabled'] ?? 1),
];

$errors    = [];
$formAction = $isEdit ? ('version_edit.php?id=' . $id) : 'version_edit.php';

if (admin_is_post()) {
    admin_require_csrf();

    $form['platform']  = strtolower(admin_clip(trim((string) ($_POST['platform'] ?? 'android')), 16));
    $form['channel']   = strtolower(admin_clip(trim((string) ($_POST['channel'] ?? 'default')), 32));
    $form['version_name'] = admin_clip(trim((string) ($_POST['version_name'] ?? '')), 32);
    // release_notes 是 TEXT：上限 65535 是**字节**，所以这里按字节截，不能按字符（见 admin_clip_bytes）。
    $form['release_notes'] = admin_clip_bytes(trim((string) ($_POST['release_notes'] ?? '')), 65535);
    $form['version_code']  = (string) max(0, (int) ($_POST['version_code'] ?? 0));
    $form['min_support_version'] = (string) max(0, (int) ($_POST['min_support_version'] ?? 1));
    $form['force_update']  = ((int) ($_POST['force_update'] ?? 0) === 1) ? 1 : 0;
    $form['enabled']       = ((int) ($_POST['enabled'] ?? 0) === 1) ? 1 : 0;
    $externalUrl           = admin_clip(trim((string) ($_POST['download_external'] ?? '')), 512);

    $versionCode = (int) $form['version_code'];
    $minSupport  = (int) $form['min_support_version'];

    // platform / channel 进唯一键，限制字符集，免得塞进奇怪的字符。
    if (preg_match('/^[a-z0-9_-]{1,16}$/', $form['platform']) !== 1) {
        $errors[] = '平台只允许小写字母、数字、下划线、连字符（1~16 位），建议填 android。';
    }
    if (preg_match('/^[a-z0-9_-]{1,32}$/', $form['channel']) !== 1) {
        $errors[] = '渠道只允许小写字母、数字、下划线、连字符（1~32 位），默认 default。';
    }
    if ($versionCode < 1) {
        $errors[] = 'version_code 必须是大于 0 的整数。';
    }
    if ($form['version_name'] === '') {
        $errors[] = '版本名不能为空。';
    }
    if ($minSupport < 1) {
        $minSupport = 1;
        $form['min_support_version'] = '1';
    }

    // 下载地址：上传优先于外链；两者都没动时，编辑态保留原地址、新增态报错。
    $finalDownload = (string) $form['download_url'];
    $finalSize     = isset($existing['file_size']) ? (int) $existing['file_size'] : null;
    $finalSha      = $existing['sha256'] ?? null;
    $oldApkToDelete = null;
    $newlyUploadedApk = null;

    $file    = admin_file_entry('apk');
    $fileErr = ($file !== null) ? (int) ($file['error'] ?? UPLOAD_ERR_NO_FILE) : UPLOAD_ERR_NO_FILE;

    if ($file !== null && $fileErr !== UPLOAD_ERR_NO_FILE) {
        if ($fileErr !== UPLOAD_ERR_OK) {
            $errors[] = admin_upload_error_message($fileErr);
        } else {
            $result = admin_store_apk(
                (string) ($file['tmp_name'] ?? ''),
                (int) ($file['size'] ?? 0),
                (string) ($file['name'] ?? '')
            );
            if ($result['error'] !== '') {
                $errors[] = $result['error'];
            } else {
                $finalDownload    = $result['path'];
                $finalSize        = $result['size'];
                $finalSha         = $result['sha256'];
                $oldApkToDelete   = (string) $form['download_url'];
                $newlyUploadedApk = $result['path'];
            }
        }
    } elseif ($externalUrl !== '') {
        $finalDownload  = $externalUrl;
        $finalSize      = null;
        $finalSha       = null;
        $oldApkToDelete = (string) $form['download_url'];
    }

    if ($finalDownload === '') {
        $errors[] = '必须上传 APK，或填写下载地址外链。';
    }

    $form['download_url'] = $finalDownload;

    if ($errors === []) {
        $now         = zx_now();
        $publishedAt = ($existing['published_at'] ?? null);
        if ($publishedAt === '') {
            $publishedAt = null;
        }
        if ($form['enabled'] === 1 && $publishedAt === null) {
            $publishedAt = $now;
        }

        try {
            if ($isEdit) {
                $stmt = $db->prepare(
                    'UPDATE `xiaozhi_android_client_app_version`
                        SET `platform` = ?, `channel` = ?, `version_code` = ?, `version_name` = ?,
                            `min_support_version` = ?, `download_url` = ?, `file_size` = ?, `sha256` = ?,
                            `release_notes` = ?, `force_update` = ?, `enabled` = ?, `published_at` = ?,
                            `updated_at` = ?
                      WHERE `id` = ?'
                );
                $stmt->execute([
                    $form['platform'], $form['channel'], $versionCode, $form['version_name'],
                    $minSupport, $finalDownload, $finalSize, $finalSha,
                    ($form['release_notes'] === '' ? null : $form['release_notes']),
                    $form['force_update'], $form['enabled'], $publishedAt, $now, $id,
                ]);
            } else {
                $stmt = $db->prepare(
                    'INSERT INTO `xiaozhi_android_client_app_version`
                        (`platform`, `channel`, `version_code`, `version_name`, `min_support_version`,
                         `download_url`, `file_size`, `sha256`, `release_notes`, `force_update`, `enabled`,
                         `published_at`, `created_at`, `updated_at`)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
                );
                $stmt->execute([
                    $form['platform'], $form['channel'], $versionCode, $form['version_name'],
                    $minSupport, $finalDownload, $finalSize, $finalSha,
                    ($form['release_notes'] === '' ? null : $form['release_notes']),
                    $form['force_update'], $form['enabled'], $publishedAt, $now, $now,
                ]);
            }
        } catch (PDOException $e) {
            // UNIQUE(platform, channel, version_code) 撞车时把 1062 翻成人话，
            // 而不是把一段堆栈甩给运营。
            if ((int) ($e->errorInfo[1] ?? 0) === 1062) {
                $errors[] = '同一「平台 + 渠道」下已经有 version_code = ' . $versionCode
                    . ' 的版本了。请改用别的 version_code，或直接编辑那条记录。';
            } else {
                throw $e;
            }
        }

        if ($errors === []) {
            if ($oldApkToDelete !== null && $oldApkToDelete !== $finalDownload) {
                admin_delete_uploaded_file($oldApkToDelete);
            }
            admin_flash($isEdit ? '版本已更新。' : '版本已新增。');
            admin_redirect('version_list.php');
        }
    }

    // 没重定向说明校验没过（含唯一键冲突）：刚传上来的 APK 还没有记录引用，删掉免得留孤儿。
    if ($newlyUploadedApk !== null) {
        admin_delete_uploaded_file($newlyUploadedApk);
    }
}

$csrf = admin_csrf_token();

/**
 * 校验并落盘一个 APK，返回结果数组。
 *
 * @return array{error:string,path:string,size:?int,sha256:?string}
 */
function admin_store_apk(string $tmpName, int $size, string $originalName): array
{
    $fail = static fn(string $msg): array => ['error' => $msg, 'path' => '', 'size' => null, 'sha256' => null];

    $maxBytes = 200 * 1024 * 1024;   // 200 MB
    if ($size <= 0 || $size > $maxBytes) {
        return $fail('APK 大小必须在 1 字节 ~ 200 MB 之间（当前 ' . admin_human_bytes($size) . '）。');
    }
    if ($tmpName === '' || !is_uploaded_file($tmpName)) {
        return $fail('上传的临时文件无效。');
    }

    // 扩展名只作为第一道门；真正的类型下面用文件头判。
    if (strtolower((string) pathinfo($originalName, PATHINFO_EXTENSION)) !== 'apk') {
        return $fail('只接受 .apk 文件。');
    }

    // APK 就是 ZIP：合法文件以 PK\x03\x04 开头。读 4 字节即可挡住改名的文件。
    $fh    = fopen($tmpName, 'rb');
    $magic = ($fh !== false) ? (string) fread($fh, 4) : '';
    if ($fh !== false) {
        fclose($fh);
    }
    if ($magic !== "PK\x03\x04") {
        return $fail('这个文件不是有效的 APK（不是 ZIP 结构，可能只是改了后缀）。');
    }

    $dir  = admin_ensure_upload_dir('apk');
    $rel  = 'uploads/apk/' . admin_random_filename('apk');
    $dest = $dir . '/' . basename($rel);

    if (!@move_uploaded_file($tmpName, $dest)) {
        return $fail('APK 保存失败，请检查 uploads/apk 目录的权限。');
    }
    @chmod($dest, 0644);

    $sha   = hash_file('sha256', $dest);
    $bytes = filesize($dest);

    return [
        'error'  => '',
        'path'   => $rel,
        'size'   => ($bytes === false) ? null : (int) $bytes,
        'sha256' => ($sha === false) ? null : strtolower($sha),
    ];
}

admin_page_header($isEdit ? '编辑版本' : '新增版本', 'versions');
?>
<h1 class="page"><?= $isEdit ? '编辑版本 #' . $id : '新增版本' ?></h1>

<div class="warnbox">
  <strong>签名必须一致：</strong>上传的 APK 必须用客户端 pin 的那张证书签名
  （Android 端常量 <span class="mono">EXPECTED_SIGNER_SHA256</span>），否则客户端会拒绝安装。
  服务端无法替你验证这一点，请在打包时自行确认。
</div>

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
      <span>平台（建议 android）</span>
      <input type="text" name="platform" maxlength="16" value="<?= e($form['platform']) ?>">
    </label>
    <label class="field">
      <span>渠道（默认 default，预留灰度）</span>
      <input type="text" name="channel" maxlength="32" value="<?= e($form['channel']) ?>">
    </label>
  </div>

  <div class="grid2">
    <label class="field">
      <span>version_code（整数，必须递增）</span>
      <input type="number" name="version_code" min="1" value="<?= e($form['version_code']) ?>">
    </label>
    <label class="field">
      <span>version_name（如 1.2.12）</span>
      <input type="text" name="version_name" maxlength="32" value="<?= e($form['version_name']) ?>">
    </label>
  </div>

  <div class="grid2">
    <label class="field">
      <span>min_support_version（低于此版本强制升级）</span>
      <input type="number" name="min_support_version" min="1" value="<?= e($form['min_support_version']) ?>">
    </label>
    <label class="field">
      <span>强制升级</span>
      <select name="force_update">
        <option value="0" <?= $form['force_update'] !== 1 ? 'selected' : '' ?>>否</option>
        <option value="1" <?= $form['force_update'] === 1 ? 'selected' : '' ?>>是</option>
      </select>
    </label>
  </div>

  <label class="field">
    <span>更新说明（客户端弹窗展示）</span>
    <textarea name="release_notes"><?= e($form['release_notes']) ?></textarea>
  </label>

  <?php if ($form['download_url'] !== ''): ?>
    <div class="field">
      <span class="note" style="display:block;margin-bottom:.35rem">当前下载地址</span>
      <div class="mono"><?= e($form['download_url']) ?></div>
    </div>
  <?php endif; ?>

  <div class="grid2">
    <label class="field">
      <span>上传 APK（≤200 MB，会上传覆盖当前地址）</span>
      <input type="file" name="apk" accept=".apk,application/vnd.android.package-archive">
    </label>
    <label class="field">
      <span>或者：下载地址外链 URL</span>
      <input type="text" name="download_external" maxlength="512" value=""
             placeholder="留空则不动；填了就用外链">
    </label>
  </div>

  <label class="field">
    <span>状态</span>
    <select name="enabled">
      <option value="1" <?= $form['enabled'] === 1 ? 'selected' : '' ?>>发布</option>
      <option value="0" <?= $form['enabled'] !== 1 ? 'selected' : '' ?>>草稿</option>
    </select>
  </label>

  <div class="form-actions">
    <button class="btn primary" type="submit"><?= $isEdit ? '保存' : '新增' ?></button>
    <a class="btn" href="version_list.php">返回列表</a>
  </div>
</form>
<?php
admin_page_footer();
