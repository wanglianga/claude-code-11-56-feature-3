package com.windfarm.faultref.web;

import com.windfarm.faultref.repo.MappingRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.service.MappingService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.MappingDtos;
import com.windfarm.faultref.web.dto.MappingDtos.AssertionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/mappings")
@Tag(name = "Mappings", description = "Time-validity mapping assertions, preserved conflicts and engineer adjudication")
public class MappingController {

    private final MappingService mappings;
    private final MappingRepo mappingRepo;
    private final ReferenceRepo reference;

    public MappingController(MappingService mappings, MappingRepo mappingRepo, ReferenceRepo reference) {
        this.mappings = mappings;
        this.mappingRepo = mappingRepo;
        this.reference = reference;
    }

    @Operation(summary = "Create a mapping assertion (source code or vendor term -> concept)",
            description = "Assertions are time-valid and never overwrite history. Contradictory assertions "
                    + "for the same scope are stored side by side and surface as a preserved conflict.")
    @PostMapping("/assertions")
    public Map<String, Object> createAssertion(@Valid @RequestBody ApiRequests.AssertionCreate req) {
        UUID id = mappings.createAssertion(new MappingService.CreateAssertion(
                req.sourceCodeId(), req.termAttachmentId(), req.conceptCode(), req.definitionVersion(),
                req.manufacturerCode(), req.modelCode(), req.firmwareFrom(), req.firmwareTo(),
                req.qualifiers(), req.confidence(), req.state(), req.validFrom(), req.validTo(),
                req.supersedesId(), req.approvalRef(), req.approvedBy(), req.notes()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("state", req.state() == null ? "ACTIVE" : req.state());
        return out;
    }

    @Operation(summary = "Retract or supersede an assertion, closing its validity window")
    @PostMapping("/assertions/{id}/close")
    public Map<String, Object> closeAssertion(@PathVariable UUID id,
                                              @RequestBody(required = false) ApiRequests.AssertionClose req) {
        ApiRequests.AssertionClose body = req == null ? new ApiRequests.AssertionClose(null, null) : req;
        mappings.closeAssertion(id, body.validTo(), body.state());
        return Map.of("id", id, "closed", true);
    }

    @Operation(summary = "Assertion history for a controller source code (all states)")
    @GetMapping("/source-codes/{sourceCodeId}/history")
    public Map<String, Object> codeHistory(@PathVariable UUID sourceCodeId) {
        Map<String, Object> code = ReferenceRepo.require(reference.sourceCodeById(sourceCodeId),
                "SOURCE_CODE_NOT_FOUND", "Unknown source code " + sourceCodeId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceCode", code.get("code"));
        out.put("sourceSystemCode", code.get("source_system_code"));
        out.put("rawText", code.get("raw_text"));
        out.put("assertions", mappingRepo.toAssertionViews(mappingRepo.assertionsForCode(sourceCodeId)));
        out.put("adjudications", mappingRepo.adjudicationsForCode(sourceCodeId));
        return out;
    }

    @Operation(summary = "Assertion history for a vendor term attachment (all states)")
    @GetMapping("/terms/{termId}/history")
    public Map<String, Object> termHistory(@PathVariable UUID termId) {
        Map<String, Object> term = ReferenceRepo.require(reference.termById(termId),
                "TERM_NOT_FOUND", "Unknown term " + termId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor", term.get("vendor"));
        out.put("termText", term.get("term_text"));
        out.put("assertions", mappingRepo.toAssertionViews(mappingRepo.assertionsForTerm(termId)));
        out.put("adjudications", mappingRepo.adjudicationsForTerm(termId));
        return out;
    }

    /**
     * Preserved conflicts: same source + same effective scope, currently active,
     * yielding more than one concept. Nothing is collapsed into a single answer.
     */
    @Operation(summary = "List currently preserved mapping conflicts")
    @GetMapping("/conflicts")
    public List<MappingDtos.ConflictEntry> conflicts() {
        List<Map<String, Object>> groups = mappingRepo.activeConflictGroups();
        List<MappingDtos.ConflictEntry> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            UUID codeId = ReferenceRepo.asUuid(g, "source_code_id");
            UUID termId = ReferenceRepo.asUuid(g, "term_attachment_id");
            List<Map<String, Object>> rows = codeId != null
                    ? mappingRepo.activeCandidatesForGroup(codeId, null,
                        ReferenceRepo.asString(g, "man"), ReferenceRepo.asString(g, "mdl"),
                        ReferenceRepo.asString(g, "fw"))
                    : mappingRepo.activeCandidatesForGroup(null, termId,
                        ReferenceRepo.asString(g, "man"), ReferenceRepo.asString(g, "mdl"),
                        ReferenceRepo.asString(g, "fw"));
            List<MappingDtos.Candidate> candidates = rows.stream()
                    .map(mappings::toCandidateView).toList();
            Map<String, Object> source = codeId != null
                    ? reference.sourceCodeById(codeId).orElse(Map.of())
                    : reference.termById(termId).orElse(Map.of());
            // Surface the firmware bound (if the group is firmware-scoped) and the
            // adjudications that apply to this exact scope, so the conflict is auditable.
            String fwFrom = rows.isEmpty() ? null : ReferenceRepo.asString(rows.get(0), "firmware_from");
            String fwTo = rows.isEmpty() ? null : ReferenceRepo.asString(rows.get(0), "firmware_to");
            List<Map<String, Object>> adjudications = codeId != null
                    ? mappingRepo.adjudicationsForCode(codeId)
                    : mappingRepo.adjudicationsForTerm(termId);
            out.add(new MappingDtos.ConflictEntry(
                    codeId != null ? "CODE" : "TERM",
                    ReferenceRepo.asString(source, "source_system_code"),
                    ReferenceRepo.asString(source, "code"),
                    ReferenceRepo.asString(source, "vendor"),
                    ReferenceRepo.asString(source, "term_text"),
                    ReferenceRepo.asString(g, "manufacturer_code"),
                    ReferenceRepo.asString(g, "model_code"),
                    fwFrom, fwTo,
                    candidates,
                    adjudications));
        }
        return out;
    }

    @Operation(summary = "Adjudicate a preserved conflict explicitly (admin only)",
            description = "Adds a time-valid decision record; the conflicting assertions are left untouched.")
    @PostMapping("/conflicts/{conflictKey}/adjudications")
    public Map<String, Object> adjudicate(@PathVariable String conflictKey,
                                          @Valid @RequestBody ApiRequests.AdjudicationCreate req) {
        UUID id = mappings.adjudicate(new MappingService.Adjudicate(
                req.sourceCodeId(), req.termAttachmentId(), req.conceptACode(), req.conceptBCode(),
                req.chosenConceptCode(), req.modelCode(), req.manufacturerCode(),
                req.validFrom(), req.validTo(), req.decision(), req.adjudicatedBy(), req.approvalRef()));
        return Map.of("id", id, "conflictKey", conflictKey, "adjudicated", true);
    }
}
