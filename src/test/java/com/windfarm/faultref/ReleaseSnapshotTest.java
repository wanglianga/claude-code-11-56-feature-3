package com.windfarm.faultref;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.ReleaseRepo;
import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * History model B: published releases are immutable snapshots. Replaying one
 * must be independent of later catalog edits, and the frozen entries must point
 * back to the original assertion and approval.
 */
@TestPropertySource(properties = "app.security.enabled=false")
class ReleaseSnapshotTest extends IntegrationTestBase {

    @Autowired
    private TestFixtures fx;
    @Autowired
    private DiagnosisService diagnosis;
    @Autowired
    private ReleaseRepo releaseRepo;
    @Autowired
    private CatalogRepo catalogRepo;

    @BeforeEach
    void setUp() {
        fx.manufacturer("NW", "NordWind");
        fx.model("NW", "NW-8");
        fx.firmware("NW", "NW-8", "V1.0", 1);
        fx.unit("A01", "NW", "NW-8");
        fx.install("A01", "V1.0", "2023-01-01T00:00:00Z", null);

        fx.controller("NW_CTRL");
        fx.concept("HYD_LOW", "Low pressure", "HYDRAULIC", "HIGH", "pressure below 120 bar");
        UUID code = fx.sourceCode("NW_CTRL", "HYD-201");
        fx.assertion(code, "HYD_LOW", 1, null, null, null, null, "MOC-1");

        fx.part("HF-900", "FILTER");
        fx.catalogVersion("CAT-1", "DRAFT", "2023-01-01T00:00:00Z");
        fx.entry("CAT-1", "HF-900");
        fx.disposition("HYD_LOW", "CAT-1", "FILTER", "REPLACE", "HF-900", 1, null, true, 1,
                "2023-01-01T00:00:00Z");
        publishCatalog("CAT-1");
        fx.inventory("INV-1", "2023-01-01T00:00:00Z", "CAT-1", List.of(fx.line("HF-900", "W1", 5)));

        fx.alarm("ALT-1", "A01", "NW_CTRL", "HYD-201", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");
        fx.publishRelease("REL-1", "2025-01-01T00:00:00Z", "CAT-1", "INV-1");
    }

    @Test
    void releaseFreezesContentAndRecordsApproval() {
        Map<String, Object> rel = ReferenceRepo.require(releaseRepo.releaseByLabel("REL-1"),
                "X", "missing");
        assertThat(ReferenceRepo.asString(rel, "status")).isEqualTo("PUBLISHED");
        assertThat(ReferenceRepo.asString(rel, "content_hash")).hasSize(64);
        assertThat(ReferenceRepo.asString(rel, "approval_ref")).isEqualTo("REL-APPROVE-REL-1");

        UUID releaseId = ReferenceRepo.asUuid(rel, "id");
        List<Map<String, Object>> entries = releaseRepo.codeEntries(releaseId, "NW_CTRL", "HYD-201");
        assertThat(entries).hasSize(1);
        Map<String, Object> entry = entries.get(0);
        assertThat(ReferenceRepo.asString(entry, "concept_code")).isEqualTo("HYD_LOW");
        assertThat(ReferenceRepo.asString(entry, "definition_text")).isEqualTo("pressure below 120 bar");
        assertThat(ReferenceRepo.asString(entry, "approval_ref")).isEqualTo("MOC-1");
        assertThat(ReferenceRepo.asUuid(entry, "assertion_id")).isNotNull();
    }

    @Test
    void snapshotReplayIsUnaffectedByLaterCatalogPublish() {
        DiagnosisDtos.Run before = diagnosis.run(snapshotRun("REL-1"), "tester");
        assertThat(before.results().get(0).status()).isEqualTo("RESOLVED");
        assertThat(before.results().get(0).primaryConceptCode()).isEqualTo("HYD_LOW");

        // Change the live world: new catalog, out-of-stock remedy.
        fx.catalogVersion("CAT-2", "DRAFT", "2026-01-01T00:00:00Z");
        fx.entry("CAT-2", "HF-900");
        fx.disposition("HYD_LOW", "CAT-2", "INSPECT-ONLY", "INSPECT", null, 1, null, false, 1,
                "2026-01-01T00:00:00Z");
        publishCatalog("CAT-2");
        fx.inventory("INV-2", "2026-01-01T00:00:00Z", "CAT-2", List.of(fx.line("HF-900", "W1", 0)));

        DiagnosisDtos.Run after = diagnosis.run(snapshotRun("REL-1"), "tester");
        assertThat(after.results().get(0).status()).isEqualTo("RESOLVED");
        assertThat(after.results().get(0).primaryConceptCode()).isEqualTo("HYD_LOW");
        // The release still pins its original catalog, not the newly published one.
        assertThat(after.pinnedCatalogVersionLabel()).isEqualTo("CAT-1");

        // A latest/assertion run against the live world does see the new catalog.
        DiagnosisDtos.Run live = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");
        assertThat(live.pinnedCatalogVersionLabel()).isEqualTo("CAT-2");
    }

    @Test
    void draftReleaseCannotBeReplayed() {
        fx.releaseService().createDraft("REL-DRAFT", Instant.parse("2025-01-01T00:00:00Z"),
                "CAT-1", "INV-1", "tester", null);
        assertThatThrownBy(() -> diagnosis.run(snapshotRun("REL-DRAFT"), "tester"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not PUBLISHED");
    }

    @Test
    void snapshotModeRequiresReleaseBasis() {
        assertThatThrownBy(() -> diagnosis.run(new DiagnosisDtos.RunRequest("SNAPSHOT", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SNAPSHOT");
    }

    @Test
    void contentHashIsDeterministicForIdenticalInputs() {
        fx.publishRelease("REL-2", "2025-01-01T00:00:00Z", "CAT-1", "INV-1");
        String h1 = ReferenceRepo.asString(ReferenceRepo.require(releaseRepo.releaseByLabel("REL-1"),
                "X", "missing"), "content_hash");
        String h2 = ReferenceRepo.asString(ReferenceRepo.require(releaseRepo.releaseByLabel("REL-2"),
                "X", "missing"), "content_hash");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void catalogEntryRejectedAfterPublish() {
        assertThatThrownBy(() -> fx.entry("CAT-1", "HF-900"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DRAFT");
    }

    private DiagnosisDtos.RunRequest snapshotRun(String releaseLabel) {
        return new DiagnosisDtos.RunRequest("SNAPSHOT", "RELEASE", null, releaseLabel, null,
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"),
                null, false, null, null, null);
    }

    private void publishCatalog(String label) {
        jdbc.getJdbcTemplate().update(
                "update catalog_version set status='PUBLISHED', published_at=now(), approved_by='approver' "
                        + "where version_label = ?", label);
    }
}
