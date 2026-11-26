package com.whc.fivechess.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码加盐哈希工具。
 * 新密码使用 PBKDF2WithHmacSHA256 加盐哈希后保存，存储格式：pbkdf2$迭代次数$盐(base64)$摘要(base64)；
 * 同时兼容早期直接保存 32 位 MD5 摘要的历史账号，校验通过后由调用方升级为新格式。
 */
public class PasswordUtil {

    private static final String SCHEME = "pbkdf2";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    //迭代次数与盐长度
    private static final int ITERATIONS = 100000;
    private static final int SALT_BYTES = 16;
private static final int KEY_BITS = 256;
    //允许的迭代次数上限：数据库中的摘要若被篡改成极大值，登录时会长时间占用 CPU，
    //形成拒绝服务；这里对异常值直接判为无效。
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成加盐哈希，同一个密码每次结果都不同
     */
    public static String hash(String rawPassword){
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = pbkdf2(rawPassword, salt, ITERATIONS);
        return SCHEME + "$" + ITERATIONS + "$" + base64(salt) + "$" + base64(key);
    }

    /**
     * 校验密码：支持加盐哈希与历史 MD5 摘要两种存储格式
     */
    public static boolean matches(String rawPassword, String stored){
        if(rawPassword==null || stored==null){
            return false;
        }
        if(stored.startsWith(SCHEME + "$")){
            String[] parts = stored.split("\\$");
            if(parts.length!=4){
                return false;
            }
            try{
int iterations = Integer.parseInt(parts[1]);
                if(iterations < 1000 || iterations > MAX_ITERATIONS){
                    return false;
                }
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                if(salt.length < 8 || expected.length < 16 || expected.length > 64){
                    return false;
                }
                byte[] actual = pbkdf2(rawPassword, salt, iterations);
                return constantTimeEquals(expected, actual);
            }catch (Exception e){
                return false;
            }
        }
//历史数据：无盐 MD5 摘要（默认字符集与 UTF-8 两种编码都尝试，兼容换环境后的历史账号）
        return stored.equalsIgnoreCase(MD5.toMD5(rawPassword))
                || stored.equalsIgnoreCase(MD5.toMD5Utf8(rawPassword));
    }

    /**
     * 是否为待升级的历史摘要（32 位十六进制 MD5）
     */
    public static boolean isLegacyHash(String stored){
        return stored!=null && stored.matches("(?i)[0-9a-f]{32}");
    }

    private static byte[] pbkdf2(String rawPassword, byte[] salt, int iterations){
        try{
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        }catch (Exception e){
            throw new IllegalStateException("密码摘要计算失败", e);
        }
    }

    private static String base64(byte[] data){
        return Base64.getEncoder().encodeToString(data);
    }

    private static boolean constantTimeEquals(byte[] expected, byte[] actual){
        if(expected.length != actual.length){
            return false;
        }
        int diff = 0;
        for(int i = 0; i < expected.length; i++){
            diff |= expected[i] ^ actual[i];
        }
        return diff == 0;
    }
}