<?php

declare(strict_types=1);

/**
 * 三个接口的公共库：配置、PDO、统一响应信封、签名校验。
 *
 * 约定（三个接口必须一致，客户端按同样口径实现）：
 *
 *   响应：{"code":0,"message":"ok","serverTime":1760246400,"data":{...}}
 *         code 非 0 即错误：400 参数 / 401 签名 / 403 停用 / 404 / 500
 *
 *   签名：请求头 X-App-Key、X-Timestamp（秒）、X-Sign
 *         X-Sign = hash_hmac('sha256', "$method\n$path\n$appKey\n$ts\n" . sha256($rawBody), $appSecret)
 *         $path 取 URI 的路径部分（不含 query），如 /apps/xiaozhi_android_client/v1/heartbeat.php
 *
 * 这里有意的取舍：三个接口分别是「幂等写 / 只读 / 只读」，重放没有实际收益，
 * 所以只用时间窗防重放，不建 nonce 表。
 */

// ---- 错误码 ----------------------------------------------------------------

const ZX_CODE_OK           = 0;
const ZX_CODE_BAD_REQUEST  = 400;
const ZX_CODE_UNAUTHORIZED = 401;
const ZX_CODE_FORBIDDEN    = 403;
const ZX_CODE_NOT_FOUND    = 404;
const ZX_CODE_SERVER_ERROR = 500;

// ---- 配置 ------------------------------------------------------------------

/**
 * 读取 config.php，只在进程内解析一次。
 *
 * @return array<string,mixed>
 */
function zx_config(): array
{
    static $config = null;
    if ($config !== null) {
        return $config;
    }

    $path = __DIR__ . '/config.php';
    if (!is_file($path)) {
        // 配置缺失是部署问题，而且此时还没有任何东西可以回给客户端 ——
        // 但要保证回的是 JSON 而不是一个 PHP 的空白页，否则客户端拿到的是
        // 解析失败的 body，排查方向会被带偏。
        header('Content-Type: application/json; charset=utf-8', true, 500);
        echo json_encode([
            'code'    => ZX_CODE_SERVER_ERROR,
            'message' => '服务端未配置：缺少 config.php（请从 config.example.php 复制）',
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $config = require $path;
    if (!is_array($config)) {
        $config = [];
    }

    return $config;
}

/** 取配置项，支持 'db.host' 这样的点号路径。 */
function zx_config_get(string $key, mixed $default = null): mixed
{
    $value = zx_config();
    foreach (explode('.', $key) as $segment) {
        if (!is_array($value) || !array_key_exists($segment, $value)) {
            return $default;
        }
        $value = $value[$segment];
    }

    return $value;
}

// ---- 数据库 ----------------------------------------------------------------

/** PDO 单例。全部走预编译语句。 */
function zx_db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $dsn = sprintf(
        'mysql:host=%s;port=%d;dbname=%s;charset=%s',
        (string) zx_config_get('db.host', '127.0.0.1'),
        (int) zx_config_get('db.port', 3306),
        (string) zx_config_get('db.name', ''),
        (string) zx_config_get('db.charset', 'utf8mb4'),
    );

    $pdo = new PDO(
        $dsn,
        (string) zx_config_get('db.user', ''),
        (string) zx_config_get('db.password', ''),
        [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            // 关掉模拟预编译，让参数真正在服务端绑定 —— 整数参数才会以整数类型下去，
            // 否则 MySQL 会把 '15' 和 15 在某些比较里区别对待。
            PDO::ATTR_EMULATE_PREPARES   => false,
        ],
    );

    return $pdo;
}

/** 从 xiaozhi_android_client_config 读运行时配置。 */
function zx_setting(string $key, ?string $default = null): ?string
{
    static $cache = [];
    if (array_key_exists($key, $cache)) {
        return $cache[$key];
    }

    try {
        $stmt = zx_db()->prepare('SELECT `cfg_value` FROM `xiaozhi_android_client_config` WHERE `cfg_key` = ? LIMIT 1');
        $stmt->execute([$key]);
        $value = $stmt->fetchColumn();
        $cache[$key] = ($value === false) ? $default : (string) $value;
    } catch (Throwable) {
        // 配置表读不到不该让整个接口挂掉 —— 降级到调用方给的默认值。
        $cache[$key] = $default;
    }

    return $cache[$key];
}

function zx_setting_int(string $key, int $default): int
{
    $raw = zx_setting($key, null);

    return ($raw === null || $raw === '' || !is_numeric($raw)) ? $default : (int) $raw;
}

// ---- 响应 ------------------------------------------------------------------

/**
 * 输出统一信封并结束请求。
 *
 * @param array<string,mixed>|null $data  data 字段。传 null 则整个 `data` 键不出现 ——
 *                                        upgrade.php 需要这个：它的响应必须同时是
 *                                        「统一信封」和「version.json 的形状」，
 *                                        多一个空的 data 只会让人以为漏了什么。
 * @param array<string,mixed>      $extra 需要平铺在顶层的额外字段。目前只有 upgrade.php 用：
 *                                        它把 versionCode / downloadUrl 这些平铺出来，
 *                                        客户端 AppUpdateManager 里那几行
 *                                        json.optInt("versionCode") 才能一行都不用改。
 */
function zx_json_out(int $code, string $message, ?array $data = [], array $extra = []): void
{
    if (!headers_sent()) {
        header('Content-Type: application/json; charset=utf-8');
        // 接口数据都是实时的，别让中间层缓存。
        header('Cache-Control: no-store, no-cache, must-revalidate');
        http_response_code($code === ZX_CODE_OK ? 200 : zx_http_status_for($code));
    }

    $payload = array_merge(
        ['code' => $code, 'message' => $message, 'serverTime' => time()],
        $extra,
    );
    if ($data !== null) {
        $payload['data'] = $data;
    }

    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function zx_ok(array $data = [], array $extra = []): void
{
    zx_json_out(ZX_CODE_OK, 'ok', $data, $extra);
}

function zx_fail(int $code, string $message): void
{
    zx_json_out($code, $message);
}

/**
 * 业务错误码 → HTTP 状态码。
 *
 * 只有 401/403/404 能直接对上；400 和 500 保留原样。这样既让
 * `curl -i` 一眼看出问题，也不至于把 401 的 body 伪装成 200。
 */
function zx_http_status_for(int $code): int
{
    return match ($code) {
        ZX_CODE_BAD_REQUEST  => 400,
        ZX_CODE_UNAUTHORIZED => 401,
        ZX_CODE_FORBIDDEN    => 403,
        ZX_CODE_NOT_FOUND    => 404,
        default              => 500,
    };
}

// ---- 请求解析 --------------------------------------------------------------

/** 原始请求体。签名要覆盖它，所以必须原样取一次、之后复用。 */
function zx_raw_body(): string
{
    static $body = null;
    if ($body === null) {
        $raw = file_get_contents('php://input');
        $body = ($raw === false) ? '' : $raw;
    }

    return $body;
}

/**
 * 解析请求参数：优先 JSON body，其次表单，最后 query。
 *
 * 客户端三个接口都发 JSON，但用 curl 手工验证时 form/query 更顺手，
 * 所以三个来源都收。
 *
 * @return array<string,mixed>
 */
function zx_input(): array
{
    static $input = null;
    if ($input !== null) {
        return $input;
    }

    $input  = [];
    $raw    = zx_raw_body();
    $trimmed = ltrim($raw);

    if ($trimmed !== '' && ($trimmed[0] === '{' || $trimmed[0] === '[')) {
        $decoded = json_decode($raw, true);
        if (is_array($decoded)) {
            $input = $decoded;
        }
    } elseif ($raw !== '') {
        $parsed = [];
        parse_str($raw, $parsed);
        if (is_array($parsed)) {
            $input = $parsed;
        }
    }

    // query 兜底，但不覆盖 body 里已有的键。
    foreach ($_GET as $key => $value) {
        if (!array_key_exists($key, $input)) {
            $input[$key] = $value;
        }
    }

    return $input;
}

function zx_str(array $input, string $key, string $default = ''): string
{
    $value = $input[$key] ?? null;
    if (is_scalar($value)) {
        return trim((string) $value);
    }

    return $default;
}

function zx_int(array $input, string $key, int $default = 0): int
{
    $value = $input[$key] ?? null;

    return (is_numeric($value)) ? (int) $value : $default;
}

/** 只允许给定集合里的值，其余一律回落到默认值。 */
function zx_enum(array $input, string $key, array $allowed, string $default): string
{
    $value = zx_str($input, $key, $default);

    return in_array($value, $allowed, true) ? $value : $default;
}

function zx_method(): string
{
    return strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
}

/** 客户端 IP，兼容反代。 */
function zx_client_ip(): string
{
    $forwarded = $_SERVER['HTTP_X_FORWARDED_FOR'] ?? '';
    if (is_string($forwarded) && $forwarded !== '') {
        // 取第一跳，那就是最初发起请求的客户端。
        $first = trim(explode(',', $forwarded)[0]);
        if ($first !== '' && filter_var($first, FILTER_VALIDATE_IP) !== false) {
            return $first;
        }
    }

    $remote = $_SERVER['REMOTE_ADDR'] ?? '';

    return (is_string($remote) && filter_var($remote, FILTER_VALIDATE_IP) !== false) ? $remote : '';
}

/** 当前请求的路径部分（不含 query），签名口径与客户端一致。 */
function zx_request_path(): string
{
    $uri  = (string) ($_SERVER['REQUEST_URI'] ?? '/');
    $path = parse_url($uri, PHP_URL_PATH);

    return is_string($path) ? $path : '/';
}

// ---- 签名 ------------------------------------------------------------------

/**
 * 校验请求签名，失败直接回 401 并结束。
 *
 * 注意：app_secret 编译进 APK，必然可以被逆向提取。这层签名只挡「随手抓包
 * 刷接口」，不是安全边界。真正的防护靠 device 表的 status 字段 + 服务器限流。
 */
function zx_require_sign(): void
{
    $appKey    = (string) ($_SERVER['HTTP_X_APP_KEY'] ?? '');
    $timestamp = (string) ($_SERVER['HTTP_X_TIMESTAMP'] ?? '');
    $signature = (string) ($_SERVER['HTTP_X_SIGN'] ?? '');

    if ($appKey === '' || $timestamp === '' || $signature === '') {
        zx_fail(ZX_CODE_UNAUTHORIZED, '缺少签名请求头');
    }

    $expectedKey = (string) zx_config_get('sign.app_key', '');
    if ($expectedKey === '' || !hash_equals($expectedKey, $appKey)) {
        zx_fail(ZX_CODE_UNAUTHORIZED, 'app_key 无效');
    }

    if (!ctype_digit($timestamp)) {
        zx_fail(ZX_CODE_UNAUTHORIZED, '时间戳格式不正确');
    }

    $tolerance = (int) zx_config_get('sign.tolerance_sec', 300);
    $skew      = abs(time() - (int) $timestamp);
    if ($skew > $tolerance) {
        // 把偏差值回给客户端，方便对照排查是不是本机时钟没同步。
        zx_fail(ZX_CODE_UNAUTHORIZED, sprintf('请求已过期（时间偏差 %d 秒）', $skew));
    }

    $expected = zx_sign(zx_method(), zx_request_path(), $appKey, $timestamp, zx_raw_body());
    if (!hash_equals($expected, strtolower($signature))) {
        zx_fail(ZX_CODE_UNAUTHORIZED, '签名校验失败');
    }
}

/** 签名计算。客户端 BackendSigner.kt 是同一口径。 */
function zx_sign(string $method, string $path, string $appKey, string $timestamp, string $rawBody): string
{
    $canonical = implode("\n", [
        strtoupper($method),
        $path,
        $appKey,
        $timestamp,
        hash('sha256', $rawBody),
    ]);

    return hash_hmac('sha256', $canonical, (string) zx_config_get('sign.app_secret', ''));
}

// ---- 杂项 ------------------------------------------------------------------

/** 只允许指定的 HTTP 方法，否则回 400。 */
function zx_require_method(string ...$allowed): void
{
    if (!in_array(zx_method(), $allowed, true)) {
        zx_fail(ZX_CODE_BAD_REQUEST, '不支持的请求方法，仅接受 ' . implode('/', $allowed));
    }
}

function zx_now(): string
{
    return date('Y-m-d H:i:s');
}

/** 把上传的相对路径拼成下发给客户端的绝对地址。 */
function zx_absolute_url(string $path): string
{
    $base = rtrim((string) zx_config_get('base_url', ''), '/');
    if ($path === '' || $base === '') {
        return $path;
    }
    if (preg_match('#^https?://#i', $path) === 1) {
        return $path;
    }

    return $base . '/' . ltrim($path, '/');
}

// ---- 全局异常处理 ----------------------------------------------------------
//
// 没有这层的话，一个 PHP notice 会把 HTML 错误块混进 JSON 响应里，客户端拿到的
// 就是解析失败的 body —— 报错信息丢失，排查方向还会被带偏。

set_error_handler(static function (int $severity, string $message, string $file = '', int $line = 0): bool {
    // 尊重 @ 抑制符和当前 error_reporting 设置。
    if ((error_reporting() & $severity) === 0) {
        return false;
    }
    throw new ErrorException($message, 0, $severity, $file, $line);
});

set_exception_handler(static function (Throwable $e): void {
    error_log('[xiaozhi] ' . get_class($e) . ': ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());

    $debug = (bool) zx_config_get('debug', false);
    zx_json_out(
        ZX_CODE_SERVER_ERROR,
        $debug ? ($e->getMessage() . ' @ ' . basename($e->getFile()) . ':' . $e->getLine()) : '服务器内部错误',
    );
});

// 致命错误（E_ERROR 这类走不到异常处理器的）也要保证输出是 JSON。
register_shutdown_function(static function (): void {
    $error = error_get_last();
    if ($error === null || !in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR], true)) {
        return;
    }
    if (headers_sent()) {
        return;
    }

    error_log('[xiaozhi] fatal: ' . $error['message'] . ' @ ' . $error['file'] . ':' . $error['line']);
    zx_json_out(ZX_CODE_SERVER_ERROR, '服务器内部错误');
});
