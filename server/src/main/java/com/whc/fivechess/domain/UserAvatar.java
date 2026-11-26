package com.whc.fivechess.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户上传的头像（图片二进制直接存数据库）。
 *
 * 之所以不落文件：前后端分离部署时后端容器盘与前端静态目录不是同一块磁盘，
 * 写文件既到不了前端，容器重启还会丢；存库后任何实例都能读到同一份数据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("user_avatar")
public class UserAvatar {
    @TableId(value = "user_id", type = IdType.INPUT)
    private Integer userId;      //用户id（主键，一个用户只保留最新一张）
    private byte[] content;      //图片二进制（上传时已压到 256x256，单张上限 3MB）
    private String contentType;  //image/jpeg、image/png、image/gif、image/webp
    private Long version;        //版本号（上传时间毫秒），拼在访问地址后用于刷新缓存
    private String updatedAt;    //更新时间
}
