package com.whc.fivechess.util;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MyUtil {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 格式化时间。
     * SimpleDateFormat 本身线程不安全，HTTP 线程与多个 WebSocket 连接线程会同时调用，
     * 共用一个静态实例会得到错乱的时间字符串甚至抛出异常，因此这里每次调用新建实例。
     */
    public static String format(Date date){
        return new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    /**
     * 当前时间，格式：yyyy-MM-dd HH:mm:ss
     */
    public static String now(){
        return format(new Date());
    }

    public static ByteArrayOutputStream getByteArrayOutputStream(){
        return new ByteArrayOutputStream();
    }

    /**
     * 去掉全部空白与不可见字符（零宽、双向控制符、BOM）。
     *
     * 用于「保留名」比较：昵称 "管理 员"、"管理\u200B员"、"管理员 " 在界面上与被保护的
     * 「管理员」几乎无法区分，若按原字符串比较就会放过这些变体，等于允许冒充。
     * 只做比较用的归一化，不改写用户实际昵称。
     */
    public static String stripInvisible(String value){
        if(value == null){
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(Character.isWhitespace(c) || Character.isSpaceChar(c)){
                continue;
            }
            if(c < 0x20 || c == 0x7F){
                continue;
            }
            if(c == 0x200B || c == 0x200C || c == 0x200D || c == 0x200E || c == 0x200F
                    || c == 0xFEFF
                    || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069)){
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 是否包含不可见字符：控制字符（换行、回车、制表、NUL 等）与零宽/双向控制符。
     *
     * 用于账号、昵称这类「标识符」字段（会被写进日志、大厅、房间与聊天前缀）：
     * 1) 回车换行可以伪造日志行（日志注入），也能破坏列表展示；
     * 2) 零宽字符能让「admin」与「admin+零宽」视觉完全一致，配合昵称唯一索引可以冒充他人。
     * 自由文本（聊天内容）不走这个判断，那里换行是正常输入。
     */
    public static boolean containsInvisibleChar(String value){
        if(value == null){
            return false;
        }
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c < 0x20 || c == 0x7F){
                return true;
            }
            if(c == 0x200B || c == 0x200C || c == 0x200D || c == 0x200E || c == 0x200F
                    || c == 0xFEFF
                    || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069)){
                return true;
            }
        }
        return false;
    }
}