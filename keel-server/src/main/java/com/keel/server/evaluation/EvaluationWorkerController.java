package com.keel.server.evaluation;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/eval/runs/{runId}")
public class EvaluationWorkerController {
    private final EvaluationWorkerBridge bridge;
    public EvaluationWorkerController(EvaluationWorkerBridge bridge){this.bridge=bridge;}
    @PostMapping("/context") public R<Map<String,Object>> context(@PathVariable String runId,@RequestHeader("X-Keel-Eval-Capability") String cap,@RequestBody JsonNode body){return R.ok(bridge.context(runId,cap,body));}
    @PostMapping("/turns") public R<Map<String,Object>> turn(@PathVariable String runId,@RequestHeader("X-Keel-Eval-Capability") String cap,@RequestBody JsonNode body){return R.ok(bridge.saveTurn(runId,cap,body));}
    @PostMapping("/progress") public R<Map<String,Object>> progress(@PathVariable String runId,@RequestHeader("X-Keel-Eval-Capability") String cap,@RequestBody JsonNode body){bridge.progress(runId,cap,body);return R.ok(Map.of());}
}
