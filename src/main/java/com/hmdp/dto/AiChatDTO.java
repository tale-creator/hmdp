package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 提问请求。
 */
@Data
public class AiChatDTO {

    /** 用户的问题 */
    private String question;

    /**
     * 检索范围：
     * all（默认，留空即为此值）= 搜全站所有人的笔记，外加自己上传的文档；
     * mine = 只搜自己的内容。
     */
    private String scope;

    /** 覆盖配置里的 topK，留空用默认值 */
    private Integer topK;

    /**
     * 之前的对话轮次（从旧到新），用于让模型理解上下文，比如"那家店呢"这种指代。
     * 只取最近若干轮，留空表示这是一次全新的提问。
     */
    private List<ChatTurn> history;
}
