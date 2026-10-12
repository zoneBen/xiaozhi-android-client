<?php
/**
 * PHP 版本守卫。每个接口的第一行都要先 require 这个文件：
 *
 *     require_once __DIR__ . '/../guard.php';
 *
 * 为什么要单独一个文件、而不是把判断写进 common.php：PHP 是先解析整个文件再执行的，
 * common.php 里有 `match` 和 `mixed` 这些 8.0 才有的语法，在 7.x 的机器上会直接
 * parse error —— 那时里面的任何判断都来不及跑。而 parse error 的输出是一片空白，
 * 部署的人只会看到「接口没反应」，根本猜不到是版本问题。
 *
 * 所以这里刻意只用 PHP 5 也能解析的语法：版本不够时至少能回一句人话。
 */

if (PHP_VERSION_ID < 80000) {
    if (!headers_sent()) {
        header('Content-Type: application/json; charset=utf-8', true, 500);
    }
    echo json_encode(array(
        'code'    => 500,
        'message' => '服务器 PHP 版本过低：本接口需要 PHP 8.0 或更高，当前为 ' . PHP_VERSION,
    ));
    exit;
}
