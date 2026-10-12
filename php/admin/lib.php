<?php

declare(strict_types=1);

/**
 * 后台公共库：会话、登录校验、CSRF、布局，以及**自己的错误处理**。
 *
 * ## 为什么要单独一套错误处理
 *
 * common.php 在 include 时就把「异常 → JSON 信封」的处理器注册到了全局。后台吐的是
 * HTML，如果沿用那套，一个 notice 就会把一段 JSON 混进页面中间 —— 页面看起来是坏的，
 * 报错信息还丢了。所以这里 require 完 common.php 之后，立刻**重新注册**自己的处理器：
 *
 *   set_error_handler      —— 覆盖 common.php 的（该函数是替换语义）
 *   set_exception_handler  —— 覆盖 common.php 的（同样是替换语义）
 *   register_shutdown_function —— 追加一个 HTML 版致命错误处理器
 *
 * 注意 register_shutdown_function 是**追加**语义，没法注销 common.php 已注册的那个，
 * 所以进程里会同时存在两个关机函数，执行顺序是先注册的先跑：
 *
 *   1) common.php 的：只在「响应头还没发出去」且是 E_ERROR 这类致命错误时才回 JSON；
 *   2) 我们的：把同样的致命错误渲染成 HTML。
 *
 * 也就是说，只有当「页面一个字都还没输出、同时又发生致命错误」时，common.php 的那个
 * 才会抢先吐出 JSON。后台页面在渲染前会先把事件处理完，实际能撞上这条路径的只剩
 * 「渲染中途调用未定义函数」这种代码缺陷，属于可接受的残余风险（详见交付报告）。
 * 要彻底消除，只能改 common.php 让它把处理器注册挪到一个开关后面，而那个文件不允许改。
 *
 * 复用来自 common.php 的：zx_config / zx_config_get / zx_db / zx_absolute_url /
 * zx_now / zx_setting / zx_setting_int。业务 SQL 一律走 PDO 预编译。
 *
 * ## 为什么这里也要先 require guard.php
 *
 * `v1/` 下每个接口的第一行都是 `guard.php`，但后台这边原先漏了 —— 于是 PHP 7 的机器上
 * 接口能回一句「PHP 版本过低」的人话，后台却因为 common.php 里的 `match` / `mixed`
 * 直接 parse error，只留一片**空白页**。同一台机器上两种表现，最容易被误判成
 * 「后台文件传丢了」。guard.php 吐的是 JSON 而不是 HTML，在后台页面上看着是有点怪，
 * 但那是「一眼看得出是版本问题」和「一片空白」之间的取舍。
 */

require_once __DIR__ . '/../guard.php';
require_once __DIR__ . '/../common.php';

// ---- 通用小工具 ------------------------------------------------------------

/** HTML 转义。参数收 mixed，省得调用点到处都是 (string) 强转。 */
function e(mixed $value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES, 'UTF-8');
}

function admin_is_post(): bool
{
    return strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET')) === 'POST';
}

/** 当前脚本文件名，用来高亮导航。 */
function admin_current_script(): string
{
    return basename((string) ($_SERVER['SCRIPT_NAME'] ?? ''));
}

function admin_redirect(string $path): void
{
    if (!headers_sent()) {
        header('Location: ' . $path);
    }
    exit;
}

/** 一次性提示，跨一次重定向存活。 */
function admin_flash(string $message, string $type = 'ok'): void
{
    $_SESSION['flash'] = ['message' => $message, 'type' => $type];
}

/** @return array{message:string,type:string}|null */
function admin_take_flash(): ?array
{
    $flash = $_SESSION['flash'] ?? null;
    unset($_SESSION['flash']);

    return is_array($flash) ? $flash : null;
}

// ---- 错误渲染 --------------------------------------------------------------

/**
 * 渲染 HTML 错误页并结束。刻意用最简、内联的 HTML：这样即使是在「页面已经输出了一半」
 * 或者样式表根本没加载出来的情况下，也一定能看到一句人话。
 */
function admin_error(string $message, int $status = 500): void
{
    if (!headers_sent()) {
        header('Content-Type: text/html; charset=utf-8');
        http_response_code($status);
    }

    echo '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
        . '<meta name="viewport" content="width=device-width, initial-scale=1">'
        . '<title>出错了</title></head>'
        . '<body style="font-family:system-ui,-apple-system,\'Segoe UI\',sans-serif;'
        . 'max-width:40rem;margin:4rem auto;padding:0 1.25rem;color:#1c1f24;line-height:1.6">'
        . '<h1 style="font-size:1.25rem">出错了</h1>'
        . '<p style="background:#fff4f2;border:1px solid #f3c2bd;padding:.75rem 1rem;border-radius:6px">'
        . e($message) . '</p>'
        . '<p><a href="index.php" style="color:#1f6feb">返回设备列表</a></p>'
        . '</body></html>';
    exit;
}

// ---- 上传目录 --------------------------------------------------------------

/** 上传根目录（php/uploads），与 .gitignore 里忽略的路径一致。 */
function admin_upload_base(): string
{
    return dirname(__DIR__) . '/uploads';
}

/**
 * 按需创建上传子目录并确认可写。权限用 0755 —— 不要 0777，上传目录里放的是将来要
 * 由 Web 服务器直接吐出去的文件，绝不能是「任何人可执行」。
 */
function admin_ensure_upload_dir(string $sub): string
{
    $base = admin_upload_base();
    $dir  = $base . '/' . $sub;

    if (!is_dir($dir)) {
        if (!@mkdir($dir, 0755, true) && !is_dir($dir)) {
            admin_error('上传目录创建失败：' . $dir . '。请检查 php/uploads 的权限。');
        }
    }
    if (!is_writable($dir)) {
        admin_error('上传目录不可写：' . $dir . '。请让 Web 用户对该目录有写权限。');
    }

    return $dir;
}

/**
 * 删除一个「我们自己上传的」文件。外链、以及不在 uploads/ 下的路径一律不动 ——
 * 免得把运营手填的外部地址对应的东西也当成自己的删了。
 */
function admin_delete_uploaded_file(?string $relative): void
{
    if ($relative === null || $relative === '') {
        return;
    }
    // 只碰 uploads/ 下的相对路径，并挡掉目录穿越。
    if (!str_starts_with($relative, 'uploads/') || str_contains($relative, '..')) {
        return;
    }

    $full = dirname(__DIR__) . '/' . $relative;
    if (is_file($full)) {
        @unlink($full);
    }
}

/**
 * 取 $_FILES 里的一项；没传文件时返回 null。
 *
 * @return array{name?:string,type?:string,tmp_name?:string,error?:int,size?:int}|null
 */
function admin_file_entry(string $field): ?array
{
    $entry = $_FILES[$field] ?? null;
    if (!is_array($entry) || !array_key_exists('error', $entry)) {
        return null;
    }

    return $entry;
}

/** 上传错误码 → 中文提示。NO_FILE 由调用方单独判（它常常是「没传」而不是「传错」）。 */
function admin_upload_error_message(int $code): string
{
    return match ($code) {
        UPLOAD_ERR_INI_SIZE,
        UPLOAD_ERR_FORM_SIZE => '文件超过服务器允许的上传大小（upload_max_filesize / post_max_size 太小）。'
            . '大 APK 需要同时调大这两个 PHP 配置和 nginx 的 client_max_body_size。',
        UPLOAD_ERR_PARTIAL   => '文件只上传了一部分，请重试。',
        UPLOAD_ERR_NO_FILE   => '没有选择文件。',
        UPLOAD_ERR_NO_TMP_DIR => '服务器缺少临时目录，请联系运维。',
        UPLOAD_ERR_CANT_WRITE => '服务器写入临时文件失败，请检查磁盘空间。',
        UPLOAD_ERR_EXTENSION  => '上传被某个 PHP 扩展中止。',
        default               => '上传失败（错误码 ' . $code . '）。',
    };
}

/** 生成一个随机文件名（不含目录），扩展名由调用方按内容决定，绝不沿用用户原名。 */
function admin_random_filename(string $extension): string
{
    return bin2hex(random_bytes(16)) . '.' . ltrim($extension, '.');
}

/**
 * 把「不是合法 UTF-8」的输入变成合法的。
 *
 * 为什么需要：表单字段是原样进 `$_POST` 的，谁都能往 `title` 里塞一段二进制。后面所有
 * 「按字符截断」的假设都建立在合法 UTF-8 上，一旦不成立，截断点算不准，非法字节还会被
 * 直接送进 MySQL —— 严格模式下那是 1366/1406 报错，后台表现为「服务器内部错误」。
 *
 * 处理方式是**只保留 ASCII 可见字符**（含制表/换行）。内容会残，但这是个**已经坏了**的
 * 输入而不是正常路径，后台的操作者能立刻看出自己的内容丢了，比一个 500 强。
 */
function admin_utf8_sanitize(string $value): string
{
    if (preg_match('//u', $value) === 1) {
        return $value;
    }

    return (string) preg_replace('/[^\x09\x0A\x0D\x20-\x7E]/', '', $value);
}

/**
 * 丢掉尾部残缺的多字节序列。**最多退 3 个字节**：合法 UTF-8 的最长序列是 4 字节，
 * 任何前缀最多只可能在最后 3 个字节处被劈开（调用方需先用 admin_utf8_sanitize 保证
 * 输入合法，否则坏的字节可能不在尾部）。
 *
 * 这里有个真实的坑，上一版就是这么写的：只删尾部的**续字节**不够。`中` 是 E4 B8 AD，
 * 按字节截到 2 个字节得到 `E4 B8`，删掉续字节 B8 之后剩下一个**孤立的前导字节 E4** ——
 * 那仍然是非法 UTF-8，读起来却像是「已经处理过了」。
 */
function admin_trim_partial_tail(string $cut): string
{
    while ($cut !== '' && preg_match('//u', $cut) !== 1) {
        $cut = substr($cut, 0, -1);
    }

    return $cut;
}

/**
 * 按「字符」截断到指定长度，用于 `VARCHAR(n)` 列（utf8mb4 的列宽算的是**字符数**，
 * 直接用 substr 会把一个汉字劈成两半）。优先用 mbstring；没有时退回字节截断
 * （更保守：对中文最多只剩 1/3 长度，但绝不会截出非法 UTF-8）。
 */
function admin_clip(string $value, int $maxChars): string
{
    if ($maxChars <= 0) {
        return '';
    }

    $value = admin_utf8_sanitize($value);

    if (function_exists('mb_substr')) {
        return mb_substr($value, 0, $maxChars, 'UTF-8');
    }
    if (strlen($value) <= $maxChars) {
        return $value;
    }

    return admin_trim_partial_tail(substr($value, 0, $maxChars));
}

/**
 * 按「字节」截断到指定长度，用于 `TEXT` 列。
 *
 * 为什么不能拿 admin_clip 凑合：`TEXT` 的上限 65535 是**字节**，不是字符。`release_notes`
 * 整篇汉字时 65535 字符 ≈ 196KB，远超列宽 —— 按字符截完照样被 MySQL 以 1406 拒收。
 */
function admin_clip_bytes(string $value, int $maxBytes): string
{
    if ($maxBytes <= 0) {
        return '';
    }

    $value = admin_utf8_sanitize($value);

    if (strlen($value) <= $maxBytes) {
        return $value;
    }
    if (function_exists('mb_strcut')) {
        return mb_strcut($value, 0, $maxBytes, 'UTF-8');
    }

    return admin_trim_partial_tail(substr($value, 0, $maxBytes));
}

// ---- 数值/时间格式化 -------------------------------------------------------

function admin_human_bytes(int $bytes): string
{
    if ($bytes <= 0) {
        return '0 B';
    }
    $units = ['B', 'KB', 'MB', 'GB', 'TB'];
    $power = (int) floor(log($bytes, 1024));
    $power = max(0, min($power, count($units) - 1));
    $value = $bytes / (1024 ** $power);

    return ($power === 0 ? (string) $bytes : number_format($value, 1)) . ' ' . $units[$power];
}

/** 「x 分钟前」这类相对时间。传入的是 'Y-m-d H:i:s'（服务器本地时区，与 zx_now 一致）。 */
function admin_relative_time(?string $datetime): string
{
    if ($datetime === null || $datetime === '') {
        return '—';
    }
    $ts = strtotime($datetime);
    if ($ts === false) {
        return '—';
    }
    $diff = time() - $ts;
    if ($diff < 0) {
        return '刚刚';
    }
    if ($diff < 60) {
        return $diff . ' 秒前';
    }
    if ($diff < 3600) {
        return intdiv($diff, 60) . ' 分钟前';
    }
    if ($diff < 86400) {
        return intdiv($diff, 3600) . ' 小时前';
    }

    return intdiv($diff, 86400) . ' 天前';
}

/**
 * 解析表单里的时间：留空 → null；接受 datetime-local（'Y-m-dTH:i'）和完整秒级字符串。
 * 不合法时返回 null 并让调用方按「留空」处理 —— 运营手抖输错不该让整页挂掉。
 */
function admin_parse_datetime(mixed $raw): ?string
{
    if (!is_string($raw)) {
        return null;
    }
    $value = str_replace('T', ' ', trim($raw));
    if ($value === '') {
        return null;
    }
    if (preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/', $value) === 1) {
        $value .= ':00';
    }
    if (preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/', $value) !== 1) {
        return null;
    }
    $ts = strtotime($value);
    if ($ts === false) {
        return null;
    }

    return date('Y-m-d H:i:s', $ts);
}

/** 数据库时间 → datetime-local 输入框的 value。 */
function admin_datetime_input_value(?string $datetime): string
{
    if ($datetime === null || $datetime === '') {
        return '';
    }
    $ts = strtotime($datetime);
    if ($ts === false) {
        return '';
    }

    return date('Y-m-d\TH:i', $ts);
}

// ---- 登录 / 会话 -----------------------------------------------------------

function admin_is_logged_in(): bool
{
    if (($_SESSION['admin_authed'] ?? false) !== true) {
        return false;
    }
    $ttl  = max(60, (int) zx_config_get('admin.session_ttl', 7200));
    $last = (int) ($_SESSION['admin_last_activity'] ?? 0);
    if ($last <= 0 || (time() - $last) > $ttl) {
        return false;
    }

    return true;
}

/** 除 login.php 外的每个后台页面开头都要调一次。 */
function admin_require_login(): void
{
    if (!admin_is_logged_in()) {
        // 过期或被清掉都回登录页；顺便把残留的会话数据清干净。
        $_SESSION = [];
        admin_redirect('login.php');
    }
    // 有活动就续期，空闲超过 session_ttl 才算掉线。
    $_SESSION['admin_last_activity'] = time();
}

/** 校验密码。哈希只存在于 config.php 的 admin.password_hash，代码里不落任何明文。 */
function admin_verify_password(string $plain): bool
{
    $hash = (string) zx_config_get('admin.password_hash', '');
    if ($hash === '') {
        return false;
    }

    return password_verify($plain, $hash);
}

/** 登录成功后的会话固定：换一个 session id，并重置活动时间与 CSRF。 */
function admin_login_user(): void
{
    session_regenerate_id(true);
    $_SESSION['admin_authed']        = true;
    $_SESSION['admin_last_activity'] = time();
    $_SESSION['csrf_token']          = bin2hex(random_bytes(32));
}

function admin_logout_user(): void
{
    $_SESSION = [];
    if (ini_get('session.use_cookies') === '1') {
        $params = session_get_cookie_params();
        setcookie(
            session_name(),
            '',
            [
                'expires'  => time() - 42000,
                'path'     => $params['path'],
                'domain'   => $params['domain'],
                'secure'   => $params['secure'],
                'httponly' => $params['httponly'],
                'samesite' => $params['samesite'] ?? 'Lax',
            ],
        );
    }
    session_destroy();
}

// ---- CSRF ------------------------------------------------------------------

function admin_csrf_token(): string
{
    if (!isset($_SESSION['csrf_token']) || !is_string($_SESSION['csrf_token']) || $_SESSION['csrf_token'] === '') {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }

    return $_SESSION['csrf_token'];
}

/** 所有会改数据的 POST 都必须先过这里。 */
function admin_require_csrf(): void
{
    $token = $_POST['csrf'] ?? '';
    if (!is_string($token) || $token === '' || !hash_equals(admin_csrf_token(), $token)) {
        admin_error('请求校验失败（CSRF 令牌不匹配），请返回上一页重新提交。', 400);
    }
}

// ---- 布局 ------------------------------------------------------------------

/** @param string $active 导航高亮键：devices / splash / versions 之一，留空则不亮。 */
function admin_page_header(string $title, string $active = ''): void
{
    if ($active === '') {
        // 默认按当前脚本推断，省得每个页面都手填。
        $active = match (admin_current_script()) {
            'splash_list.php', 'splash_edit.php'     => 'splash',
            'version_list.php', 'version_edit.php'   => 'versions',
            default                                  => 'devices',
        };
    }

    $navItems = [
        'devices'  => ['index.php', '设备'],
        'splash'   => ['splash_list.php', '开屏素材'],
        'versions' => ['version_list.php', '版本发布'],
    ];
    ?>
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= e($title) ?> · 小智 Android 后台</title>
<style>
  :root {
    --bg: #f4f5f3;
    --panel: #ffffff;
    --ink: #1b1e23;
    --muted: #6b7280;
    --line: #e2e5ea;
    --accent: #1f6feb;
    --ok: #1a7f4b;
    --warn: #9a6700;
    --danger: #b42318;
    --mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--ink);
    font: 14px/1.5 system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  }
  header.topbar {
    background: #14171c;
    color: #eef1f5;
    padding: 0 1.25rem;
    display: flex;
    align-items: center;
    gap: 1.5rem;
    min-height: 52px;
  }
  header.topbar .brand { font-weight: 650; letter-spacing: .02em; }
  header.topbar .brand small { color: #8b93a1; font-weight: 400; margin-left: .5rem; }
  nav.topnav { display: flex; gap: .25rem; }
  nav.topnav a {
    color: #c6ccd6;
    text-decoration: none;
    padding: .45rem .8rem;
    border-radius: 6px;
  }
  nav.topnav a:hover { background: #232830; color: #fff; }
  nav.topnav a.active { background: #2b313b; color: #fff; }
  header.topbar .spacer { flex: 1; }
  header.topbar a.logout { color: #c6ccd6; text-decoration: none; padding: .45rem .8rem; }
  header.topbar a.logout:hover { color: #fff; }
  main { max-width: 1240px; margin: 0 auto; padding: 1.25rem; }
  h1.page { font-size: 1.15rem; margin: 0 0 1rem; }
  h1.page .hint { font-size: .8rem; color: var(--muted); font-weight: 400; margin-left: .5rem; }
  .panel {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 8px;
    padding: 1rem 1.25rem;
    margin-bottom: 1rem;
  }
  .flash { padding: .65rem 1rem; border-radius: 6px; margin-bottom: 1rem; border: 1px solid; }
  .flash.ok { background: #eefaf1; border-color: #b7e2c5; color: #14532d; }
  .flash.err { background: #fdecea; border-color: #f3c2bd; color: #78200f; }
  .alarm { display: flex; gap: 2rem; align-items: baseline; flex-wrap: wrap; }
  .alarm .stat { display: flex; flex-direction: column; }
  .alarm .stat b { font-size: 1.6rem; font-variant-numeric: tabular-nums; }
  .alarm .stat span { color: var(--muted); font-size: .8rem; }
  .alarm.alert { border-color: #f0b4ae; background: #fff5f4; }
  table { width: 100%; border-collapse: collapse; font-size: .86rem; }
  th, td { text-align: left; padding: .5rem .55rem; border-bottom: 1px solid var(--line); vertical-align: top; }
  th { color: var(--muted); font-weight: 600; white-space: nowrap; background: #fafbfa; }
  tbody tr:hover { background: #f8fafc; }
  td.mono, .mono { font-family: var(--mono); font-size: .82rem; }
  .badge { display: inline-block; padding: .1rem .5rem; border-radius: 999px; font-size: .76rem; border: 1px solid; }
  .badge.on { color: var(--ok); border-color: #b7e2c5; background: #eefaf1; }
  .badge.off { color: var(--muted); border-color: var(--line); background: #f3f4f6; }
  .badge.warn { color: var(--warn); border-color: #f0dcae; background: #fdf6e3; }
  a { color: var(--accent); }
  .btn {
    display: inline-block; padding: .3rem .6rem; border-radius: 6px; border: 1px solid var(--line);
    background: #fff; color: var(--ink); cursor: pointer; font-size: .82rem; text-decoration: none;
  }
  .btn:hover { background: #f3f4f6; }
  .btn.primary { background: var(--accent); border-color: var(--accent); color: #fff; }
  .btn.primary:hover { background: #1a5fd0; }
  .btn.danger { color: var(--danger); border-color: #f3c2bd; }
  .btn.danger:hover { background: #fdecea; }
  form.inline { display: inline; }
  .toolbar { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: 1rem; }
  .pager { display: flex; gap: .75rem; align-items: center; margin-top: 1rem; color: var(--muted); font-size: .85rem; }
  label.field, .field { display: block; margin-bottom: .9rem; }
  label.field > span, .field > span { display: block; font-size: .82rem; color: var(--muted); margin-bottom: .25rem; }
  label.field > small, .field > small { display: block; margin-top: .25rem; font-size: .78rem; color: var(--muted); line-height: 1.45; }
  input[type=text], input[type=number], input[type=password], input[type=datetime-local], select, textarea {
    width: 100%; padding: .45rem .55rem; border: 1px solid var(--line); border-radius: 6px;
    font: inherit; background: #fff; color: var(--ink);
  }
  textarea { min-height: 6rem; resize: vertical; }
  .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1.25rem; }
  .form-actions { margin-top: .5rem; display: flex; gap: .6rem; align-items: center; }
  .note { font-size: .82rem; color: var(--muted); }
  .warnbox { background: #fdf6e3; border: 1px solid #f0dcae; color: #7a5200; padding: .6rem .8rem; border-radius: 6px; font-size: .84rem; margin-bottom: 1rem; }
  img.thumb { width: 96px; height: 54px; object-fit: cover; border: 1px solid var(--line); border-radius: 4px; background: #f0f0f0; }
  .empty { color: var(--muted); padding: 1.5rem 0; text-align: center; }
</style>
</head>
<body>
<header class="topbar">
  <div class="brand">小智 Android 后台<small>xiaozhi_android_client</small></div>
  <nav class="topnav">
    <?php foreach ($navItems as $key => [$href, $label]): ?>
      <a href="<?= e($href) ?>" class="<?= $active === $key ? 'active' : '' ?>"><?= e($label) ?></a>
    <?php endforeach; ?>
  </nav>
  <div class="spacer"></div>
  <a class="logout" href="logout.php">退出</a>
</header>
<main>
    <?php
    $flash = admin_take_flash();
    if ($flash !== null) {
        $cls = ($flash['type'] === 'err') ? 'err' : 'ok';
        echo '<div class="flash ' . $cls . '">' . e($flash['message']) . '</div>';
    }
}

function admin_page_footer(): void
{
    ?>
</main>
<footer style="max-width:1240px;margin:0 auto;padding:1rem 1.25rem 2rem;color:#8b93a1;font-size:.78rem">
  小智 Android 定制版 · 自建后台。接口口径见 php/common.php 顶部说明。
</footer>
<script>
// 删除确认统一走 data-confirm，**不要**再写成 onsubmit="return confirm('…<?= e($x) ?>…')"。
//
// 原因：`e()` 是 htmlspecialchars，它保证的是「在 HTML 里安全」。而那段数据落在
// **HTML 属性内部的 JS 字符串**里 —— 浏览器会先做实体解码，`&#039;` 还原成裸的 `'`，
// 于是存成 `');fetch('/admin/index.php')//` 这样的版本名，点删除就会执行。
// htmlspecialchars 在这个上下文里不够用，只有「别把数据往内联 JS 里放」才够用。
document.addEventListener('submit', function (event) {
  var form = event.target;
  var message = form && form.getAttribute ? form.getAttribute('data-confirm') : null;
  if (message && !window.confirm(message)) {
    event.preventDefault();
  }
});
</script>
</body>
</html>
    <?php
}

// ---- 接管错误处理（必须早于会话引导和任何页面逻辑） ------------------------

set_error_handler(static function (int $severity, string $message, string $file = '', int $line = 0): bool {
    // 尊重 @ 抑制符和当前的 error_reporting。
    if ((error_reporting() & $severity) === 0) {
        return false;
    }
    throw new ErrorException($message, 0, $severity, $file, $line);
});

set_exception_handler(static function (Throwable $e): void {
    error_log('[xiaozhi-admin] ' . get_class($e) . ': ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
    $debug = (bool) zx_config_get('debug', false);
    $msg = $debug
        ? $e->getMessage() . ' @ ' . basename($e->getFile()) . ':' . $e->getLine()
        : '服务器内部错误，详情见服务器错误日志。';
    admin_error($msg, 500);
});

// 追加一个 HTML 版致命错误处理器。见文件顶部说明：common.php 的关机函数无法注销，
// 且注册在先、会先跑。因此这里的策略是「不跟它抢」：
//   - 若响应头还没发出去 → 我们自己渲染 HTML 错误页（覆盖不了 common.php 已先输出的情形，
//     但它一旦输出过，headers_sent 就为真，会落到下面一支）；
//   - 若响应头已经发出去 → 只记日志，绝不追加内容。否则会往 common.php 已经吐出的 JSON
//     信封后面粘一段 HTML，把响应彻底弄坏。
register_shutdown_function(static function (): void {
    $error = error_get_last();
    if ($error === null || !in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR], true)) {
        return;
    }
    if (headers_sent()) {
        error_log('[xiaozhi-admin] fatal(after output): ' . $error['message'] . ' @ ' . $error['file'] . ':' . $error['line']);
        return;
    }

    error_log('[xiaozhi-admin] fatal: ' . $error['message'] . ' @ ' . $error['file'] . ':' . $error['line']);
    admin_error('服务器内部错误，详情见服务器错误日志。', 500);
});

// ---- 会话引导 --------------------------------------------------------------

// 反代终止 TLS 时靠 X-Forwarded-Proto 判断，避免把 secure 关掉导致 Cookie 明文回落。
$adminIsSecure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || (strtolower((string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '')) === 'https');

session_set_cookie_params([
    'lifetime' => 0,
    'path'     => '/',
    'domain'   => '',
    'secure'   => $adminIsSecure,   // 只有真走 HTTPS 才标 secure，否则本地 http 调试会丢 Cookie
    'httponly' => true,
    'samesite' => 'Lax',
]);
session_name('ZXADMINSESSID');
session_start();
unset($adminIsSecure);
