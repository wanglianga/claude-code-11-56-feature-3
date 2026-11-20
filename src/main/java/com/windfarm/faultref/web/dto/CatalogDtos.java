package com.windfarm.faultref.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CatalogVersion(UUID id, String versionLabel, String status, Instant effectiveFrom,
                                 Instant publishedAt, String approvedBy, String approvalRef, String notes) {
    }

    public record Part(UUID id, String partNumber, String name, String category, String manufacturer) {
    }

    public record Compat(String manufacturerCode, String modelCode, String firmwareFrom, String firmwareTo) {
    }

    public record PartEntry(UUID id, String catalogVersionLabel, String partNumber, String partName,
                            String category, String description, Map<String, Object> attributes,
                            List<Compat> compatibility) {
    }

    public record InventorySnapshot(UUID id, String label, Instant takenAt, String source,
                                    String catalogVersionLabel, List<InventoryLine> levels) {
    }

    public record InventoryLine(String partNumber, String partName, String warehouse, int qtyAvailable) {
    }

    public record Disposition(UUID id, String conceptCode, String code, String action, String description,
                              String partNumber, int quantity, String exclusiveGroup, boolean requiresStock,
                              int minStock, String catalogVersionLabel, Instant validFrom, Instant validTo,
                              String approvedBy, String approvalRef) {
    }

    /**
     * Evaluated disposition for a concept under one pinned catalog + inventory
     * context. Availability is explicit so callers can see the boundary rather
     * than a silently dropped suggestion.
     */
    public record DispositionEvaluation(
            String code,
            String action,
            String description,
            String partNumber,
            int quantity,
            String exclusiveGroup,
            boolean requiresStock,
            int minStock,
            int availableQty,
            String availability,
            String reason) {
    }

    /** Result of disposition evaluation for one concept. */
    public record ConceptDisposition(
            String status,
            String reason,
            List<DispositionEvaluation> options,
            List<DispositionEvaluation> unavailable,
            String exclusiveGroup) {
    }
}
