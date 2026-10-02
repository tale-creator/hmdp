package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AiAnswerVO;
import com.hmdp.dto.AiChatDTO;
import com.hmdp.dto.ChatTurn;
import com.hmdp.dto.KbChunkHit;
import com.hmdp.service.AiChatService;
import com.hmdp.service.KnowledgeBaseService;
import com.hmdp.utils.ai.AiClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答实现。
 * <p>
 * 三级降级，保证任何情况下都有东西返回给用户：
 * 1. 正常：检索分片 → 交给模型总结
 * 2. 没配 Key 或模型调用失败：直接返回最相关的原文片段
 * 3. 一条都没检索到：明确告知"没有相关记录"，并提示怎么补
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    /** 用户单次最多能要多少条分片 */
    private static final int MAX_TOP_K = 20;

    /** 上下文预算小于这个数就停止追加资料，避免塞一条半截的进去 */
    private static final int MIN_REMAIN_CHARS = 50;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private AiClient aiClient;

    @Resource
    private AiProperties props;

    @Override
    public AiAnswerVO ask(Long userId, AiChatDTO dto) {
        String question = StrUtil.trimToEmpty(dto == null ? null : dto.getQuestion());
        if (StrUtil.isBlank(question)) {
            throw new IllegalArgumentException("问题不能为空");
        }

        // 默认搜全站：知识库是社区共享的，别人的笔记也是知识来源。
        // 只有显式传 scope=mine 才退化成"只搜自己的"。
        boolean includeOthers = !"mine".equalsIgnoreCase(StrUtil.trimToEmpty(dto.getScope()));
        int topK = (dto.getTopK() != null && dto.getTopK() > 0)
                ? Math.min(dto.getTopK(), MAX_TOP_K)
                : props.getTopK();

        List<ChatTurn> history = recentHistory(dto.getHistory());

        // 追问（"它停车方便吗"）里的指代词没有检索信号，直接用原句会召回错内容，
        // 所以检索时把上一轮的问题拼上；回答时仍然只把原问题给模型。
        String retrievalQuery = buildRetrievalQuery(question, history);
        List<KbChunkHit> hits = knowledgeBaseService.retrieve(userId, retrievalQuery, includeOthers, topK);

        AiAnswerVO answer = new AiAnswerVO()
                .setSources(toSources(hits))
                .setScope(includeOthers ? "all" : "mine")
                .setDegraded(hits.stream().anyMatch(hit -> Boolean.FALSE.equals(hit.getVectorScored())));

        if (hits.isEmpty()) {
            String where = includeOthers ? "全站笔记" : "你自己的笔记";
            return answer
                    .setAnswer("在" + where + "里没有找到和这个问题相关的内容。"
                            + "可以去发布相关的笔记，再点「重建全站索引」把它加进知识库。")
                    .setNotice("未检索到相关分片");
        }

        if (!aiClient.configured()) {
            return answer
                    .setAnswer(buildExtractiveAnswer(hits))
                    .setNotice("未配置 AI Key，这里直接把最相关的原文片段返回给你");
        }

        try {
            String reply = aiClient.chat(props.getSystemPrompt(), history, buildUserPrompt(question, hits));
            return answer.setAnswer(reply);
        } catch (Exception e) {
            log.error("调用对话模型失败，降级为返回原文片段", e);
            return answer
                    .setAnswer(buildExtractiveAnswer(hits))
                    .setNotice("调用对话模型失败，已降级为返回原文片段：" + e.getMessage());
        }
    }

    /** 出现这些词，基本可以断定是在指代上文说过的对象 */
    private static final String[] FOLLOW_UP_HINTS = {
            "它", "他", "她", "那家", "那个", "这个", "这家", "刚才", "上面", "还有", "怎么样呢"};

    /** 短问题大概率依赖上文 */
    private static final int SHORT_QUESTION_LEN = 14;

    /**
     * 拼出用于检索的查询串。
     * <p>
     * "它停车方便吗"这种追问，单独拿去做向量检索几乎没有信号，
     * 容易召回不相干的笔记把模型带偏，所以把上一轮的问题一起拼进去。
     * 只有判断为追问时才拼，换话题的长问题不受影响。
     */
    private String buildRetrievalQuery(String question, List<ChatTurn> history) {
        if (history == null || history.isEmpty() || !looksLikeFollowUp(question)) {
            return question;
        }
        // 取最近一轮的问题作为检索补充
        for (int i = history.size() - 1; i >= 0; i--) {
            String prev = history.get(i).getQuestion();
            if (StrUtil.isNotBlank(prev)) {
                return prev + " " + question;
            }
        }
        return question;
    }

    private static boolean looksLikeFollowUp(String question) {
        if (question.length() <= SHORT_QUESTION_LEN) {
            return true;
        }
        for (String hint : FOLLOW_UP_HINTS) {
            if (question.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只带最近几轮对话，避免上下文无限增长。
     */
    private List<ChatTurn> recentHistory(List<ChatTurn> history) {
        int max = props.getMaxHistoryTurns() == null ? 0 : Math.max(0, props.getMaxHistoryTurns());
        if (history == null || history.isEmpty() || max == 0) {
            return null;
        }
        return history.size() <= max
                ? history
                : history.subList(history.size() - max, history.size());
    }

    /** 把检索到的分片按上下文预算拼成资料区 */
    private String buildUserPrompt(String question, List<KbChunkHit> hits) {
        StringBuilder sb = new StringBuilder("【资料】\n");
        int used = 0;
        int index = 1;
        int budget = props.getMaxContextChars();

        for (KbChunkHit hit : hits) {
            int remain = budget - used;
            if (remain < MIN_REMAIN_CHARS) {
                break;
            }
            String body = StrUtil.blankToDefault(hit.getContent(), "");
            if (body.length() > remain) {
                body = body.substring(0, remain) + "…";
            }
            sb.append('[').append(index++).append("] 《")
                    .append(StrUtil.blankToDefault(hit.getSourceName(), "未命名")).append("》\n")
                    .append(body).append("\n\n");
            used += body.length();
        }

        sb.append("【问题】\n").append(question);
        return sb.toString();
    }

    /** 不调模型时的兜底：直接把原文片段摆出来 */
    private String buildExtractiveAnswer(List<KbChunkHit> hits) {
        StringBuilder sb = new StringBuilder("找到以下相关片段：\n\n");
        int index = 1;
        for (KbChunkHit hit : hits) {
            sb.append(index++).append(". 《")
                    .append(StrUtil.blankToDefault(hit.getSourceName(), "未命名"))
                    .append("》（相关度 ").append(String.format("%.2f", hit.getScore())).append("）\n")
                    .append(StrUtil.maxLength(hit.getContent(), 200))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private List<AiAnswerVO.Source> toSources(List<KbChunkHit> hits) {
        List<AiAnswerVO.Source> sources = new ArrayList<>(hits.size());
        for (KbChunkHit hit : hits) {
            sources.add(new AiAnswerVO.Source()
                    .setBlogId(hit.getBlogId())
                    .setSourceType(hit.getSourceType())
                    .setTitle(StrUtil.blankToDefault(hit.getSourceName(), "未命名"))
                    .setScore(hit.getScore())
                    .setSnippet(StrUtil.maxLength(hit.getContent(), 120)));
        }
        return sources;
    }
}
