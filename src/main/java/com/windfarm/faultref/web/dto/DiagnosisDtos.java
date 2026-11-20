package com.windfarm.faultref.web.dto;

import com.windfarm.faultref.web.dto.CatalogDtos.DispositionEvaluation;
import com.windfarm.faultref.web.dto.MappingDtos.Candidate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiagnosisDtos {

    private DiagnosisDtos() {
    }

    public record RunRequest(
            String mode,
            String basis,
            Instant asOf,
            String releaseLabel,
            List<UUID> alarmIds,
            Instant from,
            Instant to,
            String unitCode,
            Boolean offlineOnly,
            String catalogVersionLabel,
            String inventorySnapshotLabel,
            Instant knowledgeCutoff) {
    }

    public record Finding(
            UUID id,
            UUID conceptId,
            String conceptCode,
            String conceptName,
            String category,
            int definitionVersion,
            String definitionText,
            BigDecimal confidence,
            String dispositionStatus,
            Map<String, Object> disposition,
            Map<String, Object> mappingChain,
            List<UUID> evidenceAssertionIds,
            List<UUID> evidenceReleaseEntryIds) {
    }

    public record Result(
            UUID id,
            UUID alarmId,
            String alarmExternalId,
            String unitCode,
            String sourceSystemCode,
            String sourceSystemKind,
            String rawCode,
            Instant occurredAt,
            Instant receivedAt,
            String ingestType,
            String status,
            String explanation,
            boolean adjudicated,
            String primaryConceptCode,
            String primaryConceptName,
            List<Finding> findings,
            List<Map<String, Object>> relatedWorkOrders,
            List<Map<String, Object>> relatedVendorTerms) {
    }

    public record Run(
            UUID id,
            String mode,
            String basis,
            String releaseLabel,
            UUID releaseId,
            Instant asOf,
            Instant evaluationTime,
            Instant knowledgeCutoff,
            String pinnedCatalogVersionLabel,
            String pinnedInventorySnapshotLabel,
            String status,
            String createdBy,
            Instant createdAt,
            Instant finishedAt,
            Map<String, Object> summary,
            List<Result> results) {
    }

    public record RunSummary(
            UUID id,
            String mode,
            String basis,
            String releaseLabel,
            String pinnedCatalogVersionLabel,
            String pinnedInventorySnapshotLabel,
            String status,
            Instant createdAt,
            Map<String, Object> summary) {
    }

    public record ConceptStat(String conceptCode, String conceptName, String category, long findings) {
    }

    public record Statistics(
            UUID runId,
            String mode,
            long alarms,
            Map<String, Object> statusCounts,
            List<ConceptStat> concepts,
            Map<String, Object> boundaries,
            String pinnedCatalogVersionLabel,
            String pinnedInventorySnapshotLabel) {
    }

    public record DiffEntry(
            UUID alarmId,
            String alarmExternalId,
            String unitCode,
            String leftStatus,
            String rightStatus,
            String leftConcept,
            String rightConcept,
            String leftDispositionStatus,
            String rightDispositionStatus,
            String classification) {
    }

    public record RunDiff(
            UUID leftRunId,
            UUID rightRunId,
            Map<String, Object> left,
            Map<String, Object> right,
            Map<String, Long> classificationCounts,
            List<DiffEntry> entries,
            String explanation) {
    }

    public record Explain(
            UUID resultId,
            UUID alarmId,
            String alarmExternalId,
            String unitCode,
            String status,
            String explanation,
            Map<String, Object> alarm,
            Map<String, Object> source,
            List<Map<String, Object>> mappingChain,
            List<Map<String, Object>> findings,
            List<Map<String, Object>> relatedWorkOrders,
            Map<String, Object> runContext) {
    }
}
