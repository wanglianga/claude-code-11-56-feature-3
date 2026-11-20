package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.DiagnosisRepo;
import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.ReleaseRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.repo.SubstitutionRepo;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.SubstitutionDtos;
import com.windfarm.faultref.web.dto.SubstitutionDtos.ConceptPartAdvice;
import com.windfarm.faultref.web.dto.SubstitutionDtos.EdgeCheck;
import com.windfarm.faultref.web.dto.SubstitutionDtos.OriginalRecommendation;
import com.windfarm.faultref.web.dto.SubstitutionDtos.PartAdvice;
import com.windfarm.faultref.web.dto.SubstitutionDtos.PathNode;
import com.windfarm.faultref.web.dto.SubstitutionDtos.SubstitutionPath;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spare-part substitution advice.
 *
 * <p>The substitution graph is traversed <b>forward at query time</b> from the
 * historical recommended part: every edge is verified against the device model,
 * ambient temperature, interface, the business instant and the pinned inventory
 * snapshot. This fits a catalog that changes frequently — no precomputed
 * concept-to-part closure has to be invalidated or rebuilt — and every returned
 * path carries its edge-level checks, blocked reasons and pending engineering
 * conditions, so the advice is fully explainable.
 *
 * <p>Time separation is strict: what a historical work order recommended lives
 * immutably in {@code work_order_part_recommendation} and is never recomputed
 * from today's catalog; current executable alternatives are always evaluated
 * against the explicitly pinned catalog + inventory context.
 *
 * <p>Cycles are surfaced as conflict paths, never silently broken. Stock
 * contention is owned by the warehouse: diagnosis only flags that a suggestion
 * may be invalid; a warehouse CONFIRMED_ALLOCATED record removes the stock from
 * the executable set.
 */
@Service
public class SubstitutionService {

    /** Defensive depth cap so a malformed graph cannot make the traversal unbounded. */
    private static final int MAX_DEPTH = 10;

    private final SubstitutionRepo substitutions;
    private final CatalogRepo catalog;
    private final ReferenceRepo reference;
    private final EvidenceRepo evidence;
    private final ReleaseRepo releaseRepo;
    private final DiagnosisRepo diagnosisRepo;
    private final CatalogService catalogService;

    public SubstitutionService(SubstitutionRepo substitutions, CatalogRepo catalog,
                               ReferenceRepo reference, EvidenceRepo evidence, ReleaseRepo releaseRepo,
                               DiagnosisRepo diagnosisRepo, CatalogService catalogService) {
        this.substitutions = substitutions;
        this.catalog = catalog;
        this.reference = reference;
        this.evidence = evidence;
        this.releaseRepo = releaseRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.catalogService = catalogService;
    }

    // ==================================================================
    // Admin writes
    // ==================================================================

    @Transactional
    public Map<String, Object> createSubstitution(ApiRequests.SubstitutionCreate req) {
        Map<String, Object> cv = ReferenceRepo.require(
                catalog.catalogVersionByLabel(req.catalogVersionLabel()),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + req.catalogVersionLabel());
        if (!"DRAFT".equals(ReferenceRepo.asString(cv, "status"))) {
            throw ApiException.conflict("CATALOG_NOT_DRAFT",
                    "Substitution edges may only be added to a DRAFT catalog version");
        }
        UUID fromId = requirePartId(req.fromPartNumber());
        UUID toId = requirePartId(req.toPartNumber());
        if (fromId.equals(toId)) {
            throw ApiException.badRequest("SUBSTITUTION_INVALID", "A part cannot substitute itself");
        }
        UUID modelId = null;
        if (req.modelCode() != null) {
            modelId = reference.modelByCodeOnly(req.modelCode())
                    .map(r -> ReferenceRepo.asUuid(r, "id")).orElse(null);
            if (modelId == null) {
                throw ApiException.notFound("MODEL_NOT_FOUND", "Unknown model " + req.modelCode());
            }
        }
        Instant validFrom = req.validFrom() == null ? Instant.now() : req.validFrom();
        if (req.validTo() != null && !req.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("VALIDITY_INVALID", "validTo must be after validFrom");
        }
        try {
            UUID id = substitutions.insertSubstitution(new MapSqlParameterSource()
                    .addValue("catalogVersionId", ReferenceRepo.asUuid(cv, "id"))
                    .addValue("fromPartId", fromId)
                    .addValue("toPartId", toId)
                    .addValue("kind", req.substitutionKind())
                    .addValue("modelId", modelId)
                    .addValue("tempMin", req.tempMinC())
                    .addValue("tempMax", req.tempMaxC())
                    .addValue("interface", req.interfaceRequirement())
                    .addValue("confirm", Boolean.TRUE.equals(req.requiresEngineeringConfirm()))
                    .addValue("validFrom", Sql.odt(validFrom))
                    .addValue("validTo", Sql.odt(req.validTo()))
                    .addValue("state", "ACTIVE")
                    .addValue("approvalRef", req.approvalRef())
                    .addValue("approvedBy", req.approvedBy())
                    .addValue("approvedAt", req.approvedBy() == null ? null : Sql.odt(Instant.now()))
                    .addValue("notes", req.notes()));
            return Map.of("id", id, "fromPartNumber", req.fromPartNumber(),
                    "toPartNumber", req.toPartNumber(), "state", "ACTIVE");
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SUBSTITUTION_EXISTS",
                    "Edge " + req.fromPartNumber() + " -> " + req.toPartNumber()
                            + " already exists in " + req.catalogVersionLabel());
        }
    }

    @Transactional
    public Map<String, Object> retractSubstitution(UUID edgeId) {
        substitutions.retractSubstitution(edgeId);
        return Map.of("id", edgeId, "state", "RETRACTED");
    }

    @Transactional
    public Map<String, Object> createLifecycle(ApiRequests.PartLifecycleCreate req) {
        Map<String, Object> cv = ReferenceRepo.require(
                catalog.catalogVersionByLabel(req.catalogVersionLabel()),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + req.catalogVersionLabel());
        if (!"DRAFT".equals(ReferenceRepo.asString(cv, "status"))) {
            throw ApiException.conflict("CATALOG_NOT_DRAFT",
                    "Part lifecycle may only be edited on a DRAFT catalog version");
        }
        UUID partId = requirePartId(req.partNumber());
        UUID successorId = req.supersededByPartNumber() == null ? null
                : requirePartId(req.supersededByPartNumber());
        if ("OBSOLETE".equals(req.state()) && req.obsoleteReason() == null) {
            throw ApiException.unprocessable("OBSOLETE_REASON_REQUIRED",
                    "Retiring a part requires an obsoleteReason");
        }
        try {
            UUID id = substitutions.insertLifecycle(new MapSqlParameterSource()
                    .addValue("catalogVersionId", ReferenceRepo.asUuid(cv, "id"))
                    .addValue("partId", partId)
                    .addValue("state", req.state())
                    .addValue("reason", req.obsoleteReason())
                    .addValue("successorId", successorId)
                    .addValue("decidedAt", Sql.odt(Instant.now()))
                    .addValue("approvedBy", req.approvedBy())
                    .addValue("approvalRef", req.approvalRef()));
            return Map.of("id", id, "partNumber", req.partNumber(), "state", req.state());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LIFECYCLE_EXISTS",
                    "Part " + req.partNumber() + " already has a lifecycle record in "
                            + req.catalogVersionLabel());
        }
    }

    @Transactional
    public Map<String, Object> recordRecommendation(ApiRequests.WorkOrderRecommendationCreate req, String user) {
        Map<String, Object> wo = ReferenceRepo.require(evidence.workOrderByExternalId(req.workOrderExternalId()),
                "WORK_ORDER_NOT_FOUND", "Unknown work order " + req.workOrderExternalId());
        UUID partId = req.partNumber() == null ? null : requirePartId(req.partNumber());
        UUID id = substitutions.insertWorkOrderRecommendation(new MapSqlParameterSource()
                .addValue("workOrderId", ReferenceRepo.asUuid(wo, "id"))
                .addValue("conceptCode", req.conceptCode())
                .addValue("partId", partId)
                .addValue("dispositionCode", req.dispositionCode())
                .addValue("catalogLabel", req.catalogVersionLabel())
                .addValue("quantity", req.quantity() == null ? 1 : req.quantity())
                .addValue("status", req.recommendationStatus())
                .addValue("reason", req.reason())
                .addValue("recordedBy", req.recordedBy() == null ? user : req.recordedBy())
                .addValue("approvalRef", req.approvalRef()));
        return Map.of("id", id, "workOrderExternalId", req.workOrderExternalId(),
                "recommendationStatus", req.recommendationStatus());
    }

    @Transactional
    public Map<String, Object> recordContention(ApiRequests.StockContentionCreate req, String user) {
        Map<String, Object> snapshot = ReferenceRepo.require(
                catalog.inventorySnapshotByLabel(req.inventorySnapshotLabel()),
                "INVENTORY_SNAPSHOT_NOT_FOUND",
                "Unknown inventory snapshot " + req.inventorySnapshotLabel());
        UUID partId = requirePartId(req.partNumber());
        String confirmedBy = null;
        Instant confirmedAt = null;
        if ("CONFIRMED_ALLOCATED".equals(req.status())) {
            confirmedBy = req.confirmedBy() == null ? user : req.confirmedBy();
            confirmedAt = Instant.now();
        }
        UUID id = substitutions.insertContention(new MapSqlParameterSource()
                .addValue("snapshotId", ReferenceRepo.asUuid(snapshot, "id"))
                .addValue("partId", partId)
                .addValue("warehouse", req.warehouse())
                .addValue("status", req.status())
                .addValue("demand", req.competingDemand())
                .addValue("confirmedBy", confirmedBy)
                .addValue("confirmedAt", Sql.odt(confirmedAt))
                .addValue("notes", req.notes()));
        return Map.of("id", id, "partNumber", req.partNumber(), "status", req.status());
    }

    // ==================================================================
    // Read listings
    // ==================================================================

    public List<SubstitutionDtos.SubstitutionEdge> edges(String catalogLabel) {
        UUID cvId = requireCatalogId(catalogLabel);
        return substitutions.substitutionsForCatalog(cvId).stream()
                .map(this::toEdgeDto).toList();
    }

    public List<SubstitutionDtos.PartLifecycle> lifecycle(String catalogLabel) {
        UUID cvId = requireCatalogId(catalogLabel);
        return substitutions.lifecycleForCatalog(cvId).stream().map(r -> new SubstitutionDtos.PartLifecycle(
                ReferenceRepo.asUuid(r, "id"), catalogLabel, ReferenceRepo.asString(r, "part_number"),
                ReferenceRepo.asString(r, "state"), ReferenceRepo.asString(r, "obsolete_reason"),
                ReferenceRepo.asString(r, "superseded_by_part_number"),
                ReferenceRepo.asInstant(r, "decided_at"), ReferenceRepo.asString(r, "approved_by"),
                ReferenceRepo.asString(r, "approval_ref"))).toList();
    }

    public List<SubstitutionDtos.WorkOrderRecommendation> recommendations() {
        return substitutions.allWorkOrderRecommendations().stream()
                .map(this::toRecommendationDto).toList();
    }

    /**
     * Attach the immutable historical part recommendations (and, in SNAPSHOT
     * mode, their frozen counterparts) to each work-order view. Forward
     * substitution links are intentionally not inlined here — they are computed
     * by the advice endpoint against an explicitly pinned inventory point, so a
     * historical work order can never silently inherit today's issuable part.
     */
    public void attachHistoricalRecommendations(List<Map<String, Object>> workOrders, UUID releaseId) {
        for (Map<String, Object> wo : workOrders) {
            Object externalIdRaw = wo.get("externalId");
            if (externalIdRaw == null) {
                externalIdRaw = wo.get("external_id");
            }
            String externalId = externalIdRaw == null ? null : externalIdRaw.toString();
            if (externalId == null) {
                continue;
            }
            List<SubstitutionDtos.WorkOrderRecommendation> recs;
            if (releaseId != null) {
                recs = substitutions.releaseRecommendations(releaseId).stream()
                        .filter(r -> externalId.equals(ReferenceRepo.asString(r, "work_order_external_id")))
                        .map(r -> new SubstitutionDtos.WorkOrderRecommendation(
                                ReferenceRepo.asUuid(r, "id"), externalId,
                                ReferenceRepo.asString(r, "concept_code"),
                                ReferenceRepo.asString(r, "part_number"),
                                ReferenceRepo.asString(r, "disposition_code"),
                                ReferenceRepo.asString(r, "catalog_version_label"),
                                ReferenceRepo.asInt(r, "quantity") == null ? 1 : ReferenceRepo.asInt(r, "quantity"),
                                ReferenceRepo.asString(r, "recommendation_status"),
                                ReferenceRepo.asString(r, "reason"),
                                ReferenceRepo.asInstant(r, "recorded_at"),
                                ReferenceRepo.asString(r, "recorded_by"),
                                ReferenceRepo.asString(r, "approval_ref")))
                        .toList();
            } else {
                recs = evidence.workOrderByExternalId(externalId)
                        .map(w -> substitutions.recommendationsForWorkOrder(ReferenceRepo.asUuid(w, "id")))
                        .orElse(List.of()).stream()
                        .map(this::toRecommendationDto).toList();
            }
            wo.put("partRecommendations", recs);
        }
    }

    public List<SubstitutionDtos.StockContention> contentions(String inventoryLabel) {
        UUID snapshotId = ReferenceRepo.asUuid(ReferenceRepo.require(
                catalog.inventorySnapshotByLabel(inventoryLabel), "INVENTORY_SNAPSHOT_NOT_FOUND",
                "Unknown inventory snapshot " + inventoryLabel), "id");
        return substitutions.contentionsForSnapshot(snapshotId).stream()
                .map(r -> new SubstitutionDtos.StockContention(
                        ReferenceRepo.asUuid(r, "id"),
                        ReferenceRepo.asString(r, "inventory_snapshot_label"),
                        ReferenceRepo.asString(r, "part_number"),
                        ReferenceRepo.asString(r, "warehouse"),
                        ReferenceRepo.asString(r, "status"),
                        ReferenceRepo.asString(r, "competing_demand"),
                        ReferenceRepo.asString(r, "confirmed_by"),
                        ReferenceRepo.asInstant(r, "confirmed_at"),
                        ReferenceRepo.asString(r, "notes"),
                        ReferenceRepo.asInstant(r, "created_at")))
                .toList();
    }

    // ==================================================================
    // On-demand advice
    // ==================================================================

    /**
     * Request-level entry point: resolves device/environment/work-order context,
     * pins one catalog + inventory pair, and evaluates one concept. The pin is
     * resolved before any graph read so a catalog publish during the request
     * cannot mix two catalog generations into one answer.
     */
    public ConceptPartAdvice evaluateRequest(ApiRequests.SubstitutionAdviceRequest req) {
        // ---- identify evidence: alarm and/or work order ----------------
        Map<String, Object> alarm = null;
        Map<String, Object> workOrder = null;
        if (req.alarmExternalId() != null) {
            alarm = ReferenceRepo.require(evidence.alarmByExternalId(req.alarmExternalId()),
                    "ALARM_NOT_FOUND", "Unknown alarm " + req.alarmExternalId());
        }
        if (req.workOrderExternalId() != null) {
            workOrder = ReferenceRepo.require(evidence.workOrderByExternalId(req.workOrderExternalId()),
                    "WORK_ORDER_NOT_FOUND", "Unknown work order " + req.workOrderExternalId());
        }

        String unitCode = req.unitCode();
        if (unitCode == null && alarm != null) {
            unitCode = ReferenceRepo.require(reference.unitById(ReferenceRepo.asUuid(alarm, "unit_id")),
                    "UNIT_NOT_FOUND", "Alarm references a missing unit").get("code").toString();
        }
        if (unitCode == null && workOrder != null) {
            unitCode = ReferenceRepo.require(reference.unitById(ReferenceRepo.asUuid(workOrder, "unit_id")),
                    "UNIT_NOT_FOUND", "Work order references a missing unit").get("code").toString();
        }
        if (unitCode == null) {
            throw ApiException.badRequest("UNIT_REQUIRED",
                    "unitCode is required when neither alarmExternalId nor workOrderExternalId identifies a unit");
        }
        Map<String, Object> unit = ReferenceRepo.require(reference.unitByCode(unitCode),
                "UNIT_NOT_FOUND", "Unknown unit " + unitCode);
        Map<String, Object> model = ReferenceRepo.require(
                reference.modelById(ReferenceRepo.asUuid(unit, "model_id")),
                "MODEL_NOT_FOUND", "Unit references a missing model");

        Instant at = req.occurredAt();
        if (at == null && alarm != null) {
            at = ReferenceRepo.asInstant(alarm, "occurred_at");
        }
        if (at == null && workOrder != null) {
            at = ReferenceRepo.asInstant(workOrder, "opened_at");
        }
        if (at == null) {
            at = Instant.now();
        }

        String conceptCode = req.conceptCode();
        String conceptName = null;
        if (conceptCode == null && alarm != null) {
            // The concept must come from a stored diagnosis result, never guessed here.
            throw ApiException.badRequest("CONCEPT_REQUIRED",
                    "conceptCode is required when the request is not anchored to a diagnosis result");
        }
        if (conceptCode != null) {
            Map<String, Object> c = reference.conceptByCode(conceptCode).orElse(null);
            if (c != null) {
                conceptName = ReferenceRepo.asString(c, "name");
            }
        }

        // Environment: explicit request values win; otherwise read alarm attributes
        // (e.g. ambientTempC / interfaceCode recorded by the controller).
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (alarm != null) {
            attributes.putAll(Sql.readMap(alarm.get("attributes")));
        }
        BigDecimal ambientTemp = req.ambientTempC();
        if (ambientTemp == null && attributes.get("ambientTempC") instanceof Number n) {
            ambientTemp = new BigDecimal(n.toString());
        }
        String interfaceCode = req.interfaceCode();
        if (interfaceCode == null && attributes.get("interfaceCode") != null) {
            interfaceCode = String.valueOf(attributes.get("interfaceCode"));
        }
        String firmwareVersion = null;
        if (alarm != null && ReferenceRepo.asUuid(alarm, "firmware_version_id") != null) {
            firmwareVersion = reference.firmwareById(ReferenceRepo.asUuid(alarm, "firmware_version_id"))
                    .map(r -> ReferenceRepo.asString(r, "version")).orElse(null);
        }

        // ---- pin one catalog + inventory context -----------------------
        UUID catalogId;
        UUID inventoryId;
        if (req.catalogVersionLabel() != null || req.inventorySnapshotLabel() != null) {
            CatalogService.PinnedContext pin = catalogService.pinByLabels(
                    req.catalogVersionLabel(), req.inventorySnapshotLabel());
            catalogId = pin.catalogVersionId();
            inventoryId = pin.inventorySnapshotId();
        } else {
            CatalogService.PinnedContext pin = catalogService.pin(at, null);
            catalogId = pin.catalogVersionId();
            inventoryId = pin.inventorySnapshotId();
        }

        AdviceQuery query = new AdviceQuery(conceptCode, conceptName, req.workOrderExternalId(),
                unitCode, ReferenceRepo.asString(model, "manufacturer_code"),
                ReferenceRepo.asString(model, "code"), firmwareVersion, ambientTemp, interfaceCode,
                at, Instant.now(), catalogId, inventoryId, attributes);
        return adviceForConcept(query, null);
    }

    /**
     * Build advice for every concept of a stored diagnosis result. The run's
     * pinned catalog/inventory context is reused so the part advice is evaluated
     * against exactly the same snapshot the diagnosis used; SNAPSHOT runs read
     * the frozen release tables.
     */
    public List<ConceptPartAdvice> adviceForDiagnosisResult(UUID resultId,
                                                            BigDecimal ambientTempC,
                                                            String interfaceCode,
                                                            String workOrderExternalId) {
        Map<String, Object> result = ReferenceRepo.require(diagnosisRepo.resultById(resultId),
                "RESULT_NOT_FOUND", "Unknown diagnosis result " + resultId);
        UUID runId = ReferenceRepo.asUuid(result, "run_id");
        Map<String, Object> run = ReferenceRepo.require(diagnosisRepo.runById(runId),
                "RUN_NOT_FOUND", "Unknown diagnosis run " + runId);
        Map<String, Object> alarm = ReferenceRepo.require(
                evidence.alarmById(ReferenceRepo.asUuid(result, "alarm_id")),
                "ALARM_NOT_FOUND", "Result references a missing alarm");
        Map<String, Object> unit = ReferenceRepo.require(
                reference.unitById(ReferenceRepo.asUuid(alarm, "unit_id")),
                "UNIT_NOT_FOUND", "Alarm references a missing unit");
        Map<String, Object> model = ReferenceRepo.require(
                reference.modelById(ReferenceRepo.asUuid(unit, "model_id")),
                "MODEL_NOT_FOUND", "Unit references a missing model");

        Map<String, Object> attributes = Sql.readMap(alarm.get("attributes"));
        BigDecimal ambientTemp = ambientTempC;
        if (ambientTemp == null && attributes.get("ambientTempC") instanceof Number n) {
            ambientTemp = new BigDecimal(n.toString());
        }
        String interfaceCodeResolved = interfaceCode;
        if (interfaceCodeResolved == null && attributes.get("interfaceCode") != null) {
            interfaceCodeResolved = String.valueOf(attributes.get("interfaceCode"));
        }
        String firmwareVersion = ReferenceRepo.asUuid(alarm, "firmware_version_id") == null ? null
                : reference.firmwareById(ReferenceRepo.asUuid(alarm, "firmware_version_id"))
                .map(r -> ReferenceRepo.asString(r, "version")).orElse(null);
        Instant at = ReferenceRepo.asInstant(alarm, "occurred_at");

        UUID releaseId = ReferenceRepo.asUuid(run, "release_id");
        UUID catalogId = ReferenceRepo.asUuid(run, "pinned_catalog_version_id");
        UUID inventoryId = ReferenceRepo.asUuid(run, "pinned_inventory_snapshot_id");

        List<Map<String, Object>> findings = diagnosisRepo.findingsForResult(resultId);
        List<ConceptPartAdvice> out = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            String conceptCode = ReferenceRepo.asString(f, "concept_code");
            String conceptName = ReferenceRepo.asString(f, "concept_name");
            AdviceQuery query = new AdviceQuery(conceptCode, conceptName, workOrderExternalId,
                    ReferenceRepo.asString(unit, "code"),
                    ReferenceRepo.asString(model, "manufacturer_code"),
                    ReferenceRepo.asString(model, "code"), firmwareVersion, ambientTemp,
                    interfaceCodeResolved, at, Instant.now(), catalogId, inventoryId, attributes);
            out.add(adviceForConcept(query, releaseId));
        }
        return out;
    }

    /**
     * Evaluate advice for one diagnostic concept at the pinned catalog +
     * inventory point. {@code releaseId} non-null switches every read to the
     * frozen release_* tables so SNAPSHOT replay stays independent of later edits.
     */
    public ConceptPartAdvice adviceForConcept(AdviceQuery query, UUID releaseId) {
        Instant at = query.businessAt() == null ? Instant.now() : query.businessAt();
        Graph graph = releaseId == null
                ? loadLiveGraph(query.catalogVersionId(), query.inventorySnapshotId())
                : loadReleaseGraph(releaseId);

        OriginalRecommendation original = resolveOriginalRecommendation(query, releaseId);

        // Starting parts: the immutably recorded historical part wins; otherwise
        // fall back to the parts the pinned catalog itself attaches to the concept.
        List<StartingPart> starts = startingParts(query, releaseId, graph, original);

        List<PartAdvice> currentAdvice = new ArrayList<>();
        List<SubstitutionPath> conceptCycleConflicts = new ArrayList<>();
        Set<String> conceptBlocked = new LinkedHashSet<>();
        Set<String> conceptPending = new LinkedHashSet<>();

        for (StartingPart start : starts) {
            PartAdvice advice = evaluatePart(graph, start, query, query.evaluatedAt());
            currentAdvice.add(advice);
            conceptCycleConflicts.addAll(advice.cycleConflicts());
            conceptBlocked.addAll(advice.blockedReasons());
            conceptPending.addAll(advice.pendingEngineeringConditions());
        }

        List<String> notSubstitutable = new ArrayList<>();
        boolean anyExecutable = currentAdvice.stream().anyMatch(a -> a.paths().stream()
                .anyMatch(p -> "EXECUTABLE".equals(p.pathStatus()) || "PENDING_CONFIRM".equals(p.pathStatus())));
        if (!starts.isEmpty() && !anyExecutable) {
            notSubstitutable.add("No executable alternative under the pinned context; see blocked reasons per part");
        }
        if (starts.isEmpty()) {
            notSubstitutable.add("No historical recommendation and no stock-bound disposition part exists for concept "
                    + query.conceptCode() + " in " + graph.catalogLabel);
        }
        if (query.ambientTempC() == null && tempConditionRequired(graph, starts)) {
            notSubstitutable.add("Ambient temperature is missing while one or more substitution edges are "
                    + "temperature-restricted; the low-temperature/cold-climate alternative cannot be verified");
        }

        String summary = buildSummary(original, currentAdvice, anyExecutable, conceptCycleConflicts,
                notSubstitutable, new ArrayList<>(conceptPending));

        SubstitutionDtos.EvaluationContext ctx = new SubstitutionDtos.EvaluationContext(
                query.unitCode(), query.manufacturerCode(), query.modelCode(), query.firmwareVersion(),
                query.ambientTempC(), query.interfaceCode(), at, query.evaluatedAt(),
                query.alarmAttributes());

        return new ConceptPartAdvice(query.conceptCode(), query.conceptName(), ctx,
                graph.catalogLabel, graph.inventoryLabel, original, currentAdvice, conceptCycleConflicts,
                notSubstitutable, new ArrayList<>(conceptPending), summary);
    }

    private PartAdvice evaluatePart(Graph graph, StartingPart start, AdviceQuery query, Instant at) {
        String partNumber = start.partNumber();
        GraphNode self = graph.nodes.getOrDefault(partNumber, GraphNode.unknown(partNumber));
        int qty = effectiveStock(graph, partNumber);
        String contentionStatus = contentionStatus(graph, partNumber);
        String contentionDemand = contentionDemand(graph, partNumber);

        List<String> blocked = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<SubstitutionPath> paths = new ArrayList<>();
        List<SubstitutionPath> cycles = new ArrayList<>();

        // Zero-length path: the original part itself, if it can still be issued.
        boolean selfInStock = qty >= start.requiredQty();
        if (selfInStock && !"OBSOLETE".equals(self.lifecycleState)) {
            String status = contentionStatus == null ? "EXECUTABLE"
                    : ("CONFIRMED_ALLOCATED".equals(contentionStatus) ? "BLOCKED" : "PENDING_CONFIRM");
            if ("BLOCKED".equals(status)) {
                blocked.add(partNumber + " stock is confirmed allocated to competing demand: " + contentionDemand);
            } else {
                paths.add(new SubstitutionPath(pathId(partNumber, 0), status,
                        List.of(nodeOf(graph, partNumber, 0, false)), List.of(), partNumber, true, false,
                        new ArrayList<>(), new ArrayList<>(), List.of(), contentionStatus));
            }
        } else if ("OBSOLETE".equals(self.lifecycleState)) {
            blocked.add(partNumber + " is OBSOLETE in " + graph.catalogLabel
                    + (self.obsoleteReason == null ? "" : ": " + self.obsoleteReason));
        } else if (!selfInStock) {
            blocked.add(partNumber + " has insufficient stock (available " + qty + ", needs "
                    + start.requiredQty() + ")");
        }

        // Forward traversal along the substitution graph.
        List<LinkedHashMap<String, Object>> rawPaths = new ArrayList<>();
        dfs(graph, partNumber, new ArrayList<>(List.of(partNumber)), new ArrayList<>(),
                query, at, rawPaths, 0);

        for (LinkedHashMap<String, Object> raw : rawPaths) {
            @SuppressWarnings("unchecked")
            List<String> nodeParts = (List<String>) raw.get("nodes");
            @SuppressWarnings("unchecked")
            List<EdgeCheck> checks = (List<EdgeCheck>) raw.get("checks");
            boolean cycle = Boolean.TRUE.equals(raw.get("cycle"));
            String terminal = nodeParts.get(nodeParts.size() - 1);
            List<PathNode> nodes = new ArrayList<>();
            for (int i = 0; i < nodeParts.size(); i++) {
                nodes.add(nodeOf(graph, nodeParts.get(i), i,
                        cycle && i == nodeParts.size() - 1));
            }
            List<String> pathBlocked = checks.stream()
                    .filter(c -> "FAILED".equals(c.checkStatus()))
                    .flatMap(c -> c.failedChecks().stream().map(f -> c.fromPartNumber() + "->" + c.toPartNumber()
                            + " failed " + f))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            List<String> pathPending = checks.stream()
                    .filter(EdgeCheck::requiresEngineeringConfirm)
                    .map(c -> "Engineering confirmation required for edge " + c.fromPartNumber() + "->"
                            + c.toPartNumber() + (c.interfaceRequirement() == null ? ""
                            : " (" + c.interfaceRequirement() + ")"))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            boolean hardFailed = checks.stream().anyMatch(c -> "FAILED".equals(c.checkStatus()));
            boolean retrofit = checks.stream().anyMatch(c -> "RETROFIT".equals(c.substitutionKind()));
            int terminalQty = effectiveStock(graph, terminal);
            boolean terminalStock = terminalQty >= start.requiredQty();
            String terminalContention = contentionStatus(graph, terminal);

            String status;
            if (cycle) {
                status = "CYCLE";
                @SuppressWarnings("unchecked")
                List<String> cyclePath = (List<String>) raw.get("cyclePath");
                SubstitutionPath p = new SubstitutionPath(pathId(partNumber, paths.size() + cycles.size() + 1),
                        status, nodes, checks, terminal, terminalStock, retrofit,
                        pathPending, pathBlocked, cyclePath, terminalContention);
                cycles.add(p);
                blocked.add("Substitution cycle detected: " + String.join(" -> ", cyclePath));
                continue;
            }
            if (hardFailed) {
                status = "BLOCKED";
            } else if ("CONFIRMED_ALLOCATED".equals(terminalContention)) {
                // Warehouse decision takes precedence over the raw stock figure.
                status = "BLOCKED";
                pathBlocked.add(terminal + " stock confirmed allocated to competing demand: "
                        + contentionDemand(graph, terminal));
            } else if (!terminalStock) {
                status = "BLOCKED";
                pathBlocked.add(terminal + " terminal stock insufficient (available " + terminalQty
                        + ", needs " + start.requiredQty() + ")");
            } else if ("CLAIM_PENDING".equals(terminalContention)) {
                // The warehouse has not decided: diagnosis may only flag that the
                // suggestion may become invalid, never treat it as executable.
                status = "PENDING_CONFIRM";
                pathPending.add(terminal + " has a pending warehouse claim ("
                        + contentionDemand(graph, terminal)
                        + "); executable only after the warehouse confirms allocation");
            } else if (!pathPending.isEmpty() || retrofit) {
                status = "PENDING_CONFIRM";
                if (retrofit) {
                    pathPending.add("Retrofit/interface modification must be engineered before issuing " + terminal);
                }
            } else {
                status = "EXECUTABLE";
            }
            if ("BLOCKED".equals(status)) {
                blocked.addAll(pathBlocked);
            }
            pending.addAll(pathPending);
            paths.add(new SubstitutionPath(pathId(partNumber, paths.size() + 1), status, nodes, checks,
                    terminal, terminalStock, retrofit, pathPending, pathBlocked, List.of(),
                    terminalContention));
        }

        if (paths.isEmpty() && cycles.isEmpty() && "OBSOLETE".equals(self.lifecycleState)) {
            blocked.add(partNumber + " has no outgoing substitution edge in " + graph.catalogLabel);
        }

        String stockStatus = qty == 0 ? "OUT_OF_STOCK"
                : (qty < start.requiredQty() ? "INSUFFICIENT" : "IN_STOCK");
        return new PartAdvice(partNumber, self.partName, self.lifecycleState, self.obsoleteReason,
                qty, start.requiredQty(), stockStatus, contentionStatus, contentionDemand,
                dedupe(blocked), dedupe(pending), paths, cycles);
    }

    /**
     * Recursive forward walk. A part repeated on the current walk closes a cycle:
     * the closing path is recorded and that branch stops. Each edge's condition
     * checks are always attached, even on blocked branches, so nothing is silently
     * dropped.
     */
    private void dfs(Graph graph, String current, List<String> walk, List<EdgeCheck> checks,
                     AdviceQuery query, Instant at, List<LinkedHashMap<String, Object>> out, int depth) {
        List<GraphEdge> edges = graph.edgesByFrom.getOrDefault(current, List.of());
        if (edges.isEmpty() || depth >= MAX_DEPTH) {
            if (!walk.isEmpty()) {
                LinkedHashMap<String, Object> terminal = new LinkedHashMap<>();
                terminal.put("nodes", new ArrayList<>(walk));
                terminal.put("checks", new ArrayList<>(checks));
                terminal.put("cycle", false);
                out.add(terminal);
            }
            return;
        }
        for (GraphEdge edge : edges) {
            EdgeCheck check = checkEdge(edge, query, at);
            List<String> nextWalk = new ArrayList<>(walk);
            List<EdgeCheck> nextChecks = new ArrayList<>(checks);
            nextChecks.add(check);
            if (walk.contains(edge.to)) {
                // Cycle: capture the exact conflicting path A -> ... -> A.
                int first = walk.indexOf(edge.to);
                List<String> cyclePath = new ArrayList<>(walk.subList(first, walk.size()));
                cyclePath.add(edge.to);
                nextWalk.add(edge.to);
                LinkedHashMap<String, Object> cyc = new LinkedHashMap<>();
                cyc.put("nodes", nextWalk);
                cyc.put("checks", nextChecks);
                cyc.put("cycle", true);
                cyc.put("cyclePath", cyclePath);
                out.add(cyc);
                continue;
            }
            nextWalk.add(edge.to);
            dfs(graph, edge.to, nextWalk, nextChecks, query, at, out, depth + 1);
        }
    }

    private EdgeCheck checkEdge(GraphEdge edge, AdviceQuery query, Instant evaluationInstant) {
        List<String> failed = new ArrayList<>();
        if (edge.validFrom != null && edge.validFrom.isAfter(evaluationInstant)) {
            failed.add("TIME_NOT_YET_VALID");
        }
        if (edge.validTo != null && !edge.validTo.isAfter(evaluationInstant)) {
            failed.add("TIME_EXPIRED");
        }
        if (edge.modelCode != null && !edge.modelCode.equals(query.modelCode())) {
            failed.add("MODEL_MISMATCH (edge requires " + edge.modelCode + ", unit is "
                    + query.modelCode() + ")");
        }
        if (edge.tempMin != null || edge.tempMax != null) {
            if (query.ambientTempC() == null) {
                failed.add("AMBIENT_TEMP_MISSING");
            } else {
                BigDecimal t = query.ambientTempC();
                if (edge.tempMin != null && t.compareTo(edge.tempMin) < 0) {
                    failed.add("TEMP_BELOW_MINIMUM (ambient " + t + " < " + edge.tempMin + ")");
                }
                if (edge.tempMax != null && t.compareTo(edge.tempMax) > 0) {
                    failed.add("TEMP_ABOVE_MAXIMUM (ambient " + t + " > " + edge.tempMax + ")");
                }
            }
        }
        if (edge.interfaceRequirement != null && !edge.interfaceRequirement.isBlank()) {
            if (query.interfaceCode() == null) {
                failed.add("INTERFACE_UNKNOWN (edge requires " + edge.interfaceRequirement + ")");
            } else if (!edge.interfaceRequirement.equals(query.interfaceCode())) {
                if ("RETROFIT".equals(edge.kind)) {
                    // A retrofit edge is usable only after an engineered interface
                    // modification; that is a pending confirmation, not a hard fail.
                    // The pending condition is materialized when the path is built.
                } else {
                    failed.add("INTERFACE_MISMATCH (edge requires " + edge.interfaceRequirement
                            + ", unit has " + query.interfaceCode() + ")");
                }
            }
        }
        return new EdgeCheck(edge.from, edge.to, edge.kind,
                failed.isEmpty() ? "PASSED" : "FAILED", failed, edge.modelCode,
                edge.tempMin, edge.tempMax, query.ambientTempC(), edge.interfaceRequirement,
                edge.requiresConfirm, edge.approvalRef, edge.notes);
    }

    // ==================================================================
    // Graph loading (live catalog vs frozen release)
    // ==================================================================

    private Graph loadLiveGraph(UUID catalogVersionId, UUID inventorySnapshotId) {
        Graph graph = new Graph();
        if (catalogVersionId == null) {
            return graph;
        }
        graph.catalogLabel = catalog.catalogVersionById(catalogVersionId)
                .map(r -> ReferenceRepo.asString(r, "version_label")).orElse(null);
        graph.inventoryLabel = inventorySnapshotId == null ? null
                : catalog.inventorySnapshotById(inventorySnapshotId)
                .map(r -> ReferenceRepo.asString(r, "label")).orElse(null);
        for (Map<String, Object> r : substitutions.allActiveEdges(catalogVersionId)) {
            GraphEdge edge = new GraphEdge(
                    ReferenceRepo.asString(r, "from_part_number"),
                    ReferenceRepo.asString(r, "to_part_number"),
                    ReferenceRepo.asString(r, "substitution_kind"),
                    ReferenceRepo.asString(r, "model_code"),
                    Sql.decimal(r.get("temp_min_c")), Sql.decimal(r.get("temp_max_c")),
                    ReferenceRepo.asString(r, "interface_requirement"),
                    Boolean.TRUE.equals(r.get("requires_engineering_confirm")),
                    ReferenceRepo.asInstant(r, "valid_from"), ReferenceRepo.asInstant(r, "valid_to"),
                    ReferenceRepo.asString(r, "approval_ref"), ReferenceRepo.asString(r, "notes"));
            graph.edgesByFrom.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge);
            graph.nodes.computeIfAbsent(edge.from, GraphNode::new);
            graph.nodes.computeIfAbsent(edge.to, GraphNode::new);
        }
        for (Map<String, Object> r : substitutions.lifecycleForCatalog(catalogVersionId)) {
            String pn = ReferenceRepo.asString(r, "part_number");
            GraphNode node = graph.nodes.computeIfAbsent(pn, GraphNode::new);
            node.lifecycleState = ReferenceRepo.asString(r, "state");
            node.obsoleteReason = ReferenceRepo.asString(r, "obsolete_reason");
        }
        if (inventorySnapshotId != null) {
            for (Map<String, Object> line : catalog.inventoryLevels(inventorySnapshotId)) {
                graph.stock.merge(ReferenceRepo.asString(line, "part_number"),
                        ReferenceRepo.asInt(line, "qty_available"), Integer::sum);
            }
            for (Map<String, Object> c : substitutions.contentionsForSnapshot(inventorySnapshotId)) {
                graph.contention.put(ReferenceRepo.asString(c, "part_number"), c);
            }
        }
        for (Map<String, Object> p : catalog.listParts()) {
            GraphNode node = graph.nodes.computeIfAbsent(ReferenceRepo.asString(p, "part_number"), GraphNode::new);
            if (node.partName == null) {
                node.partName = ReferenceRepo.asString(p, "name");
            }
            node.lifecycleState = node.lifecycleState == null ? "ACTIVE" : node.lifecycleState;
        }
        return graph;
    }

    private Graph loadReleaseGraph(UUID releaseId) {
        Graph graph = new Graph();
        Map<String, Object> rel = ReferenceRepo.require(releaseRepo.releaseById(releaseId),
                "RELEASE_NOT_FOUND", "Unknown release " + releaseId);
        graph.catalogLabel = ReferenceRepo.asString(rel, "catalog_version_label");
        graph.inventoryLabel = ReferenceRepo.asString(rel, "inventory_snapshot_label");
        for (Map<String, Object> r : substitutions.releaseSubstitutions(releaseId)) {
            if (!"ACTIVE".equals(ReferenceRepo.asString(r, "state"))) {
                continue;
            }
            GraphEdge edge = new GraphEdge(
                    ReferenceRepo.asString(r, "from_part_number"),
                    ReferenceRepo.asString(r, "to_part_number"),
                    ReferenceRepo.asString(r, "substitution_kind"),
                    ReferenceRepo.asString(r, "model_code"),
                    Sql.decimal(r.get("temp_min_c")), Sql.decimal(r.get("temp_max_c")),
                    ReferenceRepo.asString(r, "interface_requirement"),
                    Boolean.TRUE.equals(r.get("requires_engineering_confirm")),
                    ReferenceRepo.asInstant(r, "valid_from"), ReferenceRepo.asInstant(r, "valid_to"),
                    ReferenceRepo.asString(r, "approval_ref"), ReferenceRepo.asString(r, "notes"));
            graph.edgesByFrom.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge);
            graph.nodes.computeIfAbsent(edge.from, GraphNode::new);
            graph.nodes.computeIfAbsent(edge.to, GraphNode::new);
        }
        for (Map<String, Object> r : substitutions.releaseLifecycle(releaseId)) {
            String pn = ReferenceRepo.asString(r, "part_number");
            GraphNode node = graph.nodes.computeIfAbsent(pn, GraphNode::new);
            node.lifecycleState = ReferenceRepo.asString(r, "state");
            node.obsoleteReason = ReferenceRepo.asString(r, "obsolete_reason");
        }
        for (Map<String, Object> line : substitutions.releaseInventory(releaseId)) {
            graph.stock.merge(ReferenceRepo.asString(line, "part_number"),
                    ReferenceRepo.asInt(line, "qty_available"), Integer::sum);
        }
        for (Map<String, Object> c : substitutions.releaseContentions(releaseId)) {
            graph.contention.put(ReferenceRepo.asString(c, "part_number"), c);
        }
        for (Map<String, Object> p : releaseRepo.parts(releaseId)) {
            String pn = ReferenceRepo.asString(p, "part_number");
            GraphNode node = graph.nodes.computeIfAbsent(pn, GraphNode::new);
            if (node.partName == null) {
                node.partName = ReferenceRepo.asString(p, "name");
            }
            node.lifecycleState = node.lifecycleState == null ? "ACTIVE" : node.lifecycleState;
        }
        return graph;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private OriginalRecommendation resolveOriginalRecommendation(AdviceQuery query, UUID releaseId) {
        if (query.workOrderExternalId() == null) {
            return null;
        }
        List<Map<String, Object>> rows;
        if (releaseId != null) {
            rows = substitutions.releaseRecommendations(releaseId).stream()
                    .filter(r -> query.workOrderExternalId()
                            .equals(ReferenceRepo.asString(r, "work_order_external_id")))
                    .toList();
        } else {
            Map<String, Object> wo = evidence.workOrderByExternalId(query.workOrderExternalId())
                    .orElse(null);
            if (wo == null) {
                return null;
            }
            rows = substitutions.recommendationsForWorkOrder(ReferenceRepo.asUuid(wo, "id"));
        }
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return new OriginalRecommendation(
                ReferenceRepo.asString(r, "work_order_external_id"),
                ReferenceRepo.asString(r, "concept_code"),
                ReferenceRepo.asString(r, "part_number"),
                ReferenceRepo.asString(r, "disposition_code"),
                ReferenceRepo.asString(r, "catalog_version_label"),
                ReferenceRepo.asInt(r, "quantity") == null ? 1 : ReferenceRepo.asInt(r, "quantity"),
                ReferenceRepo.asString(r, "recommendation_status"),
                ReferenceRepo.asString(r, "reason"),
                ReferenceRepo.asInstant(r, "recorded_at"),
                ReferenceRepo.asString(r, "recorded_by"));
    }

    private List<StartingPart> startingParts(AdviceQuery query, UUID releaseId, Graph graph,
                                             OriginalRecommendation original) {
        if (original != null && original.partNumber() != null) {
            return List.of(new StartingPart(original.partNumber(),
                    Math.max(1, original.quantity())));
        }
        List<StartingPart> out = new ArrayList<>();
        if (releaseId != null) {
            for (Map<String, Object> d : substitutions.releaseDispositionsForConcept(
                    releaseId, query.conceptCode())) {
                String pn = ReferenceRepo.asString(d, "part_number");
                if (pn != null) {
                    out.add(new StartingPart(pn, Math.max(1, ReferenceRepo.asInt(d, "quantity"))));
                }
            }
        } else if (query.catalogVersionId() != null) {
            for (Map<String, Object> d : catalog.dispositionsForCatalog(query.catalogVersionId())) {
                if (!query.conceptCode().equals(ReferenceRepo.asString(d, "concept_code"))) {
                    continue;
                }
                String pn = ReferenceRepo.asString(d, "part_number");
                if (pn != null) {
                    out.add(new StartingPart(pn, Math.max(1, ReferenceRepo.asInt(d, "quantity"))));
                }
            }
        }
        // de-duplicate while keeping order
        Map<String, StartingPart> uniq = new LinkedHashMap<>();
        out.forEach(s -> uniq.putIfAbsent(s.partNumber(), s));
        return new ArrayList<>(uniq.values());
    }

    private boolean tempConditionRequired(Graph graph, List<StartingPart> starts) {
        Set<String> seen = new LinkedHashSet<>();
        for (StartingPart s : starts) {
            if (collectReachable(graph, s.partNumber(), seen).stream()
                    .flatMap(pn -> graph.edgesByFrom.getOrDefault(pn, List.of()).stream())
                    .anyMatch(e -> e.tempMin != null || e.tempMax != null)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> collectReachable(Graph graph, String from, Set<String> acc) {
        if (!acc.add(from)) {
            return acc;
        }
        for (GraphEdge e : graph.edgesByFrom.getOrDefault(from, List.of())) {
            collectReachable(graph, e.to, acc);
        }
        return acc;
    }

    private int effectiveStock(Graph graph, String partNumber) {
        int qty = graph.stock.getOrDefault(partNumber, 0);
        Object c = graph.contention.get(partNumber);
        if (c instanceof Map<?, ?> m && "CONFIRMED_ALLOCATED".equals(m.get("status"))) {
            return 0;
        }
        return qty;
    }

    private String contentionStatus(Graph graph, String partNumber) {
        Object c = graph.contention.get(partNumber);
        return c instanceof Map<?, ?> m ? Objects.toString(m.get("status"), null) : null;
    }

    private String contentionDemand(Graph graph, String partNumber) {
        Object c = graph.contention.get(partNumber);
        return c instanceof Map<?, ?> m ? Objects.toString(m.get("competing_demand"), null) : null;
    }

    private PathNode nodeOf(Graph graph, String partNumber, int depth, boolean cycle) {
        GraphNode n = graph.nodes.getOrDefault(partNumber, GraphNode.unknown(partNumber));
        return new PathNode(depth, partNumber, n.partName, n.lifecycleState,
                graph.stock.getOrDefault(partNumber, 0), cycle);
    }

    private String buildSummary(OriginalRecommendation original, List<PartAdvice> advice,
                                boolean anyExecutable, List<SubstitutionPath> cycles,
                                List<String> notSubstitutable, List<String> pending) {
        StringBuilder sb = new StringBuilder();
        if (original != null) {
            sb.append("Historical work order ").append(original.workOrderExternalId())
                    .append(" recorded part ").append(original.partNumber())
                    .append(" (").append(original.recommendationStatus()).append("); ");
        }
        long executableParts = advice.stream().filter(a -> a.paths().stream()
                .anyMatch(p -> "EXECUTABLE".equals(p.pathStatus()))).count();
        long pendingParts = advice.stream().filter(a -> a.paths().stream()
                .anyMatch(p -> "PENDING_CONFIRM".equals(p.pathStatus()))).count();
        sb.append(executableParts).append(" part(s) with an executable current alternative, ")
                .append(pendingParts).append(" pending engineering confirmation");
        if (!cycles.isEmpty()) {
            sb.append(", ").append(cycles.size()).append(" substitution cycle conflict(s)");
        }
        if (!notSubstitutable.isEmpty()) {
            sb.append("; not substitutable: ").append(notSubstitutable.size()).append(" boundary condition(s)");
        }
        if (!pending.isEmpty()) {
            sb.append("; ").append(pending.size()).append(" pending engineering condition(s)");
        }
        return sb.toString();
    }

    private UUID requirePartId(String partNumber) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(catalog.partByNumber(partNumber),
                "PART_NOT_FOUND", "Unknown part " + partNumber), "id");
    }

    private UUID requireCatalogId(String label) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(catalog.catalogVersionByLabel(label),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + label), "id");
    }

    private SubstitutionDtos.SubstitutionEdge toEdgeDto(Map<String, Object> r) {
        return new SubstitutionDtos.SubstitutionEdge(
                ReferenceRepo.asUuid(r, "id"),
                catalog.catalogVersionById(ReferenceRepo.asUuid(r, "catalog_version_id"))
                        .map(x -> ReferenceRepo.asString(x, "version_label")).orElse(null),
                ReferenceRepo.asString(r, "from_part_number"),
                ReferenceRepo.asString(r, "to_part_number"),
                ReferenceRepo.asString(r, "substitution_kind"),
                ReferenceRepo.asString(r, "model_code"),
                Sql.decimal(r.get("temp_min_c")), Sql.decimal(r.get("temp_max_c")),
                ReferenceRepo.asString(r, "interface_requirement"),
                Boolean.TRUE.equals(r.get("requires_engineering_confirm")),
                ReferenceRepo.asInstant(r, "valid_from"), ReferenceRepo.asInstant(r, "valid_to"),
                ReferenceRepo.asString(r, "state"), ReferenceRepo.asString(r, "approval_ref"),
                ReferenceRepo.asString(r, "approved_by"), ReferenceRepo.asInstant(r, "approved_at"),
                ReferenceRepo.asString(r, "notes"));
    }

    private SubstitutionDtos.WorkOrderRecommendation toRecommendationDto(Map<String, Object> r) {
        return new SubstitutionDtos.WorkOrderRecommendation(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asString(r, "work_order_external_id"),
                ReferenceRepo.asString(r, "concept_code"),
                ReferenceRepo.asString(r, "part_number"),
                ReferenceRepo.asString(r, "disposition_code"),
                ReferenceRepo.asString(r, "catalog_version_label"),
                ReferenceRepo.asInt(r, "quantity") == null ? 1 : ReferenceRepo.asInt(r, "quantity"),
                ReferenceRepo.asString(r, "recommendation_status"),
                ReferenceRepo.asString(r, "reason"),
                ReferenceRepo.asInstant(r, "recorded_at"),
                ReferenceRepo.asString(r, "recorded_by"),
                ReferenceRepo.asString(r, "approval_ref"));
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private static String pathId(String root, int n) {
        return root + "#path-" + n;
    }

    // ---- internal value types ------------------------------------------

    public record AdviceQuery(
            String conceptCode,
            String conceptName,
            String workOrderExternalId,
            String unitCode,
            String manufacturerCode,
            String modelCode,
            String firmwareVersion,
            BigDecimal ambientTempC,
            String interfaceCode,
            Instant businessAt,
            Instant evaluatedAt,
            UUID catalogVersionId,
            UUID inventorySnapshotId,
            Map<String, Object> alarmAttributes) {
    }

    private record StartingPart(String partNumber, int requiredQty) {
    }

    private record GraphEdge(
            String from, String to, String kind, String modelCode,
            BigDecimal tempMin, BigDecimal tempMax, String interfaceRequirement,
            boolean requiresConfirm, Instant validFrom, Instant validTo,
            String approvalRef, String notes) {
    }

    private static final class GraphNode {
        final String partNumber;
        String partName;
        String lifecycleState;
        String obsoleteReason;

        GraphNode(String partNumber) {
            this.partNumber = partNumber;
        }

        static GraphNode unknown(String partNumber) {
            GraphNode n = new GraphNode(partNumber);
            n.partName = partNumber;
            n.lifecycleState = "UNLISTED";
            return n;
        }
    }

    private static final class Graph {
        String catalogLabel;
        String inventoryLabel;
        final Map<String, List<GraphEdge>> edgesByFrom = new LinkedHashMap<>();
        final Map<String, GraphNode> nodes = new LinkedHashMap<>();
        final Map<String, Integer> stock = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> contention = new LinkedHashMap<>();
    }
}
