-- ============================================================================
-- 小智安卓定制版 —— 自建后端建表脚本
--
-- 用法：
--   mysql -u<user> -p <dbname> < install.sql
--
-- 所有表统一前缀 xiaozhi_android_client_，与客户端 applicationId
-- (me.xiaozhi.androidclient) 对应，便于在一台服务器上放多个 app 时区分。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------------------------------------------------------
-- 设备主表：一台设备一行。心跳首次上报时 INSERT，之后全部走 UPDATE。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_device` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `device_uid`        VARCHAR(64)     NOT NULL COMMENT '唯一码：客户端 deviceId（由 ANDROID_ID 派生的伪 MAC），首次上报后不再变',
  `client_id`         VARCHAR(64)         NULL COMMENT '小智 client-id（每次安装生成一个 UUID）',
  `android_id`        VARCHAR(64)         NULL COMMENT 'Settings.Secure.ANDROID_ID',
  `mac_address`       VARCHAR(32)         NULL COMMENT '同一 device_uid 的副本，便于人工核对',
  `serial_number`     VARCHAR(64)         NULL COMMENT 'DeviceIdentityStore 生成的 SN-XXXXXXXX-xxxx',
  `install_id`        VARCHAR(64)         NULL COMMENT '识别重装/刷机：同一个 device_uid 换了 install_id 就是重装过',
  `brand`             VARCHAR(48)         NULL,
  `manufacturer`      VARCHAR(48)         NULL,
  `model`             VARCHAR(64)         NULL,
  `device_name`       VARCHAR(64)         NULL COMMENT 'Build.DEVICE',
  `os_version`        VARCHAR(32)         NULL COMMENT 'Build.VERSION.RELEASE',
  `sdk_int`           INT UNSIGNED        NULL,
  `app_version_code`  INT UNSIGNED        NULL,
  `app_version_name`  VARCHAR(32)         NULL,
  `screen_width`      INT UNSIGNED        NULL,
  `screen_height`     INT UNSIGNED        NULL,
  `net_type`          VARCHAR(16)         NULL COMMENT 'wifi / cellular / ethernet / none / unknown',
  `last_ip`           VARCHAR(45)         NULL COMMENT '45 字符以兼容 IPv6',
  `first_seen_at`     DATETIME        NOT NULL,
  `last_seen_at`      DATETIME        NOT NULL,
  `heartbeat_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `app_open_count`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'source=boot 的次数，即冷启动次数',
  `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '1=正常 / 0=停用（停用后心跳回 403，客户端停止上报）',
  `remark`            VARCHAR(255)        NULL COMMENT '后台备注，如「展厅 3 号机」',
  `created_at`        DATETIME        NOT NULL,
  `updated_at`        DATETIME        NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_uid` (`device_uid`),
  KEY `idx_last_seen` (`last_seen_at`),
  KEY `idx_status_seen` (`status`, `last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备心跳主表';

-- ----------------------------------------------------------------------------
-- 心跳明细：画在线曲线、排查「这台机器为什么没上报」用。
-- 写入量 = 设备数 × (86400 / 心跳间隔)，默认间隔下约 96 行/台/天。
-- 定期清理见 README.md。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_heartbeat_log` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `device_uid`       VARCHAR(64)     NOT NULL,
  `app_version_code` INT UNSIGNED        NULL,
  `net_type`         VARCHAR(16)         NULL,
  `ip`               VARCHAR(45)         NULL,
  `source`           VARCHAR(16)     NOT NULL DEFAULT 'app' COMMENT 'app=协程循环 / worker=WorkManager 兜底 / boot=冷启动',
  `created_at`       DATETIME        NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_uid`, `created_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='心跳明细';

-- ----------------------------------------------------------------------------
-- 开屏素材：图片 + 标题/副标题 + 跳转 + 生效时段 + 展示时长 + 权重。
-- 客户端把图和 meta 缓存到本地，服务器挂了也照常展示（见客户端 SplashAdStore）。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_splash` (
  `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '主标题，展示在图片下方',
  `subtitle`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '副标题',
  `image_url`    VARCHAR(512) NOT NULL COMMENT '图片地址：上传后自动填绝对地址，也支持直接填外链',
  `image_sha256` CHAR(64)         NULL COMMENT '客户端判断「要不要重新下图」的唯一依据',
  `image_width`  INT UNSIGNED     NULL,
  `image_height` INT UNSIGNED     NULL,
  `link_url`     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '点击跳转；留空则整块不可点',
  `start_at`     DATETIME         NULL COMMENT '生效开始，NULL=不限',
  `end_at`       DATETIME         NULL COMMENT '生效结束，NULL=长期',
  `duration_ms`  INT UNSIGNED NOT NULL DEFAULT 3000 COMMENT '客户端展示时长（毫秒）',
  `weight`       INT          NOT NULL DEFAULT 100 COMMENT '多条命中时权重高者优先',
  `enabled`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 / 0=停用',
  `remark`       VARCHAR(255)     NULL,
  `created_at`   DATETIME     NOT NULL,
  `updated_at`   DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_live` (`enabled`, `start_at`, `end_at`, `weight`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='开屏素材';

-- ----------------------------------------------------------------------------
-- 曝光/点击统计。stat_date 是冗余出来的日期列，避免每次统计都 GROUP BY DATE()。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_splash_stat` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `splash_id`  INT UNSIGNED    NOT NULL,
  `device_uid` VARCHAR(64)     NOT NULL,
  `action`     ENUM('show','click') NOT NULL,
  `stat_date`  DATE            NOT NULL,
  `created_at` DATETIME        NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_splash_date` (`splash_id`, `stat_date`, `action`),
  KEY `idx_device_time` (`device_uid`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='开屏曝光点击统计';

-- ----------------------------------------------------------------------------
-- 版本发布记录。upgrade.php 取 platform+channel 下 enabled=1 的 MAX(version_code) 那条。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_app_version` (
  `id`                  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `platform`            VARCHAR(16)  NOT NULL DEFAULT 'android',
  `channel`             VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '渠道，预留灰度用',
  `version_code`        INT UNSIGNED NOT NULL,
  `version_name`        VARCHAR(32)  NOT NULL,
  `min_support_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '低于此 version_code 的客户端强制升级',
  `download_url`        VARCHAR(512) NOT NULL,
  `file_size`           BIGINT UNSIGNED  NULL COMMENT '字节；上传 APK 时自动填',
  `sha256`              CHAR(64)         NULL COMMENT '客户端下载后校验，必须与 APK 实际值一致',
  `release_notes`       TEXT             NULL,
  `force_update`        TINYINT      NOT NULL DEFAULT 0 COMMENT '按版本强制；另有 min_support_version 自动兜底',
  `enabled`             TINYINT      NOT NULL DEFAULT 1 COMMENT '1=已发布 / 0=草稿',
  `published_at`        DATETIME         NULL,
  `created_at`          DATETIME     NOT NULL,
  `updated_at`          DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version` (`platform`, `channel`, `version_code`),
  KEY `idx_latest` (`platform`, `channel`, `enabled`, `version_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本发布';

-- ----------------------------------------------------------------------------
-- 键值配置。心跳间隔等运行时参数放这里，改值即可生效，不用重新发包。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_config` (
  `cfg_key`    VARCHAR(64)  NOT NULL,
  `cfg_value`  VARCHAR(512) NOT NULL,
  `remark`     VARCHAR(255)     NULL,
  `updated_at` DATETIME     NOT NULL,
  PRIMARY KEY (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行时配置';

-- ----------------------------------------------------------------------------
-- 后台登录失败计数。按 IP 记，给 admin/login.php 做服务端限速用。
--
-- 为什么不能放会话里：会话是请求方自己带的 Cookie，攻击脚本第一发不带 Cookie
-- 就是一个干净的新会话，计数永远从 0 开始 —— 那样的限速一次都不会生效。
--
-- 时间列由 MySQL 的 NOW() 写、也由 NOW() 比较，不掺 PHP 的 time()，
-- 免得两个时钟（哪怕只因为时区）不一致时把锁定窗口算错。
-- 这个表的行会随不同来源 IP 增长，清理 SQL 见 README。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xiaozhi_android_client_admin_login` (
  `ip`           VARCHAR(45)  NOT NULL COMMENT 'REMOTE_ADDR（仅反代白名单命中时才用 XFF 第一跳）',
  `fail_count`   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前窗口内的累计失败次数',
  `last_fail_at` DATETIME     NOT NULL COMMENT '最后一次失败时刻，锁定窗口从它算起',
  PRIMARY KEY (`ip`),
  KEY `idx_last_fail` (`last_fail_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台登录失败计数';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 初始数据
-- ============================================================================

INSERT INTO `xiaozhi_android_client_config` (`cfg_key`, `cfg_value`, `remark`, `updated_at`) VALUES
  ('heartbeat_interval_sec', '900',  '心跳间隔（秒）。客户端会钳制到 [60,3600]', NOW()),
  ('splash_enabled',         '1',    '开屏总开关。0=所有素材都不下发',           NOW()),
  ('splash_ttl_sec',         '600',  '客户端多久重新拉一次开屏素材（秒）',       NOW()),
  ('splash_max_items',       '3',    '一次最多下发几条素材',                     NOW()),
  ('api_version',            '1',    '当前接口版本',                             NOW())
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

-- 与客户端当前版本一致的一条记录。没有它的话 upgrade.php 表为空时返回 0，
-- 客户端同样会判定「已是最新」，但后台列表里会少一行参照。
INSERT INTO `xiaozhi_android_client_app_version`
  (`platform`, `channel`, `version_code`, `version_name`, `min_support_version`,
   `download_url`, `sha256`, `release_notes`, `force_update`, `enabled`, `published_at`, `created_at`, `updated_at`)
VALUES
  ('android', 'default', 15, '1.2.11', 1,
   'https://github.com/fenghui12/xiaozhi-android-client/releases/download/v1.2.11/xiaozhi-v1.2.11.apk',
   '611d3ce8a9d63be71eb9ee539aec7743b8614390feae7cf01bf0e7df99d28ca1',
   '这一版加入实时语音打断，并自动兼容新旧音频设备。', 0, 1, NOW(), NOW(), NOW())
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);
