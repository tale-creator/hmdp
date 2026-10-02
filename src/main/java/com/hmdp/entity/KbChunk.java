package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库分片。
 * <p>
 * 一篇博客（或一个上传的文件）会被切成若干分片，每条分片独立向量化。
 * embedding 为空表示未向量化，检索时会走关键词兜底。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_kb_chunk")
public class KbChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 知识库归属用户id */
    private Long userId;

    /** 来源博客id，文件上传时为 null */
    private Long blogId;

    /** 来源类型：BLOG=用户博客，FILE=上传文件 */
    private String sourceType;

    /** 来源名称：博客标题或文件名 */
    private String sourceName;

    /** 分片在原文中的序号，从 0 开始 */
    private Integer chunkIndex;

    /** 分片正文 */
    private String content;

    /** 向量，JSON 数组文本 */
    private String embedding;

    /** 向量维度 */
    private Integer embeddingDim;

    private LocalDateTime createTime;
}
