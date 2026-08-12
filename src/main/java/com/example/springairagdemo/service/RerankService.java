package com.example.springairagdemo.service;

import java.util.List;

/**
 * 召回重排序（Rerank）服务
 *
 * <p>在向量召回得到候选片段后，用专门的 Cross-Encoder 重排序模型
 * 对 "问题 vs 片段" 逐对打分，将最相关的片段排到前面，提升上下文质量。
 */
public interface RerankService {

    /**
     * 是否启用重排序
     */
    boolean isEnabled();

    /**
     * 对候选片段进行重排序
     *
     * @param query     用户问题
     * @param documents 候选片段文本（与待排序集合一一对应）
     * @param topN      返回分数最高的前 N 条（&lt;=0 时返回全部）
     * @return 按重排分数降序的 (原始下标, 相关性分数) 列表；失败时可返回 null 由调用方降级
     */
    List<RerankItem> rerank(String query, List<String> documents, int topN);

    /**
     * 重排序结果：原始下标 + 相关性分数
     */
    record RerankItem(int index, double score) {
    }
}
