package com.keel.server.insight;

import com.keel.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quality")
public class QualityController {
    private final QualityCenterService quality;

    public QualityController(QualityCenterService quality) {
        this.quality = quality;
    }

    @GetMapping
    public R<QualityCenterService.Report> report(@RequestParam(required = false) String agent) {
        return R.ok(quality.report(agent));
    }

    @PostMapping("/cases")
    public R<QualityCenterService.Added> add(@RequestBody AddCase body) {
        return R.ok(quality.add(body.agent(), body.traceId()));
    }

    public record AddCase(String agent, String traceId) {}
}
