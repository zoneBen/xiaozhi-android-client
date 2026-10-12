# 小智 Android 定制版 —— 自建后端

三个接口 + 一个极简后台。部署到 `http://api.mrcong.cn:300/apps/xiaozhi_android_client/`，
即把本目录整体放进 webroot 的 `/apps/xiaozhi_android_client/`。

| 接口 | 文件 | 干什么 |
|---|---|---|
| 心跳 | `v1/heartbeat.php` | 客户端上报设备唯一码与元数据，服务端下发心跳间隔 / 开屏开关 |
| 开屏 | `v1/splash.php` | `GET` 拉素材（图 + 标题 + 跳转），`POST` 上报曝光 / 点击 |
| 升级 | `v1/upgrade.php` | 下发最新版本、下载地址、SHA-256、是否强制 |

后台在 `admin/`：设备列表、开屏素材管理（带图片上传）、版本发布（带 APK 上传）。

---

## 0. 先读这一段：服务器挂掉时会发生什么

**客户端三个功能会一起安静地失效，界面上一个字都不会提示。** 这是刻意的设计，不是没做完：

- 开屏：有本地缓存就用缓存图，没有就直接进主界面走原动画，不等待、不转圈
- 心跳：静默重试、指数退避，不影响启动速度，不影响任何本地功能（语音、定时任务、拍照、邮件）
- 升级：静默检查失败当无事发生，只有手动点「检查更新」才会显示失败

代价是**服务端出事没有任何人会被通知**。所以后台设备列表顶部那个「最近 5 分钟有心跳」
的数字就是这个系统唯一的报警器 —— 它掉到 0（且设备总数不为 0）时页面会变色，那就是
服务器/数据库/网络出问题的信号。定期看一眼，或者给它配个外部拨测。

---

## 1. 目录结构

```
php/
├── guard.php            PHP 版本守卫。每个入口的第一行 require 它
├── common.php           公共库：PDO、统一响应信封、签名校验、全局异常 → JSON
├── config.example.php   配置模板（进 git）
├── config.php           真实配置（.gitignore，含数据库账密与签名密钥）
├── install.sql          建表 + 初始数据
├── .gitignore           忽略 config.php / uploads/ / sessions/
├── v1/                  三个对外接口
│   ├── heartbeat.php
│   ├── splash.php
│   └── upgrade.php
├── admin/               管理后台
│   ├── lib.php          会话、登录校验、CSRF、布局、上传工具
│   ├── login.php  logout.php
│   ├── index.php        设备列表 + 报警看板
│   ├── splash_list.php  splash_edit.php
│   └── version_list.php  version_edit.php
└── uploads/             上传的图片与 APK（web 需可读，PHP 需可写）
    ├── splash/
    └── apk/
```

`guard.php` 之所以单独一个文件：`common.php` 里有 `match` / `mixed` 这些 8.0 才有的语法，
在 PHP 7 的机器上会直接 parse error，那时里面任何版本判断都来不及跑，而 parse error 的
输出是一片空白 —— 部署的人只会看到「接口没反应」。`guard.php` 只用 PHP 5 也能解析的语法，
版本不够时至少能回一句人话。

---

## 2. 环境要求

- **PHP 8.0+**（必须）。扩展：`pdo_mysql`（必须）、`fileinfo`（开屏图片上传时校验真实 MIME）、
  `mbstring`（可选，缺了也能跑，见 `admin_clip()` 的兜底 —— 那时中文按**字节**截断，比按
  字符更保守，可能提前截短，但不会截出非法 UTF-8 把一次保存变成 500）
- **MySQL 5.7+ / MariaDB 10.3+**。用到 `utf8mb4` 和 `INSERT … ON DUPLICATE KEY UPDATE`
- nginx 或 Apache 都行，下面是 nginx 的要点
- 无 Composer、无框架依赖，整个目录拷过去就能跑

---

## 3. 部署步骤

```bash
# 1) 建库建表（库名可自定，但要和 config.php 里对上）
mysql -u root -p -e "CREATE DATABASE xiaozhi_android_client DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p xiaozhi_android_client < install.sql

# 2) 生成两份密钥
php -r "echo 'app_secret  = ', bin2hex(random_bytes(32)), PHP_EOL;"
php -r "echo 'admin_hash  = ', password_hash('你的后台密码', PASSWORD_DEFAULT), PHP_EOL;"

# 3) 配置
cp config.example.php config.php
vi config.php      # 填 db.* / sign.app_secret / admin.password_hash / base_url

# 4) 传上去（映射到 webroot 的 /apps/xiaozhi_android_client/）
rsync -a ./ user@api.mrcong.cn:/var/www/apps/xiaozhi_android_client/

# 5) 上传目录给写权限（用户按你的 PHP-FPM 跑的用户改）
mkdir -p uploads/splash uploads/apk
chown -R www-data:www-data uploads && chmod -R 755 uploads

# 6) 把 sign.app_key / sign.app_secret 同步进客户端（见 §5）
```

`install.sql` 末尾会写进 5 条运行时配置（心跳间隔 900 秒、开屏总开关等）和一条与
当前客户端一致的版本记录（`version_code=15` / `1.2.11`），让升级接口一上线就能正确回答
「已是最新」，而不是因为表为空返回 0。

**改心跳间隔不用重新发包**：改 `xiaozhi_android_client_config` 里的
`heartbeat_interval_sec` 即可，客户端下次心跳就会拿到新值（会被钳到 `[60, 3600]`）。

---

## 4. nginx 与 PHP 的上传体积

后台要传 APK，**当前包约 77 MB**。三处都得放过，漏一处就只会看到一句
「文件超过服务器允许的上传大小」：

```nginx
server {
    listen 300;
    server_name api.mrcong.cn;
    root /var/www;

    # ① APK 上传：留足余量，77MB 的包给 128M
    client_max_body_size 128m;

    location /apps/xiaozhi_android_client/ {
        index index.php;
        try_files $uri $uri/ =404;

        location ~ \.php$ {
            include fastcgi_params;
            fastcgi_pass unix:/run/php/php8.2-fpm.sock;
            fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
            # 大包慢链路下别被 FPM 掐断
            fastcgi_read_timeout 300s;
        }
    }

    # ② 这些文件不该被直接访问。按**后缀**拦，不要只列文件名 ——
    #    只列文件名的话，某天目录里多出一个 php.zip / 备份 / .bak，就漏了。
    location ~ ^/apps/xiaozhi_android_client/.*\.(sql|md|zip|bak|log|ini|example\.php)$ {
        deny all;
    }
    # config.php 是 PHP，直接请求也会被执行掉而不吐内容，但仍然挡掉
    location ~ ^/apps/xiaozhi_android_client/(config|common|guard)\.php$ { deny all; }
    location ~ ^/apps/xiaozhi_android_client/admin/lib\.php$ { deny all; }
}
```

（`config.php` 是 PHP，直接请求也会被执行掉而不吐出内容，但白名单式 deny 更稳妥。）

> **注意 `php/php.zip`**：目录里如果留着一个源码快照（打包/交付时很常见），它会跟着
> `rsync` 一起进 webroot，被任何人以 `GET …/php.zip` 整包下载。它现在不含 `config.php`
> 所以不泄密，但下次重新打包时非常容易连 `config.php` 一起压进去 —— 那就是 `app_secret`
> 和数据库账密一起泄露。建议在同步前把它排除掉（上面的 deny 只是兜底）：
>
> ```bash
> rsync -a --exclude='*.zip' --exclude='config.php' --exclude='uploads/' ./ user@host:/var/www/apps/xiaozhi_android_client/
> ```

```ini
; ③ php.ini —— 这两个都要大过 APK 体积，只调一个没用
upload_max_filesize = 128M
post_max_size       = 128M
max_execution_time  = 300
max_input_time      = 300
```

`uploads/` 必须能被 web **直接读到** —— 开屏图和 APK 是客户端自己直连下载的，不经过 PHP。
**不要**给上传目录 0777，代码里建目录用的是 0755（见 `admin_ensure_upload_dir()`）。

---

## 5. 客户端对接

客户端只需要两个值：**接口基址**和 **app_secret**。

```bash
# 在 Android 仓库根目录
cp .backend.local.example .backend.local
vi .backend.local       # appSecret=<config.php 里的 sign.app_secret>
```

`.backend.local` 在 `.gitignore` 里，编译时经 `app/build.gradle.kts` 变成
`BuildConfig.BACKEND_APP_SECRET`，由 `backend/BackendConfig.kt` 读取。

- `appSecret` 为空 → 客户端认为「这个包没接通后端」，心跳/开屏/升级**整块跳过**，
  连请求都不发（升级会改用 `.invalid` 占位地址，安静地报「没接通升级服务」）。
  所以忘了配的表现是「什么都不发生」，不是报错 —— 这也是为什么它必须有个开关。
- `app_key` 不参与保密，只在数值对不上时才需要改（默认两端都是 `xiaozhi-android-client`）。
- 基址是客户端里的编译期常量（`backend/BackendConfig.kt` 的 `DEFAULT_BASE_URL`），
  **没有运行期改它的入口** —— 早先在「设备控制」页有个输入框，2026-10-12 撤掉了：
  它是运维级的东西，让用户能改只会多出一种「这台机器跟别人不一样」的排查分支。
  换基址要改常量重新发包。

**`app_secret` 绝不进 git**：服务端的在 `config.php`（已 ignore），客户端的在 `.backend.local`
（已 ignore）。但要清楚它最终会编译进 APK、**必然可以被逆向提取**，见 §9。

---

## 6. 接口契约

三个接口的响应都是同一个信封：

```json
{ "code": 0, "message": "ok", "serverTime": 1760246400, "data": { } }
```

`code` 非 0 即错误：`400` 参数 / `401` 签名 / `403` 设备已停用 / `404` / `500`，
HTTP 状态码同步（`curl -i` 一眼能看出问题）。

`data` 这个键是**按调用点**决定要不要出现的（`zx_json_out()` 的 `$data` 默认是空数组、
传 `null` 才省略），所以两种形状都会见到：

- 错误响应（`zx_fail`）带一个空数组：`{"code":401,…,"data":[]}`
- **`upgrade.php` 显式传 `null`**，于是没有 `data` 键，版本字段直接平铺在顶层 —— 客户端
  `AppUpdateManager` 读的就是顶层那几个键，一行都不用改（见 §6.3）

### 6.1 签名（三个接口统一）

```
X-App-Key:   xiaozhi-android-client
X-Timestamp: 1760246400                      # 秒
X-Sign:      HMAC-SHA256(规范串, app_secret)  # 小写十六进制
```

规范串是把下面 5 段用 `\n` 连起来：

```
POST                                          ← 方法，大写
/apps/xiaozhi_android_client/v1/heartbeat.php ← 路径，**带部署前缀**，不含 query
xiaozhi-android-client                        ← app_key
1760246400                                    ← 时间戳
<sha256(原始请求体) 的十六进制>                 ← GET 也要算，空体是 sha256("")
```

服务端时间窗 ±300 秒（`sign.tolerance_sec`）。不建 nonce 表：三个接口分别是
「幂等写 / 只读 / 只读」，重放没有实际收益。

> **路径前缀这个坑**：路径取自 `parse_url($_SERVER['REQUEST_URI'])`，线上就挂在
> `/apps/xiaozhi_android_client/` 这层子目录下，所以客户端签的是
> `/apps/xiaozhi_android_client/v1/heartbeat.php`。谁把它「顺手规范化」成
> `/v1/heartbeat.php`，线上会**全线 401**，而本地怎么点都看不出来。
> 客户端那侧有固定向量的单测钉着这一点（`BackendSignerTest`）。

### 6.2 自测（本地起服务）

```bash
# 起一个不依赖 nginx 的本地服务。注意此时路径前缀是 /v1/…，签名里的 path 也要跟着改
php -S 127.0.0.1:8080 -t php/
```

```bash
# 打一发心跳（把值换成你 config.php 里的）
BASE=http://127.0.0.1:8080
URI=/v1/heartbeat.php
APP_KEY=xiaozhi-android-client
APP_SECRET=改成你的
BODY='{"deviceUid":"a2:1f:cc:00:11:22","source":"boot","model":"PLQ110","appVersionCode":15}'

TS=$(date +%s)
BODY_HASH=$(printf '%s' "$BODY" | sha256sum | cut -d' ' -f1)
CANON=$(printf 'POST\n%s\n%s\n%s\n%s' "$URI" "$APP_KEY" "$TS" "$BODY_HASH")
SIGN=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$APP_SECRET" -r | cut -d' ' -f1)

curl -i -X POST "$BASE$URI" \
  -H "X-App-Key: $APP_KEY" -H "X-Timestamp: $TS" -H "X-Sign: $SIGN" \
  -H 'Content-Type: application/json' --data-binary "$BODY"
```

（`sha256sum` 来自 coreutils，macOS 上是 `shasum -a 256`。）

覆盖这几条就够确认接入是通的：

| 想验的事 | 怎么做 | 期望 |
|---|---|---|
| 签名正确 | 上面那发 | `{"code":0,…}` + `device` 表出现一行 |
| 签名错误 | 把 `X-Sign` 最后一位改掉 | `401 签名校验失败` |
| 超时间窗 | `TS=$(date -d '-10 min' +%s)` 重签 | `401 请求已过期（时间偏差 N 秒）` |
| 首次 vs 二次 | 同一发连打两次 | `heartbeat_count` 从 1 变 2，设备还是同一行 |
| 停用设备 | 后台把设备停用，再打一发 | `403 设备已被停用`，且 `last_seen_at` **不刷新** |
| 强制升级 | 发一条 `version_code=16, min_support_version=16` 的版本，用 `versionCode=15` 请求 upgrade | `forceUpdate: true` |
| 表里没有版本 | 清空 `…_app_version` 后请求 upgrade | `versionCode: 0`（客户端据此判定「已是最新」） |

### 6.3 各接口要点

**`POST v1/heartbeat.php`** — 请求体见上面示例。落库用
`INSERT … ON DUPLICATE KEY UPDATE`，元数据一律**新值非空才覆盖**（`COALESCE(NULLIF(...))`），
免得某次只发了部分字段就把之前攒的型号、SN 抹掉。响应 `data`：

```json
{ "intervalSec": 900, "deviceStatus": 1, "splashEnabled": true,
  "splashTtlSec": 600, "latestVersionCode": 15 }
```

同时往 `…_heartbeat_log` 写一行明细（`source` 区分 `boot` / `app` / `worker`）。
明细写失败只记日志，不影响主流程。

**`GET v1/splash.php?deviceUid=…&lastRevision=…`** — 条件请求用的是 `revision`
（生效素材集合里最大的 `updated_at`，秒），不是素材 id：**id 相同不代表内容相同**，
后台换掉一张已有素材的图，id 不变，用 id 会让客户端一直用旧图。响应 `data`：

```json
{ "enabled": true, "ttlSec": 600, "revision": 1760246400, "notModified": false,
  "items": [ { "id": 3, "title": "国庆活动", "subtitle": "扫码领券 ›",
               "imageUrl": "http://…/uploads/splash/ab12….jpg",
               "imageSha256": "…", "linkUrl": "https://…",
               "durationMs": 3000, "weight": 100 } ] }
```

`revision` 一致时回 `notModified: true` + 空 `items`。**总开关关掉时回 `enabled: false`**，
客户端据此清掉本地缓存 —— 这是运营让一张已经在设备上的旧图下线的唯一办法。
客户端判断「要不要重新下图」只看每条的 `imageSha256`。

> **`durationMs` 不是「展示时长」的最终值**：客户端会把它钳到 **2000 ~ 5000**（`0` 当作不放）。
> 头屏是「进主界面前的一段等待」，这段等待有多长由主界面决定，不由广告决定 ——
> 这台 PLQ110 上主界面 0.4 秒就画好了，不设下限的话广告在屏幕上活不过一帧。
> 详见客户端 `SplashAdCache.visibleMillis`。后台表单里也写着这一句。

**`POST v1/splash.php`** — 体为 `{"deviceUid":"…","splashId":3,"action":"show"}`。
素材已被删掉时回 404，不在统计表里攒指向不存在素材的行。

**`GET v1/upgrade.php?deviceUid=…&versionCode=15&channel=default`** — 字段平铺在顶层：

```json
{ "code": 0, "message": "ok", "serverTime": 1760246400,
  "versionCode": 16, "versionName": "1.2.12", "minSupportVersion": 1,
  "downloadUrl": "http://…/uploads/apk/….apk", "fileSize": 80530637,
  "sha256": "…", "releaseNotes": "…", "forceUpdate": false,
  "publishedAt": "2026-10-12 15:00:00" }
```

平铺是为了让客户端的解析一行都不用改（它读的就是老的 `version.json` 形状）。
`forceUpdate` 有两条来源：运营手工开的 `force_update=1`，或**请求的 versionCode 低于
`min_support_version`** 自动兜底。服务端只给出这个事实，**不做**「要不要升级」的判断 ——
那是客户端的 `remoteVersionCode > currentVersionCode`。

---

## 7. 后台

`/apps/xiaozhi_android_client/admin/`，密码是 `config.php` 里的 `admin.password_hash`
（只存哈希，代码里不落任何明文）。会话默认 2 小时无操作过期（`admin.session_ttl`）。

**登录限速在服务端**：连续失败 5 次锁 15 分钟，计数落在 `…_admin_login` 表里、按 IP 记。
（早先放在 `$_SESSION` 里的写法是无效的 —— 会话是请求方自己带的 Cookie，脚本第一发不带
Cookie 就是一个干净的新会话，计数永远从 0 开始。改动的原因写在 `admin/login.php` 顶部。）

限速的 key 用 `REMOTE_ADDR`。**如果你在反代后面部署**，把反代地址填进
`config.php` 的 `admin.trusted_proxies`，否则所有访客共享反代那一个 IP，
攻击者失败 5 次会把你自己也锁在门外 15 分钟。填了之后才采信 XFF 第一跳。

- **设备**：顶部看板（设备总数 / 最近 5 分钟有心跳）；列表按在线优先排；可改备注（如「展厅 3 号机」）、
  可停用/启用。**在线判定 = 最近 3 个心跳周期内有上报**，用 3 倍是为了容忍丢包和时钟抖动。
- **开屏素材**：列表带缩略图；新增/编辑时上传图片会自动算 `sha256` 与宽高并填库，
  也可以直接填外链（对接 CDN 时用）。生效时段留空即不限。
  图片只收 jpeg/png/webp，按**文件内容**判类型（`finfo`），不信扩展名也不信 `$_FILES['type']`。
- **版本发布**：上传 APK 会自动算 `sha256` 和 `file_size` 并拼出下载地址，也可以手填 URL
  （对接 GitHub Release 时用）。上传文件名一律随机，不沿用用户原名。

所有会改数据的提交都是 `POST + CSRF`，改完重定向回列表。登录成功会换一次 session id。

---

## 8. 运维

**心跳明细会持续增长**，一天一台设备约 96 行（默认 15 分钟间隔）。留着最近 30 天足够画曲线：

```sql
-- 先看清楚要删多少
SELECT COUNT(*) FROM xiaozhi_android_client_heartbeat_log WHERE created_at < NOW() - INTERVAL 30 DAY;

-- 分批删，别一条语句锁太久（每次 5 万行，循环跑）
DELETE FROM xiaozhi_android_client_heartbeat_log
 WHERE created_at < NOW() - INTERVAL 30 DAY
 ORDER BY id LIMIT 50000;
```

开屏统计（`…_splash_stat`）增长慢得多，按天聚合查：

```sql
SELECT s.title, t.stat_date, t.action, COUNT(*) AS n
  FROM xiaozhi_android_client_splash_stat t
  JOIN xiaozhi_android_client_splash s ON s.id = t.splash_id
 WHERE t.stat_date >= CURDATE() - INTERVAL 7 DAY
 GROUP BY t.splash_id, t.stat_date, t.action
 ORDER BY t.stat_date DESC;
```

登录失败计数（`…_admin_login`）一行一个来源 IP，量很小，顺手清一下即可：

```sql
DELETE FROM xiaozhi_android_client_admin_login WHERE last_fail_at < NOW() - INTERVAL 7 DAY;
```

**`install.sql` 可以重复执行**：建表全是 `CREATE TABLE IF NOT EXISTS`，初始数据是
`ON DUPLICATE KEY UPDATE`。以后本目录里新增了表（比如上面那张登录计数表），
在服务器上把最新版的 `install.sql` 再跑一遍就行，不会动已有数据和配置。

**备份**至少覆盖 `config.php` 和 `uploads/`（数据库里有引用），表本身用 `mysqldump` 常规备份即可。

真正的监控还是那一句：**盯「最近 5 分钟有心跳」的数字**。客户端是静默降级的，
服务器挂了不会有任何设备来抱怨。

---

## 9. 几件必须知道的事

### 9.1 上传的 APK 必须用同一个证书签名

客户端的 `AppUpdateManager` 在装之前会校验下载包的签名证书 SHA-256，必须等于
`EXPECTED_SIGNER_SHA256`（当前 `dcc4c2f7bb13e6b7a31f8abfab1839ed0889fbbf340d3c5f6e7cd2a750637384`，
对应定制版发版用的 `mrcong.jks`）。用别的证书签的包会被拒装。

**用别的证书签的包会被拒装**，而且拒绝发生在下载、校验 SHA-256 都通过之后 ——
现象是「明明提示有新版本、下载也成功，就是装不上」。这是特性不是 bug：它是防镜像投毒的
最后一道闸门。换签名密钥时必须同步改客户端那个常量，否则所有设备的升级会**永久失效**。

### 9.2 签名不是安全边界

`app_secret` 会被编译进 APK，**必然可以被逆向提取**。这层 HMAC 只能挡住「随手抓包刷接口」
和「用错地址的野生客户端」，**挡不住任何有心人**。

真正的防护是：

1. `…_device.status` 字段 —— 后台一键停用某台设备，它的心跳立刻收到 403、客户端停止上报
2. 服务器层面的限流（nginx `limit_req`、fail2ban 之类）
3. 数据本身不敏感：报上来的是设备型号、版本、IP，没有任何用户内容

同样地，线上走的是 `http`（`:300` 上没有证书）。客户端发的元数据不涉密，且完整性依赖
签名而不是传输加密，所以明文可接受 —— 但要知道它是明文。

### 9.3 签名不覆盖 query string（已知缺口，未修）

规范串里只有方法、路径、app_key、时间戳、`sha256(请求体)`。心跳是 POST、参数在体里，
所以是覆盖到的；但 **`upgrade.php` 的 `versionCode` / `channel` 和 `splash.php` 的
`deviceUid` / `lastRevision` 走的是 query，不在签名范围内**。

也就是说，一个能在链路上的攻击者，抓到一发已签名的 GET 之后，**不改签名、不动请求头**
就能在 300 秒内改掉这些参数：

- `versionCode=15` 改成 `versionCode=0` → 服务端算出 `forceUpdate=true` → 客户端弹强制升级框
- `channel=default` 改成别的渠道值 → 拿到另一条发布记录

**装不上恶意包**：SHA-256 校验和证书 pin 都还在（见 9.1），所以这是骚扰/误导级别的，
不是 RCE。`splash.php` 那两个 get 参数则没有任何安全含义（最多让它多拉一次清单）。

**为什么没有顺手修**：把 query 也拼进规范串是显而易见的修法，但 query 的百分号编码
在中间层（代理、网关、WAF）可能被重写，两端一旦对不上就是**全线 401** ——
那正是这个项目最怕的失败模式（同一个坑见 §6.1 的路径前缀）。所以先记在这里。

**真要堵死这条路，正确的做法是上 HTTPS**，而不是在签名里补 query：有证书之后"链路中间
的人"这个前提就不成立了，而且顺带解决的还有 9.2 里的明文传输。

### 9.4 后台显示的 IP 是可以被伪造的

`zx_client_ip()` 优先采信 `X-Forwarded-For`（为了兼容反代），而 XFF 是个请求头、谁都能写。
所以 `…_device.last_ip` 和后台设备列表里那一列 **只能当参考，不要拿它做封禁依据**。
（登录限速不受影响 —— 它另有 key，只信 `REMOTE_ADDR`，见 §7。）

---

## 10. 排错

| 现象 | 先查什么 |
|---|---|
| 接口回一片空白，没有任何 JSON | PHP 版本低于 8.0。`guard.php` 本该回一句人话，若连它都没输出，看 PHP 错误日志（多半是 `pdo_mysql` 没装） |
| `500 服务端未配置：缺少 config.php` | 忘了从 `config.example.php` 复制 |
| 全线 `401 签名校验失败` | 九成是签名里的**路径前缀**对不上（线上必须带 `/apps/xiaozhi_android_client`）；或 `app_secret` 两端不一致 |
| `401 请求已过期（时间偏差 N 秒）` | 设备时钟没同步。看响应里的偏差值，必要时调大 `sign.tolerance_sec` |
| 客户端「什么都没发生」 | `appSecret` 没配（`BackendConfig.isConfigured` 为 false，请求整块跳过）。检查 `.backend.local` 与 `BuildConfig` |
| 后台传 APK 报「超过允许的上传大小」 | `client_max_body_size` / `upload_max_filesize` / `post_max_size` 三处要一起改（§4） |
| 后台登录页能打开、一提交就 500 | 数据库连不上。登录限速要写 `…_admin_login` 表，读不到就**拒绝**（fail-closed，故意的）。查 MySQL 和 `config.php` 里的账密 |
| APK 下载完装不上 | 签名证书与 `EXPECTED_SIGNER_SHA256` 不一致（§9.1） |
| 表格里一堆设备但「最近 5 分钟有心跳」是 0 | 大概率是服务端/数据库/网络出事，而不是设备全下线。先查 PHP 错误日志和 MySQL |
| 开屏图换了但设备还是旧的 | 后台换了图但忘记看 `revision` 是否变化（`updated_at` 变了就会变）；或设备上 `splash_enabled` 已被前台关掉，客户端在等下一次拉取（最长一个 `ttlSec`） |
