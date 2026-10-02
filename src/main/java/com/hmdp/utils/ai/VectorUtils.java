package com.hmdp.utils.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

/**
 * 向量与相似度计算工具。
 * <p>
 * 不引入向量数据库，直接把向量以 JSON 数组文本存进 MySQL 的 mediumtext 列，
 * 检索时按用户取出后在内存里算余弦相似度。单用户分片量级下足够快。
 */
public class VectorUtils {

    private VectorUtils() {
    }

    /**
     * 余弦相似度。任一为空、维度不一致或含零向量时返回 0，表示"不相关"。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0D;
        }
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0D || normB == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 向量转 JSON 数组文本，直接落库。
     */
    public static String toJson(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * JSON 数组文本转向量，解析失败或为空时返回 null（视为未向量化）。
     */
    public static float[] parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            float[] vector = new float[array.size()];
            for (int i = 0; i < array.size(); i++) {
                vector[i] = array.getFloat(i);
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }
}
