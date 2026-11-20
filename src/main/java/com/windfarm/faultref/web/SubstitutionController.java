package com.windfarm.faultref.web;

import com.windfarm.faultref.service.SubstitutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.SubstitutionDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spare-part substitution graph admin and time-separated advice.
 *
 * Read endpoints are available to every role; write endpoints follow the
 * global ENGINEER/ADMIN rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/substitutions")
@Tag(name = "Substitutions",
        description = "Spare-part substitution graph, part retirement, historical work-order "
                + "recommendations, warehouse stock contention and executable alternative advice")
public class SubstitutionController {

    private final SubstitutionService service;

    public SubstitutionController(SubstitutionService service) {
        this.service = service;
    }

    // ---- graph admin ----------------------------------------------------

    @Operation(summary = "Add a condition-bearing substitution edge to a DRAFT catalog version")
    @PostMapping("/edges")
    public Map<String, Object> createEdge(@Valid @RequestBody ApiRequests.SubstitutionCreate req) {
        return service.createSubstitution(req);
    }

    @Operation(summary = "Retract a substitution edge (the historical row is kept)")
    @PostMapping("/edges/{id}/retract")
    public Map<String, Object> retractEdge(@PathVariable UUID id) {
        return service.retractSubstitution(id);
    }

    @Operation(summary = "List substitution edges defined in a catalog version")
    @GetMapping("/catalogs/{catalogLabel}/edges")
    public List<SubstitutionDtos.SubstitutionEdge> edges(@PathVariable String catalogLabel) {
        return service.edges(catalogLabel);
    }

    // ---- part lifecycle -------------------------------------------------

    @Operation(summary = "Record a part lifecycle state (ACTIVE/OBSOLETE) on a DRAFT catalog")
    @PostMapping("/part-lifecycle")
    public Map<String, Object> createLifecycle(@Valid @RequestBody ApiRequests.PartLifecycleCreate req) {
        return service.createLifecycle(req);
    }

    @Operation(summary = "List part lifecycle records of a catalog version")
    @GetMapping("/catalogs/{catalogLabel}/part-lifecycle")
    public List<SubstitutionDtos.PartLifecycle> lifecycle(@PathVariable String catalogLabel) {
        return service.lifecycle(catalogLabel);
    }

    // ---- historical work-order recommendations --------------------------

    @Operation(summary = "Record the immutable part recommendation captured for a historical work order",
            description = "The row is never rewritten by later catalog revisions; forward substitution "
                    + "links are computed separately so today's issuable parts cannot be backfilled.")
    @PostMapping("/work-order-recommendations")
    public Map<String, Object> recordRecommendation(
            @Valid @RequestBody ApiRequests.WorkOrderRecommendationCreate req, Authentication auth) {
        return service.recordRecommendation(req, auth == null ? "system" : auth.getName());
    }

    @Operation(summary = "List historical work-order part recommendations")
    @GetMapping("/work-order-recommendations")
    public List<SubstitutionDtos.WorkOrderRecommendation> recommendations() {
        return service.recommendations();
    }

    // ---- warehouse stock contention -------------------------------------

    @Operation(summary = "Record warehouse-side stock contention; diagnosis only flags, the warehouse decides",
            description = "CLAIM_PENDING marks competing demand; CONFIRMED_ALLOCATED removes the stock from "
                    + "the executable advice set; RELEASED clears the contention.")
    @PostMapping("/stock-contentions")
    public Map<String, Object> recordContention(@Valid @RequestBody ApiRequests.StockContentionCreate req,
                                                Authentication auth) {
        return service.recordContention(req, auth == null ? "system" : auth.getName());
    }

    @Operation(summary = "List stock contention records for an inventory snapshot")
    @GetMapping("/inventory-snapshots/{inventoryLabel}/stock-contentions")
    public List<SubstitutionDtos.StockContention> contentions(@PathVariable String inventoryLabel) {
        return service.contentions(inventoryLabel);
    }

    // ---- advice ---------------------------------------------------------

    @Operation(summary = "Advice for one diagnostic concept at device, environment, work-order time and inventory point",
            description = "Returns the immutable original recommendation separately from the currently "
                    + "executable alternatives, with full substitution paths, edge-level condition checks, "
                    + "cycle conflicts, not-substitutable reasons and pending engineering conditions.")
    @PostMapping("/advice")
    public SubstitutionDtos.ConceptPartAdvice advice(
            @Valid @RequestBody ApiRequests.SubstitutionAdviceRequest req) {
        return service.evaluateRequest(req);
    }

    @Operation(summary = "Part advice for every concept of a stored diagnosis result",
            description = "Reuses the run's pinned catalog/inventory (or frozen release) so part advice "
                    + "is evaluated against the same snapshot as the diagnosis.")
    @GetMapping("/diagnosis/results/{resultId}/part-advice")
    public List<SubstitutionDtos.ConceptPartAdvice> resultAdvice(
            @PathVariable UUID resultId,
            @RequestParam(required = false) BigDecimal ambientTempC,
            @RequestParam(required = false) String interfaceCode,
            @RequestParam(required = false) String workOrderExternalId) {
        return service.adviceForDiagnosisResult(resultId, ambientTempC, interfaceCode, workOrderExternalId);
    }
}
