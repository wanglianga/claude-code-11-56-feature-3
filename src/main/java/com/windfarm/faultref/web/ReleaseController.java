package com.windfarm.faultref.web;

import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.ReleaseRepo;
import com.windfarm.faultref.repo.SubstitutionRepo;
import com.windfarm.faultref.service.ReleaseService;
import com.windfarm.faultref.web.dto.ApiRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/releases")
@Tag(name = "Releases", description = "Immutable release snapshots: publish, inspect and replay")
public class ReleaseController {

    private final ReleaseService releases;
    private final ReleaseRepo releaseRepo;
    private final SubstitutionRepo substitutionRepo;

    public ReleaseController(ReleaseService releases, ReleaseRepo releaseRepo,
                             SubstitutionRepo substitutionRepo) {
        this.releases = releases;
        this.releaseRepo = releaseRepo;
        this.substitutionRepo = substitutionRepo;
    }

    @Operation(summary = "Create a DRAFT release bound to a PUBLISHED catalog version and inventory snapshot")
    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody ApiRequests.ReleaseCreate req,
                                      Authentication auth) {
        return releases.createDraft(req.versionLabel(), req.effectiveFrom(), req.catalogVersionLabel(),
                req.inventorySnapshotLabel(), auth == null ? "system" : auth.getName(), req.notes());
    }

    @Operation(summary = "Publish a release: freeze codes, terms, concepts, firmware, catalog, stock and dispositions")
    @PostMapping("/{label}/publish")
    public Map<String, Object> publish(@PathVariable String label,
                                       @Valid @RequestBody ApiRequests.ReleasePublish req) {
        return releases.publish(label, req.approvedBy(), req.approvalRef());
    }

    @Operation(summary = "List releases")
    @GetMapping
    public List<Map<String, Object>> list() {
        return releaseRepo.listReleases();
    }

    @Operation(summary = "Get a release header")
    @GetMapping("/{label}")
    public Map<String, Object> get(@PathVariable String label) {
        return ReferenceRepo.require(releaseRepo.releaseByLabel(label), "RELEASE_NOT_FOUND",
                "Unknown release " + label);
    }

    @Operation(summary = "Inspect the frozen content of a published release")
    @GetMapping("/{label}/content")
    public Map<String, Object> content(@PathVariable String label) {
        Map<String, Object> rel = ReferenceRepo.require(releaseRepo.releaseByLabel(label),
                "RELEASE_NOT_FOUND", "Unknown release " + label);
        if (!"PUBLISHED".equals(ReferenceRepo.asString(rel, "status"))
                && !"RETIRED".equals(ReferenceRepo.asString(rel, "status"))) {
            throw com.windfarm.faultref.error.ApiException.conflict("RELEASE_NOT_PUBLISHED",
                    "Release " + label + " has not been published; content is not frozen");
        }
        UUID id = ReferenceRepo.asUuid(rel, "id");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("release", rel);
        out.put("sourceCodes", releaseRepo.sourceCodes(id));
        out.put("terms", releaseRepo.terms(id));
        out.put("concepts", releaseRepo.concepts(id));
        out.put("firmware", releaseRepo.firmware(id));
        out.put("codeEntries", releaseRepo.allCodeEntries(id));
        out.put("termEntries", releaseRepo.allTermEntries(id));
        out.put("parts", releaseRepo.parts(id));
        out.put("inventory", releaseRepo.inventory(id));
        out.put("dispositions", releaseRepo.dispositions(id));
        out.put("adjudications", releaseRepo.adjudications(id));
        out.put("substitutions", substitutionRepo.releaseSubstitutions(id));
        out.put("partLifecycle", substitutionRepo.releaseLifecycle(id));
        out.put("workOrderRecommendations", substitutionRepo.releaseRecommendations(id));
        out.put("stockContentions", substitutionRepo.releaseContentions(id));
        return out;
    }

    @Operation(summary = "Retire a release (still replayable; excluded from default selection)")
    @PostMapping("/{label}/retire")
    public Map<String, Object> retire(@PathVariable String label) {
        Map<String, Object> rel = ReferenceRepo.require(releaseRepo.releaseByLabel(label),
                "RELEASE_NOT_FOUND", "Unknown release " + label);
        releaseRepo.retire(ReferenceRepo.asUuid(rel, "id"));
        return Map.of("versionLabel", label, "status", "RETIRED");
    }
}
