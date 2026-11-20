package com.windfarm.faultref.web;

import com.windfarm.faultref.service.CatalogAdminService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.CatalogDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
@Tag(name = "Catalog", description = "Catalog versions, spare parts, compatibility, inventory snapshots and dispositions")
public class CatalogController {

    private final CatalogAdminService admin;

    public CatalogController(CatalogAdminService admin) {
        this.admin = admin;
    }

    @Operation(summary = "List catalog versions")
    @GetMapping("/versions")
    public List<CatalogDtos.CatalogVersion> versions() {
        return admin.versions();
    }

    @Operation(summary = "Create a catalog version (starts DRAFT)")
    @PostMapping("/versions")
    public Map<String, Object> createVersion(@Valid @RequestBody ApiRequests.CatalogVersionCreate req) {
        return admin.createVersion(req);
    }

    @Operation(summary = "Publish a DRAFT catalog version (freezes it from further edits)")
    @PostMapping("/versions/{label}/publish")
    public Map<String, Object> publish(@PathVariable String label,
                                       @Valid @RequestBody ApiRequests.CatalogPublish req) {
        return admin.publish(label, req);
    }

    @Operation(summary = "List spare parts")
    @GetMapping("/parts")
    public List<CatalogDtos.Part> parts() {
        return admin.parts();
    }

    @Operation(summary = "Create a spare part")
    @PostMapping("/parts")
    public Map<String, Object> createPart(@Valid @RequestBody ApiRequests.PartCreate req) {
        return admin.createPart(req);
    }

    @Operation(summary = "Add a part to a DRAFT catalog version with compatibility rules")
    @PostMapping("/entries")
    public Map<String, Object> addEntry(@Valid @RequestBody ApiRequests.CatalogEntryCreate req) {
        return admin.addEntry(req);
    }

    @Operation(summary = "List entries and compatibility for a part")
    @GetMapping("/parts/{partNumber}/entries")
    public List<CatalogDtos.PartEntry> entries(@PathVariable String partNumber) {
        return admin.entries(partNumber);
    }

    @Operation(summary = "List inventory snapshots")
    @GetMapping("/inventory-snapshots")
    public List<CatalogDtos.InventorySnapshot> snapshots() {
        return admin.snapshots();
    }

    @Operation(summary = "Create an inventory snapshot with per-warehouse quantities")
    @PostMapping("/inventory-snapshots")
    public Map<String, Object> createSnapshot(@Valid @RequestBody ApiRequests.InventorySnapshotCreate req) {
        return admin.createSnapshot(req);
    }

    @Operation(summary = "Add a disposition option to a DRAFT catalog version")
    @PostMapping("/dispositions")
    public Map<String, Object> createDisposition(@Valid @RequestBody ApiRequests.DispositionCreate req) {
        return admin.createDisposition(req);
    }

    @Operation(summary = "List dispositions defined in a catalog version")
    @GetMapping("/versions/{label}/dispositions")
    public List<Map<String, Object>> dispositions(@PathVariable String label) {
        return admin.dispositions(label);
    }
}
