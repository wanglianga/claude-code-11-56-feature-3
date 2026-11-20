package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.CatalogDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin operations for catalog versions, parts, inventory and dispositions. */
@Service
public class CatalogAdminService {

    private final CatalogRepo catalog;
    private final ReferenceRepo reference;
    private final CatalogService catalogService;

    public CatalogAdminService(CatalogRepo catalog, ReferenceRepo reference, CatalogService catalogService) {
        this.catalog = catalog;
        this.reference = reference;
        this.catalogService = catalogService;
    }

    public List<CatalogDtos.CatalogVersion> versions() {
        return catalog.listCatalogVersions().stream()
                .map(r -> new CatalogDtos.CatalogVersion(ReferenceRepo.asUuid(r, "id"),
                        ReferenceRepo.asString(r, "version_label"), ReferenceRepo.asString(r, "status"),
                        ReferenceRepo.asInstant(r, "effective_from"), ReferenceRepo.asInstant(r, "published_at"),
                        ReferenceRepo.asString(r, "approved_by"), ReferenceRepo.asString(r, "approval_ref"),
                        ReferenceRepo.asString(r, "notes")))
                .toList();
    }

    @Transactional
    public Map<String, Object> createVersion(ApiRequests.CatalogVersionCreate req) {
        String status = req.status() == null ? "DRAFT" : req.status();
        if (!List.of("DRAFT", "PUBLISHED").contains(status)) {
            throw ApiException.badRequest("STATUS_INVALID", "status must be DRAFT or PUBLISHED");
        }
        if ("PUBLISHED".equals(status) && req.approvedBy() == null) {
            throw ApiException.unprocessable("APPROVAL_REQUIRED", "Publishing a catalog version requires approvedBy");
        }
        try {
            UUID id = catalog.insertCatalogVersion(req.versionLabel(), status,
                    req.effectiveFrom() == null ? Instant.now() : req.effectiveFrom(),
                    req.approvedBy(), req.approvalRef(), req.notes());
            return Map.of("id", id, "versionLabel", req.versionLabel(), "status", status);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CATALOG_VERSION_EXISTS",
                    "Catalog version " + req.versionLabel() + " already exists");
        }
    }

    @Transactional
    public Map<String, Object> publish(String label, ApiRequests.CatalogPublish req) {
        Map<String, Object> cv = ReferenceRepo.require(catalog.catalogVersionByLabel(label),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + label);
        if (!"DRAFT".equals(ReferenceRepo.asString(cv, "status"))) {
            throw ApiException.conflict("CATALOG_NOT_DRAFT",
                    "Catalog version " + label + " is " + ReferenceRepo.asString(cv, "status"));
        }
        catalog.publishCatalogVersion(ReferenceRepo.asUuid(cv, "id"), req.approvedBy(), req.approvalRef());
        return Map.of("versionLabel", label, "status", "PUBLISHED", "approvedBy", req.approvedBy());
    }

    public List<CatalogDtos.Part> parts() {
        return catalog.listParts().stream()
                .map(r -> new CatalogDtos.Part(ReferenceRepo.asUuid(r, "id"),
                        ReferenceRepo.asString(r, "part_number"), ReferenceRepo.asString(r, "name"),
                        ReferenceRepo.asString(r, "category"), ReferenceRepo.asString(r, "manufacturer")))
                .toList();
    }

    @Transactional
    public Map<String, Object> createPart(ApiRequests.PartCreate req) {
        try {
            UUID id = catalog.insertPart(req.partNumber(), req.name(), req.category(), req.manufacturer());
            return Map.of("id", id, "partNumber", req.partNumber());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PART_EXISTS", "Part " + req.partNumber() + " already exists");
        }
    }

    @Transactional
    public Map<String, Object> addEntry(ApiRequests.CatalogEntryCreate req) {
        Map<String, Object> cv = ReferenceRepo.require(catalog.catalogVersionByLabel(req.catalogVersionLabel()),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + req.catalogVersionLabel());
        if (!"DRAFT".equals(ReferenceRepo.asString(cv, "status"))) {
            throw ApiException.conflict("CATALOG_NOT_DRAFT",
                    "Entries may only be added to a DRAFT catalog version");
        }
        UUID partId = catalogService.requirePart(req.partNumber());
        UUID entryId;
        try {
            entryId = catalog.insertEntry(ReferenceRepo.asUuid(cv, "id"), partId, req.description(),
                    req.attributes());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CATALOG_ENTRY_EXISTS",
                    "Part " + req.partNumber() + " already has an entry in " + req.catalogVersionLabel());
        }
        if (req.compatibility() != null) {
            for (ApiRequests.CompatSpec spec : req.compatibility()) {
                UUID modelId = null;
                Integer fromSeq = null;
                Integer toSeq = null;
                if (spec.modelCode() != null) {
                    if (spec.manufacturerCode() == null) {
                        throw ApiException.badRequest("COMPAT_INVALID", "modelCode requires manufacturerCode");
                    }
                    Map<String, Object> model = ReferenceRepo.require(
                            reference.modelByCode(spec.manufacturerCode(), spec.modelCode()),
                            "MODEL_NOT_FOUND", "Unknown model " + spec.manufacturerCode() + "/" + spec.modelCode());
                    modelId = ReferenceRepo.asUuid(model, "id");
                    if (spec.firmwareFrom() != null) {
                        fromSeq = ReferenceRepo.asInt(ReferenceRepo.require(
                                reference.firmwareByModelAndVersion(modelId, spec.firmwareFrom()),
                                "FIRMWARE_NOT_FOUND", "Unknown firmware " + spec.firmwareFrom()), "sequence");
                    }
                    if (spec.firmwareTo() != null) {
                        toSeq = ReferenceRepo.asInt(ReferenceRepo.require(
                                reference.firmwareByModelAndVersion(modelId, spec.firmwareTo()),
                                "FIRMWARE_NOT_FOUND", "Unknown firmware " + spec.firmwareTo()), "sequence");
                    }
                }
                catalog.insertCompat(entryId, modelId, spec.firmwareFrom(), spec.firmwareTo(), fromSeq, toSeq);
            }
        }
        return Map.of("id", entryId, "partNumber", req.partNumber(),
                "catalogVersion", req.catalogVersionLabel());
    }

    public List<CatalogDtos.PartEntry> entries(String partNumber) {
        UUID partId = catalogService.requirePart(partNumber);
        return catalog.entriesForPart(partId).stream()
                .map(e -> {
                    UUID entryId = ReferenceRepo.asUuid(e, "id");
                    List<CatalogDtos.Compat> compat = catalog.compatForEntry(entryId).stream()
                            .map(c -> new CatalogDtos.Compat(ReferenceRepo.asString(c, "manufacturer_code"),
                                    ReferenceRepo.asString(c, "model_code"),
                                    ReferenceRepo.asString(c, "firmware_from"),
                                    ReferenceRepo.asString(c, "firmware_to")))
                            .toList();
                    return new CatalogDtos.PartEntry(entryId,
                            ReferenceRepo.asString(e, "version_label"), partNumber,
                            ReferenceRepo.asString(e, "part_name"), ReferenceRepo.asString(e, "category"),
                            ReferenceRepo.asString(e, "description"),
                            Sql.readMap(e.get("attributes")), compat);
                })
                .toList();
    }

    public List<CatalogDtos.InventorySnapshot> snapshots() {
        List<CatalogDtos.InventorySnapshot> out = new ArrayList<>();
        for (Map<String, Object> s : catalog.listInventorySnapshots()) {
            UUID id = ReferenceRepo.asUuid(s, "id");
            UUID cvId = ReferenceRepo.asUuid(s, "catalog_version_id");
            String cvLabel = cvId == null ? null : catalog.catalogVersionById(cvId)
                    .map(r -> ReferenceRepo.asString(r, "version_label")).orElse(null);
            List<CatalogDtos.InventoryLine> lines = catalog.inventoryLevels(id).stream()
                    .map(l -> new CatalogDtos.InventoryLine(ReferenceRepo.asString(l, "part_number"),
                            ReferenceRepo.asString(l, "part_name"), ReferenceRepo.asString(l, "warehouse"),
                            ReferenceRepo.asInt(l, "qty_available")))
                    .toList();
            out.add(new CatalogDtos.InventorySnapshot(id, ReferenceRepo.asString(s, "label"),
                    ReferenceRepo.asInstant(s, "taken_at"), ReferenceRepo.asString(s, "source"),
                    cvLabel, lines));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createSnapshot(ApiRequests.InventorySnapshotCreate req) {
        UUID cvId = req.catalogVersionLabel() == null ? null
                : catalogService.requireCatalogVersion(req.catalogVersionLabel());
        try {
            UUID id = catalog.insertInventorySnapshot(req.label(),
                    req.takenAt() == null ? Instant.now() : req.takenAt(), req.source(), cvId);
            int lines = 0;
            if (req.lines() != null) {
                for (ApiRequests.InventoryLineSpec line : req.lines()) {
                    UUID partId = catalogService.requirePart(line.partNumber());
                    catalog.insertInventoryLevel(id, partId, line.warehouse(), line.qtyAvailable());
                    lines++;
                }
            }
            return Map.of("id", id, "label", req.label(), "lines", lines);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("INVENTORY_SNAPSHOT_EXISTS",
                    "Inventory snapshot " + req.label() + " already exists");
        }
    }

    @Transactional
    public Map<String, Object> createDisposition(ApiRequests.DispositionCreate req) {
        UUID id = catalogService.createDisposition(new CatalogService.NewDisposition(
                req.conceptCode(), req.catalogVersionLabel(), req.code(), req.action(), req.description(),
                req.partNumber(), req.quantity(), req.exclusiveGroup(), req.requiresStock(), req.minStock(),
                req.validFrom(), req.validTo(), req.approvedBy(), req.approvalRef()));
        return Map.of("id", id, "code", req.code(), "conceptCode", req.conceptCode());
    }

    public List<Map<String, Object>> dispositions(String label) {
        UUID cvId = catalogService.requireCatalogVersion(label);
        return catalog.dispositionsForCatalog(cvId).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>(d);
            return m;
        }).toList();
    }
}
