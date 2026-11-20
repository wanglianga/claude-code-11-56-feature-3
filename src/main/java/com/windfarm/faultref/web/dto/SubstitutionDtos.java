package com.windfarm.faultref.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read models for the spare-part substitution graph and the time-separated
 * advice ("what was recommended then" vs "what can be issued now").
 */
public final class SubstitutionDtos {

    private SubstitutionDtos() {
    }

    // ---- admin ----------------------------------------------------------

    public record SubstitutionEdge(
            UUID id,
            String catalogVersionLabel,
            String fromPartNumber,
            String toPartNumber,
            String substitutionKind,
            String modelCode,
            BigDecimal tempMinC,
            BigDecimal tempMaxC,
            String interfaceRequirement,
            boolean requiresEngineeringConfirm,
            Instant validFrom,
            Instant validTo,
            String state,
            String approvalRef,
            String approvedBy,
            Instant approvedAt,
            String notes) {
    }

    public record PartLifecycle(
            UUID id,
            String catalogVersionLabel,
            String partNumber,
            String state,
            String obsoleteReason,
            String supersededByPartNumber,
            Instant decidedAt,
            String approvedBy,
            String approvalRef) {
    }

    public record WorkOrderRecommendation(
            UUID id,
            String workOrderExternalId,
            String conceptCode,
            String partNumber,
            String dispositionCode,
            String catalogVersionLabel,
            int quantity,
            String recommendationStatus,
            String reason,
            Instant recordedAt,
            String recordedBy,
            String approvalRef) {
    }

    public record StockContention(
            UUID id,
            String inventorySnapshotLabel,
            String partNumber,
            String warehouse,
            String status,
            String competingDemand,
            String confirmedBy,
            Instant confirmedAt,
            String notes,
            Instant createdAt) {
    }

    // ---- traversal ------------------------------------------------------

    /** One verified edge inside a substitution path. */
    public record EdgeCheck(
            String fromPartNumber,
            String toPartNumber,
            String substitutionKind,
            String checkStatus,
            List<String> failedChecks,
            String modelCode,
            BigDecimal tempMinC,
            BigDecimal tempMaxC,
            BigDecimal ambientTempC,
            String interfaceRequirement,
            boolean requiresEngineeringConfirm,
            String approvalRef,
            String notes) {
    }

    public record PathNode(
            int depth,
            String partNumber,
            String partName,
            String lifecycleState,
            int availableQty,
            boolean cycle) {
    }

    /**
     * A full forward path from the historical part to a terminal candidate.
     * pathStatus is EXECUTABLE, BLOCKED or CYCLE; every edge check and every
     * pending engineering condition is attached so the chain is explainable.
     */
    public record SubstitutionPath(
            String pathId,
            String pathStatus,
            List<PathNode> nodes,
            List<EdgeCheck> edgeChecks,
            String terminalPartNumber,
            boolean terminalInStock,
            boolean retrofitRequired,
            List<String> pendingEngineeringConditions,
            List<String> blockedReasons,
            List<String> cyclePath,
            String contentionStatus) {
    }

    public record PartAdvice(
            String partNumber,
            String partName,
            String lifecycleState,
            String obsoleteReason,
            int availableQty,
            int requiredQty,
            String stockStatus,
            String contentionStatus,
            String contentionCompetingDemand,
            List<String> blockedReasons,
            List<String> pendingEngineeringConditions,
            List<SubstitutionPath> paths,
            List<SubstitutionPath> cycleConflicts) {
    }

    /** Immutable recommendation captured against a historical work order. */
    public record OriginalRecommendation(
            String workOrderExternalId,
            String conceptCode,
            String partNumber,
            String dispositionCode,
            String catalogVersionLabel,
            int quantity,
            String recommendationStatus,
            String reason,
            Instant recordedAt,
            String recordedBy) {
    }

    public record EvaluationContext(
            String unitCode,
            String manufacturerCode,
            String modelCode,
            String firmwareVersion,
            BigDecimal ambientTempC,
            String interfaceCode,
            Instant occurredAt,
            Instant evaluatedAt,
            Map<String, Object> alarmAttributes) {
    }

    /**
     * The complete advice for one diagnostic concept at a device, environment,
     * work-order time and inventory point. Historical recommendation and
     * current executable alternatives are deliberately separate objects.
     */
    public record ConceptPartAdvice(
            String conceptCode,
            String conceptName,
            EvaluationContext context,
            String catalogVersionLabel,
            String inventorySnapshotLabel,
            OriginalRecommendation originalRecommendation,
            List<PartAdvice> currentAdvice,
            List<SubstitutionPath> cycleConflicts,
            List<String> notSubstitutableReasons,
            List<String> pendingEngineeringConditions,
            String summary) {
    }
}
