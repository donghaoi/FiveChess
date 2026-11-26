package com.whc.fivechess.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SensitiveWordUtil {
    // 违规词列表，按需扩充
    private static final List<String> BAD_WORDS = Arrays.asList(
            //===== 毒品相关（原料、成品、暗语）=====
            "海洛因","冰毒","k粉","可卡因","大麻","鸦片","摇头丸","杜冷丁","吗啡","古柯","三唑仑",
            "LSD","安眠酮","麻古","神仙水","奶茶粉","邮票毒品","制毒","贩毒","吸毒","料子","白粉",
            "黄皮","大烟","罂粟","麻黄素","冰油","上头","飘货","烟油","嗑药","溜粉","扎针",
            "heroin","cocaine","meth","weed","大麻油","甲卡西酮","芬太尼",

            //===== 色情涉黄（招嫖、淫秽资源、服务）=====
            "嫖娼","卖淫","约炮","上门服务","特殊服务","裸聊","色情视频","黄片","三级片","淫秽",
            "成人资源","原味","SM陪玩","外围女","援交","私密视频","资源网盘","色情直播",
            "约睡","同城约","有偿陪侍","av","小电影","露点","淫乱","催情药","成人片",

            //===== 赌博相关（平台、博彩、赌资、跑分）=====
            "赌博","博彩","赌球","六合彩","时时彩","线上赌场","棋牌赌","跑分","洗码","赌资",
            "下注","赌庄","博彩平台","私彩","打鱼赌博","龙虎斗","百家乐","串码","投注","博彩网址",
            "赌币","赌博代理","杀猪盘赌","外围赌球"
    );

    /**
     * 预处理后的词条。
     * 词表固定，把「是否纯 ASCII」「小写形式」「掩码」都提前算好：
     * 原先每条消息对每个词都要做一次 toLowerCase 与一次 matches 正则编译，
     * 2000 字的聊天记录要重复上百次；鉴权时回填 50 条历史消息会被放大成明显的 CPU 开销。
     */
    private static final List<Word> WORDS;

    static {
        List<Word> list = new ArrayList<>(BAD_WORDS.size());
        for (String word : BAD_WORDS) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            list.add(new Word(word, word.toLowerCase(Locale.ROOT), word.matches("[A-Za-z0-9]+")));
        }
        WORDS = Collections.unmodifiableList(list);
    }

    private static final class Word {
        final String text;
        final String lower;
        final String star;
        final boolean ascii;

        Word(String text, String lower, boolean ascii) {
            this.text = text;
            this.lower = lower;
            this.ascii = ascii;
            this.star = star(text.length());
        }
    }

public static String filterMsg(String msg){
        if(msg == null || msg.isEmpty()) return msg;
        String current = msg;
        //正常消息基本不含违规词：这里只算一次小写，命中后才重算，
        //避免对每个词都重新 toLowerCase 一遍整段文本
        String lower = current.toLowerCase(Locale.ROOT);
        for(Word word : WORDS){
            String next = replaceIgnoreCase(current, lower, word);
            if(next != current){
                current = next;
                lower = current.toLowerCase(Locale.ROOT);
            }
        }
        return current;
    }

    /**
     * 判断文本是否包含违规词。
     * 复用 filterMsg 的词表与整词匹配规则，避免注册校验和聊天过滤口径不一致。
     */
    public static boolean containsSensitiveWord(String text){
        if(text == null || text.isEmpty()){
            return false;
        }
        return !filterMsg(text).equals(text);
    }

    /**
     * 生成与违规词等长的掩码，保证替换后原文长度不变
     */
    private static String star(int length){
        return new String(new char[length]).replace('\0', '*');
    }

    /**
     * 忽略大小写替换；纯英文/数字词按整词匹配，
     * 避免 have、avatar、save 这类单词里包含 av 而被误伤。
     * 没有任何命中时返回原字符串引用，供调用方判断是否需要重算小写文本。
     */
    private static String replaceIgnoreCase(String src, String lowerSrc, Word word){
        // 个别字符转小写会改变长度（例如某些特殊 Unicode 字符），此时改用正则忽略大小写替换，
        // 不能退化为区分大小写的 replace，否则原文大小写不同时会漏过滤。
        if(lowerSrc.length() != src.length()){
            if(!lowerSrc.contains(word.lower)){
                return src;
            }
            return Pattern.compile(Pattern.quote(word.text), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(src)
                    .replaceAll(Matcher.quoteReplacement(word.star));
        }
        StringBuilder sb = null;
        int from = 0;
        while(true){
            int idx = lowerSrc.indexOf(word.lower, from);
            if(idx < 0){
                if(sb == null){
                    return src;
                }
                sb.append(src, from, src.length());
                return sb.toString();
            }
            if(word.ascii && !isWordBoundary(src, idx, word.text.length())){
                //整词不匹配：跳过这个词的开头继续找。
                //注意此时 sb 可能还没创建，from 已经前进过，首次创建必须从 0 开始补全前缀
                from = idx + 1;
                continue;
            }
            if(sb == null){
                sb = new StringBuilder(src.length());
                sb.append(src, 0, idx);
            }else{
                sb.append(src, from, idx);
            }
            sb.append(word.star);
            from = idx + word.text.length();
        }
    }

    private static boolean isWordBoundary(String src, int idx, int len){
        boolean leftOk = idx == 0 || !isAsciiLetterOrDigit(src.charAt(idx - 1));
        int end = idx + len;
        boolean rightOk = end >= src.length() || !isAsciiLetterOrDigit(src.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isAsciiLetterOrDigit(char c){
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
