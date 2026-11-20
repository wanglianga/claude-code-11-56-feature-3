package com.windfarm.faultref.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.DiagnosisRepo;
import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.repo.MappingRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.ReleaseRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.CatalogDtos.ConceptDisposition;
import com.windfarm.faultref.web.dto.CatalogDtos.DispositionEvaluation;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import com.windfarm.faultref.web.dto.MappingDtos.Candidate;
import com.windfarm.faultref.web.dto.MappingDtos.Resolution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Diagnosis engine.
 *
 * Invariants implemented here:
 *  - an alarm is interpreted at its occurrence instant, never at receipt time;
 *  - one run pins exactly one catalog + inventory context before any alarm is
 *    evaluated, so a catalog publish mid-run cannot mix into its results;
 *  - a preserved mapping conflict yields CONFLICT, never a silent pick;
 *  - mutually exclusive dispositions yield PENDING_ENGINEER;
 *  - every finding carries the mapping chain and evidence ids back to approvals;
 *  - SNAPSHOT mode reads only release_* rows, so replay is independent of later edits.
 */
@Service
public class DiagnosisService {

    private final DiagnosisRepo diagnosis;
    private final EvidenceRepo evidence;
    private final MappingRepo mappings;
    private final ReferenceRepo reference;
    private final CatalogRepo catalog;
    private final CatalogService catalogService;
    private final ReleaseRepo releases;
    private final MappingService mappingService;
    private final SubstitutionService substitutionService;

    @Value("${app.diagnosis.max-batch-size:5000}")
    private int maxBatchSize;

    public DiagnosisService(DiagnosisRepo diagnosis, EvidenceRepo evidence, MappingRepo mappings,
                            ReferenceRepo reference, CatalogRepo catalog, CatalogService catalogService,
                            ReleaseRepo releases, MappingService mappingService,
                            SubstitutionService substitutionService) {
        this.diagnosis = diagnosis;
        this.evidence = evidence;
        this.mappings = mappings;
        this.reference = reference;
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.releases = releases;
        this.mappingService = mappingService;
        this.substitutionService = substitutionService;
    }

    // ==================================================================
    // Run creation
    // ==================================================================

    @Transactional
    public DiagnosisDtos.Run run(DiagnosisDtos.RunRequest req, String user) {
        String mode = req.mode() == null ? "ASSERTION" : req.mode().toUpperCase();
        if (!List.of("ASSERTION", "SNAPSHOT").contains(mode)) {
            throw ApiException.badRequest("MODE_INVALID", "mode must be ASSERTION or SNAPSHOT");
        }
        String basis = req.basis() == null
                ? ("SNAPSHOT".equals(mode) ? "RELEASE" : "LATEST")
                : req.basis().toUpperCase();
        if (!List.of("LATEST", "AS_OF", "RELEASE").contains(basis)) {
            throw ApiException.badRequest("BASIS_INVALID", "basis must be LATEST, AS_OF or RELEASE");
        }
        if ("RELEASE".equals(basis) && !"SNAPSHOT".equals(mode)) {
            throw ApiException.unprocessable("BASIS_MODE_MISMATCH",
                    "basis RELEASE requires mode SNAPSHOT; assertion mode resolves by business instant");
        }
        if ("SNAPSHOT".equals(mode) && !"RELEASE".equals(basis)) {
            throw ApiException.unprocessable("BASIS_MODE_MISMATCH",
                    "mode SNAPSHOT requires basis RELEASE (a frozen release is the only snapshot basis)");
        }
        if ("AS_OF".equals(basis) && req.asOf() == null) {
            throw ApiException.badRequest("AS_OF_REQUIRED", "basis AS_OF requires asOf");
        }

        Instant now = Instant.now();
        Instant replayPoint = req.asOf() == null ? now : req.asOf();
        // AS_OF reproduces the business-instant relations, not a knowledge limit:
        // it must not hide assertions that were approved for that instant. A
        // knowledge cutoff is an explicit, separate control for audits that need
        // to replay "what did we know when".
        Instant knowledgeCutoff = req.knowledgeCutoff();

        // Pin context once, before evaluating any alarm.
        UUID releaseId = null;
        String releaseLabel = req.releaseLabel();
        CatalogService.PinnedContext pin;
        if ("SNAPSHOT".equals(mode)) {
            Map<String, Object> rel = releaseLabel != null
                    ? ReferenceRepo.require(releases.releaseByLabel(releaseLabel), "RELEASE_NOT_FOUND",
                            "Unknown release " + releaseLabel)
                    : ReferenceRepo.require(releases.publishedReleaseAt(replayPoint), "RELEASE_NOT_FOUND",
                            "No PUBLISHED release effective at " + replayPoint);
            if (!"PUBLISHED".equals(ReferenceRepo.asString(rel, "status"))) {
                throw ApiException.conflict("RELEASE_NOT_PUBLISHED",
                        "Release " + ReferenceRepo.asString(rel, "version_label") + " is not PUBLISHED");
            }
            releaseId = ReferenceRepo.asUuid(rel, "id");
            releaseLabel = ReferenceRepo.asString(rel, "version_label");
            pin = new CatalogService.PinnedContext(null, null,
                    ReferenceRepo.asString(rel, "catalog_version_label"),
                    ReferenceRepo.asString(rel, "inventory_snapshot_label"));
        } else if (req.catalogVersionLabel() != null || req.inventorySnapshotLabel() != null) {
            pin = catalogService.pinByLabels(req.catalogVersionLabel(), req.inventorySnapshotLabel());
        } else {
            pin = catalogService.pin(replayPoint, knowledgeCutoff);
        }

        List<Map<String, Object>> alarms = selectAlarms(req);
        if (alarms.size() > maxBatchSize) {
            throw ApiException.unprocessable("BATCH_TOO_LARGE",
                    "Run would evaluate " + alarms.size() + " alarms, exceeding limit " + maxBatchSize);
        }

        Map<String, Object> requestJson = new LinkedHashMap<>();
        requestJson.put("mode", mode);
        requestJson.put("basis", basis);
        requestJson.put("asOf", req.asOf());
        requestJson.put("releaseLabel", releaseLabel);
        requestJson.put("alarmIds", req.alarmIds());
        requestJson.put("from", req.from());
        requestJson.put("to", req.to());
        requestJson.put("unitCode", req.unitCode());
        requestJson.put("offlineOnly", req.offlineOnly());
        requestJson.put("catalogVersionLabel", req.catalogVersionLabel());
        requestJson.put("inventorySnapshotLabel", req.inventorySnapshotLabel());
        requestJson.put("knowledgeCutoff", knowledgeCutoff);

        UUID runId = diagnosis.insertRun(new MapSqlParameterSource()
                .addValue("mode", mode)
                .addValue("basis", basis)
                .addValue("releaseId", releaseId)
                .addValue("asOf", req.asOf() == null ? null : Sql.odt(req.asOf()))
                .addValue("catalogId", pin.catalogVersionId())
                .addValue("inventoryId", pin.inventorySnapshotId())
                .addValue("evaluationTime", Sql.odt(replayPoint))
                .addValue("knowledgeCutoff", knowledgeCutoff == null ? null : Sql.odt(knowledgeCutoff))
                .addValue("evaluatedAt", Sql.odt(now))
                .addValue("status", "RUNNING")
                .addValue("createdBy", user)
                .addValue("request", Sql.jsonb(requestJson))
                .addValue("summary", Sql.jsonb(Map.of())));

        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            for (Map<String, Object> alarm : alarms) {
                evaluateAndStore(runId, mode, releaseId, pin, alarm, knowledgeCutoff, counts);
            }
        } catch (RuntimeException e) {
            diagnosis.failRun(runId, e.getMessage());
            throw e;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("alarms", alarms.size());
        summary.put("statusCounts", counts);
        summary.put("mode", mode);
        summary.put("basis", basis);
        summary.put("pinnedCatalogVersion", pin.catalogVersionLabel());
        summary.put("pinnedInventorySnapshot", pin.inventorySnapshotLabel());
        diagnosis.completeRun(runId, summary);
        return getRun(runId);
    }

    private List<Map<String, Object>> selectAlarms(DiagnosisDtos.RunRequest req) {
        if (req.alarmIds() != null && !req.alarmIds().isEmpty()) {
            List<Map<String, Object>> rows = evidence.alarmsByIds(req.alarmIds());
            if (rows.size() != new LinkedHashSet<>(req.alarmIds()).size()) {
                throw ApiException.notFound("ALARM_NOT_FOUND", "One or more alarmIds do not exist");
            }
            return rows;
        }
        return evidence.alarmsOccurringBetween(req.from(), req.to(),
                Boolean.TRUE.equals(req.offlineOnly()));
    }

    // ==================================================================
    // Per-alarm evaluation
    // ==================================================================

    private void evaluateAndStore(UUID runId, String mode, UUID releaseId, CatalogService.PinnedContext pin,
                                  Map<String, Object> alarm, Instant cutoff, Map<String, Long> counts) {
        UUID alarmId = ReferenceRepo.asUuid(alarm, "id");
        MappingService.AlarmContext ctx = contextFor(alarm);
        UUID sourceCodeId = ReferenceRepo.asUuid(alarm, "source_code_id");
        UUID termId = ReferenceRepo.asUuid(alarm, "term_attachment_id");
        Instant occurredAt = ReferenceRepo.asInstant(alarm, "occurred_at");

        Resolution resolution = "SNAPSHOT".equals(mode)
                ? resolveFromRelease(releaseId, alarm, ctx)
                : mappingService.resolve(sourceCodeId, termId, ctx, occurredAt, cutoff);

        List<Map<String, Object>> relatedWorkOrders = relatedWorkOrders(alarm, ctx);

        if ("NO_MAPPING".equals(resolution.outcome())) {
            storeResult(runId, alarmId, "NO_MAPPING", null, false, resolution.reason(),
                    List.of(), counts);
            return;
        }

        if ("CONFLICT".equals(resolution.outcome())) {
            // Preserve the conflict: record every competing concept but assert no single conclusion.
            List<FindingInput> findings = new ArrayList<>();
            Set<UUID> seen = new LinkedHashSet<>();
            for (Candidate c : resolution.candidates()) {
                if (c.specificity() != resolution.candidates().stream()
                        .mapToInt(Candidate::specificity).max().orElse(0)) {
                    continue;
                }
                if (!seen.add(c.conceptId())) {
                    continue;
                }
                ConceptDisposition disp = evaluateDisposition(c.conceptId(), pin,
                        occurredAt, mode, releaseId);
                findings.add(new FindingInput(c, disp, resolution, "CONFLICT"));
            }
            storeResult(runId, alarmId, "CONFLICT", null, false, resolution.reason(), findings, counts);
            return;
        }

        // RESOLVED or RESOLVED_BY_ADJUDICATION
        Candidate selected = resolution.selected();
        boolean adjudicated = "RESOLVED_BY_ADJUDICATION".equals(resolution.outcome());
        ConceptDisposition disp = evaluateDisposition(selected.conceptId(), pin,
                occurredAt, mode, releaseId);

        String status = switch (disp.status()) {
            case "PENDING_ENGINEER" -> "PENDING_ENGINEER";
            case "NO_DISPOSITION" -> "NO_DISPOSITION";
            default -> "RESOLVED";
        };
        String explanation = resolution.reason() + ". " + disp.reason();
        List<FindingInput> findings = List.of(new FindingInput(selected, disp, resolution, status));
        storeResult(runId, alarmId, status, selected.conceptId(), adjudicated, explanation,
                findings, counts);
    }

    private void storeResult(UUID runId, UUID alarmId, String status, UUID primaryConcept,
                             boolean adjudicated, String explanation, List<FindingInput> findings,
                             Map<String, Long> counts) {
        UUID resultId = diagnosis.insertResult(runId, alarmId, status, primaryConcept, adjudicated,
                explanation, findings.size());
        for (FindingInput f : findings) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("outcome", f.resolution().outcome());
            chain.put("reason", f.resolution().reason());
            chain.put("specificity", f.candidate().specificity());
            chain.put("suppressedBySpecificity", f.resolution().suppressedBySpecificity());
            chain.put("selectedAssertionId", f.candidate().assertionId());
            chain.put("selectedReleaseEntryId", f.candidate().releaseEntryId());
            chain.put("definitionApprovalRef", f.candidate().definitionApprovalRef());
            chain.put("definitionVersion", f.candidate().definitionVersion());
            chain.put("mappingApprovalRef", f.candidate().approvalRef());
            chain.put("adjudicationId", f.resolution().adjudicationId());
            chain.put("adjudicationRef", f.resolution().adjudicationRef());
            chain.put("adjudicationDecision", f.resolution().adjudicationDecision());
            chain.put("scope", Map.of(
                    "manufacturerCode", nullSafe(f.candidate().manufacturerCode()),
                    "modelCode", nullSafe(f.candidate().modelCode()),
                    "firmwareFrom", nullSafe(f.candidate().firmwareFrom()),
                    "firmwareTo", nullSafe(f.candidate().firmwareTo())));
            chain.put("candidates", f.resolution().candidates().stream().map(this::candidateMap).toList());

            Map<String, Object> dispositionJson = new LinkedHashMap<>();
            dispositionJson.put("status", f.disposition().status());
            dispositionJson.put("reason", f.disposition().reason());
            dispositionJson.put("options", f.disposition().options());
            dispositionJson.put("unavailable", f.disposition().unavailable());

            List<UUID> assertionIds = new ArrayList<>();
            if (f.candidate().assertionId() != null) {
                assertionIds.add(f.candidate().assertionId());
            }
            // Trace this finding to the exact frozen entry it came from; fall back to
            // the resolution-level list only when a single entry cannot be identified.
            List<UUID> releaseEntryIds = new ArrayList<>();
            if (f.candidate().releaseEntryId() != null) {
                releaseEntryIds.add(f.candidate().releaseEntryId());
            } else if (f.resolution().releaseEntryIds() != null
                    && f.resolution().releaseEntryIds().size() == 1) {
                releaseEntryIds.addAll(f.resolution().releaseEntryIds());
            }

            diagnosis.insertFinding(new MapSqlParameterSource()
                    .addValue("resultId", resultId)
                    .addValue("conceptId", f.candidate().conceptId())
                    .addValue("conceptCode", f.candidate().conceptCode())
                    .addValue("conceptName", f.candidate().conceptName())
                    .addValue("definitionVersion", f.candidate().definitionVersion())
                    .addValue("definitionText", f.candidate().definition())
                    .addValue("confidence", f.candidate().confidence())
                    .addValue("dispositionStatus", f.disposition().status())
                    .addValue("disposition", Sql.jsonb(dispositionJson))
                    .addValue("mappingChain", Sql.jsonb(chain))
                    .addValue("assertionIds", Sql.uuidArray(assertionIds))
                    .addValue("releaseEntryIds", Sql.uuidArray(releaseEntryIds)));
        }
        counts.merge(status, 1L, Long::sum);
    }

    private Map<String, Object> candidateMap(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assertionId", c.assertionId());
        m.put("conceptCode", c.conceptCode());
        m.put("conceptName", c.conceptName());
        m.put("specificity", c.specificity());
        m.put("modelCode", c.modelCode());
        m.put("manufacturerCode", c.manufacturerCode());
        m.put("firmwareFrom", c.firmwareFrom());
        m.put("firmwareTo", c.firmwareTo());
        m.put("validFrom", c.validFrom());
        m.put("validTo", c.validTo());
        m.put("approvalRef", c.approvalRef());
        return m;
    }

    private String nullSafe(String v) {
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------------
    // Snapshot-mode resolution over release_* rows
    // ------------------------------------------------------------------

    private Resolution resolveFromRelease(UUID releaseId, Map<String, Object> alarm,
                                          MappingService.AlarmContext ctx) {
        String systemCode = ReferenceRepo.asString(alarm, "source_system_code");
        String rawCode = ReferenceRepo.asString(alarm, "raw_code");
        String vendor = null;
        String termText = null;
        boolean isTerm = "VENDOR_TERM".equals(ReferenceRepo.asString(alarm, "source_system_kind"));

        List<Map<String, Object>> rows;
        if (isTerm) {
            Map<String, Object> termRow = reference.termById(ReferenceRepo.asUuid(alarm, "term_attachment_id"))
                    .orElseThrow(() -> ApiException.conflict("RELEASE_TERM_MISSING",
                            "Alarm references a term attachment absent from the live term table"));
            vendor = ReferenceRepo.asString(termRow, "vendor");
            termText = ReferenceRepo.asString(termRow, "term_text");
            rows = releases.termEntries(releaseId, vendor, termText);
        } else {
            rows = releases.codeEntries(releaseId, systemCode, rawCode);
        }

        if (rows.isEmpty()) {
            return new Resolution("NO_MAPPING",
                    "Release " + releaseId + " contains no entry for " + systemCode + "/" + rawCode
                            + " — the source code was not part of this frozen release",
                    List.of(), null, null, null, null, 0);
        }

        List<Candidate> kept = new ArrayList<>();
        int excludedByScope = 0;
        int excludedByFirmware = 0;
        int excludedByQualifier = 0;
        List<UUID> entryIds = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID rowModel = scopeMatch(ctx, r);
            if (rowModel == null) {
                excludedByScope++;
                continue;
            }
            Integer fFrom = ReferenceRepo.asInt(r, "firmware_from_seq");
            Integer fTo = ReferenceRepo.asInt(r, "firmware_to_seq");
            if ((fFrom != null || fTo != null) && !firmwareMatch(fFrom, fTo, ctx.firmwareSequence())) {
                excludedByFirmware++;
                continue;
            }
            Map<String, Object> qualifiers = Sql.readMap(r.get("qualifiers"));
            if (!qualifiersMatch(qualifiers, ctx.attributes())) {
                excludedByQualifier++;
                continue;
            }
            UUID entryId = ReferenceRepo.asUuid(r, "id");
            entryIds.add(entryId);
            kept.add(new Candidate(
                    ReferenceRepo.asUuid(r, "assertion_id"),
                    conceptIdFromRelease(releaseId, ReferenceRepo.asString(r, "concept_code")),
                    ReferenceRepo.asString(r, "concept_code"),
                    ReferenceRepo.asString(r, "concept_name"),
                    ReferenceRepo.asInt(r, "definition_version"),
                    ReferenceRepo.asString(r, "definition_text"),
                    ReferenceRepo.asString(r, "approval_ref"),
                    specificity(rowModel, ReferenceRepo.asString(r, "model_code"),
                            ReferenceRepo.asString(r, "manufacturer_code"), fFrom, fTo, qualifiers),
                    Sql.decimal(r.get("confidence")),
                    ReferenceRepo.asString(r, "model_code"),
                    ReferenceRepo.asString(r, "manufacturer_code"),
                    ReferenceRepo.asString(r, "firmware_from"),
                    ReferenceRepo.asString(r, "firmware_to"),
                    ReferenceRepo.asInstant(r, "valid_from"),
                    ReferenceRepo.asInstant(r, "valid_to"),
                    ReferenceRepo.asString(r, "approval_ref"),
                    entryId));
        }

        if (kept.isEmpty()) {
            return new Resolution("NO_MAPPING",
                    "All " + rows.size() + " frozen entries for " + systemCode + "/" + rawCode
                            + " were excluded (scope=" + excludedByScope + ", firmware=" + excludedByFirmware
                            + ", qualifiers=" + excludedByQualifier + ")",
                    List.of(), null, null, null, null, 0);
        }

        int best = kept.stream().mapToInt(Candidate::specificity).max().orElse(0);
        List<Candidate> top = kept.stream().filter(c -> c.specificity() == best).toList();
        int suppressed = (int) kept.stream().filter(c -> c.specificity() < best).count();
        Set<UUID> concepts = new LinkedHashSet<>();
        top.forEach(c -> concepts.add(c.conceptId()));

        if (concepts.size() == 1) {
            Candidate selected = top.get(0);
            return new Resolution("RESOLVED",
                    "Frozen release resolved a single concept " + selected.conceptCode()
                            + " at specificity " + best,
                    kept, selected, null, null, null, suppressed, entryIds);
        }

        Map<String, Object> adj = pickReleaseAdjudication(releaseId, isTerm, systemCode, rawCode,
                vendor, termText, ctx, top);
        if (adj != null) {
            String chosenCode = ReferenceRepo.asString(adj, "chosen_concept_code");
            Candidate chosen = top.stream().filter(c -> c.conceptCode().equals(chosenCode))
                    .findFirst().orElse(null);
            if (chosen != null) {
                return new Resolution("RESOLVED_BY_ADJUDICATION",
                        "Frozen release adjudication resolved a preserved conflict to " + chosen.conceptCode(),
                        kept, chosen, ReferenceRepo.asUuid(adj, "id"),
                        ReferenceRepo.asString(adj, "approval_ref"),
                        ReferenceRepo.asString(adj, "decision"), suppressed, entryIds);
            }
        }

        return new Resolution("CONFLICT",
                "Frozen release preserves a conflict: " + concepts.size()
                        + " distinct concepts at specificity " + best,
                kept, null, null, null, null, suppressed, entryIds);
    }

    /**
     * Findings always anchor to the stable live concept identity; the frozen
     * release contributes the definition text/version that was in force.
     */
    private UUID conceptIdFromRelease(UUID releaseId, String conceptCode) {
        return reference.conceptByCode(conceptCode)
                .map(r -> ReferenceRepo.asUuid(r, "id"))
                .orElseThrow(() -> ApiException.conflict("RELEASE_CONCEPT_MISSING",
                        "Release concept " + conceptCode + " has no stable concept identity"));
    }

    private UUID scopeMatch(MappingService.AlarmContext ctx, Map<String, Object> row) {
        String modelCode = ReferenceRepo.asString(row, "model_code");
        String manufacturerCode = ReferenceRepo.asString(row, "manufacturer_code");
        if (modelCode != null) {
            Map<String, Object> model = ctx.modelId() == null ? null
                    : reference.modelById(ctx.modelId()).orElse(null);
            if (model == null || !modelCode.equals(ReferenceRepo.asString(model, "code"))) {
                return null;
            }
        } else if (manufacturerCode != null) {
            Map<String, Object> man = ctx.manufacturerId() == null ? null
                    : reference.manufacturerById(ctx.manufacturerId()).orElse(null);
            if (man == null || !manufacturerCode.equals(ReferenceRepo.asString(man, "code"))) {
                return null;
            }
        }
        return ctx.modelId();
    }

    private Map<String, Object> pickReleaseAdjudication(UUID releaseId, boolean isTerm, String systemCode,
                                                        String rawCode, String vendor, String termText,
                                                        MappingService.AlarmContext ctx, List<Candidate> top) {
        Set<String> codes = top.stream().map(Candidate::conceptCode).collect(Collectors.toCollection(LinkedHashSet::new));
        for (Map<String, Object> adj : releases.adjudications(releaseId)) {
            String modelCode = ReferenceRepo.asString(adj, "model_code");
            if (modelCode != null) {
                Map<String, Object> model = ctx.modelId() == null ? null
                        : reference.modelById(ctx.modelId()).orElse(null);
                if (model == null || !modelCode.equals(ReferenceRepo.asString(model, "code"))) {
                    continue;
                }
            }
            boolean matches;
            if (isTerm) {
                matches = vendor.equals(ReferenceRepo.asString(adj, "vendor"))
                        && termText.equals(ReferenceRepo.asString(adj, "term_text"));
            } else {
                matches = systemCode.equals(ReferenceRepo.asString(adj, "source_system_code"))
                        && rawCode.equals(ReferenceRepo.asString(adj, "source_code"));
            }
            if (!matches) {
                continue;
            }
            String a = ReferenceRepo.asString(adj, "concept_a_code");
            String b = ReferenceRepo.asString(adj, "concept_b_code");
            if (codes.contains(a) && codes.contains(b)) {
                return adj;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Disposition evaluation (pinned context)
    // ------------------------------------------------------------------

    private ConceptDisposition evaluateDisposition(UUID conceptId, CatalogService.PinnedContext pin,
                                                   Instant at, String mode, UUID releaseId) {
        if ("SNAPSHOT".equals(mode)) {
            return evaluateReleaseDisposition(releaseId, conceptId, at);
        }
        if (pin.catalogVersionId() == null) {
            return new ConceptDisposition("NO_DISPOSITION",
                    "No PUBLISHED catalog version is effective at the pinned evaluation context",
                    List.of(), List.of(), null);
        }
        return catalogService.evaluate(conceptId, pin.catalogVersionId(), pin.inventorySnapshotId(), at);
    }

    private ConceptDisposition evaluateReleaseDisposition(UUID releaseId, UUID conceptId, Instant at) {
        String conceptCode = reference.conceptById(conceptId)
                .map(r -> ReferenceRepo.asString(r, "code")).orElse(null);
        if (conceptCode == null) {
            return new ConceptDisposition("NO_DISPOSITION",
                    "Concept is not resolvable in the live concept table", List.of(), List.of(), null);
        }
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (Map<String, Object> row : releases.inventory(releaseId)) {
            stock.merge(ReferenceRepo.asString(row, "part_number"),
                    ReferenceRepo.asInt(row, "qty_available"), Integer::sum);
        }
        List<Map<String, Object>> dispRows = releases.dispositions(releaseId).stream()
                .filter(r -> conceptCode.equals(ReferenceRepo.asString(r, "concept_code")))
                .toList();
        if (dispRows.isEmpty()) {
            return new ConceptDisposition("NO_DISPOSITION",
                    "The frozen release defines no disposition for " + conceptCode, List.of(), List.of(), null);
        }
        List<DispositionEvaluation> available = new ArrayList<>();
        List<DispositionEvaluation> unavailable = new ArrayList<>();
        for (Map<String, Object> r : dispRows) {
            Instant vf = ReferenceRepo.asInstant(r, "valid_from");
            Instant vt = ReferenceRepo.asInstant(r, "valid_to");
            boolean timeValid = (vf == null || !vf.isAfter(at)) && (vt == null || vt.isAfter(at));
            String code = ReferenceRepo.asString(r, "code");
            String action = ReferenceRepo.asString(r, "action");
            String desc = ReferenceRepo.asString(r, "description");
            String partNumber = ReferenceRepo.asString(r, "part_number");
            int quantity = ReferenceRepo.asInt(r, "quantity");
            String group = ReferenceRepo.asString(r, "exclusive_group");
            boolean requiresStock = Boolean.TRUE.equals(r.get("requires_stock"));
            int minStock = ReferenceRepo.asInt(r, "min_stock");
            int avail = partNumber == null ? 0 : stock.getOrDefault(partNumber, 0);
            DispositionEvaluation eval;
            if (!timeValid) {
                eval = new DispositionEvaluation(code, action, desc, partNumber, quantity, group,
                        requiresStock, minStock, avail, "UNAVAILABLE", "Disposition not valid at diagnosis instant");
                unavailable.add(eval);
            } else if (requiresStock && avail < Math.max(minStock, quantity)) {
                eval = new DispositionEvaluation(code, action, desc, partNumber, quantity, group,
                        true, minStock, avail, "UNAVAILABLE",
                        "Insufficient frozen stock: available " + avail + ", needs "
                                + Math.max(minStock, quantity));
                unavailable.add(eval);
            } else {
                eval = new DispositionEvaluation(code, action, desc, partNumber, quantity, group,
                        requiresStock, minStock, avail, "AVAILABLE", "Satisfiable under frozen release");
                available.add(eval);
            }
        }

        Map<String, List<DispositionEvaluation>> byGroup = new LinkedHashMap<>();
        List<DispositionEvaluation> ungrouped = new ArrayList<>();
        for (DispositionEvaluation e : available) {
            if (e.exclusiveGroup() == null || e.exclusiveGroup().isBlank()) {
                ungrouped.add(e);
            } else {
                byGroup.computeIfAbsent(e.exclusiveGroup(), k -> new ArrayList<>()).add(e);
            }
        }
        List<String> conflicted = byGroup.entrySet().stream()
                .filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey).toList();
        if (!conflicted.isEmpty()) {
            return new ConceptDisposition("PENDING_ENGINEER",
                    "Frozen release has mutually exclusive dispositions simultaneously satisfiable in "
                            + conflicted + "; engineer must adjudicate",
                    available, unavailable, conflicted.get(0));
        }
        List<DispositionEvaluation> confirmed = new ArrayList<>(ungrouped);
        byGroup.values().forEach(v -> confirmed.add(v.get(0)));
        if (confirmed.isEmpty()) {
            return new ConceptDisposition("NO_DISPOSITION",
                    "All frozen dispositions are unavailable under the release stock snapshot",
                    available, unavailable, null);
        }
        return new ConceptDisposition("CONFIRMED",
                confirmed.size() + " satisfiable disposition(s) in the frozen release",
                available, unavailable, null);
    }

    // ------------------------------------------------------------------
    // Context / helpers
    // ------------------------------------------------------------------

    private MappingService.AlarmContext contextFor(Map<String, Object> alarm) {
        Map<String, Object> unit = ReferenceRepo.require(
                reference.unitById(ReferenceRepo.asUuid(alarm, "unit_id")),
                "UNIT_NOT_FOUND", "Alarm references a missing unit");
        UUID modelId = ReferenceRepo.asUuid(unit, "model_id");
        UUID manufacturerId = ReferenceRepo.asUuid(unit, "manufacturer_id");
        Integer sequence = null;
        String version = null;
        UUID firmwareId = ReferenceRepo.asUuid(alarm, "firmware_version_id");
        if (firmwareId != null) {
            Map<String, Object> fw = reference.firmwareById(firmwareId).orElse(null);
            if (fw != null) {
                sequence = ReferenceRepo.asInt(fw, "sequence");
                version = ReferenceRepo.asString(fw, "version");
            }
        }
        return new MappingService.AlarmContext(modelId, manufacturerId, sequence, version,
                Sql.readMap(alarm.get("attributes")));
    }

    private List<Map<String, Object>> relatedWorkOrders(Map<String, Object> alarm,
                                                        MappingService.AlarmContext ctx) {
        UUID unitId = ReferenceRepo.asUuid(alarm, "unit_id");
        Instant occurred = ReferenceRepo.asInstant(alarm, "occurred_at");
        if (unitId == null || occurred == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = evidence.workOrdersForUnitNear(unitId,
                occurred.minus(Duration.ofDays(7)), occurred.plus(Duration.ofDays(3)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("externalId", ReferenceRepo.asString(r, "external_id"));
            m.put("vendor", ReferenceRepo.asString(r, "vendor"));
            m.put("reportedTerm", ReferenceRepo.asString(r, "reported_term"));
            m.put("openedAt", ReferenceRepo.asInstant(r, "opened_at"));
            m.put("closedAt", ReferenceRepo.asInstant(r, "closed_at"));
            m.put("status", ReferenceRepo.asString(r, "status"));
            m.put("termAttachmentId", ReferenceRepo.asString(r, "term_attachment_id"));
            out.add(m);
        }
        return out;
    }

    private boolean firmwareMatch(Integer from, Integer to, Integer alarmSeq) {
        if (alarmSeq == null) {
            return false;
        }
        if (from != null && alarmSeq < from) {
            return false;
        }
        return to == null || alarmSeq <= to;
    }

    private boolean qualifiersMatch(Map<String, Object> qualifiers, Map<String, Object> attributes) {
        if (qualifiers == null || qualifiers.isEmpty()) {
            return true;
        }
        if (attributes == null) {
            return false;
        }
        for (Map.Entry<String, Object> e : qualifiers.entrySet()) {
            Object actual = attributes.get(e.getKey());
            if (actual == null) {
                return false;
            }
            String a = actual instanceof Number n
                    ? new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()
                    : String.valueOf(actual).trim();
            String b = e.getValue() instanceof Number n
                    ? new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()
                    : String.valueOf(e.getValue()).trim();
            if (!a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    private int specificity(UUID model, String modelCode, String manufacturerCode,
                            Integer from, Integer to, Map<String, Object> qualifiers) {
        int score = 0;
        if (modelCode != null) {
            score += 1000;
        } else if (manufacturerCode != null) {
            score += 500;
        }
        if (from != null || to != null) {
            score += 100;
        }
        score += Math.min(qualifiers == null ? 0 : qualifiers.size(), 20) * 5;
        return score;
    }

    private record FindingInput(Candidate candidate, ConceptDisposition disposition,
                                Resolution resolution, String resultStatus) {
    }

    // ==================================================================
    // Read APIs
    // ==================================================================

    public DiagnosisDtos.Run getRun(UUID runId) {
        Map<String, Object> run = ReferenceRepo.require(diagnosis.runById(runId), "RUN_NOT_FOUND",
                "Unknown diagnosis run " + runId);
        List<Map<String, Object>> resultRows = diagnosis.resultsForRun(runId);
        List<DiagnosisDtos.Result> results = new ArrayList<>();
        for (Map<String, Object> rr : resultRows) {
            UUID resultId = ReferenceRepo.asUuid(rr, "id");
            List<DiagnosisDtos.Finding> findings = diagnosis.findingsForResult(resultId).stream()
                    .map(f -> new DiagnosisDtos.Finding(
                            ReferenceRepo.asUuid(f, "id"),
                            ReferenceRepo.asUuid(f, "concept_id"),
                            ReferenceRepo.asString(f, "concept_code"),
                            ReferenceRepo.asString(f, "concept_name"),
                            ReferenceRepo.asString(f, "category"),
                            ReferenceRepo.asInt(f, "definition_version"),
                            ReferenceRepo.asString(f, "definition_text"),
                            Sql.decimal(f.get("confidence")),
                            ReferenceRepo.asString(f, "disposition_status"),
                            Sql.readMap(f.get("disposition")),
                            Sql.readMap(f.get("mapping_chain")),
                            uuidList(f.get("evidence_assertion_ids")),
                            uuidList(f.get("evidence_release_entry_ids"))))
                    .toList();
            Map<String, Object> alarm = evidence.alarmById(ReferenceRepo.asUuid(rr, "alarm_id")).orElse(Map.of());
            results.add(new DiagnosisDtos.Result(
                    resultId,
                    ReferenceRepo.asUuid(rr, "alarm_id"),
                    ReferenceRepo.asString(rr, "external_id"),
                    ReferenceRepo.asString(rr, "unit_code"),
                    ReferenceRepo.asString(rr, "source_system_code"),
                    ReferenceRepo.asString(rr, "source_system_kind"),
                    ReferenceRepo.asString(rr, "raw_code"),
                    ReferenceRepo.asInstant(rr, "occurred_at"),
                    ReferenceRepo.asInstant(rr, "received_at"),
                    ReferenceRepo.asString(rr, "ingest_type"),
                    ReferenceRepo.asString(rr, "status"),
                    ReferenceRepo.asString(rr, "explanation"),
                    Boolean.TRUE.equals(rr.get("adjudicated")),
                    ReferenceRepo.asString(rr, "primary_concept_code"),
                    ReferenceRepo.asString(rr, "primary_concept_name"),
                    findings,
                    relatedWorkOrders(alarm, null),
                    List.of()));
        }
        String releaseLabel = ReferenceRepo.asString(run, "release_label");
        if (releaseLabel == null && ReferenceRepo.asUuid(run, "release_id") != null) {
            releaseLabel = releases.releaseById(ReferenceRepo.asUuid(run, "release_id"))
                    .map(r -> ReferenceRepo.asString(r, "version_label")).orElse(null);
        }
        String catalogLabel = ReferenceRepo.asUuid(run, "pinned_catalog_version_id") == null ? null
                : catalog.catalogVersionById(ReferenceRepo.asUuid(run, "pinned_catalog_version_id"))
                .map(r -> ReferenceRepo.asString(r, "version_label")).orElse(null);
        String invLabel = ReferenceRepo.asUuid(run, "pinned_inventory_snapshot_id") == null ? null
                : catalog.inventorySnapshotById(ReferenceRepo.asUuid(run, "pinned_inventory_snapshot_id"))
                .map(r -> ReferenceRepo.asString(r, "label")).orElse(null);
        if ("SNAPSHOT".equals(ReferenceRepo.asString(run, "mode"))) {
            Map<String, Object> rel = releases.releaseById(ReferenceRepo.asUuid(run, "release_id")).orElse(Map.of());
            catalogLabel = ReferenceRepo.asString(rel, "catalog_version_label");
            invLabel = ReferenceRepo.asString(rel, "inventory_snapshot_label");
        }
        UUID snapshotReleaseId = "SNAPSHOT".equals(ReferenceRepo.asString(run, "mode"))
                ? ReferenceRepo.asUuid(run, "release_id") : null;
        for (DiagnosisDtos.Result r : results) {
            substitutionService.attachHistoricalRecommendations(r.relatedWorkOrders(), snapshotReleaseId);
        }

        return new DiagnosisDtos.Run(
                runId,
                ReferenceRepo.asString(run, "mode"),
                ReferenceRepo.asString(run, "basis"),
                releaseLabel,
                ReferenceRepo.asUuid(run, "release_id"),
                ReferenceRepo.asInstant(run, "as_of"),
                ReferenceRepo.asInstant(run, "evaluation_time"),
                ReferenceRepo.asInstant(run, "knowledge_cutoff"),
                catalogLabel,
                invLabel,
                ReferenceRepo.asString(run, "status"),
                ReferenceRepo.asString(run, "created_by"),
                ReferenceRepo.asInstant(run, "created_at"),
                ReferenceRepo.asInstant(run, "finished_at"),
                Sql.readMap(run.get("summary")),
                results);
    }

    private List<UUID> uuidList(Object raw) {
        if (raw instanceof java.sql.Array arr) {
            try {
                Object a = arr.getArray();
                List<UUID> out = new ArrayList<>();
                for (Object o : (Object[]) a) {
                    if (o != null) {
                        out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
                    }
                }
                return out;
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
    }
}
