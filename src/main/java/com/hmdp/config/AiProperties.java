package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 相关配置，对应 application.yaml 的 hmdp.ai 前缀。
 * <p>
 * 接口按 OpenAI 兼容格式调用，换服务商只要改 baseUrl / chatModel / embeddingModel 三项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai")
public class AiProperties {

    /** 总开关，关掉后 /ai/** 接口直接返回未启用 */
    private boolean enabled = true;

    /** OpenAI 兼容接口的 base url，结尾不要带 / */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** API Key，建议通过环境变量 HMD_AI_API_KEY 注入，不要提交到仓库 */
    private String apiKey = "";

    /** 对话模型 */
    private String chatModel = "qwen-plus";

    /** 向量化模型 */
    private String embeddingModel = "text-embedding-v3";

    /** 传 0 表示不带 dimensions 参数，由服务端决定维度 */
    private Integer embeddingDimensions = 0;

    /** 单次 embedding 请求最多提交几条文本，百炼限制为 10 */
    private Integer embeddingBatchSize = 10;

    /** HTTP 超时（毫秒） */
    private Integer timeout = 60000;

    /** 检索返回的分片数 */
    private Integer topK = 5;

    /** 单次检索最多加载多少条候选分片，防止用户分片过多时拖垮内存 */
    private Integer maxCandidates = 2000;

    /** 向量模式的相似度阈值（余弦相似度），低于该值的分片不参与回答 */
    private Double minScore = 0.25;

    /**
     * 关键词兜底模式单独用一套阈值。
     * 它的分数是"问题里有多少比例的词出现在分片中"，和余弦相似度不是一个量纲，
     * 复用 minScore 会把明明相关的分片全部滤掉。
     */
    private Double keywordMinScore = 0.08;

    /** 分片长度（字符） */
    private Integer chunkSize = 400;

    /** 相邻分片的重叠长度（字符），避免句子被切断后语义丢失 */
    private Integer chunkOverlap = 60;

    /** 拼进 prompt 的资料区最大字符数，防止超出模型上下文 */
    private Integer maxContextChars = 3000;

    /** 生成温度，总结类任务调低更稳 */
    private Double temperature = 0.3;

    /** 最多带上最近几轮对话作为上下文，0 表示不带（每次提问独立） */
    private Integer maxHistoryTurns = 4;

    /** 系统提示词 */
    private String systemPrompt = """
            你是「黑马点评」App 的智能助手。
            请严格依据下面提供的【资料】回答用户问题：
            1. 只使用【资料】中的信息，不要编造资料里没有的内容；
            2. 如果资料不足以回答，就明确告诉用户「你的博客里没有提到这个」，不要硬编；
            3. 用中文回答，内容较多时分点总结；
            4. 不要重复罗列资料来源，来源会由前端单独展示。
            """;
}
