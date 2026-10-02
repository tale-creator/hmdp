package com.hmdp.utils.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.ChatTurn;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容格式的 AI 客户端：向量化 + 对话。
 * <p>
 * 不依赖任何 SDK，只用 hutool 发 HTTP，换服务商只要改配置里的三项。
 */
@Slf4j
@Component
public class AiClient {

    @Resource
    private AiProperties props;

    /** 是否已经配好可用的 key */
    public boolean configured() {
        return props.isEnabled() && StrUtil.isNotBlank(props.getApiKey());
    }

    /**
     * 批量向量化，内部按 embeddingBatchSize 自动分批。
     *
     * @return 与入参一一对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        int batchSize = Math.max(1, props.getEmbeddingBatchSize());
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> slice = texts.subList(i, Math.min(texts.size(), i + batchSize));
            vectors.addAll(embedBatch(slice));
        }
        return vectors;
    }

    /** 单条向量化 */
    public float[] embedOne(String text) {
        List<float[]> vectors = embed(Collections.singletonList(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * 单轮对话，没有上文。
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, null, userPrompt);
    }

    /**
     * 带历史上下文的对话补全。
     *
     * @param history 之前的对话轮次，从旧到新；可为 null
     */
    public String chat(String systemPrompt, List<ChatTurn> history, String userPrompt) {
        JSONArray messages = JSONUtil.createArray();
        messages.add(JSONUtil.createObj().set("role", "system").set("content", systemPrompt));

        if (history != null) {
            for (ChatTurn turn : history) {
                if (turn == null || StrUtil.isBlank(turn.getQuestion())) {
                    continue;
                }
                messages.add(JSONUtil.createObj()
                        .set("role", "user")
                        .set("content", turn.getQuestion()));
                if (StrUtil.isNotBlank(turn.getAnswer())) {
                    messages.add(JSONUtil.createObj()
                            .set("role", "assistant")
                            .set("content", turn.getAnswer()));
                }
            }
        }
        messages.add(JSONUtil.createObj().set("role", "user").set("content", userPrompt));

        JSONObject body = JSONUtil.createObj()
                .set("model", props.getChatModel())
                .set("temperature", props.getTemperature())
                .set("messages", messages);

        JSONObject resp = post("/chat/completions", body);
        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("对话接口未返回任何结果");
        }
        String content = choices.getJSONObject(0).getJSONObject("message").getStr("content");
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("对话接口返回了空内容");
        }
        return content.trim();
    }

    private List<float[]> embedBatch(List<String> texts) {
        JSONObject body = JSONUtil.createObj()
                .set("model", props.getEmbeddingModel())
                .set("input", texts)
                .set("encoding_format", "float");
        if (props.getEmbeddingDimensions() != null && props.getEmbeddingDimensions() > 0) {
            body.set("dimensions", props.getEmbeddingDimensions());
        }

        JSONObject resp = post("/embeddings", body);
        JSONArray data = resp.getJSONArray("data");
        if (data == null || data.size() != texts.size()) {
            throw new IllegalStateException("向量化返回条数与请求不一致，期望 " + texts.size()
                    + " 实际 " + (data == null ? 0 : data.size()));
        }

        // 按 index 归位，不依赖返回顺序
        float[][] ordered = new float[texts.size()][];
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            JSONArray arr = item.getJSONArray("embedding");
            if (arr == null || arr.isEmpty()) {
                throw new IllegalStateException("向量化返回了空向量");
            }
            float[] vector = new float[arr.size()];
            for (int j = 0; j < arr.size(); j++) {
                vector[j] = arr.getFloat(j);
            }
            int index = item.getInt("index", i);
            if (index < 0 || index >= ordered.length) {
                index = i;
            }
            ordered[index] = vector;
        }

        List<float[]> result = new ArrayList<>(texts.size());
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new IllegalStateException("向量化返回条数与请求不一致");
            }
            result.add(vector);
        }
        return result;
    }

    private JSONObject post(String path, JSONObject body) {
        String url = StrUtil.removeSuffix(StrUtil.removeSuffix(props.getBaseUrl(), "/"), "/chat/completions")
                + path;
        String raw;
        try (HttpResponse response = HttpUtil.createPost(url)
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(props.getTimeout())
                .execute()) {
            raw = response.body();
            if (!response.isOk()) {
                log.error("AI 接口返回异常 status={} url={} body={}",
                        response.getStatus(), url, StrUtil.maxLength(raw, 500));
                throw new IllegalStateException("AI 接口调用失败，HTTP " + response.getStatus());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI 接口请求异常 url={}", url, e);
            throw new IllegalStateException("AI 接口请求异常：" + e.getMessage(), e);
        }

        JSONObject json = JSONUtil.parseObj(raw);
        if (json.containsKey("error")) {
            String message = json.getJSONObject("error").getStr("message", "未知错误");
            log.error("AI 接口返回错误 url={} message={}", url, message);
            throw new IllegalStateException("AI 接口返回错误：" + message);
        }
        return json;
    }
}
