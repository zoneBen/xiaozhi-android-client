<?php

declare(strict_types=1);

/**
 * 后台登录页。
 *
 * 密码哈希只存在于 config.php 的 admin.password_hash，用 password_verify 校验；
 * 代码里不出现任何明文口令（遵循「无硬编码密钥」）。
 *
 * ## 防爆破为什么必须落在服务端
 *
 * 原先的写法把失败次数放 `$_SESSION` 里。那是**一点用都没有的**：会话是请求方自己带
 * 的 Cookie，攻击脚本第一发就不带 Cookie、或者每发换一个，`session_start()` 每次开出
 * 一个干净的新会话，计数永远从 0 开始，锁定分支一次都进不去。
 * 所以计数落在 `xiaozhi_android_client_admin_login` 表里，按 IP 记。
 *
 * ## 限速用 REMOTE_ADDR，不用 X-Forwarded-For
 *
 * `zx_client_ip()` 会优先采信 XFF（那是给「后台显示来源 IP」用的，可伪造、可接受）。
 * 但限速的 key 绝不能用它 —— 一个能伪造来源头的攻击者同时也就能给自己的每次尝试
 * 换一个 key，等于把闸门交给对手。所以这里另取 REMOTE_ADDR：
 *
 *   - 直连（本部署的默认形态，nginx 就监听在 :300）→ REMOTE_ADDR 就是真实客户端
 *   - 若前面真挂了反代，把反代地址填进 config.php 的 `admin.trusted_proxies`，
 *     这时才采信 XFF 的第一跳（见 `login_client_ip()`）
 *
 * 不填 trusted_proxies 而在反代后面跑，后果是**所有访客共享反代那一个 IP**：
 * 攻击者失败 5 次会把真实管理员一起锁在门外 15 分钟。宁可这样，也不要一个能绕过的限速 ——
 * 被锁的人看到的是「失败次数过多，请等待 N 秒」，而绕过是无声的。
 */

require_once __DIR__ . '/lib.php';

/** 连续失败多少次开始锁。 */
const LOGIN_MAX_FAILS = 5;
/** 锁多久（秒）。 */
const LOGIN_LOCK_SECONDS = 900;
/** 两次失败间隔超过这个时间就重新计数（秒）。与锁定时长一致：锁自然到期后重新给 5 次机会。 */
const LOGIN_WINDOW_SECONDS = 900;

/**
 * 限速用的客户端标识。
 *
 * 只信 REMOTE_ADDR；仅当 REMOTE_ADDR 命中 `admin.trusted_proxies` 白名单时才改用
 * XFF 的第一跳。`trusted_proxies` 支持精确 IP 与前缀写法（`10.0.0.` / `fd00::`）。
 */
function login_client_ip(): string
{
    $remote = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
    if ($remote === '') {
        // 拿不到就归到一个公共桶里：宁可所有人共享限速，也不要每个人都成为新 key。
        return 'unknown';
    }

    foreach ((array) zx_config_get('admin.trusted_proxies', []) as $trusted) {
        $trusted = trim((string) $trusted);
        if ($trusted === '') {
            continue;
        }
        // 支持「前缀」写法，够用且不必引 CIDR 解析库；填完整 IP 时就是精确匹配。
        if ($remote === $trusted || str_starts_with($remote, $trusted)) {
            $forwarded = trim(explode(',', (string) ($_SERVER['HTTP_X_FORWARDED_FOR'] ?? ''))[0]);
            if ($forwarded !== '' && filter_var($forwarded, FILTER_VALIDATE_IP) !== false) {
                return substr($forwarded, 0, 45);
            }
            break;
        }
    }

    return substr($remote, 0, 45);
}

/**
 * 记一次失败。**时间一律用 MySQL 的 NOW() 算**，不掺 PHP 的 time()：
 * 两个时钟一旦（哪怕只因为时区）不一致，锁定窗口就会算错，而这种错很难在测试里发现。
 *
 * 距离上次失败超过窗口就重新计数（`fail_count = 1`），否则累加。
 */
function login_record_failure(string $ip): void
{
    $sql = 'INSERT INTO `xiaozhi_android_client_admin_login` (`ip`, `fail_count`, `last_fail_at`)
            VALUES (?, 1, NOW())
            ON DUPLICATE KEY UPDATE
              `fail_count`  = IF(`last_fail_at` < NOW() - INTERVAL ' . LOGIN_WINDOW_SECONDS . ' SECOND, 1, `fail_count` + 1),
              `last_fail_at` = NOW()';

    try {
        zx_db()->prepare($sql)->execute([$ip]);
    } catch (Throwable $e) {
        // 记不上就算了：挡不住的是「少计一次」，而不是放行 —— 下面读不到状态同样会锁。
        error_log('[xiaozhi-admin] 登录失败计数写入失败：' . $e->getMessage());
    }
}

/** 还在锁定中则返回剩余秒数，否则返回 0。 */
function login_lock_remaining(string $ip): int
{
    $stmt = zx_db()->prepare(
        'SELECT IF(`fail_count` >= ' . LOGIN_MAX_FAILS . ',
                    GREATEST(0, ' . LOGIN_LOCK_SECONDS . ' - TIMESTAMPDIFF(SECOND, `last_fail_at`, NOW())),
                    0)
           FROM `xiaozhi_android_client_admin_login` WHERE `ip` = ? LIMIT 1'
    );
    $stmt->execute([$ip]);

    return max(0, (int) $stmt->fetchColumn());
}

/** 登录成功就清掉这个 IP 的失败记录。 */
function login_clear_failures(string $ip): void
{
    try {
        zx_db()->prepare('DELETE FROM `xiaozhi_android_client_admin_login` WHERE `ip` = ?')->execute([$ip]);
    } catch (Throwable $e) {
        error_log('[xiaozhi-admin] 登录失败计数清理失败：' . $e->getMessage());
    }
}

/** 当前窗口内的累计失败次数，只用于给用户一句准确的提示。 */
function login_fail_count(string $ip): int
{
    $stmt = zx_db()->prepare(
        'SELECT `fail_count` FROM `xiaozhi_android_client_admin_login` WHERE `ip` = ? LIMIT 1'
    );
    $stmt->execute([$ip]);

    return max(0, (int) $stmt->fetchColumn());
}

// 已经登录就没什么好输的了。
if (admin_is_logged_in()) {
    admin_redirect('index.php');
}

$error = '';

if (admin_is_post()) {
    admin_require_csrf();

    $ip       = login_client_ip();
    $lockLeft = login_lock_remaining($ip);

    if ($lockLeft > 0) {
        // 锁定期间**连正确的密码也不放行**：否则攻击者只要在锁定期里正好猜中，
        // 那次尝试就会被当成「合法登录」而不是「绕过限速」。
        $error = '失败次数过多，请等待 ' . $lockLeft . ' 秒后再试。';
    } else {
        $password = is_string($_POST['password'] ?? null) ? (string) $_POST['password'] : '';

        if ($password !== '' && admin_verify_password($password)) {
            login_clear_failures($ip);
            admin_login_user();
            admin_redirect('index.php');
        }

        login_record_failure($ip);
        $fails = login_fail_count($ip);

        $error = ($fails >= LOGIN_MAX_FAILS)
            ? '连续失败 ' . LOGIN_MAX_FAILS . ' 次，请等待 ' . intdiv(LOGIN_LOCK_SECONDS, 60) . ' 分钟后再试。'
            : '密码不正确（已失败 ' . $fails . ' 次）。';
    }
}

$csrf = admin_csrf_token();
?>
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>登录 · 小智 Android 后台</title>
<style>
  :root { --ink:#1b1e23; --muted:#6b7280; --line:#e2e5ea; --accent:#1f6feb; --danger:#b42318; }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
    background: #14171c; color: var(--ink);
    font: 14px/1.5 system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  }
  .card { background: #fff; border-radius: 10px; padding: 2rem; width: 22rem; box-shadow: 0 12px 40px rgba(0,0,0,.35); }
  .card h1 { font-size: 1.15rem; margin: 0 0 .25rem; }
  .card p.sub { color: var(--muted); margin: 0 0 1.25rem; font-size: .84rem; }
  label { display: block; margin-bottom: .9rem; }
  label span { display: block; font-size: .82rem; color: var(--muted); margin-bottom: .25rem; }
  input[type=password] {
    width: 100%; padding: .55rem .6rem; border: 1px solid var(--line); border-radius: 6px; font: inherit;
  }
  button {
    width: 100%; padding: .6rem; border: 0; border-radius: 6px; background: var(--accent); color: #fff;
    font: inherit; font-weight: 600; cursor: pointer;
  }
  button:hover { background: #1a5fd0; }
  .err { background: #fdecea; border: 1px solid #f3c2bd; color: var(--danger); padding: .55rem .7rem; border-radius: 6px; margin-bottom: 1rem; font-size: .84rem; }
</style>
</head>
<body>
  <form class="card" method="post" action="login.php" autocomplete="off">
    <h1>小智 Android 后台</h1>
    <p class="sub">请输入管理密码</p>
    <?php if ($error !== ''): ?>
      <div class="err"><?= e($error) ?></div>
    <?php endif; ?>
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <label>
      <span>密码</span>
      <input type="password" name="password" maxlength="200" autofocus required>
    </label>
    <button type="submit">登录</button>
  </form>
</body>
</html>
