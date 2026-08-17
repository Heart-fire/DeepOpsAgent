package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.service.search.Bm25Scorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量搜索服务：知识库检索入口（Dense/Sparse 混合检索 + RRF 融合 + 元数据过滤）。
 *
 * 检索链路（rag.hybrid-enabled=true，默认）：
 *   1) 元数据过滤：filters 转 Milvus 布尔表达式（metadata["k"] == "v" and ...），先缩小检索空间；
 *   2) Dense 召回：Milvus COSINE 向量检索（text-embedding-v4 语义向量），负责语义相似
 *      （"接口响应变慢" ≈ "请求延迟升高"）；
 *   3) Sparse 召回：应用层 BM25（{@link Bm25Scorer}），负责精确技术关键词
 *      （错误码、异常类名、服务名——dense 容易丢的信号）；
 *   4) RRF 融合：score = Σ 1/(rrf-k + rank)，两路排名融合取 TopK。
 *
 * 为什么 Sparse 在应用层（面试口径）：
 *   Milvus 原生 BM25 需要 SparseFloatVector 字段 + Function 并重灌全量数据，
 *   演示规模（数百分片）下应用层 BM25（候选池 ≤ sparse-candidate-limit）成本更低、
 *   效果等价；生产规模切换 Milvus 原生 BM25 / ES，本方法对外签名不变。
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    /** Dense 单路召回的样本截断上限（混合检索时 Dense 扩大召回给 RRF 用） */
    private static final int DENSE_RECALL_MULTIPLIER = 3;

    /** queryMetric 风格的样本上限：单路 Dense 检索返回结果条数硬顶 */
    private static final int MAX_DENSE_RESULTS = 50;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Value("${rag.hybrid-enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.rrf-k:60}")
    private int rrfK;

    @Value("${rag.sparse-candidate-limit:500}")
    private int sparseCandidateLimit;

    /**
     * 搜索相似文档（无过滤，向后兼容原签名）。
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        return searchSimilarDocuments(query, topK, null);
    }

    /**
     * 搜索相似文档（元数据过滤 + 混合检索）。
     *
     * @param query           查询文本
     * @param topK            最终返回条数
     * @param metadataFilters 元数据等值过滤（如 service=payment-service, alert_type=HighCPUUsage）；null/空=不过滤
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK, Map<String, String> metadataFilters) {
        try {
            logger.info("知识库检索开始, query={}, topK={}, filters={}, hybrid={}",
                    query, topK, metadataFilters, hybridEnabled);

            List<SearchResult> results = hybridEnabled
                    ? hybridSearch(query, topK, metadataFilters)
                    : denseSearch(query, topK, metadataFilters);

            logger.info("知识库检索完成, 返回 {} 条", results.size());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    // ==================== 混合检索 ====================

    private List<SearchResult> hybridSearch(String query, int topK, Map<String, String> filters) throws Exception {
        String filterExpr = buildFilterExpr(filters);

        // 1. Dense 召回（扩大召回窗口给 RRF 融合）
        List<SearchResult> denseHits = denseSearchRaw(query, Math.min(topK * DENSE_RECALL_MULTIPLIER, MAX_DENSE_RESULTS), filterExpr);

        // 2. Sparse/BM25 召回（同一过滤口径的候选池上打分）
        Map<String, String> pool = queryCandidatePool(filterExpr);
        Bm25Scorer bm25 = new Bm25Scorer(new ArrayList<>(pool.values()));
        Map<String, Double> bm25Scores = new HashMap<>();
        for (Map.Entry<Integer, Double> e : rankByScore(bm25.score(query)).entrySet()) {
            String id = poolIds(pool).get(e.getKey());
            if (id != null) {
                bm25Scores.put(id, e.getValue());
            }
        }

        // 3. RRF 融合：score = Σ 1/(rrfK + rank)，只出现在单路榜单的文档仅累加该路得分
        Map<String, Double> fused = new HashMap<>();
        List<String> denseRanking = denseHits.stream().map(SearchResult::getId).collect(Collectors.toList());
        for (int i = 0; i < denseRanking.size(); i++) {
            fused.merge(denseRanking.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        }
        List<String> sparseRanking = bm25Scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        for (int i = 0; i < sparseRanking.size(); i++) {
            fused.merge(sparseRanking.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        }

        // 4. 取 TopK 并组装结果（dense 命中的带真实相似度分，仅 sparse 命中的从候选池构造）
        Map<String, SearchResult> denseById = new HashMap<>();
        denseHits.forEach(h -> denseById.put(h.getId(), h));

        List<SearchResult> fusedTopK = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList())) {
            SearchResult hit = denseById.get(e.getKey());
            if (hit == null) {
                hit = buildFromPool(e.getKey(), pool, e.getValue());
            } else {
                hit.setFusedScore(e.getValue());
            }
            fusedTopK.add(hit);
        }
        logger.info("混合检索融合完成: dense={}, sparse={}, fusedTop={}",
                denseRanking.size(), sparseRanking.size(), fusedTopK.size());
        return fusedTopK;
    }

    /** Dense 单路检索（原纯向量检索逻辑 + 元数据过滤 + 结果上限截断） */
    private List<SearchResult> denseSearch(String query, int topK, Map<String, String> filters) throws Exception {
        return denseSearchRaw(query, topK, buildFilterExpr(filters));
    }

    private List<SearchResult> denseSearchRaw(String query, int topK, String filterExpr) throws Exception {
        logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

        List<Float> queryVector = embeddingService.generateQueryVector(query);
        logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withVectorFieldName("vector")
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(topK)
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withOutFields(List.of("id", "content", "metadata"))
                .withParams("{\"nprobe\":10}");
        if (filterExpr != null && !filterExpr.isBlank()) {
            builder.withExpr(filterExpr);   // 元数据过滤：向量检索前先缩小检索空间
        }

        R<SearchResults> searchResponse = milvusClient.search(builder.build());
        if (searchResponse.getStatus() != 0) {
            throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResult result = new SearchResult();
            result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
            result.setContent((String) wrapper.getFieldData("content", 0).get(i));
            result.setScore(wrapper.getIDScore(0).get(i).getScore());
            Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
            if (metadataObj != null) {
                result.setMetadata(metadataObj.toString());
            }
            results.add(result);
        }
        return results;
    }

    /** 拉取 BM25 候选池（同一元数据过滤口径），返回 content -> id 映射关系由 poolIds 保证顺序 */
    private Map<String, String> queryCandidatePool(String filterExpr) throws Exception {
        QueryParam.Builder builder = QueryParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr(filterExpr == null || filterExpr.isBlank() ? "id != \"\"" : filterExpr)
                .withOutFields(List.of("id", "content"))
                .withLimit((long) sparseCandidateLimit);
        R<io.milvus.grpc.QueryResults> resp = milvusClient.query(builder.build());
        if (resp.getStatus() != 0) {
            throw new RuntimeException("BM25 候选池拉取失败: " + resp.getMessage());
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(resp.getData());
        Map<String, String> pool = new LinkedHashMap<>();   // content -> id（顺序稳定，供 Bm25Scorer 下标对齐）
        for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
            Object id = record.get("id");
            Object content = record.get("content");
            if (id != null && content != null) {
                pool.put(String.valueOf(content), String.valueOf(id));
            }
        }
        return pool;
    }

    private SearchResult buildFromPool(String id, Map<String, String> pool, double fusedScore) {
        SearchResult r = new SearchResult();
        r.setId(id);
        r.setContent(pool.entrySet().stream()
                .filter(e -> id.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(""));
        r.setScore(0f);   // 未进 Dense 榜单，无向量相似度分
        r.setFusedScore(fusedScore);
        return r;
    }

    /** Map<Integer,Double>（下标->分）按分值降序返回 */
    private Map<Integer, Double> rankByScore(Map<Integer, Double> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 稳定顺序的池内容下标列表（与 queryCandidatePool 的 LinkedHashMap 顺序一致） */
    private List<String> poolIds(Map<String, String> pool) {
        return new ArrayList<>(pool.values());
    }

    /** 元数据等值过滤 -> Milvus 布尔表达式；空过滤返回空串（不过滤） */
    private String buildFilterExpr(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return filters.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank()
                        && e.getValue() != null && !e.getValue().isBlank())
                .map(e -> String.format("metadata[\"%s\"] == \"%s\"",
                        e.getKey().replace("\"", ""), e.getValue().replace("\"", "")))
                .collect(Collectors.joining(" and "));
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        /** RRF 融合分（仅混合检索时非 null；序列化为 fused_score，与向量相似度分 score 并存） */
        @com.fasterxml.jackson.annotation.JsonProperty("fused_score")
        private Double fusedScore;
    }
}
