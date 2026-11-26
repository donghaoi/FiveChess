-- ============================================================================
-- 五子棋（FiveChess）建库脚本 —— 首次建库专用，一个文件一条命令搞定
--
--   mysql -u<账号> -p --default-character-set=utf8mb4 < 数据库/chess.sql
--   （Windows 示例：mysql -uroot -p --default-character-set=utf8mb4 < C:\FiveChess\数据库\chess.sql）
--   TiDB Cloud：控制台「导入」指向本文件，目标库选 chess 即可。
--
-- 内容：自动建库 chess（已存在则跳过）+ 7 张表 + 内置管理员账号
-- 库名：想换名字只改下面 CREATE DATABASE 与 USE 两行的 `chess`
-- 兼容：MySQL 8 / TiDB（含 TiDB Cloud），全文不含触发器、存储过程；
--       表级排序规则用的是 utf8mb4_0900_ai_ci（MySQL 8 默认），MariaDB 不支持这个
--       规则，若要在 MariaDB 上跑，把全文的 utf8mb4_0900_ai_ci 换成 utf8mb4_general_ci。
-- 说明：本项目只维护这一个建库脚本，字段与索引以本文件为唯一来源，不提供增量迁移脚本。
--       脚本是 DROP TABLE + CREATE 的重建模式，只用于首次建库；
--       已有数据的库执行会清空这 7 张表，老库升级请先删库再用本文件重建。
-- ============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `chess` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `chess`;

-- ---------------------------------------------------------------------------
-- 1. user：账号表
--    muted / banned 必须写成 tinyint(1)：后端实体字段是 Boolean，JDBC 只在
--    tinyint(1) 上映射为布尔，写成 tinyint 会变成整型、实体赋值类型不匹配。
--
--    封号 IP 的判定依据就是本表的 register_ip 与 last_ip 两列，不再另设
--    ban_ip / ban_ip_last 快照列：那两个值必然等于封号当时的这两个字段，
--    多存一份只会带来「解封要记得两边都清」的隐患。
--    自增初值不要写 AUTO_INCREMENT=n：显式插入 id=1 的管理员后，下一个
--    注册用户会自动变成 2，写死 n 会让新库的 id 从 n 开始跳。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `login` varchar(20) DEFAULT NULL COMMENT '登录账号',
  `name` varchar(20) DEFAULT NULL COMMENT '昵称',
  `password` varchar(100) DEFAULT NULL COMMENT 'PBKDF2 加盐哈希，不存明文',
  `time` varchar(30) DEFAULT NULL COMMENT '注册时间',
  `muted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否被管理员禁言',
  `banned` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否被管理员封号',
  `ban_time` varchar(30) DEFAULT NULL COMMENT '封号时间：banned=1 时有值，解封时清空',
  `register_ip` varchar(64) DEFAULT NULL COMMENT '注册时的客户端IP（注册写入，此后不变）',
  `last_ip` varchar(64) DEFAULT NULL COMMENT '最近一次登录成功的客户端IP（每次登录覆盖）',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像地址：为空时每次登录随机分配内置头像；上传后固定为 /avatar/{用户id}',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_name` (`name`),
  UNIQUE KEY `uk_user_login` (`login`),
  KEY `idx_user_ban` (`banned`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 内置管理员：账号 admin / 昵称「管理员」，口令由下面这行哈希决定，上线前务必改掉。
-- 显式列出列名：VALUES 全列写法在表加字段后必然列数不匹配（曾报 ERROR 1136）。
-- 显式写 id=1 不会把自增抬高，下一个注册用户必然是 2；建表语句里不要写 AUTO_INCREMENT=n，
-- 否则新库的第一个用户会从 n 开始（曾因写成 AUTO_INCREMENT=30 导致 id 从 30 起跳）。
INSERT INTO `user` (`id`,`login`,`name`,`password`,`time`,`muted`,`banned`,`ban_time`,`avatar`)
VALUES (1,'admin','管理员','pbkdf2$100000$5P19h4J60Tml4Jc9DeSQIQ==$wXXC9QFMgemKdO2VxskRbBPmV3XLqUG8T975FYoGD7I=','2026-11-16 12:29:28',0,0,NULL,NULL);

-- ---------------------------------------------------------------------------
-- 2. user_avatar：用户上传的头像图片本体
--    前后端分离部署时后端写不到前端静态目录、容器重启还会丢文件，所以图片直接入库，
--    由后端 GET /avatar/{用户id} 输出；user.avatar 只存路径不存图。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `user_avatar`;
CREATE TABLE `user_avatar` (
  `user_id` int NOT NULL COMMENT '用户id（user.id）：一个用户只保留最新一张',
  `content` mediumblob NOT NULL COMMENT '图片二进制（前端已压到 256x256，单张上限 3MB）',
  `content_type` varchar(32) NOT NULL DEFAULT 'image/jpeg' COMMENT 'image/jpeg、image/png、image/gif、image/webp',
  `version` bigint NOT NULL DEFAULT '0' COMMENT '版本号（上传时间毫秒），拼在访问地址后用于刷新浏览器缓存',
  `updated_at` varchar(30) DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 3. record：对局战绩
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `record`;
CREATE TABLE `record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `player1` int DEFAULT NULL,
  `name1` varchar(255) DEFAULT NULL,
  `player2` int DEFAULT NULL,
  `name2` varchar(255) DEFAULT NULL,
  `start` varchar(30) DEFAULT NULL COMMENT '开始时间',
  `end` varchar(30) DEFAULT NULL COMMENT '结束时间',
  `win` int DEFAULT NULL COMMENT '胜方用户id；0 表示和棋',
  `steps` int DEFAULT NULL COMMENT '总步数',
  `data` varchar(50) DEFAULT NULL COMMENT '棋谱标识',
  `type` varchar(20) DEFAULT NULL COMMENT '对局类型',
  PRIMARY KEY (`id`),
  KEY `idx_record_p1` (`player1`,`start`,`id`),
  KEY `idx_record_p2` (`player2`,`start`,`id`),
  KEY `idx_record_data` (`data`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 4. game：进行中的棋局快照（JSON 序列化后的二进制）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `game`;
CREATE TABLE `game` (
  `id` varchar(50) NOT NULL,
  `data` blob,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 5. chat_public_record：公共聊天记录
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `chat_public_record`;
CREATE TABLE `chat_public_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `player` int DEFAULT NULL COMMENT '发言者用户id',
  `nick` varchar(20) DEFAULT NULL COMMENT '发言者昵称',
  `content` varchar(2000) NOT NULL COMMENT '原始消息内容（未过滤敏感词）',
  `ip` varchar(64) DEFAULT NULL COMMENT '发言者实时IP',
  `created_at` varchar(30) DEFAULT NULL COMMENT '发言时间',
  PRIMARY KEY (`id`),
  KEY `idx_pub_created` (`created_at`),
  KEY `idx_pub_player` (`player`),
  KEY `idx_pub_ip` (`ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 6. chat_room_record：房间内聊天记录
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `chat_room_record`;
CREATE TABLE `chat_room_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `player` int DEFAULT NULL COMMENT '发言者用户id',
  `nick` varchar(20) DEFAULT NULL COMMENT '发言者昵称',
  `room_id` varchar(64) DEFAULT NULL COMMENT '房间号',
  `content` varchar(2000) NOT NULL COMMENT '原始消息内容（未过滤敏感词）',
  `ip` varchar(64) DEFAULT NULL COMMENT '发言者实时IP',
  `created_at` varchar(30) DEFAULT NULL COMMENT '发言时间',
  PRIMARY KEY (`id`),
  KEY `idx_room_created` (`created_at`),
  KEY `idx_room_room` (`room_id`,`id`),
  KEY `idx_room_player` (`player`),
  KEY `idx_room_ip` (`ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 7. moderation_log：管理员操作记录（禁言 / 封号 / 解散房间）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `moderation_log`;
CREATE TABLE `moderation_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `admin_id` int DEFAULT NULL COMMENT '操作人用户id',
  `admin_login` varchar(20) DEFAULT NULL COMMENT '操作人账号',
  `target_id` int DEFAULT NULL COMMENT '被操作用户id（解散房间时为空）',
  `target_login` varchar(20) DEFAULT NULL COMMENT '被操作用户账号',
  `action` varchar(20) DEFAULT NULL COMMENT 'mute/unmute/ban/unban/dismiss-room',
  `detail` varchar(255) DEFAULT NULL COMMENT '补充说明',
  `created_at` varchar(30) DEFAULT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_mod_target` (`target_id`),
  KEY `idx_mod_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 管理员口令变更（上线前务必执行一次）
-- 口令只能写 PBKDF2 加盐哈希，不能写明文。最省事的做法：
--   ① 用系统注册一个临时账号，口令设为想给 admin 用的那个；
--   ② 把它的哈希复制给 admin 行，再删掉临时账号：
--      UPDATE `user` a JOIN `user` b ON b.`login` = '临时账号'
--         SET a.`password` = b.`password` WHERE a.`login` = 'admin';
--      DELETE FROM `user` WHERE `login` = '临时账号';
--   也可以调用代码里的 PasswordUtil.hash("新口令") 生成同格式字符串后直接 UPDATE。
-- ---------------------------------------------------------------------------
