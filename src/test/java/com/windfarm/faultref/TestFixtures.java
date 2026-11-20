package com.windfarm.faultref;

import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.service.CatalogAdminService;
import com.windfarm.faultref.service.CatalogService;
import com.windfarm.faultref.service.EvidenceService;
import com.windfarm.faultref.service.MappingService;
import com.windfarm.faultref.service.ReferenceService;
import com.windfarm.faultref.service.ReleaseService;
import com.windfarm.faultref.service.SubstitutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small DSL used by tests to build a consistent wind-farm scenario. */
@Component
public class TestFixtures {

    @Autowired
    private ReferenceService referenceService;
    @Autowired
    private ReferenceRepo reference;
    @Autowired
    private MappingService mappingService;
    @Autowired
    private CatalogAdminService catalogAdmin;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private EvidenceService evidence;
    @Autowired
    private ReleaseService releases;
    @Autowired
    private SubstitutionService substitutions;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @PostConstruct
    void assertWiring() {
        if (referenceService == null) {
            throw new IllegalStateException("fixtures not wired");
        }
    }

    public void manufacturer(String code, String name) {
        referenceService.createManufacturer(new ApiRequests.ManufacturerCreate(code, name));
    }

    public void model(String manufacturer, String code) {
        referenceService.createModel(new ApiRequests.ModelCreate(manufacturer, code, code + " model"));
    }

    public void firmware(String manufacturer, String model, String version, int seq) {
        referenceService.createFirmware(new ApiRequests.FirmwareCreate(manufacturer, model, version, seq,
                LocalDate.of(2024, 1, 1), null));
    }

    public void unit(String code, String manufacturer, String model) {
        referenceService.createUnit(new ApiRequests.UnitCreate(code, code, manufacturer, model,
                LocalDate.of(2024, 1, 1)));
    }

    public void install(String unit, String version, String from, String to) {
        referenceService.installFirmware(unit, new ApiRequests.FirmwareInstall(unit, version,
                Instant.parse(from), to == null ? null : Instant.parse(to)));
    }

    public void controller(String systemCode) {
        referenceService.createSourceSystem(new ApiRequests.SourceSystemCreate(systemCode, systemCode,
                "CONTROLLER", null));
    }

    public void termSystem(String systemCode) {
        referenceService.createSourceSystem(new ApiRequests.SourceSystemCreate(systemCode, systemCode,
                "VENDOR_TERM", null));
    }

    public UUID sourceCode(String system, String code) {
        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate(system, code, code + " raw", null));
        return ReferenceRepo.asUuid(ReferenceRepo.require(reference.sourceCodeBySystemAndCode(system, code),
                "X", "missing"), "id");
    }

    public UUID term(String system, String vendor, String text) {
        referenceService.createTerm(new ApiRequests.TermCreate(system, vendor, text, "zh-CN", null, null));
        return ReferenceRepo.asUuid(ReferenceRepo.require(reference.termByVendorAndText(vendor, text),
                "X", "missing"), "id");
    }

    public void concept(String code, String name, String category, String severity, String definition) {
        referenceService.createConcept(new ApiRequests.ConceptCreate(code, name, category, severity,
                definition, "approver", "MOC-" + code));
    }

    public UUID assertion(UUID codeId, String concept, int defVersion, String manufacturer, String model,
                          String fwFrom, String fwTo, String approvalRef) {
        return mappingService.createAssertion(new MappingService.CreateAssertion(codeId, null, concept,
                defVersion, manufacturer, model, fwFrom, fwTo, Map.of(), null, "ACTIVE",
                Instant.parse("2023-01-01T00:00:00Z"), null, null, approvalRef, "approver", null));
    }

    public UUID termAssertion(UUID termId, String concept, int defVersion, String manufacturer, String model,
                              String approvalRef) {
        return mappingService.createAssertion(new MappingService.CreateAssertion(null, termId, concept,
                defVersion, manufacturer, model, null, null, Map.of(), null, "ACTIVE",
                Instant.parse("2023-01-01T00:00:00Z"), null, null, approvalRef, "approver", null));
    }

    public void adjudicate(UUID termId, String a, String b, String chosen, String manufacturer,
                           String model, String decision) {
        mappingService.adjudicate(new MappingService.Adjudicate(null, termId, a, b, chosen, model,
                manufacturer, Instant.parse("2025-01-01T00:00:00Z"), null, decision, "engineer",
                "ADJ-" + chosen));
    }

    public void part(String partNumber, String category) {
        catalogAdmin.createPart(new ApiRequests.PartCreate(partNumber, partNumber, category, null));
    }

    public void catalogVersion(String label, String status, String effectiveFrom) {
        catalogAdmin.createVersion(new ApiRequests.CatalogVersionCreate(label, status,
                Instant.parse(effectiveFrom), "PUBLISHED".equals(status) ? "approver" : null,
                "CAT-APPROVE", null));
    }

    public void entry(String catalogLabel, String partNumber) {
        catalogAdmin.addEntry(new ApiRequests.CatalogEntryCreate(catalogLabel, partNumber, null,
                Map.of(), List.of()));
    }

    public void disposition(String concept, String catalogLabel, String code, String action,
                            String partNumber, Integer qty, String group, boolean requiresStock,
                            int minStock, String validFrom) {
        catalogService.createDisposition(new CatalogService.NewDisposition(concept, catalogLabel, code,
                action, code + " description", partNumber, qty, group, requiresStock, minStock,
                Instant.parse(validFrom), null, "approver", "MOC-" + code));
    }

    public void inventory(String label, String takenAt, String catalogLabel, List<ApiRequests.InventoryLineSpec> lines) {
        catalogAdmin.createSnapshot(new ApiRequests.InventorySnapshotCreate(label, Instant.parse(takenAt),
                "test", catalogLabel, lines));
    }

    public ApiRequests.InventoryLineSpec line(String part, String warehouse, int qty) {
        return new ApiRequests.InventoryLineSpec(part, warehouse, qty);
    }

    public Map<String, Object> alarm(String externalId, String unit, String system, String code,
                                     String occurredAt, String receivedAt, String ingestType) {
        return evidence.ingestAlarm(new EvidenceService.NewAlarm(externalId, unit, system, code,
                null, null, null, Instant.parse(occurredAt), Instant.parse(receivedAt), ingestType,
                Map.of(), null));
    }

    public Map<String, Object> termAlarm(String externalId, String unit, String system, String vendor,
                                         String termText, String occurredAt, String receivedAt) {
        return evidence.ingestAlarm(new EvidenceService.NewAlarm(externalId, unit, system, null,
                vendor, termText, null, Instant.parse(occurredAt), Instant.parse(receivedAt),
                "REALTIME", Map.of(), null));
    }

    public void publishRelease(String label, String effectiveFrom, String catalogLabel, String inventoryLabel) {
        releases.createDraft(label, Instant.parse(effectiveFrom), catalogLabel, inventoryLabel, "test", null);
        releases.publish(label, "approver", "REL-APPROVE-" + label);
    }

    public ReleaseService releaseService() {
        return releases;
    }

    // ---- substitution graph fixtures -----------------------------------

    public void publishCatalog(String label) {
        jdbc.getJdbcTemplate().execute(
                "update catalog_version set status='PUBLISHED', published_at=now(), approved_by='approver' "
                        + "where version_label='" + label + "'");
    }

    public void lifecycle(String catalogLabel, String partNumber, String state, String reason,
                          String successor) {
        substitutions.createLifecycle(new ApiRequests.PartLifecycleCreate(catalogLabel, partNumber,
                state, reason, successor, "approver", "MOC-LC-" + partNumber));
    }

    public java.util.UUID substitution(String catalogLabel, String from, String to, String kind,
                                       String modelCode, java.math.BigDecimal tempMin,
                                       java.math.BigDecimal tempMax, String interfaceRequirement,
                                       boolean confirm) {
        Map<String, Object> out = substitutions.createSubstitution(new ApiRequests.SubstitutionCreate(
                catalogLabel, from, to, kind, modelCode, tempMin, tempMax, interfaceRequirement,
                confirm, Instant.parse("2025-01-01T00:00:00Z"), null,
                "MOC-SUB-" + from + "-" + to, "approver", null));
        return (java.util.UUID) out.get("id");
    }

    public void contention(String inventoryLabel, String part, String status, String demand) {
        substitutions.recordContention(new ApiRequests.StockContentionCreate(inventoryLabel, part,
                "W1", status, demand, "warehouse", null), "tester");
    }

    public String workOrder(String externalId, String unitCode) {
        evidence.ingestWorkOrder(new EvidenceService.NewWorkOrder(externalId, unitCode, "vendor",
                "term", null, Instant.parse("2023-05-10T00:00:00Z"),
                Instant.parse("2023-05-15T00:00:00Z"), "CLOSED", "symptom", Map.of()));
        return externalId;
    }

    public void recommendation(String workOrderExternalId, String concept, String part,
                               String catalogLabel, String status) {
        substitutions.recordRecommendation(new ApiRequests.WorkOrderRecommendationCreate(
                workOrderExternalId, concept, part, "DISP-" + part, catalogLabel, 1,
                status, "fixture reason", "tester", "MOC-REC-" + part), "tester");
    }

    public Map<String, Object> alarmWithAttributes(String externalId, String unit, String system, String code,
                                                   String occurredAt, Map<String, Object> attributes) {
        return evidence.ingestAlarm(new EvidenceService.NewAlarm(externalId, unit, system, code,
                null, null, null, Instant.parse(occurredAt), Instant.parse(occurredAt).plusSeconds(10),
                "REALTIME", attributes, null));
    }

    public SubstitutionService substitutionService() {
        return substitutions;
    }
}
