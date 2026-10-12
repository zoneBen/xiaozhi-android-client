<?php

declare(strict_types=1);

/**
 * 退出登录：清空会话并回登录页。
 *
 * 这里刻意允许 GET：它只销毁「当前这个人」的会话，不碰数据库，也不代表任何持久化状态
 * 变更，最坏情况是被人用一张 <img src="logout.php"> 强制下线 —— 这是可接受的。真正
 * 会改数据的 POST 才必须带 CSRF。
 */

require_once __DIR__ . '/lib.php';

admin_logout_user();
admin_redirect('login.php');
