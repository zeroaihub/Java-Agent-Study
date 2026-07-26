package com.zero.ai.agentstudy.day05rag.mycode.controller;

import com.zero.ai.agentstudy.day05rag.mycode.dto.AskRequest;
import com.zero.ai.agentstudy.day05rag.mycode.dto.AskResponse;
import com.zero.ai.agentstudy.day05rag.mycode.dto.IngestRequest;
import com.zero.ai.agentstudy.day05rag.mycode.dto.IngestResponse;
import com.zero.ai.agentstudy.day05rag.mycode.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * RagController：对外 HTTP 入口。是整个 RAG Demo 的门面。
 *
 * <p>提供三个接口：
 * <ul>
 *   <li>POST /ingest ：写入知识（离线索引流）</li>
 *   <li>POST /ask    ：智能问答（在线问答流）</li>
 *   <li>DELETE /clear：清空知识库（方便反复测试）</li>
 * </ul>
 *
 * <p>路径前缀 mycode，与 docs 里演示用的接口区分开，避免冲突。
 */
@RestController
@RequestMapping("/api/day05/rag/mycode")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    /**
     * 写入知识：把 title + content 灌入向量库。
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/day05/rag/mycode/ingest \
     *   -H "Content-Type: application/json" \
     *   -d '{"title":"请假制度","content":"员工年假为10天，需提前3天申请。"}'
     * </pre>
     */
    @PostMapping("/ingest")
    public IngestResponse ingest(@Valid @RequestBody IngestRequest req) {
        return ragService.ingest(req.getTitle(), req.getContent());
    }

    /**
     * 智能问答：基于知识库回答问题，返回答案 + 引用出处。
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/day05/rag/mycode/ask \
     *   -H "Content-Type: application/json" \
     *   -d '{"question":"我一年有几天年假？"}'
     * </pre>
     */
    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest req) {
        return ragService.ask(req.getQuestion(), req.getTopK());
    }

    /**
     * 清空知识库（测试用）。
     */
    @DeleteMapping("/clear")
    public String clear() {
        ragService.clear();
        return "已清空知识库";
    }
}