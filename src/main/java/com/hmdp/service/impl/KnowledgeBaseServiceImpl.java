package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.KbChunkHit;
import com.hmdp.dto.KbStatsVO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.KbChunk;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IKbChunkService;
import com.hmdp.service.KnowledgeBaseService;
import com.hmdp.utils.ai.AiClient;
import com.hmdp.utils.ai.TextSplitter;
import com.hmdp.utils.ai.VectorUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库实现。
 * <p>
 * 检索不用向量数据库：分片向量以 JSON 存在 MySQL，按用户取出后在内存算余弦相似度。
 * 单用户几千条分片的量级下完全够用，省掉一整套中间件。
 */
@Slf4j
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    /** 关键词兜底时忽略的单字，避免"的/了/是"这类字把分数刷高 */
    private static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '是', '在', '我', '有', '和', '就', '不', '人', '都', '一',
            '上', '也', '很', '到', '说', '要', '去', '你', '会', '着', '没', '看',
            '好', '这', '那', '吗', '呢', '吧', '啊', '把', '被', '给', '让', '对');

    @Resource
    private IKbChunkService kbChunkService;

    /**
     * 直接用 Mapper 查博客，而不是注入 IBlogService。
     * 因为 BlogServiceImpl 反过来要依赖本类，注入 IBlogService 会构成循环依赖。
     */
    @Resource
    private BlogMapper blogMapper;

    @Resource
    private AiClient aiClient;

    @Resource
    private AiProperties props;

    @Async("kbExecutor")
    @Override
    public void indexBlog(Blog blog) {
        if (blog == null || blog.getId() == null || blog.getUserId() == null) {
            return;
        }
        try {
            String document = TextSplitter.buildDocument(blog.getTitle(), blog.getContent());
            if (StrUtil.isBlank(document)) {
                return;
            }
            int count = indexDocument(blog.getUserId(), blog.getId(), SOURCE_BLOG, blog.getTitle(), document);
            log.debug("博客已入库 blogId={} 分片数={}", blog.getId(), count);
        } catch (Exception e) {
            // 索引失败绝不能让用户发博客失败
            log.error("索引博客失败 blogId={}", blog.getId(), e);
        }
    }

    @Override
    public int indexDocument(Long userId, Long blogId, String sourceType, String sourceName, String text) {
        if (userId == null || StrUtil.isBlank(text)) {
            return 0;
        }

        List<String> pieces = TextSplitter.split(text, props.getChunkSize(), props.getChunkOverlap());
        if (pieces.isEmpty()) {
            return 0;
        }

        // 先做耗时的网络调用，再动数据库：避免把 embedding 请求包在事务里长时间占用连接
        List<float[]> vectors = tryEmbed(pieces);

        // 同一篇来源重复索引时先清旧数据
        if (blogId != null) {
            kbChunkService.removeByBlogId(blogId);
        }

        List<KbChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            float[] vector = (vectors != null && i < vectors.size()) ? vectors.get(i) : null;
            chunks.add(new KbChunk()
                    .setUserId(userId)
                    .setBlogId(blogId)
                    .setSourceType(sourceType)
                    .setSourceName(StrUtil.maxLength(sourceName, 250))
                    .setChunkIndex(i)
                    .setContent(pieces.get(i))
                    .setEmbedding(vector == null ? null : VectorUtils.toJson(vector))
                    .setEmbeddingDim(vector == null ? null : vector.length));
        }
        kbChunkService.saveBatch(chunks);
        return chunks.size();
    }

    @Override
    public int reindexAllBlogs() {
        // 清空全部博客来源的分片：这样已被删除的博客也会一并清掉
        int removed = kbChunkService.removeBlogChunks();

        List<Blog> blogs = blogMapper.selectList(null);
        int indexed = 0;
        for (Blog blog : blogs) {
            if (blog.getId() == null || blog.getUserId() == null) {
                continue;
            }
            String document = TextSplitter.buildDocument(blog.getTitle(), blog.getContent());
            if (StrUtil.isBlank(document)) {
                continue;
            }
            // 分片归属仍是各自的作者，检索时才做全站合并
            indexDocument(blog.getUserId(), blog.getId(), SOURCE_BLOG, blog.getTitle(), document);
            indexed++;
        }
        log.info("重建全站知识库完成：清理旧分片 {} 条，重新索引博客 {} 篇", removed, indexed);
        return indexed;
    }

    @Override
    public boolean removeBlogIndex(Long blogId, Long userId) {
        if (blogId == null || userId == null) {
            return false;
        }
        // 同时带上 userId 条件，杜绝越权删别人的索引
        return kbChunkService.remove(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getBlogId, blogId)
                .eq(KbChunk::getUserId, userId));
    }

    @Override
    public List<KbChunkHit> retrieve(Long userId, String question, boolean includeOthers, int topK) {
        if (userId == null || StrUtil.isBlank(question)) {
            return Collections.emptyList();
        }
        int limit = topK > 0 ? topK : props.getTopK();
        float[] questionVector = embedQuestion(question);

        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<>();
        if (!includeOthers) {
            // 只搜自己的：自己的博客 + 自己上传的文档
            wrapper.eq(KbChunk::getUserId, userId);
        } else {
            // 全站共享：所有人的博客 + 只有自己可见的上传文档
            // 生成 (source_type = 'BLOG' OR (user_id = ? AND source_type = 'FILE'))
            wrapper.and(w -> w
                    .eq(KbChunk::getSourceType, SOURCE_BLOG)
                    .or(o -> o.eq(KbChunk::getUserId, userId)
                            .eq(KbChunk::getSourceType, SOURCE_FILE)));
        }
        return search(questionVector, question, wrapper, limit);
    }

    @Override
    public KbStatsVO stats(Long userId) {
        KbStatsVO vo = new KbStatsVO();

        // 全站共享的部分
        vo.setTotalChunks(kbChunkService.count(null));
        vo.setVectorized(kbChunkService.count(
                new LambdaQueryWrapper<KbChunk>().isNotNull(KbChunk::getEmbedding)));
        vo.setTotalBlogs(kbChunkService.getObj(
                new QueryWrapper<KbChunk>()
                        .select("count(distinct blog_id)")
                        .eq("source_type", SOURCE_BLOG),
                o -> o == null ? 0L : ((Number) o).longValue()));

        // 只有自己能看到的：上传的文档
        vo.setMyFiles(userId == null ? 0L : kbChunkService.getObj(
                new QueryWrapper<KbChunk>()
                        .select("count(distinct source_name)")
                        .eq("user_id", userId)
                        .eq("source_type", SOURCE_FILE),
                o -> o == null ? 0L : ((Number) o).longValue()));

        vo.setAiConfigured(aiClient.configured());
        vo.setChatModel(props.getChatModel());
        vo.setEmbeddingModel(props.getEmbeddingModel());
        return vo;
    }

    /** 对候选分片逐个打分排序 */
    private List<KbChunkHit> search(float[] questionVector, String question,
                                    LambdaQueryWrapper<KbChunk> wrapper, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        // 兜底上限，防止某个用户分片过多时把整表拉进内存
        wrapper.orderByDesc(KbChunk::getId).last("limit " + Math.max(1, props.getMaxCandidates()));
        List<KbChunk> candidates = kbChunkService.list(wrapper);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<KbChunkHit> hits = new ArrayList<>();
        for (KbChunk chunk : candidates) {
            float[] vector = VectorUtils.parse(chunk.getEmbedding());
            boolean byVector = questionVector != null && vector != null;
            // 没配 Key、或这条分片当初没向量化成功，就单独走关键词兜底
            double score = byVector
                    ? VectorUtils.cosine(questionVector, vector)
                    : keywordScore(question, chunk.getContent());
            // 两种模式的分数不是一个量纲，各用各的阈值
            double threshold = byVector ? props.getMinScore() : props.getKeywordMinScore();
            if (score < threshold) {
                continue;
            }
            hits.add(new KbChunkHit()
                    .setId(chunk.getId())
                    .setBlogId(chunk.getBlogId())
                    .setSourceType(chunk.getSourceType())
                    .setSourceName(chunk.getSourceName())
                    .setChunkIndex(chunk.getChunkIndex())
                    .setContent(chunk.getContent())
                    .setScore(score)
                    .setVectorScored(byVector));
        }
        hits.sort(Comparator.comparingDouble(KbChunkHit::getScore).reversed());
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    private List<float[]> tryEmbed(List<String> pieces) {
        if (!aiClient.configured()) {
            log.warn("未配置 hmdp.ai.api-key，本次不向量化，检索将走关键词兜底；配置后调用 /ai/kb/reindex 即可补上");
            return null;
        }
        try {
            long start = System.currentTimeMillis();
            List<float[]> vectors = aiClient.embed(pieces);
            log.debug("向量化完成 {} 片，耗时 {}ms", pieces.size(), System.currentTimeMillis() - start);
            return vectors;
        } catch (Exception e) {
            log.error("向量化失败，本次降级为关键词检索", e);
            return null;
        }
    }

    private float[] embedQuestion(String question) {
        if (!aiClient.configured()) {
            return null;
        }
        try {
            return aiClient.embedOne(question);
        } catch (Exception e) {
            log.error("问题向量化失败，本次检索降级为关键词模式", e);
            return null;
        }
    }

    // ---------------- 关键词兜底 ----------------

    /**
     * 问题里的词有多大比例出现在分片里。没配 Key 时的兜底手段，精度远不如向量检索。
     */
    private static double keywordScore(String question, String content) {
        List<String> grams = bigrams(question);
        if (grams.isEmpty() || StrUtil.isBlank(content)) {
            return 0D;
        }
        String haystack = content.toLowerCase();
        int hit = 0;
        for (String gram : grams) {
            if (haystack.contains(gram)) {
                hit++;
            }
        }
        return (double) hit / grams.size();
    }

    private static List<String> bigrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        String lower = text.toLowerCase();
        // 西文与数字按整词
        for (String word : lower.split("[^a-z0-9]+")) {
            if (word.length() >= 2) {
                grams.add(word);
            }
        }
        // 中文按相邻两字切片
        StringBuilder run = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (isCjk(c)) {
                run.append(c);
            } else {
                addCjkGrams(run, grams);
                run.setLength(0);
            }
        }
        addCjkGrams(run, grams);
        return new ArrayList<>(grams);
    }

    private static void addCjkGrams(StringBuilder run, Set<String> grams) {
        if (run.length() == 1) {
            char only = run.charAt(0);
            if (!STOP_CHARS.contains(only)) {
                grams.add(String.valueOf(only));
            }
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            grams.add(run.substring(i, i + 2));
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
