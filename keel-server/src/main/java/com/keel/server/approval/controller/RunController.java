package com.keel.server.approval.controller;

import com.keel.server.approval.model.OpenRun;
import com.keel.server.approval.model.RunInput;
import com.keel.server.approval.model.SuspendedRunView;
import com.keel.server.approval.service.RunService;
import com.keel.server.common.PageResult;
import com.keel.server.common.R;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {
    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @GetMapping
    public R<PageResult<SuspendedRunView>> list(
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Pattern(regexp = "10|20|50|100") String size) {
        return R.ok(runs.page(agent, page, Integer.parseInt(size)));
    }

    @PostMapping
    public R<SuspendedRunView> open(@RequestBody OpenRun body) {
        return R.ok(runs.open(body));
    }

    @PostMapping("/{runId}/input")
    public R<Void> input(@PathVariable String runId, @RequestBody RunInput body) {
        runs.answer(runId, body == null ? null : body.text());
        return R.ok(null);
    }
}
