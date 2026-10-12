<?php
/**
 * 配置模板。**不要直接改这个文件**，复制成 config.php 再填：
 *
 *     cp config.example.php config.php
 *
 * config.php 已在 .gitignore 里，真实密钥不进版本库。
 */

return [
    // ---- 数据库 ----------------------------------------------------------
    'db' => [
        'host'     => '127.0.0.1',
        'port'     => 3306,
        'name'     => 'xiaozhi_android_client',
        'user'     => 'xiaozhi',
        'password' => '',
        'charset'  => 'utf8mb4',
    ],

    // ---- 接口签名 --------------------------------------------------------
    //
    // app_key 随请求明文发送，用来定位是哪一端；
    // app_secret 参与 HMAC 计算。
    //
    // 这两个值要同步进客户端的 backend/BackendConfig.kt。注意 app_secret 编译
    // 进 APK 之后**必然可以被逆向提取**，这层签名只挡得住「随手抓包刷接口」，
    // 不是安全边界。真正的防护靠 device 表的 status 字段（可停用设备）+
    // 服务器层面的限流。
    //
    // 生成一个够强的密钥：
    //     php -r "echo bin2hex(random_bytes(32)), PHP_EOL;"
    'sign' => [
        'app_key'    => 'xiaozhi-android-client',
        'app_secret' => 'PLEASE_CHANGE_ME_64_HEX_CHARS',
        // 允许的客户端时间偏差（秒）。客户端与本机时钟不同步时放宽此值。
        'tolerance_sec' => 300,
    ],

    // ---- 部署地址 --------------------------------------------------------
    //
    // 用来把上传的图片/APK 拼成绝对地址下发给客户端。
    // 末尾不要带斜杠。
    'base_url' => 'http://api.mrcong.cn:300/apps/xiaozhi_android_client',

    // ---- 管理后台 --------------------------------------------------------
    'admin' => [
        // 生成密码哈希：
        //     php -r "echo password_hash('你的密码', PASSWORD_DEFAULT), PHP_EOL;"
        'password_hash' => '$2y$10$PLEASE_CHANGE_ME',
        // 会话有效期（秒），超过要重新登录。
        'session_ttl'   => 7200,

        // 前挂了反向代理时，把反代的地址填进来。**只影响登录限速按哪个 IP 计数**：
        //   - 留空（默认）：限速按 REMOTE_ADDR 算。直连部署（nginx 直接监听 :300）就该留空。
        //   - 填了：只有 REMOTE_ADDR 命中白名单时才改用 X-Forwarded-For 的第一跳。
        //
        // 支持精确 IP 和前缀两种写法：['127.0.0.1', '10.0.0.']。
        //
        // 为什么不能无条件信 XFF：那是个请求头，谁都能伪造。拿它当限速的 key，
        // 攻击者每发换个伪造 IP 就等于没有限速。反过来，在反代后面**忘了填**这项，
        // 后果是所有访客共享反代那一个 IP —— 攻击者失败 5 次会把真实管理员一起锁 15 分钟。
        'trusted_proxies' => [],
    ],

    // ---- 其他 ------------------------------------------------------------
    'debug' => false,  // true 时把 PHP 异常细节回给客户端。生产环境务必 false。
];
