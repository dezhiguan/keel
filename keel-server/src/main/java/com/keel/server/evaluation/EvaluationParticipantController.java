package com.keel.server.evaluation;
import com.keel.common.model.EvaluationParticipant;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/eval/runs/{runId}/participants")
public class EvaluationParticipantController {
    private final EvaluationParticipants participants;
    public EvaluationParticipantController(EvaluationParticipants participants){this.participants=participants;}
    @PostMapping public R<EvaluationParticipant> consent(@PathVariable String runId){return R.ok(participants.consent(runId));}
}
