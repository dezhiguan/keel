package com.keel.server.approval.controller;

import com.keel.server.approval.model.ApprovalView;
import com.keel.server.approval.model.DecisionBody;
import com.keel.server.approval.model.OpenApproval;
import com.keel.server.approval.service.ApprovalService;
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
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public R<PageResult<ApprovalView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Pattern(regexp = "10|20|50|100") String size,
            @RequestParam(required = false) String source) {
        return R.ok(approvals.page(status, agent, env, page, Integer.parseInt(size), source));
    }

    @PostMapping
    public R<ApprovalView> open(@RequestBody OpenApproval body) {
        return R.ok(approvals.open(body));
    }

    @PostMapping("/{id}/decision")
    public R<ApprovalView> decide(@PathVariable String id, @RequestBody DecisionBody body) {
        // TODO(P0-5): take the approver from the auth-gateway JWT instead of the fixed local admin.
        var decision = body == null ? null : body.decision();
        return R.ok(approvals.decide(id, decision, "dev"));
    }
}
