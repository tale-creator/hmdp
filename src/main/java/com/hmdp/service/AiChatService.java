package com.hmdp.service;

import com.hmdp.dto.AiAnswerVO;
import com.hmdp.dto.AiChatDTO;

/**
 * AI 问答：检索用户知识库 → 拼 prompt → 调模型总结。
 */
public interface AiChatService {

    /**
     * 回答用户提问。
     *
     * @param userId 提问者，决定检索哪个人的知识库
     */
    AiAnswerVO ask(Long userId, AiChatDTO dto);
}
