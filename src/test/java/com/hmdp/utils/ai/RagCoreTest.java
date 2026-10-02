package com.hmdp.utils.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 核心纯逻辑测试，不依赖 Spring 容器、MySQL、Redis。
 */
class RagCoreTest {

    // ---------------- TextSplitter ----------------

    @Test
    @DisplayName("短文本不切分，原样返回一条")
    void shortTextStaysWhole() {
        List<String> chunks = TextSplitter.split("今天去了西湖边那家咖啡店，拿铁很香。", 400, 60);
        assertEquals(1, chunks.size());
        assertEquals("今天去了西湖边那家咖啡店，拿铁很香。", chunks.get(0));
    }

    @Test
    @DisplayName("空输入返回空列表")
    void blankInputYieldsNothing() {
        assertTrue(TextSplitter.split(null, 400, 60).isEmpty());
        assertTrue(TextSplitter.split("   ", 400, 60).isEmpty());
        assertTrue(TextSplitter.split("", 400, 60).isEmpty());
    }

    @Test
    @DisplayName("长文本每个分片都不超过 chunkSize，且没有空片")
    void everyChunkRespectsLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("这是第").append(i).append("句话，用来把文本撑长一些。");
        }
        int chunkSize = 400;
        List<String> chunks = TextSplitter.split(sb.toString(), chunkSize, 60);

        assertTrue(chunks.size() > 1, "长文本应该被切成多片，实际 " + chunks.size());
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty(), "不应出现空分片");
            assertTrue(chunk.length() <= chunkSize,
                    "分片超长：" + chunk.length() + " > " + chunkSize);
        }
    }

    @Test
    @DisplayName("不丢内容：分片拼起来能覆盖原文的关键信息")
    void contentIsNotLost() {
        String text = "第一段讲的是西湖的景色。第二段讲的是龙井虾仁这道菜。第三段讲的是灵隐寺。";
        List<String> chunks = TextSplitter.split(text, 20, 5);
        String joined = String.join("", chunks);
        assertTrue(joined.contains("西湖"), "丢了西湖");
        assertTrue(joined.contains("龙井虾仁"), "丢了龙井虾仁");
        assertTrue(joined.contains("灵隐寺"), "丢了灵隐寺");
    }

    @Test
    @DisplayName("超长单句（无标点）也能被硬切，不会返回超长分片")
    void oversizedSentenceIsHardSplit() {
        String noPunctuation = "啊".repeat(1000);
        List<String> chunks = TextSplitter.split(noPunctuation, 100, 10);
        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 100, "硬切后仍然超长：" + chunk.length());
        }
    }

    @Test
    @DisplayName("overlap 参数非法时自动修正，不会死循环")
    void illegalOverlapIsCorrected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是一句测试文本。");
        }
        // overlap >= chunkSize 是非法值，内部应自动纠正
        List<String> chunks = TextSplitter.split(sb.toString(), 50, 999);
        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 50);
        }
    }

    @Test
    @DisplayName("buildDocument 把标题带上（出现两次以提升召回）")
    void buildDocumentIncludesTitle() {
        String doc = TextSplitter.buildDocument("西湖咖啡", "拿铁很好喝");
        assertTrue(doc.contains("西湖咖啡"));
        assertEquals(2, doc.split("西湖咖啡", -1).length - 1, "标题应出现两次");
        assertTrue(doc.contains("拿铁很好喝"));
    }

    @Test
    @DisplayName("buildDocument 对空标题/空正文都能处理")
    void buildDocumentHandlesBlanks() {
        assertEquals("正文", TextSplitter.buildDocument(null, "正文"));
        assertEquals("标题", TextSplitter.buildDocument("标题", null));
        assertEquals("", TextSplitter.buildDocument(null, null));
    }

    @Test
    @DisplayName("小数不会被英文句点误切")
    void decimalIsNotSplit() {
        List<String> chunks = TextSplitter.split("人均消费 3.5 元，评分 4.8 分，很划算。", 400, 60);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("3.5"));
        assertTrue(chunks.get(0).contains("4.8"));
    }

    // ---------------- VectorUtils ----------------

    @Test
    @DisplayName("向量 JSON 往返一致")
    void vectorJsonRoundTrip() {
        float[] original = {0.1f, -0.25f, 0.333f, 1.0f};
        float[] parsed = VectorUtils.parse(VectorUtils.toJson(original));
        assertArrayEquals(original, parsed, 1e-6f);
    }

    @Test
    @DisplayName("非法或空 JSON 解析成 null，表示未向量化")
    void badJsonParsesToNull() {
        assertNull(VectorUtils.parse(null));
        assertNull(VectorUtils.parse(""));
        assertNull(VectorUtils.parse("   "));
        assertNull(VectorUtils.parse("not json"));
    }

    @Test
    @DisplayName("余弦相似度：同向量为 1，正交为 0，反向为 -1")
    void cosineBasics() {
        float[] a = {1f, 2f, 3f};
        assertEquals(1.0, VectorUtils.cosine(a, a), 1e-6);

        float[] orthogonal = {0f, 1f, 0f};
        float[] other = {1f, 0f, 0f};
        assertEquals(0.0, VectorUtils.cosine(orthogonal, other), 1e-6);

        float[] negated = {-1f, -2f, -3f};
        assertEquals(-1.0, VectorUtils.cosine(a, negated), 1e-6);
    }

    @Test
    @DisplayName("维度不一致、空向量、null 一律返回 0 而不是抛异常")
    void cosineEdgeCases() {
        assertEquals(0.0, VectorUtils.cosine(null, new float[]{1f}));
        assertEquals(0.0, VectorUtils.cosine(new float[]{1f}, null));
        assertEquals(0.0, VectorUtils.cosine(new float[0], new float[0]));
        assertEquals(0.0, VectorUtils.cosine(new float[]{1f, 2f}, new float[]{1f}));
        // 零向量没有方向，应视为不相关
        assertEquals(0.0, VectorUtils.cosine(new float[]{0f, 0f}, new float[]{1f, 2f}));
    }
}
