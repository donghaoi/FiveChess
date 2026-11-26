package com.whc.fivechess.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共大厅聊天记录（用于事后追溯）。
 * content 保存用户发送的原始内容（未过滤敏感词），界面展示的仍是过滤后内容。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("chat_public_record")
public class ChatPublicRecord {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer player; //发言者用户id
    private String nick;    //发言者昵称
    private String content; //原始消息内容（未过滤敏感词）
    private String ip;      //发言者实时IP
    private String createdAt; //发言时间
}