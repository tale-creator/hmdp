package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 一次检索命中的分片。
 */
@Data
@Accessors(chain = true)
public class KbChunkHit {

    private Long id;

    /** 来源博客id，文件来源时为 null */
    private Long blogId;

    /** BLOG / FILE */
    private String sourceType;

    /** 博客标题或文件名 */
    private String sourceName;

    /** 分片序号 */
    private Integer chunkIndex;

    /** 分片正文 */
    private String content;

    /** 相关度得分，向量模式是余弦相似度，关键词模式是命中率 */
    private Double score;

    /** true=向量打分，false=关键词兜底打分 */
    private Boolean vectorScored;
}
