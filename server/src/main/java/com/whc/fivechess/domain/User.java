package com.whc.fivechess.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class User {
    @TableId(type = IdType.AUTO)
    private Integer id; //主键
    private String login; //登录名
    private String name; //昵称
    private String password; //密码（PBKDF2 加盐哈希，兼容历史 MD5 摘要）
    private String time; //注册时间
    //注意：这两个字段不能给 false 初值。MyBatis-Plus 的 updateById 默认只忽略 null，
    //若初值为 false，任何"只改其它字段"的局部更新（头像、密码升级、禁言）都会把
    //muted/banned 一并写回 0，等于静默解禁/解封。保持 null 由数据库 NOT NULL DEFAULT 0 兜底。
    private Boolean muted; //是否被管理员禁言
    private Boolean banned; //是否被管理员封号
    private String banTime; //封号时间
    private String registerIp; //注册时的客户端 IP（首次注册写入，此后不变），封号时按它拦注册
    private String lastIp; //最近一次登录成功时的客户端 IP（每次登录覆盖），封号时按它拦注册
    private String avatar; //头像地址：普通用户上传后固定为本人文件，为空时每次登录随机分配内置头像
    @TableField(exist = false)
    private String token; //登录令牌：仅随登录响应下发，不落库
    @TableField(exist = false)
    private Boolean admin; //是否为管理员：仅随登录响应下发，不落库
}