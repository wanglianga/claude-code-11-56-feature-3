package com.windfarm.faultref.web;

import com.windfarm.faultref.service.DiagnosisQueryService;
import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/diagnosis")
@Tag(name = "Diagnosis", description = "Run diagnosis, replay by release, compare runs, statistics and explainability")
public class DiagnosisController {

    private final DiagnosisService diagnosis;
    private final DiagnosisQueryService query;

    public DiagnosisController(DiagnosisService diagnosis, DiagnosisQueryService query) {
        this.diagnosis = diagnosis;
        this.query = query;
    }

    @Operation(summary = "Create and execute a diagnosis run",
            description = "mode=ASSERTION resolves time-validity assertions at each alarm occurrence; "
                    + "mode=SNAPSHOT replays a frozen release. The catalog/inventory context is pinned once "
                    + "per run, so a catalog update during the run cannot affect its results.")
    @PostMapping("/runs")
    public DiagnosisDtos.Run run(@RequestBody DiagnosisDtos.RunRequest req, Authentication auth) {
        return diagnosis.run(req, auth == null ? "system" : auth.getName());
    }

    @Operation(summary = "List recent runs")
    @GetMapping("/runs")
    public List<DiagnosisDtos.RunSummary> runs(@RequestParam(defaultValue = "50") int limit) {
        return query.listRuns(Math.min(Math.max(limit, 1), 500));
    }

    @Operation(summary = "Get a run with all results and findings")
    @GetMapping("/runs/{id}")
    public DiagnosisDtos.Run run(@PathVariable UUID id) {
        return diagnosis.getRun(id);
    }

    @Operation(summary = "Diagnosis statistics for a run")
    @GetMapping("/runs/{id}/statistics")
    public DiagnosisDtos.Statistics statistics(@PathVariable UUID id) {
        return query.statistics(id);
    }

    @Operation(summary = "Compare two runs and classify every difference")
    @GetMapping("/runs/{leftId}/diff/{rightId}")
    public DiagnosisDtos.RunDiff diff(@PathVariable UUID leftId, @PathVariable UUID rightId) {
        return query.diff(leftId, rightId);
    }

    @Operation(summary = "Explain one result back to raw source, definition, approval and mapping chain")
    @GetMapping("/results/{id}/explain")
    public DiagnosisDtos.Explain explain(@PathVariable UUID id) {
        return query.explain(id);
    }
}
