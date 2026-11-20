package com.windfarm.faultref;

import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.service.SubstitutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import com.windfarm.faultref.web.dto.SubstitutionDtos;
import com.windfarm.faultref.web.dto.SubstitutionDtos.ConceptPartAdvice;
import com.windfarm.faultref.web.dto.SubstitutionDtos.PartAdvice;
import com.windfarm.faultref.web.dto.SubstitutionDtos.SubstitutionPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance scenarios for the spare-part substitution chain:
 * retired model, low-temperature-only alternative, interface retrofit,
 * missing temperature condition, substitution cycles and warehouse stock
 * contention, plus the immutable historical work-order recommendation.
 */
@TestPropertySource(properties = "app.security.enabled=false")
class SubstitutionScenarioTest extends IntegrationTestBase {

    @Autowired
    private TestFixtures fx;
    @Autowired
    private SubstitutionService substitutions;
    @Autowired
    private DiagnosisService diagnosis;

    private static final String CONCEPT = "GBX_REP";

    @BeforeEach
    void setUp() {
        fx.manufacturer("NW", "NordWind");
        fx.model("NW", "NW-8");
        fx.firmware("NW", "NW-8", "V1.0", 1);
        fx.unit("A01", "NW", "NW-8");
        fx.controller("NW_CTRL");
        fx.concept(CONCEPT, "Gearbox replacement", "DRIVETRAIN", "CRITICAL", "gearbox end of life");
        UUID code = fx.sourceCode("NW_CTRL", "GBX-1");
        fx.assertion(code, CONCEPT, 1, "NW", "NW-8", null, null, "MOC-1");

        for (String pn : List.of("GBX-100", "GBX-200", "GBX-300", "GBX-400", "GBX-500", "GBX-600")) {
            fx.part(pn, "GEARBOX");
        }
        fx.catalogVersion("CAT-X", "DRAFT", "2025-01-01T00:00:00Z");
        for (String pn : List.of("GBX-100", "GBX-200", "GBX-300", "GBX-400", "GBX-500", "GBX-600")) {
            fx.entry("CAT-X", pn);
        }
        fx.disposition(CONCEPT, "CAT-X", "GBX-OLD", "REPLACE", "GBX-100", 1, null, true, 1,
                "2025-01-01T00:00:00Z");
        fx.lifecycle("CAT-X", "GBX-100", "OBSOLETE", "factory retired", "GBX-200");

        fx.substitution("CAT-X", "GBX-100", "GBX-200", "CONDITIONAL", "NW-8",
                null, new BigDecimal("-10"), null, false);
        fx.substitution("CAT-X", "GBX-100", "GBX-300", "RETROFIT", "NW-8",
                null, null, "IF-B", true);
        fx.substitution("CAT-X", "GBX-100", "GBX-400", "DIRECT", "NW-8",
                null, null, null, false);
        fx.substitution("CAT-X", "GBX-400", "GBX-500", "DIRECT", "NW-8",
                null, null, null, false);
        fx.substitution("CAT-X", "GBX-500", "GBX-400", "DIRECT", "NW-8",
                null, null, null, false);
        fx.substitution("CAT-X", "GBX-100", "GBX-600", "DIRECT", "NW-8",
                null, null, null, false);
        fx.publishCatalog("CAT-X");

        fx.inventory("INV-X", "2025-06-01T00:00:00Z", "CAT-X", List.of(
                fx.line("GBX-100", "W1", 0),
                fx.line("GBX-200", "W1", 1),
                fx.line("GBX-300", "W1", 1),
                fx.line("GBX-400", "W1", 1),
                fx.line("GBX-500", "W1", 1),
                fx.line("GBX-600", "W1", 1)));
    }

    private ApiRequests.SubstitutionAdviceRequest request(BigDecimal temp, String iface, String inventory) {
        return new ApiRequests.SubstitutionAdviceRequest(CONCEPT, null, null, "A01",
                temp, iface, null, "CAT-X", inventory);
    }

    private PartAdvice oldPartAdvice(ConceptPartAdvice advice) {
        return advice.currentAdvice().stream()
                .filter(a -> "GBX-100".equals(a.partNumber())).findFirst().orElseThrow();
    }

    @Test
    void retiredModelKeepsObsoleteFactAndOffersColdAndRetrofitAlternatives() {
        ConceptPartAdvice cold = substitutions.evaluateRequest(
                request(new BigDecimal("-25"), "IF-A", "INV-X"));

        PartAdvice old = oldPartAdvice(cold);
        assertThat(old.lifecycleState()).isEqualTo("OBSOLETE");
        assertThat(old.obsoleteReason()).contains("factory retired");
        assertThat(old.availableQty()).isZero();

        Map<String, SubstitutionPath> byTerminal = new java.util.HashMap<>();
        old.paths().forEach(p -> byTerminal.put(p.terminalPartNumber(), p));
        assertThat(byTerminal.get("GBX-200").pathStatus()).isEqualTo("EXECUTABLE");
        assertThat(byTerminal.get("GBX-200").edgeChecks().get(0).checkStatus()).isEqualTo("PASSED");
        // The retrofit path is issuable only after engineering confirmation.
        assertThat(byTerminal.get("GBX-300").pathStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(byTerminal.get("GBX-300").pendingEngineeringConditions())
                .anyMatch(s -> s.contains("IF-B"));
        assertThat(byTerminal.get("GBX-600").pathStatus()).isEqualTo("EXECUTABLE");
    }

    @Test
    void warmEnvironmentRejectsLowTemperatureAlternative() {
        ConceptPartAdvice warm = substitutions.evaluateRequest(
                request(new BigDecimal("18"), "IF-A", "INV-X"));

        SubstitutionPath lowTemp = oldPartAdvice(warm).paths().stream()
                .filter(p -> "GBX-200".equals(p.terminalPartNumber())).findFirst().orElseThrow();
        assertThat(lowTemp.pathStatus()).isEqualTo("BLOCKED");
        assertThat(lowTemp.edgeChecks().get(0).failedChecks())
                .anyMatch(f -> f.contains("TEMP_ABOVE_MAXIMUM"));
        // The retrofit alternative remains the pending engineering route.
        SubstitutionPath retrofit = oldPartAdvice(warm).paths().stream()
                .filter(p -> "GBX-300".equals(p.terminalPartNumber())).findFirst().orElseThrow();
        assertThat(retrofit.pathStatus()).isEqualTo("PENDING_CONFIRM");
    }

    @Test
    void missingTemperatureIsAnExplicitBoundaryNotASilentGuess() {
        ConceptPartAdvice missing = substitutions.evaluateRequest(request(null, "IF-A", "INV-X"));

        SubstitutionPath lowTemp = oldPartAdvice(missing).paths().stream()
                .filter(p -> "GBX-200".equals(p.terminalPartNumber())).findFirst().orElseThrow();
        assertThat(lowTemp.pathStatus()).isEqualTo("BLOCKED");
        assertThat(lowTemp.edgeChecks().get(0).failedChecks())
                .containsExactly("AMBIENT_TEMP_MISSING");
        assertThat(missing.notSubstitutableReasons())
                .anyMatch(r -> r.contains("Ambient temperature is missing"));
    }

    @Test
    void substitutionCycleIsReturnedAsConflictPath() {
        ConceptPartAdvice advice = substitutions.evaluateRequest(
                request(new BigDecimal("-25"), "IF-A", "INV-X"));

        assertThat(advice.cycleConflicts()).isNotEmpty();
        SubstitutionPath cycle = advice.cycleConflicts().get(0);
        assertThat(cycle.pathStatus()).isEqualTo("CYCLE");
        assertThat(cycle.cyclePath()).contains("GBX-400", "GBX-500", "GBX-400");
        // Cycle nodes are marked so the conflict is visible on the path itself.
        assertThat(cycle.nodes()).anyMatch(SubstitutionDtos.PathNode::cycle);
    }

    @Test
    void pendingContentionOnlyFlagsAdviceButConfirmedAllocationRemovesStock() {
        // CLAIM_PENDING: the part stays technically in stock; advice flags the risk.
        fx.inventory("INV-PEND", "2025-06-02T00:00:00Z", "CAT-X", List.of(
                fx.line("GBX-100", "W1", 0), fx.line("GBX-600", "W1", 1)));
        fx.contention("INV-PEND", "GBX-600", "CLAIM_PENDING", "WO-X competing claim");
        ConceptPartAdvice pending = substitutions.evaluateRequest(
                request(new BigDecimal("5"), "IF-A", "INV-PEND"));
        SubstitutionPath pendingPath = oldPartAdvice(pending).paths().stream()
                .filter(p -> "GBX-600".equals(p.terminalPartNumber())).findFirst().orElseThrow();
        assertThat(pendingPath.pathStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(pendingPath.contentionStatus()).isEqualTo("CLAIM_PENDING");

        // CONFIRMED_ALLOCATED: the warehouse decision removes the stock; the path is blocked.
        fx.inventory("INV-GONE", "2025-06-03T00:00:00Z", "CAT-X", List.of(
                fx.line("GBX-100", "W1", 0), fx.line("GBX-600", "W1", 1)));
        fx.contention("INV-GONE", "GBX-600", "CONFIRMED_ALLOCATED", "WO-X competing claim");
        ConceptPartAdvice gone = substitutions.evaluateRequest(
                request(new BigDecimal("5"), "IF-A", "INV-GONE"));
        PartAdvice old = oldPartAdvice(gone);
        assertThat(old.stockStatus()).isEqualTo("OUT_OF_STOCK");
        SubstitutionPath gbx600 = old.paths().stream()
                .filter(p -> "GBX-600".equals(p.terminalPartNumber())).findFirst().orElseThrow();
        assertThat(gbx600.pathStatus()).isEqualTo("BLOCKED");
        assertThat(gbx600.contentionStatus()).isEqualTo("CONFIRMED_ALLOCATED");
        assertThat(gbx600.blockedReasons()).anyMatch(b -> b.contains("competing demand"));
        // The diagnosis never allocates stock itself: the blocked reason cites the warehouse decision.
        assertThat(gbx600.terminalInStock()).isFalse();
    }

    @Test
    void historicalWorkOrderKeepsItsOwnRecommendationSeparateFromCurrentAlternatives() {
        String wo = fx.workOrder("WO-HIST", "A01");
        fx.recommendation(wo, CONCEPT, "GBX-100", "CAT-X", "ISSUED");

        ApiRequests.SubstitutionAdviceRequest req =
                new ApiRequests.SubstitutionAdviceRequest(CONCEPT, wo, null, "A01",
                        new BigDecimal("-25"), "IF-A", null, "CAT-X", "INV-X");
        ConceptPartAdvice advice = substitutions.evaluateRequest(req);

        // The historical recommendation is immutable and returned as its own object.
        SubstitutionDtos.OriginalRecommendation original = advice.originalRecommendation();
        assertThat(original).isNotNull();
        assertThat(original.partNumber()).isEqualTo("GBX-100");
        assertThat(original.recommendationStatus()).isEqualTo("ISSUED");
        assertThat(original.catalogVersionLabel()).isEqualTo("CAT-X");
        // Today's issuable parts live in a separate object; nothing is backfilled.
        assertThat(advice.currentAdvice()).isNotEmpty();
        assertThat(advice.currentAdvice().stream().map(PartAdvice::partNumber))
                .contains("GBX-100");
        SubstitutionPath executable = oldPartAdvice(advice).paths().stream()
                .filter(p -> "EXECUTABLE".equals(p.pathStatus())).findFirst().orElseThrow();
        assertThat(executable.terminalPartNumber()).isNotEqualTo("GBX-100");

        // The stored row itself is untouched by the later catalog graph.
        assertThat(substitutions.recommendations()).hasSize(1);
        assertThat(substitutions.recommendations().get(0).partNumber()).isEqualTo("GBX-100");
    }

    @Test
    void frozenReleaseReplaysSubstitutionAdviceFromReleaseTablesOnly() {
        // A release freezes the graph; snapshot advice must read release_* rows.
        fx.publishRelease("REL-X", "2025-06-05T00:00:00Z", "CAT-X", "INV-X");
        fx.alarmWithAttributes("ALT-GBX", "A01", "NW_CTRL", "GBX-1",
                "2025-06-10T00:00:00Z", Map.of("ambientTempC", -25, "interfaceCode", "IF-A"));

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest(
                "SNAPSHOT", "RELEASE", null, "REL-X", null,
                null, null, null, false, null, null, null), "tester");
        UUID resultId = run.results().stream()
                .filter(r -> "ALT-GBX".equals(r.alarmExternalId())).findFirst().orElseThrow().id();

        List<ConceptPartAdvice> advices = substitutions.adviceForDiagnosisResult(
                resultId, null, null, null);
        assertThat(advices).hasSize(1);
        ConceptPartAdvice advice = advices.get(0);
        assertThat(advice.catalogVersionLabel()).isEqualTo("CAT-X");
        assertThat(advice.inventorySnapshotLabel()).isEqualTo("INV-X");
        PartAdvice old = oldPartAdvice(advice);
        assertThat(old.lifecycleState()).isEqualTo("OBSOLETE");
        SubstitutionPath cold = old.paths().stream()
                .filter(p -> "GBX-200".equals(p.terminalPartNumber())).findFirst().orElseThrow();
        assertThat(cold.pathStatus()).isEqualTo("EXECUTABLE");
        assertThat(advice.cycleConflicts()).isNotEmpty();
    }
}
