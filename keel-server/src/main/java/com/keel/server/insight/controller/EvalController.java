package com.keel.server.insight.controller;

import com.keel.server.common.R;
import com.keel.server.insight.EvalQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {
    private final EvalQueryService eval;

    public EvalController(EvalQueryService eval) {
        this.eval = eval;
    }

    @GetMapping("/{agent}/latest")
    public R<Map<String, Object>> latest(@PathVariable String agent) {
        return R.ok(eval.latest(agent));
    }

    @PostMapping("/{agent}/runs")
    public ResponseEntity<R<Map<String, String>>> run(@PathVariable String agent) {
        return ResponseEntity.status(202).body(R.ok(Map.of("runId", eval.start(agent))));
    }

    @GetMapping("/runs/{runId}")
    public R<Map<String, Object>> runStatus(@PathVariable String runId) {
        return R.ok(eval.run(runId));
    }
}
