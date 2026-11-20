package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.CatalogDtos;
import com.windfarm.faultref.web.dto.CatalogDtos.ConceptDisposition;
import com.windfarm.faultref.web.dto.CatalogDtos.DispositionEvaluation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog + inventory + disposition layer.
 *
 * A disposition suggestion is only CONFIRMED when exactly one exclusive option
 * group is satisfiable under the pinned catalog and inventory snapshot. If two
 * or more mutually exclusive options are both satisfiable, or an exclusive group
 * contains more than one member at all, the concept is left PENDING_ENGINEER.
 */
@Service
public class CatalogService {

    private final CatalogRepo catalog;
    private final ReferenceRepo reference;

    public CatalogService(CatalogRepo catalog, ReferenceRepo reference) {
        this.catalog = catalog;
        this.reference = reference;
    }

    // ---- reference helpers ---------------------------------------------
    public UUID requireCatalogVersion(String label) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(catalog.catalogVersionByLabel(label),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + label), "id");
    }

    public UUID requirePart(String partNumber) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(catalog.partByNumber(partNumber),
                "PART_NOT_FOUND", "Unknown part " + partNumber), "id");
    }

    public UUID requireInventorySnapshot(String label) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(catalog.inventorySnapshotByLabel(label),
                "INVENTORY_SNAPSHOT_NOT_FOUND", "Unknown inventory snapshot " + label), "id");
    }

    /** Create a disposition option bound to a specific catalog version. */
    @Transactional
    public UUID createDisposition(NewDisposition cmd) {
        UUID conceptId = ReferenceRepo.asUuid(ReferenceRepo.require(reference.conceptByCode(cmd.conceptCode()),
                "CONCEPT_NOT_FOUND", "Unknown concept " + cmd.conceptCode()), "id");
        Map<String, Object> cv = ReferenceRepo.require(catalog.catalogVersionByLabel(cmd.catalogVersionLabel()),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + cmd.catalogVersionLabel());
        if (!"DRAFT".equals(ReferenceRepo.asString(cv, "status"))) {
            throw ApiException.conflict("CATALOG_NOT_DRAFT",
                    "Dispositions may only be added to a DRAFT catalog version; "
                            + cmd.catalogVersionLabel() + " is " + ReferenceRepo.asString(cv, "status"));
        }
        UUID partId = cmd.partNumber() == null ? null : requirePart(cmd.partNumber());
        if (partId == null && "REPLACE".equals(cmd.action())) {
            throw ApiException.unprocessable("PART_REQUIRED", "REPLACE dispositions require a partNumber");
        }
        Instant validFrom = cmd.validFrom() == null ? Instant.now() : cmd.validFrom();
        if (cmd.validTo() != null && !cmd.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("VALIDITY_INVALID", "validTo must be after validFrom");
        }
        return catalog.insertDisposition(new MapSqlParameterSource()
                .addValue("conceptId", conceptId)
                .addValue("catalogVersionId", ReferenceRepo.asUuid(cv, "id"))
                .addValue("code", cmd.code())
                .addValue("action", cmd.action())
                .addValue("description", cmd.description())
                .addValue("partId", partId)
                .addValue("quantity", cmd.quantity() == null ? 1 : cmd.quantity())
                .addValue("exclusiveGroup", cmd.exclusiveGroup())
                .addValue("requiresStock", cmd.requiresStock() == null || cmd.requiresStock())
                .addValue("minStock", cmd.minStock() == null ? 1 : cmd.minStock())
                .addValue("validFrom", Sql.odt(validFrom))
                .addValue("validTo", Sql.odt(cmd.validTo()))
                .addValue("approvedBy", cmd.approvedBy())
                .addValue("approvalRef", cmd.approvalRef())
                .addValue("approvedAt", cmd.approvedBy() == null ? null : Sql.odt(Instant.now())));
    }

    /**
     * Evaluate every disposition for a concept under a pinned catalog version,
     * inventory snapshot and business instant.
     */
    public ConceptDisposition evaluate(UUID conceptId, UUID catalogVersionId, UUID inventorySnapshotId,
                                       Instant at) {
        List<Map<String, Object>> rows = catalog.dispositionsForCatalog(catalogVersionId).stream()
                .filter(r -> conceptId.equals(CatalogRepo.uuid(r, "concept_id")))
                .toList();

        List<DispositionEvaluation> available = new ArrayList<>();
        List<DispositionEvaluation> unavailable = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            Instant vf = CatalogRepo.instant(r, "valid_from");
            Instant vt = CatalogRepo.instant(r, "valid_to");
            boolean timeValid = (vf == null || !vf.isAfter(at)) && (vt == null || vt.isAfter(at));
            DispositionEvaluation eval = evaluateOne(r, inventorySnapshotId, timeValid);
            if ("AVAILABLE".equals(eval.availability())) {
                available.add(eval);
            } else {
                unavailable.add(eval);
            }
        }

        if (rows.isEmpty()) {
            return new ConceptDisposition("NO_DISPOSITION",
                    "Catalog version " + catalogLabel(catalogVersionId) + " defines no disposition for this concept",
                    List.of(), List.of(), null);
        }

        // Group by exclusive group. Options with no group are independent.
        Map<String, List<DispositionEvaluation>> byGroup = new java.util.LinkedHashMap<>();
        List<DispositionEvaluation> ungrouped = new ArrayList<>();
        for (DispositionEvaluation e : available) {
            if (e.exclusiveGroup() == null || e.exclusiveGroup().isBlank()) {
                ungrouped.add(e);
            } else {
                byGroup.computeIfAbsent(e.exclusiveGroup(), k -> new ArrayList<>()).add(e);
            }
        }

        // Detect genuine mutual exclusion: one satisfiable member per group.
        List<String> conflictedGroups = new ArrayList<>();
        for (Map.Entry<String, List<DispositionEvaluation>> g : byGroup.entrySet()) {
            if (g.getValue().size() > 1) {
                // More than one available member of an exclusive group => engineer must choose.
                conflictedGroups.add(g.getKey());
            }
        }

        if (!conflictedGroups.isEmpty()) {
            return new ConceptDisposition("PENDING_ENGINEER",
                    "Mutually exclusive dispositions are simultaneously satisfiable in group(s) "
                            + conflictedGroups + "; an engineer must adjudicate",
                    available, unavailable, conflictedGroups.get(0));
        }

        List<DispositionEvaluation> confirmed = new ArrayList<>();
        confirmed.addAll(ungrouped);
        byGroup.values().forEach(v -> confirmed.add(v.get(0)));

        if (confirmed.isEmpty()) {
            return new ConceptDisposition("NO_DISPOSITION",
                    "All defined dispositions are unavailable under the pinned catalog/inventory context",
                    available, unavailable, null);
        }
        return new ConceptDisposition("CONFIRMED",
                confirmed.size() + " satisfiable disposition(s) under the pinned context",
                available, unavailable, null);
    }

    private DispositionEvaluation evaluateOne(Map<String, Object> r, UUID inventorySnapshotId,
                                              boolean timeValid) {
        String code = CatalogRepo.str(r, "code");
        String action = CatalogRepo.str(r, "action");
        String description = CatalogRepo.str(r, "description");
        String partNumber = CatalogRepo.str(r, "part_number");
        int quantity = CatalogRepo.integer(r, "quantity") == null ? 1 : CatalogRepo.integer(r, "quantity");
        String group = CatalogRepo.str(r, "exclusive_group");
        boolean requiresStock = Boolean.TRUE.equals(CatalogRepo.bool(r, "requires_stock"));
        int minStock = CatalogRepo.integer(r, "min_stock") == null ? 1 : CatalogRepo.integer(r, "min_stock");

        int availableQty = 0;
        if (partNumber != null && inventorySnapshotId != null) {
            UUID partId = ReferenceRepo.asUuid(catalog.partByNumber(partNumber).orElse(Map.of()), "id");
            if (partId != null) {
                availableQty = catalog.availableQuantity(inventorySnapshotId, partId);
            }
        }

        if (!timeValid) {
            return new DispositionEvaluation(code, action, description, partNumber, quantity, group,
                    requiresStock, minStock, availableQty, "UNAVAILABLE", "Disposition not valid at diagnosis instant");
        }
        if (requiresStock) {
            if (partNumber == null) {
                return new DispositionEvaluation(code, action, description, null, quantity, group,
                        true, minStock, 0, "UNAVAILABLE", "Stock-dependent disposition has no part reference");
            }
            if (availableQty < Math.max(minStock, quantity)) {
                return new DispositionEvaluation(code, action, description, partNumber, quantity, group,
                        true, minStock, availableQty, "UNAVAILABLE",
                        "Insufficient stock: available " + availableQty + ", needs "
                                + Math.max(minStock, quantity));
            }
        }
        return new DispositionEvaluation(code, action, description, partNumber, quantity, group,
                requiresStock, minStock, availableQty, "AVAILABLE", "Satisfiable under pinned context");
    }

    /**
     * Pins the catalog + inventory context for a diagnosis run. Each run resolves
     * exactly one pair so a later catalog publish cannot alter an in-flight result.
     */
    public PinnedContext pin(Instant asOf, Instant knowledgeCutoff) {
        Map<String, Object> cv = catalog.publishedCatalogAt(asOf).orElse(null);
        Map<String, Object> inv = catalog.latestInventorySnapshotAt(asOf).orElse(null);
        UUID cvId = cv == null ? null : ReferenceRepo.asUuid(cv, "id");
        UUID invId = inv == null ? null : ReferenceRepo.asUuid(inv, "id");
        return new PinnedContext(cvId, invId,
                cv == null ? null : ReferenceRepo.asString(cv, "version_label"),
                inv == null ? null : ReferenceRepo.asString(inv, "label"));
    }

    public PinnedContext pinByLabels(String catalogVersionLabel, String inventorySnapshotLabel) {
        UUID cvId = catalogVersionLabel == null ? null : requireCatalogVersion(catalogVersionLabel);
        UUID invId = inventorySnapshotLabel == null ? null : requireInventorySnapshot(inventorySnapshotLabel);
        return new PinnedContext(cvId, invId, catalogVersionLabel, inventorySnapshotLabel);
    }

    private String catalogLabel(UUID id) {
        return catalog.catalogVersionById(id).map(r -> ReferenceRepo.asString(r, "version_label")).orElse("?");
    }

    public record PinnedContext(UUID catalogVersionId, UUID inventorySnapshotId,
                                String catalogVersionLabel, String inventorySnapshotLabel) {
    }

    public record NewDisposition(
            String conceptCode,
            String catalogVersionLabel,
            String code,
            String action,
            String description,
            String partNumber,
            Integer quantity,
            String exclusiveGroup,
            Boolean requiresStock,
            Integer minStock,
            Instant validFrom,
            Instant validTo,
            String approvedBy,
            String approvalRef) {
    }
}
