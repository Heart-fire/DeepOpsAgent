<p align="center">
  <img src="https://onall.oss-cn-beijing.aliyuncs.com/Gemini_Generated_Image_jh8foijh8foijh8f.png?Expires=1784431486&OSSAccessKeyId=TMP.3KvsHWwX5G7L6i8Ni8NdXH7PLe3rmEacD1pZkBTThk5EpgBxknxcobK8dUwVcNcFXLXMVC6TPviWNzkvyT2ZWT9cWfVG&Signature=jVr70oABEzKwi8LiPmXbfv%2B98BI%3D" alt="DeepOpsAgent" width="700"/>
</p>

# DeepOpsAgent

> 基于 Spring AI Alibaba 的多 Agent 智能运维诊断系统：Supervisor 编排 Planner/Executor 协作，自主调用监控、日志与知识库工具完成告警诊断。

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，**元数据过滤 + Dense/Sparse 混合检索 + RRF 融合排序**，支持多轮对话（历史摘要 + 最近 N 轮）和流式输出。

### 2. AIOps 智能运维
采用 **Supervisor + Planner + Executor** 多 Agent 架构与 **Plan-Execute-Replan** 闭环：证据缺失、工具失败或结果冲突时动态重规划；代码级**步数预算守卫**（总预算 + 同工具失败熔断）防无效循环，超限携带已有证据收敛输出《告警分析报告》。

## 🚀 核心特性

- ✅ **多 Agent 协作**: Supervisor 编排 / Planner 规划 / Executor 执行，按角色分级模型路由（qwen-max 规划 + qwen-turbo 执行）
- ✅ **混合检索 RAG**: Milvus 向量召回（Dense）+ 应用层 BM25（Sparse）+ RRF 融合，支持 service/docType 元数据过滤
- ✅ **分层会话存储**: 内存版零依赖可启动；`chat-history.storage-enabled=true` 切换 Redis + MySQL（MySQL 持久化审计 / Redis 活跃窗口缓存，Miss 回源最终一致）
- ✅ **上下文治理**: 历史摘要（滚动压缩）+ 最近 N 轮完整保留；工具结果裁剪（样本数上限 / 条数控制）
- ✅ **步数预算守卫**: 18 次工具调用总预算 + 同工具 3 次失败熔断，超限返回收敛信号强制 FINISH
- ✅ **Agent 可观测**: Micrometer 埋点 Token 消耗（prompt/completion 分型）与调用指标；自研调用链追踪还原 Thought-Action-Observation 全链路（`GET /api/trace/{sessionId}`）

## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI Alibaba | 1.1.0 | Agent 框架（ReactAgent / SupervisorAgent） |
| DashScope | 2.17.0 | 阿里云 LLM 与 Embedding（text-embedding-v4） |
| Milvus | 2.6.10 | 向量数据库（IVF_FLAT + COSINE） |
| Prometheus / Loki | - | 指标告警 / 日志数据源 |
| Redis + MySQL | - | 会话分层存储（可选启用） |
| Micrometer | - | Token 成本与调用指标 |

## 📦 核心模块

```
DeepOpsAgent/
├── src/main/java/org/example/
│   ├── agent/
│   │   ├── guard/StepBudgetGuard.java      # 步数预算守卫（总预算 + 失败熔断）⭐
│   │   └── tool/                           # Agent 工具集（6 个工具方法）
│   │       ├── DateTimeTools.java          # 时间锚点
│   │       ├── InternalDocsTools.java      # 知识库检索（元数据过滤 + 混合检索）
│   │       ├── QueryMetricsTools.java      # Prometheus 告警 + 即时指标（样本截断）
│   │       ├── LokiLogsTools.java          # Loki 日志查询（LogQL）
│   │       └── QueryLogsTools.java         # 旧 CLS 工具（已停用，mock 兜底）
│   ├── controller/
│   │   ├── ChatController.java             # 统一接口：对话 / SSE / AI Ops ⭐
│   │   └── TraceController.java            # 调用链查询
│   ├── observability/
│   │   └── AgentTraceRecorder.java         # 调用链追踪 + Token 指标上报 ⭐
│   ├── service/
│   │   ├── ChatService.java                # 对话服务（摘要 + 最近 N 轮提示词）
│   │   ├── AiOpsService.java               # 多 Agent 编排 ⭐
│   │   ├── history/                        # 会话存储 ⭐
│   │   │   ├── ChatSessionStore.java       # 存储抽象（接口）
│   │   │   ├── InMemoryChatSessionStore.java       # 内存版（默认）
│   │   │   ├── RedisMysqlChatSessionStore.java     # Redis + MySQL 分层版
│   │   │   └── HistorySummarizer.java      # 历史滚动摘要（LLM 压缩）
│   │   ├── search/Bm25Scorer.java          # 应用层 BM25（中文 2-gram）⭐
│   │   └── Vector*.java                    # 向量索引 / 混合检索 / 嵌入
│   ├── config/ChatStorageConfig.java       # 分层存储装配（幂等建表）⭐
│   └── client/MilvusClientFactory.java     # Milvus 连接与 collection 自动建
├── docs/sql/schema.sql                     # 会话表结构（与代码建表一致）
└── src/main/resources/application.yml      # 配置（含全部功能开关）
```

## ⚙️ 关键配置开关

| 配置 | 默认 | 说明 |
|------|------|------|
| `chat-history.storage-enabled` | `false` | `true` 启用 Redis + MySQL 分层存储（需配置 `chat-history.datasource` / `chat-history.redis`），启动时自动建表 |
| `chat-history.recent-pairs` | `6` | 最近 N 轮完整保留的窗口大小 |
| `rag.hybrid-enabled` | `true` | Dense + Sparse 混合检索；`false` 退回纯向量检索 |
| `rag.rrf-k` | `60` | RRF 融合常数 |
| `aiops.max-tool-calls` | `18` | 单次会话工具调用总预算 |
| `aiops.tool-fail-threshold` | `3` | 同工具连续失败熔断阈值 |
| `loki.mock-enabled` / `prometheus.mock-enabled` | `false` | 数据源离线兜底（Mock 模式） |

> 默认配置**零外部存储依赖**即可启动（内存会话 + 纯 JDK）；分层存储与数据源地址按 `application.yml` 注释开启。

## 🔌 主要接口

| 接口 | 说明 |
|------|------|
| `POST /api/chat` | 普通对话（单 ReAct Agent + 工具调用） |
| `POST /api/chat_stream` | SSE 流式对话（多轮上下文） |
| `POST /api/ai_ops` | 多 Agent 告警诊断（SSE 流式输出分析报告） |
| `GET /api/trace/{sessionId}` | 查询调用链（LLM / 工具 Span） |
| `POST /api/upload` | 知识库文档上传并自动向量化入库 |
