package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devflow")
public class DevflowController {
    private final DevflowService devflow;

    public DevflowController(DevflowService devflow) {
        this.devflow = devflow;
    }

    @GetMapping("/jobs")
    public R<DevflowTypes.JobList> jobs(@RequestParam(required = false) String layer,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String stage,
                                        @RequestParam(required = false) String batch,
                                        @RequestParam(defaultValue = "false") boolean mine) {
        return R.ok(devflow.list(layer, status, stage, batch, mine, actor()));
    }

    @GetMapping("/jobs/{jobId}")
    public R<DevflowTypes.Job> job(@PathVariable String jobId) {
        return R.ok(devflow.job(jobId));
    }

    @PostMapping("/jobs/{jobId}/takeover")
    public R<DevflowTypes.Job> takeover(@PathVariable String jobId) {
        requireWrite();
        return R.ok(devflow.takeover(jobId, actor()));
    }

    @PostMapping("/jobs/{jobId}/handback")
    public R<DevflowTypes.Job> handback(@PathVariable String jobId) {
        requireWrite();
        return R.ok(devflow.handback(jobId, actor()));
    }

    @PostMapping("/jobs/{jobId}/assist")
    public R<DevflowTypes.Job> assist(@PathVariable String jobId, @RequestBody DevflowTypes.Assist body) {
        requireWrite();
        return R.ok(devflow.assist(jobId, body == null ? null : body.instruction(), actor()));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public R<DevflowTypes.Job> cancel(@PathVariable String jobId) {
        requireWrite();
        return R.ok(devflow.cancel(jobId, actor()));
    }

    @GetMapping("/batches")
    public R<DevflowTypes.BatchList> batches() {
        return R.ok(devflow.batches());
    }

    @PostMapping("/batches/preview")
    public R<DevflowTypes.Preview> preview() {
        return R.ok(new DevflowTypes.Preview(devflow.preview()));
    }

    @PostMapping("/batches")
    public R<DevflowTypes.Batch> create(@RequestBody DevflowTypes.BatchCreate body) {
        requireWrite();
        return R.ok(devflow.create(body == null ? null : body.title(), body == null ? null : body.rows(), actor()));
    }

    @GetMapping("/settings")
    public R<DevflowTypes.Settings> settings() {
        return R.ok(devflow.settings());
    }

    @PutMapping("/settings")
    public R<DevflowTypes.Settings> save(@RequestBody DevflowTypes.Settings body) {
        requireWrite();
        var principal = ConsolePrincipal.current();
        if (principal != null && !"ADMIN".equals(principal.platformRole())) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "规则仅平台管理员可改");
        }
        return R.ok(devflow.save(body));
    }

    private static void requireWrite() {
        var principal = ConsolePrincipal.current();
        if (principal != null && principal.readOnly()) {
            throw new KeelException(ErrorCode.AUTH_PREVIEW_READONLY, ErrorCode.AUTH_PREVIEW_READONLY.message());
        }
    }

    private static String actor() {
        var principal = ConsolePrincipal.current();
        if (principal == null || principal.displayName() == null || principal.displayName().isBlank()) return "平台管理员";
        return principal.displayName();
    }
}
