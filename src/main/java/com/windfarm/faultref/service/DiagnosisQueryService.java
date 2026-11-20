package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.DiagnosisRepo;
import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.repo.MappingRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.ReleaseRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-side analysis: listings, statistics, run diff and end-to-end explainability. */
@Service
public class DiagnosisQueryService {

    private final DiagnosisRepo diagnosis;
    private final EvidenceRepo evidence;
    private final MappingRepo mappings;
    private final ReferenceRepo reference;
    private final CatalogRepo catalog;
    private final ReleaseRepo releases;
    private final DiagnosisService diagnosisService;
    private final SubstitutionService substitutionService;

    public DiagnosisQueryService(DiagnosisRepo diagnosis, EvidenceRepo evidence, MappingRepo mappings,
                                 ReferenceRepo reference, CatalogRepo catalog, ReleaseRepo releases,
                                 DiagnosisService diagnosisService,
                                 SubstitutionService substitutionService) {
        this.diagnosis = diagnosis;
        this.evidence = evidence;
        this.mappings = mappings;
        this.reference = reference;
        this.catalog = catalog;
        this.releases = releases;
        this.diagnosisService = diagnosisService;
        this.substitutionService = substitutionService;
    }

    public List<DiagnosisDtos.RunSummary> listRuns(int limit) {
        return diagnosis.listRuns(limit).stream()
                .map(r -> new DiagnosisDtos.RunSummary(
                        ReferenceRepo.asUuid(r, "id"),
                        ReferenceRepo.asString(r, "mode"),
                        ReferenceRepo.asString(r, "basis"),
                        ReferenceRepo.asString(r, "release_label"),
                        ReferenceRepo.asUuid(r, "pinned_catalog_version_id") == null ? null
                                : labelOfCatalog(ReferenceRepo.asUuid(r, "pinned_catalog_version_id")),
                        ReferenceRepo.asUuid(r, "pinned_inventory_snapshot_id") == null ? null
                                : labelOfInventory(ReferenceRepo.asUuid(r, "pinned_inventory_snapshot_id")),
                        ReferenceRepo.asString(r, "status"),
                        ReferenceRepo.asInstant(r, "created_at"),
                        Sql.readMap(r.get("summary"))))
                .toList();
    }

    public DiagnosisDtos.Statistics statistics(UUID runId) {
        Map<String, Object> run = requireRun(runId);
        Map<String, Object> summary = Sql.readMap(run.get("summary"));
        Map<String, Object> statusCounts = new LinkedHashMap<>();
        diagnosis.statusCounts(runId).forEach((k, v) ->
                statusCounts.put(k, v instanceof Number n ? n.longValue() : v));
        List<DiagnosisDtos.ConceptStat> concepts = diagnosis.conceptCounts(runId).stream()
                .map(r -> new DiagnosisDtos.ConceptStat(ReferenceRepo.asString(r, "code"),
                        ReferenceRepo.asString(r, "name"), ReferenceRepo.asString(r, "category"),
                        ((Number) r.get("findings")).longValue()))
                .toList();
        // Boundaries: how often conclusions were withheld and why.
        long total = statusCounts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
        Map<String, Object> boundaries = new LinkedHashMap<>();
        boundaries.put("evaluatedAlarms", total);
        boundaries.put("resolved", statusCounts.getOrDefault("RESOLVED", 0L));
        boundaries.put("withheldConflict", statusCounts.getOrDefault("CONFLICT", 0L));
        boundaries.put("withheldPendingEngineer", statusCounts.getOrDefault("PENDING_ENGINEER", 0L));
        boundaries.put("noMapping", statusCounts.getOrDefault("NO_MAPPING", 0L));
        boundaries.put("noDisposition", statusCounts.getOrDefault("NO_DISPOSITION", 0L));
        boundaries.put("adjudicated", countAdjudicated(runId));
        boundaries.put("offlineBackfilled", countOffline(runId));

        String catalogLabel = null;
        String invLabel = null;
        UUID releaseId = ReferenceRepo.asUuid(run, "release_id");
        if ("SNAPSHOT".equals(ReferenceRepo.asString(run, "mode")) && releaseId != null) {
            Map<String, Object> rel = releases.releaseById(releaseId).orElse(Map.of());
            catalogLabel = ReferenceRepo.asString(rel, "catalog_version_label");
            invLabel = ReferenceRepo.asString(rel, "inventory_snapshot_label");
        } else {
            UUID cv = ReferenceRepo.asUuid(run, "pinned_catalog_version_id");
            UUID inv = ReferenceRepo.asUuid(run, "pinned_inventory_snapshot_id");
            catalogLabel = cv == null ? null : labelOfCatalog(cv);
            invLabel = inv == null ? null : labelOfInventory(inv);
        }
        return new DiagnosisDtos.Statistics(runId, ReferenceRepo.asString(run, "mode"), total,
                statusCounts, concepts, boundaries, catalogLabel, invLabel);
    }

    private long countAdjudicated(UUID runId) {
        Integer n = diagnosis.countAdjudicated(runId);
        return n == null ? 0 : n;
    }

    private long countOffline(UUID runId) {
        Integer n = diagnosis.countOffline(runId);
        return n == null ? 0 : n;
    }

    public DiagnosisDtos.RunDiff diff(UUID leftId, UUID rightId) {
        Map<String, Object> left = requireRun(leftId);
        Map<String, Object> right = requireRun(rightId);
        List<Map<String, Object>> leftResults = diagnosis.resultsForRun(leftId);
        List<Map<String, Object>> rightResults = diagnosis.resultsForRun(rightId);

        Map<UUID, Map<String, Object>> leftByAlarm = indexByAlarm(leftResults);
        Map<UUID, Map<String, Object>> rightByAlarm = indexByAlarm(rightResults);

        Set<UUID> allAlarms = new LinkedHashSet<>();
        allAlarms.addAll(leftByAlarm.keySet());
        allAlarms.addAll(rightByAlarm.keySet());

        Map<String, Long> classification = new LinkedHashMap<>();
        List<DiagnosisDtos.DiffEntry> entries = new ArrayList<>();
        for (UUID alarmId : allAlarms) {
            Map<String, Object> l = leftByAlarm.get(alarmId);
            Map<String, Object> r = rightByAlarm.get(alarmId);
            String leftStatus = l == null ? "ABSENT" : ReferenceRepo.asString(l, "status");
            String rightStatus = r == null ? "ABSENT" : ReferenceRepo.asString(r, "status");
            String leftConcept = l == null ? null : ReferenceRepo.asString(l, "primary_concept_code");
            String rightConcept = r == null ? null : ReferenceRepo.asString(r, "primary_concept_code");

            String cls;
            if (l == null) {
                cls = "ONLY_IN_RIGHT";
            } else if (r == null) {
                cls = "ONLY_IN_LEFT";
            } else if (!Objects.equals(leftStatus, rightStatus)) {
                cls = "STATUS_CHANGED";
            } else if (!Objects.equals(leftConcept, rightConcept)) {
                cls = "CONCEPT_CHANGED";
            } else {
                cls = "UNCHANGED";
            }
            classification.merge(cls, 1L, Long::sum);
            Map<String, Object> base = l != null ? l : r;
            entries.add(new DiagnosisDtos.DiffEntry(alarmId,
                    ReferenceRepo.asString(base, "external_id"),
                    ReferenceRepo.asString(base, "unit_code"),
                    leftStatus, rightStatus, leftConcept, rightConcept,
                    dispositionStatusOf(l), dispositionStatusOf(r), cls));
        }

        Map<String, Object> leftMeta = new LinkedHashMap<>();
        leftMeta.put("mode", ReferenceRepo.asString(left, "mode"));
        leftMeta.put("basis", ReferenceRepo.asString(left, "basis"));
        leftMeta.put("evaluationTime", ReferenceRepo.asInstant(left, "evaluation_time"));
        leftMeta.put("createdAt", ReferenceRepo.asInstant(left, "created_at"));
        leftMeta.put("releaseId", ReferenceRepo.asUuid(left, "release_id"));
        Map<String, Object> rightMeta = new LinkedHashMap<>();
        rightMeta.put("mode", ReferenceRepo.asString(right, "mode"));
        rightMeta.put("basis", ReferenceRepo.asString(right, "basis"));
        rightMeta.put("evaluationTime", ReferenceRepo.asInstant(right, "evaluation_time"));
        rightMeta.put("createdAt", ReferenceRepo.asInstant(right, "created_at"));
        rightMeta.put("releaseId", ReferenceRepo.asUuid(right, "release_id"));

        String explanation = "Compared " + allAlarms.size() + " alarms; "
                + classification.getOrDefault("UNCHANGED", 0L) + " unchanged, "
                + classification.getOrDefault("STATUS_CHANGED", 0L) + " status changes, "
                + classification.getOrDefault("CONCEPT_CHANGED", 0L) + " concept changes, "
                + classification.getOrDefault("ONLY_IN_LEFT", 0L) + " only in left run (right run did not cover that alarm), "
                + classification.getOrDefault("ONLY_IN_RIGHT", 0L) + " only in right run (left run did not cover that alarm).";

        return new DiagnosisDtos.RunDiff(leftId, rightId, leftMeta, rightMeta, classification, entries,
                explanation);
    }

    private String dispositionStatusOf(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        List<Map<String, Object>> findings = diagnosis.findingsForResult(ReferenceRepo.asUuid(result, "id"));
        if (findings.isEmpty()) {
            return null;
        }
        return ReferenceRepo.asString(findings.get(0), "disposition_status");
    }

    private Map<UUID, Map<String, Object>> indexByAlarm(List<Map<String, Object>> results) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : results) {
            out.put(ReferenceRepo.asUuid(r, "alarm_id"), r);
        }
        return out;
    }

    public DiagnosisDtos.Explain explain(UUID resultId) {
        Map<String, Object> result = ReferenceRepo.require(diagnosis.resultById(resultId),
                "RESULT_NOT_FOUND", "Unknown diagnosis result " + resultId);
        UUID runId = ReferenceRepo.asUuid(result, "run_id");
        Map<String, Object> run = requireRun(runId);
        UUID alarmId = ReferenceRepo.asUuid(result, "alarm_id");
        Map<String, Object> alarm = ReferenceRepo.require(evidence.alarmById(alarmId),
                "ALARM_NOT_FOUND", "Unknown alarm " + alarmId);

        Map<String, Object> alarmView = new LinkedHashMap<>();
        alarmView.put("externalId", ReferenceRepo.asString(alarm, "external_id"));
        alarmView.put("rawCode", ReferenceRepo.asString(alarm, "raw_code"));
        alarmView.put("occurredAt", ReferenceRepo.asInstant(alarm, "occurred_at"));
        alarmView.put("receivedAt", ReferenceRepo.asInstant(alarm, "received_at"));
        alarmView.put("ingestType", ReferenceRepo.asString(alarm, "ingest_type"));
        alarmView.put("lateBySeconds", lateBy(alarm));
        alarmView.put("attributes", Sql.readMap(alarm.get("attributes")));
        alarmView.put("sourceCodeId", ReferenceRepo.asUuid(alarm, "source_code_id"));
        alarmView.put("termAttachmentId", ReferenceRepo.asUuid(alarm, "term_attachment_id"));
        alarmView.put("firmwareVersionId", ReferenceRepo.asUuid(alarm, "firmware_version_id"));

        Map<String, Object> sourceView = new LinkedHashMap<>();
        UUID codeId = ReferenceRepo.asUuid(alarm, "source_code_id");
        UUID termId = ReferenceRepo.asUuid(alarm, "term_attachment_id");
        if (codeId != null) {
            Map<String, Object> sc = reference.sourceCodeById(codeId).orElse(Map.of());
            sourceView.put("kind", "CONTROLLER_CODE");
            sourceView.put("sourceSystemCode", ReferenceRepo.asString(sc, "source_system_code"));
            sourceView.put("code", ReferenceRepo.asString(sc, "code"));
            sourceView.put("rawText", ReferenceRepo.asString(sc, "raw_text"));
        } else if (termId != null) {
            Map<String, Object> t = reference.termById(termId).orElse(Map.of());
            sourceView.put("kind", "VENDOR_TERM");
            sourceView.put("vendor", ReferenceRepo.asString(t, "vendor"));
            sourceView.put("termText", ReferenceRepo.asString(t, "term_text"));
            sourceView.put("attachmentRef", ReferenceRepo.asString(t, "attachment_ref"));
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> f : diagnosis.findingsForResult(resultId)) {
            Map<String, Object> chain = Sql.readMap(f.get("mapping_chain"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conceptCode", ReferenceRepo.asString(f, "concept_code"));
            m.put("conceptName", ReferenceRepo.asString(f, "concept_name"));
            m.put("definitionVersion", ReferenceRepo.asInt(f, "definition_version"));
            m.put("definitionText", ReferenceRepo.asString(f, "definition_text"));
            m.put("dispositionStatus", ReferenceRepo.asString(f, "disposition_status"));
            m.put("disposition", Sql.readMap(f.get("disposition")));
            m.put("mappingChain", chain);
            m.put("evidenceAssertionIds", uuidList(f.get("evidence_assertion_ids")));
            m.put("evidenceReleaseEntryIds", uuidList(f.get("evidence_release_entry_ids")));
            findings.add(m);
        }

        Map<String, Object> runContext = new LinkedHashMap<>();
        runContext.put("runId", runId);
        runContext.put("mode", ReferenceRepo.asString(run, "mode"));
        runContext.put("basis", ReferenceRepo.asString(run, "basis"));
        runContext.put("evaluationTime", ReferenceRepo.asInstant(run, "evaluation_time"));
        runContext.put("knowledgeCutoff", ReferenceRepo.asInstant(run, "knowledge_cutoff"));
        runContext.put("releaseId", ReferenceRepo.asUuid(run, "release_id"));
        runContext.put("pinnedCatalogVersionId", ReferenceRepo.asUuid(run, "pinned_catalog_version_id"));
        runContext.put("pinnedInventorySnapshotId", ReferenceRepo.asUuid(run, "pinned_inventory_snapshot_id"));

        List<Map<String, Object>> workOrders = relevantWorkOrders(alarm);
        UUID runReleaseId = "SNAPSHOT".equals(ReferenceRepo.asString(run, "mode"))
                ? ReferenceRepo.asUuid(run, "release_id") : null;
        substitutionService.attachHistoricalRecommendations(workOrders, runReleaseId);

        return new DiagnosisDtos.Explain(resultId, alarmId, ReferenceRepo.asString(alarm, "external_id"),
                ReferenceRepo.asString(result, "unit_code"), ReferenceRepo.asString(result, "status"),
                ReferenceRepo.asString(result, "explanation"), alarmView, sourceView,
                findings.stream()
                        .map(f -> (Map<String, Object>) f.get("mappingChain"))
                        .toList(),
                findings, workOrders, runContext);
    }

    private List<Map<String, Object>> relevantWorkOrders(Map<String, Object> alarm) {
        UUID unitId = ReferenceRepo.asUuid(alarm, "unit_id");
        java.time.Instant occurred = ReferenceRepo.asInstant(alarm, "occurred_at");
        if (unitId == null || occurred == null) {
            return List.of();
        }
        return evidence.workOrdersForUnitNear(unitId, occurred.minus(java.time.Duration.ofDays(7)),
                        occurred.plus(java.time.Duration.ofDays(3))).stream()
                .map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("externalId", ReferenceRepo.asString(w, "external_id"));
                    m.put("vendor", ReferenceRepo.asString(w, "vendor"));
                    m.put("reportedTerm", ReferenceRepo.asString(w, "reported_term"));
                    m.put("openedAt", ReferenceRepo.asInstant(w, "opened_at"));
                    m.put("status", ReferenceRepo.asString(w, "status"));
                    UUID termId = ReferenceRepo.asUuid(w, "term_attachment_id");
                    if (termId != null) {
                        m.put("termAttachmentId", termId);
                        reference.termById(termId).ifPresent(t ->
                                m.put("termText", ReferenceRepo.asString(t, "term_text")));
                        m.put("termHistory", mappings.assertionsForTerm(termId));
                    }
                    return m;
                })
                .toList();
    }

    private Long lateBy(Map<String, Object> alarm) {
        java.time.Instant occurred = ReferenceRepo.asInstant(alarm, "occurred_at");
        java.time.Instant received = ReferenceRepo.asInstant(alarm, "received_at");
        if (occurred == null || received == null) {
            return null;
        }
        return received.getEpochSecond() - occurred.getEpochSecond();
    }

    private List<UUID> uuidList(Object raw) {
        List<UUID> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof java.sql.Array arr) {
            try {
                for (Object o : (Object[]) arr.getArray()) {
                    if (o != null) {
                        out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
                    }
                }
            } catch (Exception ignored) {
                return out;
            }
            return out;
        }
        if (raw instanceof UUID[] uuids) {
            return List.of(uuids);
        }
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof UUID u) {
                    out.add(u);
                } else if (o != null) {
                    out.add(UUID.fromString(o.toString()));
                }
            }
        }
        return out;
    }

    private Map<String, Object> requireRun(UUID runId) {        return ReferenceRepo.require(diagnosis.runById(runId), "RUN_NOT_FOUND",
                "Unknown diagnosis run " + runId);
    }

    private String labelOfCatalog(UUID id) {
        return catalog.catalogVersionById(id).map(r -> ReferenceRepo.asString(r, "version_label")).orElse(null);
    }

    private String labelOfInventory(UUID id) {
        return catalog.inventorySnapshotById(id).map(r -> ReferenceRepo.asString(r, "label")).orElse(null);
    }
}
