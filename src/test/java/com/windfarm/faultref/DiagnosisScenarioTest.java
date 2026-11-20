package com.windfarm.faultref;

import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario tests for the reference-mapping and diagnosis invariants:
 * occurrence-time semantics, scope/firmware divergence, preserved conflicts,
 * adjudication, disposition boundaries and pinned catalog contexts.
 */
@TestPropertySource(properties = "app.security.enabled=false")
class DiagnosisScenarioTest extends IntegrationTestBase {

    @Autowired
    private TestFixtures fx;
    @Autowired
    private DiagnosisService diagnosis;

    private UUID hydLow;
    private UUID hydPump;

    @BeforeEach
    void setUp() {
        fx.manufacturer("NW", "NordWind");
        fx.model("NW", "NW-8");
        fx.firmware("NW", "NW-8", "V1.0", 1);
        fx.firmware("NW", "NW-8", "V2.1", 2);
        fx.unit("A01", "NW", "NW-8");
        fx.install("A01", "V1.0", "2023-01-01T00:00:00Z", "2025-03-20T00:00:00Z");
        fx.install("A01", "V2.1", "2025-03-20T00:00:00Z", null);

        fx.controller("NW_CTRL");
        fx.concept("HYD_LOW", "Low pressure", "HYDRAULIC", "HIGH", "pressure below 120 bar");
        fx.concept("HYD_PUMP", "Pump wear", "HYDRAULIC", "CRITICAL", "pump efficiency loss");

        UUID code = fx.sourceCode("NW_CTRL", "HYD-201");
        fx.assertion(code, "HYD_LOW", 1, null, null, null, null, "MOC-1");
        fx.assertion(code, "HYD_PUMP", 1, "NW", "NW-8", "V2.1", null, "MOC-2");

        fx.part("HF-900", "FILTER");
        fx.catalogVersion("CAT-A", "DRAFT", "2023-01-01T00:00:00Z");
        fx.entry("CAT-A", "HF-900");
        fx.disposition("HYD_LOW", "CAT-A", "FILTER", "REPLACE", "HF-900", 1, null, true, 1,
                "2023-01-01T00:00:00Z");
        fx.disposition("HYD_PUMP", "CAT-A", "PUMP", "INSPECT", null, 1, null, false, 1,
                "2023-01-01T00:00:00Z");
        // publish CAT-A
        jdbc.getJdbcTemplate().execute(
                "update catalog_version set status='PUBLISHED', published_at=now(), approved_by='approver' "
                        + "where version_label='CAT-A'");
        fx.inventory("INV-A", "2023-01-01T00:00:00Z", "CAT-A", List.of(fx.line("HF-900", "W1", 5)));
    }

    @Test
    void firmwareScopeChangesMeaningOfSameCode() {
        fx.alarm("ALT-NEW", "A01", "NW_CTRL", "HYD-201", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");
        fx.alarm("ALT-OLD", "A01", "NW_CTRL", "HYD-201", "2024-01-15T00:00:00Z",
                "2024-01-15T00:00:10Z", "REALTIME");

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");

        Map<String, String> byAlarm = new java.util.HashMap<>();
        run.results().forEach(r -> byAlarm.put(r.alarmExternalId(), r.primaryConceptCode()));
        assertThat(byAlarm.get("ALT-NEW")).isEqualTo("HYD_PUMP");
        assertThat(byAlarm.get("ALT-OLD")).isEqualTo("HYD_LOW");
    }

    @Test
    void offlineBackfillIsInterpretedAtOccurrenceNotReceipt() {
        // Occurred under V1.0; uploaded 15 months later, after V2.1 was installed.
        fx.alarm("ALT-BACKFILL", "A01", "NW_CTRL", "HYD-201", "2024-01-15T00:00:00Z",
                "2025-04-12T00:00:00Z", "OFFLINE_BACKFILL");

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");

        DiagnosisDtos.Result result = run.results().get(0);
        assertThat(result.ingestType()).isEqualTo("OFFLINE_BACKFILL");
        assertThat(result.primaryConceptCode()).isEqualTo("HYD_LOW");
    }

    @Test
    void lateReceiptAloneDoesNotChangeInterpretation() {
        fx.alarm("ALT-LATE", "A01", "NW_CTRL", "HYD-201", "2024-01-15T00:00:00Z",
                "2025-04-12T00:00:00Z", "REALTIME");

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");

        assertThat(run.results().get(0).primaryConceptCode()).isEqualTo("HYD_LOW");
    }

    @Test
    void contradictoryMappingsArePreservedAsConflict() {
        UUID code2 = fx.sourceCode("NW_CTRL", "GBX-1");
        fx.concept("GBX_VIB", "Gearbox vibration", "DRIVETRAIN", "HIGH", "gear mesh vibration");
        fx.concept("BLADE_IMB", "Blade imbalance", "ROTOR", "MEDIUM", "1P imbalance");
        fx.assertion(code2, "GBX_VIB", 1, null, null, null, null, "MOC-3");
        fx.assertion(code2, "BLADE_IMB", 1, null, null, null, null, "MOC-4");

        fx.alarm("ALT-CONFLICT", "A01", "NW_CTRL", "GBX-1", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");

        DiagnosisDtos.Result result = run.results().get(0);
        assertThat(result.status()).isEqualTo("CONFLICT");
        assertThat(result.primaryConceptCode()).isNull();
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings()).extracting(DiagnosisDtos.Finding::conceptCode)
                .containsExactlyInAnyOrder("GBX_VIB", "BLADE_IMB");
        // Both assertions remain on record (nothing was overwritten).
        Integer active = jdbc.queryForObject("select count(*) from mapping_assertion where state='ACTIVE'",
                Map.of(), Integer.class);
        assertThat(active).isEqualTo(4);
    }

    @Test
    void moreSpecificScopeSuppressesBroaderMapping() {
        fx.concept("HYD_OIL", "Oil temp", "HYDRAULIC", "MEDIUM", "oil too hot");
        UUID code = fx.sourceCode("NW_CTRL", "HYD-202");
        fx.assertion(code, "HYD_LOW", 1, null, null, null, null, "MOC-5");
        fx.assertion(code, "HYD_OIL", 1, "NW", "NW-8", null, null, "MOC-6");

        fx.alarm("ALT-SCOPE", "A01", "NW_CTRL", "HYD-202", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");
        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");
        assertThat(run.results().get(0).primaryConceptCode()).isEqualTo("HYD_OIL");
        assertThat(run.results().get(0).status()).isNotEqualTo("CONFLICT");
    }

    @Test
    void engineerAdjudicationResolvesConflictWithoutMutatingAssertions() {
        fx.termSystem("SVC");
        UUID term = fx.term("SVC", "vendor", "振动超限");
        fx.concept("GBX_VIB", "Gearbox vibration", "DRIVETRAIN", "HIGH", "gear mesh vibration");
        fx.concept("BLADE_IMB", "Blade imbalance", "ROTOR", "MEDIUM", "1P imbalance");
        fx.termAssertion(term, "GBX_VIB", 1, null, null, "MOC-7");
        fx.termAssertion(term, "BLADE_IMB", 1, null, null, "MOC-8");

        fx.termAlarm("ALT-TERM-1", "A01", "SVC", "vendor", "振动超限",
                "2025-04-01T00:00:00Z", "2025-04-01T00:00:10Z");

        DiagnosisDtos.Run before = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");
        assertThat(before.results().get(0).status()).isEqualTo("CONFLICT");

        fx.adjudicate(term, "GBX_VIB", "BLADE_IMB", "GBX_VIB", null, null, "gearbox fault confirmed");

        DiagnosisDtos.Run after = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");
        DiagnosisDtos.Result result = after.results().get(0);
        assertThat(result.primaryConceptCode()).isEqualTo("GBX_VIB");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.adjudicated()).isTrue();
        // Conflict is gone; the outcome is driven by dispositions, not by the mapping.
        assertThat(result.status()).isNotEqualTo("CONFLICT");
        // Assertions untouched: both still ACTIVE.
        Integer active = jdbc.queryForObject("""
                select count(*) from mapping_assertion where term_attachment_id = :t and state = 'ACTIVE'
                """, Map.of("t", term), Integer.class);
        assertThat(active).isEqualTo(2);
    }

    @Test
    void mutuallyExclusiveDispositionsStayPending() {
        fx.part("SK-1", "SEAL");
        fx.catalogVersion("CAT-B", "DRAFT", "2025-01-01T00:00:00Z");
        fx.entry("CAT-B", "HF-900");
        fx.entry("CAT-B", "SK-1");
        fx.disposition("HYD_LOW", "CAT-B", "FILTER", "REPLACE", "HF-900", 1, "remedy", true, 1,
                "2025-01-01T00:00:00Z");
        fx.disposition("HYD_LOW", "CAT-B", "SEAL", "REPAIR", "SK-1", 1, "remedy", true, 1,
                "2025-01-01T00:00:00Z");
        jdbc.getJdbcTemplate().execute(
                "update catalog_version set status='PUBLISHED', published_at=now(), approved_by='approver' "
                        + "where version_label='CAT-B'");
        fx.inventory("INV-B", "2025-01-01T00:00:00Z", "CAT-B",
                List.of(fx.line("HF-900", "W1", 5), fx.line("SK-1", "W1", 5)));

        // Occurred while V1.0 was installed (so the code maps to HYD_LOW) but after
        // both exclusive remedies became valid.
        fx.alarm("ALT-PENDING", "A01", "NW_CTRL", "HYD-201", "2025-02-01T00:00:00Z",
                "2025-02-01T00:00:10Z", "REALTIME");
        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, "CAT-B", "INV-B", null), "tester");

        DiagnosisDtos.Result result = run.results().get(0);
        assertThat(result.status()).isEqualTo("PENDING_ENGINEER");
        assertThat(result.findings().get(0).dispositionStatus()).isEqualTo("PENDING_ENGINEER");
    }

    @Test
    void insufficientStockMakesDispositionUnavailableNotSilentlyDropped() {
        fx.alarm("ALT-NOSTOCK", "A01", "NW_CTRL", "HYD-201", "2024-02-01T00:00:00Z",
                "2024-02-01T00:00:10Z", "REALTIME");
        // Inventory of 0 for the only remedy.
        fx.inventory("INV-EMPTY", "2023-02-01T00:00:00Z", "CAT-A", List.of(fx.line("HF-900", "W1", 0)));

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, "CAT-A", "INV-EMPTY", null), "tester");

        DiagnosisDtos.Result result = run.results().get(0);
        assertThat(result.status()).isEqualTo("NO_DISPOSITION");
        Map<String, Object> disposition = result.findings().get(0).disposition();
        assertThat(disposition.get("unavailable")).isNotNull();
        assertThat(disposition.get("status")).isEqualTo("NO_DISPOSITION");
    }

    @Test
    void runPinsCatalogSoMidRunUpdateCannotLeak() {
        fx.alarm("ALT-PIN", "A01", "NW_CTRL", "HYD-201", "2024-02-01T00:00:00Z",
                "2024-02-01T00:00:10Z", "REALTIME");
        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, "CAT-A", "INV-A", null), "tester");
        assertThat(run.pinnedCatalogVersionLabel()).isEqualTo("CAT-A");

        // A later catalog must not alter an already-completed run.
        fx.catalogVersion("CAT-Z", "PUBLISHED", "2026-01-01T00:00:00Z");
        DiagnosisDtos.Run reloaded = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, "CAT-A", "INV-A", null), "tester");
        assertThat(reloaded.pinnedCatalogVersionLabel()).isEqualTo("CAT-A");
        DiagnosisDtos.Run stored = reload(run.id());
        assertThat(stored.pinnedCatalogVersionLabel()).isEqualTo("CAT-A");
    }

    @Test
    void sameCodeDifferentModelResolvesDifferently() {
        fx.model("NW", "NW-9");
        fx.firmware("NW", "NW-9", "V2.1", 1);
        fx.unit("A09", "NW", "NW-9");
        fx.install("A09", "V2.1", "2023-01-01T00:00:00Z", null);

        UUID code = fx.sourceCode("NW_CTRL", "HYD-205");
        fx.assertion(code, "HYD_LOW", 1, null, null, null, null, "MOC-9");
        fx.assertion(code, "HYD_PUMP", 1, "NW", "NW-9", null, null, "MOC-10");

        fx.alarm("ALT-MODEL-A", "A01", "NW_CTRL", "HYD-205", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");
        fx.alarm("ALT-MODEL-B", "A09", "NW_CTRL", "HYD-205", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, null, null, null, false, null, null, null), "tester");
        Map<String, String> byAlarm = new java.util.HashMap<>();
        run.results().forEach(r -> byAlarm.put(r.alarmExternalId(), r.primaryConceptCode()));
        assertThat(byAlarm.get("ALT-MODEL-A")).isEqualTo("HYD_LOW");
        assertThat(byAlarm.get("ALT-MODEL-B")).isEqualTo("HYD_PUMP");
    }

    private DiagnosisDtos.Run reload(UUID id) {
        return diagnosis.getRun(id);
    }
}
