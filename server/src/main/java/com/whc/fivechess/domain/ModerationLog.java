package com.whc.fivechess.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员操作记录（禁言/解除禁言/封号/解散房间等）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("moderation_log")
public class ModerationLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer adminId;    //操作人id
    private String adminLogin;  //操作人账号
    private Integer targetId;   //被操作用户id（解散房间时可为空）
    private String targetLogin; //被操作用户账号
    private String action;      //mute / unmute / ban / dismiss-room
    private String detail;      //补充说明
    private String createdAt;   //操作时间
}