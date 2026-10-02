package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 问答返回结果。
 */
@Data
@Accessors(chain = true)
public class AiAnswerVO {

    /** 模型生成的回答；降级时是拼出来的原文片段 */
    private String answer;

    /** 引用到的来源，前端单独展示 */
    private List<Source> sources;

    /** 实际生效的检索范围：mine / all */
    private String scope;

    /** true 表示本次检索没走向量，用的关键词兜底 */
    private Boolean degraded;

    /** 给用户看的提示，正常时为 null */
    private String notice;

    @Data
    @Accessors(chain = true)
    public static class Source {

        /** 来源博客id，文件来源时为 null */
        private Long blogId;

        /** BLOG / FILE */
        private String sourceType;

        private String title;

        /** 相关度，向量模式为余弦相似度 */
        private Double score;

        /** 片段预览 */
        private String snippet;
    }
}
