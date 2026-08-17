package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG 从内部知识库检索相关文档（元数据过滤 + Dense/Sparse 混合检索 + RRF 融合）
 */
@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final VectorSearchService vectorSearchService;

    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * 查询内部文档工具（带元数据过滤）
     *
     * @param query    搜索查询，描述您要查找的信息
     * @param service  可选：按服务名过滤（如 payment-service），空则不过滤
     * @param docType  可选：按文档类型过滤（如 sop / case / alert），空则不过滤
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs metadata-filtered hybrid retrieval (Dense vector + Sparse BM25, fused by RRF) to find " +
            "similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation. " +
            "Use optional filters to narrow down: service (e.g. payment-service), docType (sop/case/alert).")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") String query,
            @ToolParam(description = "Optional: filter by service name, e.g. payment-service. Empty means no filter.", required = false) String service,
            @ToolParam(description = "Optional: filter by doc type: sop (runbook), case (历史故障案例), alert (告警说明). Empty means no filter.", required = false) String docType) {

        try {
            // 元数据过滤条件组装（空值不参与过滤）
            Map<String, String> filters = new HashMap<>();
            if (service != null && !service.isBlank()) {
                filters.put("service", service.trim());
            }
            if (docType != null && !docType.isBlank()) {
                filters.put("docType", docType.trim());
            }

            // 向量搜索服务检索（过滤 + 混合检索）
            List<VectorSearchService.SearchResult> searchResults =
                    vectorSearchService.searchSimilarDocuments(query, topK, filters.isEmpty() ? null : filters);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            // 将搜索结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(searchResults);

            return resultJson;

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }
}
