package com.hmdp.dto;

import lombok.Data;

/**
 * 一轮历史对话，前端把最近的几轮带上来，让模型有上下文。
 * <p>
 * 服务端不存对话记录，保持无状态：记录由前端保存并随请求带上。
 */
@Data
public class ChatTurn {

    private String question;

    private String answer;
}
