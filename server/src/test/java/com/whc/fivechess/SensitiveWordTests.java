package com.whc.fivechess;

import com.whc.fivechess.util.SensitiveWordUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词过滤的纯单元测试。
 *
 * 第二轮审查把过滤实现改成「词表预编译 + 命中才重算小写」，这里锁定行为不变：
 * 大小写、整词边界、多词连续替换、长度保持。
 */
class SensitiveWordTests {

    @Test
    void masksChineseWordsAndKeepsLength() {
        String input = "海洛因和冰毒都不要";
        String out = SensitiveWordUtil.filterMsg(input);
        assertEquals(input.length(), out.length(), "掩码替换不能改变长度");
        assertTrue(out.startsWith("***"), "应替换为等长星号：" + out);
        assertFalse(out.contains("海洛因"), "原文违规词应被替换干净");
        assertFalse(out.contains("冰毒"), "第二个违规词也应被替换");
        assertTrue(out.endsWith("都不要"), "非违规部分必须原样保留：" + out);
    }

    @Test
    void masksAsciiWordsIgnoringCase() {
        assertEquals("**", SensitiveWordUtil.filterMsg("AV"), "大写英文词应被替换");
        assertEquals("******", SensitiveWordUtil.filterMsg("Heroin"), "混合大小写应被替换且长度不变");
    }

    @Test
    void doesNotTouchWordsThatMerelyContainTheKeyword() {
        //整词规则：have / save / avatar 里的 av 不能被误伤
        assertEquals("have a save", SensitiveWordUtil.filterMsg("have a save"));
        assertEquals("aav", SensitiveWordUtil.filterMsg("aav"));
    }

    @Test
    void replacesEveryStandaloneOccurrence() {
        assertEquals("** ** **", SensitiveWordUtil.filterMsg("av av av"));
        assertEquals("这是**视频", SensitiveWordUtil.filterMsg("这是av视频"), "中英文混排时按整词边界替换");
    }

    @Test
    void filterAndContainsUseTheSameRule() {
        assertTrue(SensitiveWordUtil.containsSensitiveWord("这里有海洛因"));
        assertTrue(SensitiveWordUtil.containsSensitiveWord("say AV now"));
        assertFalse(SensitiveWordUtil.containsSensitiveWord("have a save"));
        assertFalse(SensitiveWordUtil.containsSensitiveWord("普通聊天内容"));
        assertFalse(SensitiveWordUtil.containsSensitiveWord(null));
        assertFalse(SensitiveWordUtil.containsSensitiveWord(""));
    }

    @Test
    void nullAndEmptyAreReturnedAsIs() {
        assertEquals(null, SensitiveWordUtil.filterMsg(null));
        assertEquals("", SensitiveWordUtil.filterMsg(""));
    }

    @Test
    void longMessageWithoutHitsIsUnchanged() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("正常的聊天内容，abcd ");
        }
        String input = sb.toString();
        assertEquals(input, SensitiveWordUtil.filterMsg(input));
    }
}
