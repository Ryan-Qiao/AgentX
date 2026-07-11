package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.trace.AgentTraceDetailResponse;
import com.kama.jchatmind.trace.AgentTraceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-traces")
public class AgentTraceController {
    private final AgentTraceQueryService queryService;

    public AgentTraceController(AgentTraceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{traceId}")
    public ApiResponse<AgentTraceDetailResponse> getTrace(@PathVariable String traceId) {
        return ApiResponse.success(queryService.getByTraceId(traceId));
    }
}
