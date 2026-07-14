package org.example.controller;

import org.example.observability.AgentTraceRecorder;
import org.example.observability.TraceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 调用链追踪查看接口。
 * 浏览器/curl 访问 GET /api/trace/{sessionId} 即可看到本次 Agent 推理的
 * thought-action-observation 全链路（LLM 调用次数/token/延迟 + 工具调用入参出参）。
 */
@RestController
@RequestMapping("/api")
public class TraceController {

    private static final Logger logger = LoggerFactory.getLogger(TraceController.class);

    @Autowired
    private AgentTraceRecorder agentTraceRecorder;

    @GetMapping("/trace/{sessionId}")
    public ResponseEntity<TraceModel.Trace> getTrace(@PathVariable String sessionId) {
        TraceModel.Trace trace = agentTraceRecorder.getTrace(sessionId);
        if (trace == null) {
            logger.info("trace 不存在: {}", sessionId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trace);
    }
}
