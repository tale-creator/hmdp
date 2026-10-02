package com.hmdp.utils.ai;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中文友好的文本分片器。
 * <p>
 * 策略：先按标点切句，再把句子拼成不超过 chunkSize 的片段，相邻片段保留 overlap 个字符的重叠，
 * 避免一个完整语义被切断后检索不到。
 */
public class TextSplitter {

    private TextSplitter() {
    }

    /**
     * 把长文本切成若干分片。
     *
     * @param text       原文
     * @param chunkSize  单片最大字符数
     * @param overlap    相邻分片重叠字符数，必须小于 chunkSize
     */
    public static List<String> split(String text, int chunkSize, int overlap) {
        if (StrUtil.isBlank(text)) {
            return Collections.emptyList();
        }
        if (chunkSize <= 0) {
            chunkSize = 400;
        }
        if (overlap < 0 || overlap >= chunkSize) {
            overlap = chunkSize / 6;
        }

        String normalized = normalize(text);
        if (normalized.length() <= chunkSize) {
            return Collections.singletonList(normalized);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : splitSentences(normalized)) {
            // 单句本身就超长，只能硬切
            if (sentence.length() > chunkSize) {
                flush(chunks, current);
                int step = chunkSize - overlap;
                for (int i = 0; i < sentence.length(); i += step) {
                    chunks.add(sentence.substring(i, Math.min(sentence.length(), i + chunkSize)));
                }
                continue;
            }
            // 放不下了，先把当前片收掉，再带上尾部重叠开新片
            if (current.length() + sentence.length() > chunkSize) {
                String done = flush(chunks, current);
                if (overlap > 0 && done.length() > overlap) {
                    current.append(done, done.length() - overlap, done.length());
                }
            }
            current.append(sentence);
        }
        flush(chunks, current);
        return chunks;
    }

    /**
     * 把标题和正文拼成待索引的文档，标题重复一次以提升标题词的召回。
     */
    public static String buildDocument(String title, String content) {
        String t = StrUtil.blankToDefault(title, "").trim();
        String c = StrUtil.blankToDefault(content, "").trim();
        if (t.isEmpty()) {
            return c;
        }
        if (c.isEmpty()) {
            return t;
        }
        return t + "\n" + t + "\n" + c;
    }

    /** 收掉当前缓冲区，返回被收掉的内容（已 trim），空则不收 */
    private static String flush(List<String> chunks, StringBuilder current) {
        String done = current.toString().trim();
        current.setLength(0);
        if (!done.isEmpty()) {
            chunks.add(done);
        }
        return done;
    }

    /** 统一换行与空白，压缩多余空行 */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\u000B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /** 按中英文标点切句 */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (isSentenceEnd(c, i + 1 < text.length() ? text.charAt(i + 1) : '\0')) {
                sentences.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            sentences.add(sb.toString());
        }
        return sentences;
    }

    private static boolean isSentenceEnd(char c, char next) {
        switch (c) {
            // 中文标点后面不需要空格
            case '。':
            case '！':
            case '？':
            case '；':
            case '\n':
                return true;
            // 英文标点要求后面是空白或结尾，避免把 3.5、1,000 这类拆开
            case '.':
            case '!':
            case '?':
            case ';':
                return next == '\0' || Character.isWhitespace(next);
            default:
                return false;
        }
    }
}
