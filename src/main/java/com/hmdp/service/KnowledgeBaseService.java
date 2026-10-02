package com.hmdp.service;

import com.hmdp.dto.KbChunkHit;
import com.hmdp.dto.KbStatsVO;
import com.hmdp.entity.Blog;

import java.util.List;

/**
 * 知识库：把博客/文档切分向量化入库，并按问题检索。
 * <p>
 * 知识库是<b>社区共享</b>的 —— 所有人的博客都在同一个库里，
 * 任何人提问都能检索到别人写过的笔记。
 * <p>
 * 唯一例外是用户上传的文件：那属于私人资料，只有上传者自己能检索到。
 */
public interface KnowledgeBaseService {

    /** 来源类型：用户发布的博客 */
    String SOURCE_BLOG = "BLOG";

    /** 来源类型：用户上传的文件 */
    String SOURCE_FILE = "FILE";

    /**
     * 索引一篇博客（异步执行，内部已吞掉异常，不影响发博客主流程）。
     */
    void indexBlog(Blog blog);

    /**
     * 索引一段文本。会先清掉同一 blogId 的旧分片再写入，因此可重复调用。
     *
     * @param blogId 文件来源传 null
     * @return 写入的分片数
     */
    int indexDocument(Long userId, Long blogId, String sourceType, String sourceName, String text);

    /**
     * 重建全站博客索引：清空所有博客来源的分片，再把所有人的博客重新索引一遍。
     * <p>
     * 知识库是社区共享的，新部署或换了向量模型后调一次即可。
     *
     * @return 实际索引的博客篇数
     */
    int reindexAllBlogs();

    /**
     * 删除某篇博客的索引。带 userId 条件，避免用户删掉别人的索引。
     *
     * @return 是否真的删到了数据
     */
    boolean removeBlogIndex(Long blogId, Long userId);

    /**
     * 检索与问题最相关的分片。
     *
     * @param includeOthers true（默认）= 搜全站所有人的博客，并额外带上自己上传的文档；
     *                      false = 只搜自己的内容
     */
    List<KbChunkHit> retrieve(Long userId, String question, boolean includeOthers, int topK);

    /** 当前用户的知识库概况 */
    KbStatsVO stats(Long userId);
}
