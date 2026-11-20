package com.windfarm.faultref.web;

import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.service.EvidenceService;
import com.windfarm.faultref.service.SubstitutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/evidence")
@Tag(name = "Evidence", description = "Controller alarms (including offline backfill) and vendor work orders")
public class EvidenceController {

    private final EvidenceService evidenceService;
    private final EvidenceRepo evidenceRepo;
    private final ReferenceRepo reference;
    private final SubstitutionService substitutionService;

    public EvidenceController(EvidenceService evidenceService, EvidenceRepo evidenceRepo,
                              ReferenceRepo reference, SubstitutionService substitutionService) {
        this.evidenceService = evidenceService;
        this.evidenceRepo = evidenceRepo;
        this.reference = reference;
        this.substitutionService = substitutionService;
    }

    @Operation(summary = "Ingest a controller alarm or a vendor-term alarm",
            description = "The alarm is interpreted at occurredAt. receivedAt only records lateness; "
                    + "OFFLINE_BACKFILL is a transport fact and does not shift interpretation.")
    @PostMapping("/alarms")
    public Map<String, Object> ingestAlarm(@Valid @RequestBody ApiRequests.AlarmCreate req) {
        return evidenceService.ingestAlarm(new EvidenceService.NewAlarm(
                req.externalId(), req.unitCode(), req.sourceSystemCode(), req.sourceCode(),
                req.vendor(), req.vendorTerm(), req.firmwareVersion(), req.occurredAt(), req.receivedAt(),
                req.ingestType(), req.attributes(), req.rawPayload()));
    }

    @Operation(summary = "Ingest a vendor work order")
    @PostMapping("/work-orders")
    public Map<String, Object> ingestWorkOrder(@Valid @RequestBody ApiRequests.WorkOrderCreate req) {
        return evidenceService.ingestWorkOrder(new EvidenceService.NewWorkOrder(
                req.externalId(), req.unitCode(), req.vendor(), req.reportedTerm(), req.vendorTerm(),
                req.openedAt(), req.closedAt(), req.status(), req.symptom(), req.rawPayload()));
    }

    @Operation(summary = "List alarms (ordered by occurrence time)")
    @GetMapping("/alarms")
    public List<Map<String, Object>> alarms() {
        return evidenceRepo.listAlarms();
    }

    @Operation(summary = "List work orders")
    @GetMapping("/work-orders")
    public List<Map<String, Object>> workOrders() {
        return evidenceRepo.listWorkOrders();
    }

    @Operation(summary = "Get a work order with its linked term and mapping history (historical explainability)")
    @GetMapping("/work-orders/{id}")
    public Map<String, Object> workOrder(@PathVariable UUID id) {
        Map<String, Object> wo = ReferenceRepo.require(evidenceRepo.workOrderById(id),
                "WORK_ORDER_NOT_FOUND", "Unknown work order " + id);
        Map<String, Object> out = new java.util.LinkedHashMap<>(wo);
        UUID termId = ReferenceRepo.asUuid(wo, "term_attachment_id");
        if (termId != null) {
            out.put("term", reference.termById(termId).orElse(null));
        }
        substitutionService.attachHistoricalRecommendations(List.of(out), null);
        return out;
    }
}
