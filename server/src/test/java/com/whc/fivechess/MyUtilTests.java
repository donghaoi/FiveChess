package com.whc.fivechess;

import com.whc.fivechess.util.MyUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三轮审查新增：标识符字段（账号/昵称）的不可见字符判定。
 *
 * 为什么要单独测工具方法：项目使用的 hutool 4.0.12 在解析 JSON 时不还原反斜杠转义
 * （"a\nb" 会变成字面反斜杠 + n），所以「换行注入」无法通过 HTTP 端到端构造出来；
 * 但校验逻辑本身必须正确 —— 一旦升级 JSON 库或换协议，这条防线就立刻生效。
 */
class MyUtilTests {

    @Test
    void controlCharsAreRejected() {
        assertTrue(MyUtil.containsInvisibleChar("a\nb"), "换行");
        assertTrue(MyUtil.containsInvisibleChar("a\rb"), "回车");
        assertTrue(MyUtil.containsInvisibleChar("a\tb"), "制表符");
        assertTrue(MyUtil.containsInvisibleChar("a\u0000b"), "NUL");
        assertTrue(MyUtil.containsInvisibleChar("a\u007Fb"), "DEL");
    }

    @Test
    void zeroWidthAndBidiCharsAreRejected() {
        assertTrue(MyUtil.containsInvisibleChar("管理\u200B员"), "零宽空格（视觉上仍是「管理员」）");
        assertTrue(MyUtil.containsInvisibleChar("a\u200Db"), "零宽连接符");
        assertTrue(MyUtil.containsInvisibleChar("a\uFEFFb"), "BOM");
        assertTrue(MyUtil.containsInvisibleChar("a\u202Eb"), "从右到左覆盖（可颠倒显示顺序）");
        assertTrue(MyUtil.containsInvisibleChar("a\u2066b"), "方向隔离符");
    }

    @Test
    void normalNicknamesAreAccepted() {
        assertFalse(MyUtil.containsInvisibleChar("棋友123"), "中文+数字");
        assertFalse(MyUtil.containsInvisibleChar("admin"), "纯英文");
        assertFalse(MyUtil.containsInvisibleChar("小明_01"), "下划线");
        assertFalse(MyUtil.containsInvisibleChar("棋手·甲"), "间隔号");
        assertFalse(MyUtil.containsInvisibleChar("玩家😀"), "emoji（代理对，不是控制字符）");
        assertFalse(MyUtil.containsInvisibleChar(""), "空串");
        assertFalse(MyUtil.containsInvisibleChar(null), "null");
    }
}
