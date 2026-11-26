package com.whc.fivechess.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class MD5 {
    /**
     * MD5加密算法（保留原行为：使用 JVM 默认字符集，兼容历史摘要）
     * @param s
     */
    public static String toMD5(String s) {
        return toMD5(s.getBytes());
    }

    /**
     * 显式以 UTF-8 计算 MD5：历史摘要在某些部署环境下按默认字符集生成，
     * 换服务器/操作系统后可能对不上，校验时两种编码都会尝试一次
     * @param s
     */
    public static String toMD5Utf8(String s) {
        return toMD5(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String toMD5(byte[] btInput) {
        char[] hexDigits = { '0', '1', '2', '3', '4',
                '5', '6', '7', '8', '9',
                'a', 'b', 'c', 'd', 'e', 'f' };
        try {
            //获得MD5摘要算法的 MessageDigest 对象
            MessageDigest mdInst = MessageDigest.getInstance("MD5");
            //使用指定的字节更新摘要
            mdInst.update(btInput);
            //获得密文
            byte[] md = mdInst.digest();
            //把密文转换成十六进制的字符串形式
            int j = md.length;
            char[] str = new char[j * 2];
            int k = 0;
            for (byte byte0 : md) {
                str[k++] = hexDigits[byte0 >>> 4 & 0xf];
                str[k++] = hexDigits[byte0 & 0xf];
            }
            return new String(str);
        }
        catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        System.out.println(MD5.toMD5("123"));
    }
}
