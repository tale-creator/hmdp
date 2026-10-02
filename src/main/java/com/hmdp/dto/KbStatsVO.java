package com.hmdp.dto;

import lombok.Data;

/**
 * 知识库概况。
 * <p>
 * 知识库是社区共享的，所以主体是全站数据；上传文档属私人资料，单独统计。
 */
@Data
public class KbStatsVO {

    /** 全站已索引的笔记篇数（按 blog_id 去重） */
    private Long totalBlogs;

    /** 全站知识片段总数 */
    private Long totalChunks;

    /** 已向量化的片段数 */
    private Long vectorized;

    /** 我上传的文档数（只有自己可见） */
    private Long myFiles;

    /** 是否已配置可用的 API Key */
    private Boolean aiConfigured;

    private String chatModel;

    private String embeddingModel;
}
