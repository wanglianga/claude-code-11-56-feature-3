package com.windfarm.faultref.seed;

import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.service.CatalogAdminService;
import com.windfarm.faultref.service.CatalogService;
import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.service.EvidenceService;
import com.windfarm.faultref.service.MappingService;
import com.windfarm.faultref.service.ReferenceService;
import com.windfarm.faultref.service.ReleaseService;
import com.windfarm.faultref.service.SubstitutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reproducible demonstration dataset.
 *
 * Narrative:
 *  - Two manufacturers/models with different controller code tables for the same
 *    hydraulic fault, and a firmware V2.1 split where the same source code starts
 *    meaning pump wear instead of low pressure.
 *  - A vendor "振动超限" term that conflates gearbox vibration and blade imbalance;
 *    both mappings stay ACTIVE and produce a preserved conflict until an engineer
 *    adjudicates it for one model only.
 *  - One source code (HAIXIN E5510) receives contradictory mappings: preserved.
 *  - An offline-backfilled 2024 alarm whose occurrence-time firmware differs from
 *    the firmware installed when it was finally uploaded.
 *  - Two catalog generations plus stock snapshots where the same concept yields
 *    filter replacement at one time, a mutually exclusive choice at another, and
 *    no available part at a third.
 *
 * Runs are seeded so the read APIs are immediately explorable.
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ReferenceRepo reference;
    private final ReferenceService referenceService;
    private final MappingService mappingService;
    private final CatalogRepo catalog;
    private final CatalogService catalogService;
    private final CatalogAdminService catalogAdmin;
    private final EvidenceService evidence;
    private final ReleaseService releases;
    private final DiagnosisService diagnosis;
    private final SubstitutionService substitutions;

    public DemoDataSeeder(NamedParameterJdbcTemplate jdbc, ReferenceRepo reference,
                          ReferenceService referenceService, MappingService mappingService,
                          CatalogRepo catalog, CatalogService catalogService,
                          CatalogAdminService catalogAdmin, EvidenceService evidence,
                          ReleaseService releases, DiagnosisService diagnosis,
                          SubstitutionService substitutions) {
        this.jdbc = jdbc;
        this.reference = reference;
        this.referenceService = referenceService;
        this.mappingService = mappingService;
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.catalogAdmin = catalogAdmin;
        this.evidence = evidence;
        this.releases = releases;
        this.diagnosis = diagnosis;
        this.substitutions = substitutions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("select count(*) from manufacturer", Map.of(), Integer.class);
        if (existing != null && existing > 0) {
            log.info("Demo seed skipped: reference data already present");
            return;
        }
        log.info("Seeding offshore wind fault-reference demonstration dataset");
        try {
            seed();
            log.info("Demo seed complete");
        } catch (RuntimeException e) {
            log.error("Demo seed failed", e);
            throw e;
        }
    }

    private void seed() {
        // ---------------------------------------------------------------
        // Manufacturers, models and firmware timeline
        // ---------------------------------------------------------------
        referenceService.createManufacturer(new ApiRequests.ManufacturerCreate("NORDWIND", "北欧风能"));
        referenceService.createManufacturer(new ApiRequests.ManufacturerCreate("HAIXIN", "海鑫重工"));

        referenceService.createModel(new ApiRequests.ModelCreate("NORDWIND", "NW-8", "NordWind 8MW 海上机组"));
        referenceService.createModel(new ApiRequests.ModelCreate("HAIXIN", "HX-6", "海鑫 6MW 海上机组"));

        // NW-8: V1.0 -> V2.0 -> V2.1 (V2.1 splits hydraulic codes)
        referenceService.createFirmware(new ApiRequests.FirmwareCreate("NORDWIND", "NW-8", "V1.0", 1,
                LocalDate.of(2023, 5, 1), "Initial release"));
        referenceService.createFirmware(new ApiRequests.FirmwareCreate("NORDWIND", "NW-8", "V2.0", 2,
                LocalDate.of(2024, 6, 15), "Control tuning"));
        referenceService.createFirmware(new ApiRequests.FirmwareCreate("NORDWIND", "NW-8", "V2.1", 3,
                LocalDate.of(2025, 3, 20), "Hydraulic diagnostics split: HYD-201 re-scoped to pump wear"));
        // HX-6: 3.1 -> 3.2
        referenceService.createFirmware(new ApiRequests.FirmwareCreate("HAIXIN", "HX-6", "3.1", 1,
                LocalDate.of(2023, 1, 10), "Initial release"));
        referenceService.createFirmware(new ApiRequests.FirmwareCreate("HAIXIN", "HX-6", "3.2", 2,
                LocalDate.of(2024, 9, 10), "Vibration sensing update"));

        // ---------------------------------------------------------------
        // Units and firmware install windows
        // ---------------------------------------------------------------
        referenceService.createUnit(new ApiRequests.UnitCreate("NW-A01", "北区 A01", "NORDWIND", "NW-8",
                LocalDate.of(2023, 5, 20)));
        referenceService.createUnit(new ApiRequests.UnitCreate("NW-A02", "北区 A02", "NORDWIND", "NW-8",
                LocalDate.of(2023, 5, 24)));
        referenceService.createUnit(new ApiRequests.UnitCreate("HX-B01", "东区 B01", "HAIXIN", "HX-6",
                LocalDate.of(2023, 1, 15)));
        referenceService.createUnit(new ApiRequests.UnitCreate("HX-B02", "东区 B02", "HAIXIN", "HX-6",
                LocalDate.of(2023, 1, 18)));

        // NW-A01 upgraded through V2.1; NW-A02 stops at V2.0.
        referenceService.installFirmware("NW-A01", new ApiRequests.FirmwareInstall("NW-A01", "V1.0",
                Instant.parse("2023-05-20T00:00:00Z"), Instant.parse("2024-06-15T00:00:00Z")));
        referenceService.installFirmware("NW-A01", new ApiRequests.FirmwareInstall("NW-A01", "V2.0",
                Instant.parse("2024-06-15T00:00:00Z"), Instant.parse("2025-03-20T00:00:00Z")));
        referenceService.installFirmware("NW-A01", new ApiRequests.FirmwareInstall("NW-A01", "V2.1",
                Instant.parse("2025-03-20T00:00:00Z"), null));

        referenceService.installFirmware("NW-A02", new ApiRequests.FirmwareInstall("NW-A02", "V1.0",
                Instant.parse("2023-05-24T00:00:00Z"), Instant.parse("2024-06-15T00:00:00Z")));
        referenceService.installFirmware("NW-A02", new ApiRequests.FirmwareInstall("NW-A02", "V2.0",
                Instant.parse("2024-06-15T00:00:00Z"), null));

        referenceService.installFirmware("HX-B01", new ApiRequests.FirmwareInstall("HX-B01", "3.1",
                Instant.parse("2023-01-15T00:00:00Z"), Instant.parse("2024-09-10T00:00:00Z")));
        referenceService.installFirmware("HX-B01", new ApiRequests.FirmwareInstall("HX-B01", "3.2",
                Instant.parse("2024-09-10T00:00:00Z"), null));

        referenceService.installFirmware("HX-B02", new ApiRequests.FirmwareInstall("HX-B02", "3.1",
                Instant.parse("2023-01-18T00:00:00Z"), null));

        // ---------------------------------------------------------------
        // Source systems and codes
        // ---------------------------------------------------------------
        referenceService.createSourceSystem(new ApiRequests.SourceSystemCreate("NORDWIND_CTRL",
                "NordWind 控制器码表", "CONTROLLER", "Controller 1.4 code table"));
        referenceService.createSourceSystem(new ApiRequests.SourceSystemCreate("HAIXIN_CTRL",
                "海鑫控制器码表", "CONTROLLER", "HX controller code table"));
        referenceService.createSourceSystem(new ApiRequests.SourceSystemCreate("SERVICE_TICKET",
                "维保商工单术语", "VENDOR_TERM", "Vendor natural-language ticket terms"));

        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("NORDWIND_CTRL", "HYD-201",
                "Hydraulic pressure low (main circuit)", "主液压回路压力低"));
        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("NORDWIND_CTRL", "HYD-202",
                "Hydraulic oil temperature high", "液压油温高"));
        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("NORDWIND_CTRL", "GBX-110",
                "Gearbox vibration over limit", "齿轮箱振动超限"));

        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("HAIXIN_CTRL", "E4410",
                "Hydraulic pressure below threshold", "液压压力低于阈值"));
        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("HAIXIN_CTRL", "E4420",
                "Hydraulic oil temperature high", "液压油温高"));
        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("HAIXIN_CTRL", "E5510",
                "Vibration alarm (drivetrain)", "传动链振动报警"));
        referenceService.createSourceCode(new ApiRequests.SourceCodeCreate("NORDWIND_CTRL", "GBX-REP-1",
                "Gearbox replacement criterion reached", "齿轮箱达到更换判据"));

        referenceService.createTerm(new ApiRequests.TermCreate("SERVICE_TICKET", "远海运维", "振动超限",
                "zh-CN", "attachment://vendor-term/vibration", "Vendor term conflates gearbox and blade issues"));
        referenceService.createTerm(new ApiRequests.TermCreate("SERVICE_TICKET", "远海运维", "液压压力低",
                "zh-CN", "attachment://vendor-term/hydraulic-low", "Vendor term for low hydraulic pressure"));

        // ---------------------------------------------------------------
        // Unified concepts (+ a revised definition for gearbox vibration)
        // ---------------------------------------------------------------
        createConcept("HYD_LOW_PRESSURE", "液压系统压力低", "HYDRAULIC", "HIGH",
                "主液压回路压力持续低于 120 bar 超过 30 秒，触发变桨系统可用性下降。",
                "张维", "MOC-2023-001", "2023-01-05T00:00:00Z");
        createConcept("HYD_OIL_TEMP_HIGH", "液压油温高", "HYDRAULIC", "MEDIUM",
                "液压油温超过 65°C，需检查冷却回路与油质。",
                "张维", "MOC-2023-002", "2023-01-05T00:00:00Z");
        createConcept("HYD_PUMP_WEAR", "液压泵磨损", "HYDRAULIC", "CRITICAL",
                "主液压泵容积效率下降，压力建立缓慢；V2.1 固件将原 HYD-201 细化为此故障。",
                "李工", "MOC-2025-014", "2025-03-18T00:00:00Z");
        createConcept("HYDRAULIC_LEAK", "液压系统泄漏", "HYDRAULIC", "HIGH",
                "液压回路存在可见泄漏，压力无法保持。",
                "张维", "MOC-2023-003", "2023-01-05T00:00:00Z");
        createConcept("GEARBOX_VIBRATION_HIGH", "齿轮箱振动超限", "DRIVETRAIN", "HIGH",
                "齿轮箱径向振动速度有效值超过 4.5 mm/s。",
                "王磊", "MOC-2023-010", "2023-02-01T00:00:00Z");
        createConcept("BLADE_IMBALANCE", "叶片不平衡", "ROTOR", "MEDIUM",
                "叶片质量或气动不平衡导致 1P 振动分量超限。",
                "王磊", "MOC-2023-011", "2023-02-01T00:00:00Z");
        createConcept("GEARBOX_REPLACEMENT", "齿轮箱总成更换", "DRIVETRAIN", "CRITICAL",
                "齿轮箱达到报废判据（轴承点蚀、齿面剥落或润滑失效），需整体更换齿轮箱总成。",
                "王磊", "MOC-2023-012", "2023-02-01T00:00:00Z");

        // Revise the gearbox definition; earlier assertions keep binding v1.
        referenceService.addDefinition("GEARBOX_VIBRATION_HIGH",
                new ApiRequests.ConceptDefinitionCreate(
                        "齿轮箱径向振动速度有效值超过 4.0 mm/s 持续 10 分钟，或出现轴承特征频率边带。",
                        "王磊", "MOC-2025-006"));

        // ---------------------------------------------------------------
        // Mapping assertions (time-valid; conflicts preserved)
        // ---------------------------------------------------------------
        UUID nwHyd201 = sourceCodeId("NORDWIND_CTRL", "HYD-201");
        UUID nwHyd202 = sourceCodeId("NORDWIND_CTRL", "HYD-202");
        UUID nwGbx110 = sourceCodeId("NORDWIND_CTRL", "GBX-110");
        UUID hxE4410 = sourceCodeId("HAIXIN_CTRL", "E4410");
        UUID hxE4420 = sourceCodeId("HAIXIN_CTRL", "E4420");
        UUID hxE5510 = sourceCodeId("HAIXIN_CTRL", "E5510");
        UUID termVibration = termId("远海运维", "振动超限");
        UUID termHydraulic = termId("远海运维", "液压压力低");

        // Global, long-lived mappings (approved 2023)
        assertCode(nwHyd201, "HYD_LOW_PRESSURE", 1, null, null, null, null,
                "MOC-2023-001", "张维", "2023-01-05T00:00:00Z");
        assertCode(nwHyd202, "HYD_OIL_TEMP_HIGH", 1, null, null, null, null,
                "MOC-2023-002", "张维", "2023-01-05T00:00:00Z");
        assertCode(nwGbx110, "GEARBOX_VIBRATION_HIGH", 1, null, null, null, null,
                "MOC-2023-010", "王磊", "2023-02-01T00:00:00Z");
        UUID nwgGbxRep = sourceCodeId("NORDWIND_CTRL", "GBX-REP-1");
        assertCode(nwgGbxRep, "GEARBOX_REPLACEMENT", 1, "NORDWIND", "NW-8", null, null,
                "MOC-2023-015", "王磊", "2023-02-01T00:00:00Z");
        assertCode(hxE4410, "HYD_LOW_PRESSURE", 1, null, null, null, null,
                "MOC-2023-020", "陈工", "2023-01-12T00:00:00Z");
        assertCode(hxE4420, "HYD_OIL_TEMP_HIGH", 1, null, null, null, null,
                "MOC-2023-021", "陈工", "2023-01-12T00:00:00Z");

        // Firmware-scoped divergence: NW-8 from V2.1 re-scopes HYD-201 to pump wear.
        assertCode(nwHyd201, "HYD_PUMP_WEAR", 1, "NORDWIND", "NW-8", "V2.1", null,
                "MOC-2025-014", "李工", "2025-03-20T00:00:00Z");

        // Contradictory mappings for the same code: both ACTIVE, preserved as conflict.
        assertCode(hxE5510, "GEARBOX_VIBRATION_HIGH", 2, null, null, null, null,
                "MOC-2023-030", "陈工", "2023-02-10T00:00:00Z");
        assertCode(hxE5510, "BLADE_IMBALANCE", 1, null, null, null, null,
                "MOC-2025-021", "周工", "2025-02-01T00:00:00Z");

        // Vendor term conflating gearbox vibration and blade imbalance: preserved conflict.
        assertTerm(termVibration, "GEARBOX_VIBRATION_HIGH", 1, null, null, null, null,
                "MOC-2023-040", "王磊", "2023-02-15T00:00:00Z");
        assertTerm(termVibration, "BLADE_IMBALANCE", 1, null, null, null, null,
                "MOC-2023-041", "王磊", "2023-02-15T00:00:00Z");
        assertTerm(termHydraulic, "HYD_LOW_PRESSURE", 1, null, null, null, null,
                "MOC-2023-042", "张维", "2023-02-15T00:00:00Z");

        // Engineer adjudicates the vibration term for NW-8 only (HX-6 remains a conflict).
        mappingService.adjudicate(new MappingService.Adjudicate(null, termVibration,
                "GEARBOX_VIBRATION_HIGH", "BLADE_IMBALANCE", "GEARBOX_VIBRATION_HIGH",
                "NW-8", "NORDWIND", Instant.parse("2025-01-01T00:00:00Z"), null,
                "NW-8 机型的 1P 振动特征与齿轮箱侧一致；叶片不平衡由独立叶片监测覆盖。",
                "王磊", "ADJ-2025-003"));

        // ---------------------------------------------------------------
        // Spare parts, catalogs, inventory
        // ---------------------------------------------------------------
        catalogAdmin.createPart(new ApiRequests.PartCreate("HF-900", "主液压滤芯", "FILTER", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("SK-220", "液压密封组件", "SEAL", "通用"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("PUMP-77", "主液压泵", "PUMP", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("PUMP-99", "通用液压泵", "PUMP", "海鑫"));
        // Gearbox family for the substitution-chain scenario: GBX-100 is the retired
        // model; GBX-200 is the low-temperature-only alternative; GBX-300 needs an
        // interface retrofit; GBX-400/GBX-500 form a catalog cycle; GBX-600 is the
        // last remaining unit subject to warehouse stock contention.
        catalogAdmin.createPart(new ApiRequests.PartCreate("GBX-100", "齿轮箱总成（旧型号）", "GEARBOX", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("GBX-200", "低温型齿轮箱总成", "GEARBOX", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("GBX-300", "改装接口齿轮箱总成", "GEARBOX", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("GBX-400", "齿轮箱替代件 400", "GEARBOX", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("GBX-500", "齿轮箱替代件 500", "GEARBOX", "NordWind"));
        catalogAdmin.createPart(new ApiRequests.PartCreate("GBX-600", "常温通用齿轮箱总成", "GEARBOX", "NordWind"));

        // Catalog 2023.01 -- only the filter remedy existed.
        catalogAdmin.createVersion(new ApiRequests.CatalogVersionCreate("CAT-2023.01", "DRAFT",
                Instant.parse("2023-01-01T00:00:00Z"), null, null, "Initial catalog"));
        addEntry("CAT-2023.01", "HF-900", "滤芯兼容所有机型", Map.of("micron", 10),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null),
                        new ApiRequests.CompatSpec("HAIXIN", "HX-6", null, null)));
        addEntry("CAT-2023.01", "SK-220", "密封组件", Map.of("material", "FKM"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, "V2.0")));
        addEntry("CAT-2023.01", "GBX-100", "齿轮箱总成（当时唯一在役型号）", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        disposition("HYD_LOW_PRESSURE", "CAT-2023.01", "HYD-FILTER-REPLACE", "REPLACE",
                "更换主液压滤芯并排气", "HF-900", 2, null, true, 2,
                "2023-01-01T00:00:00Z", "MOC-2023-050", "周工");
        disposition("GEARBOX_VIBRATION_HIGH", "CAT-2023.01", "GBX-INSPECT", "INSPECT",
                "停机检查齿轮箱轴承与润滑", null, 1, null, false, 1,
                "2023-01-01T00:00:00Z", "MOC-2023-051", "王磊");
        disposition("GEARBOX_REPLACEMENT", "CAT-2023.01", "GBX-REPLACE-OLD", "REPLACE",
                "整体更换齿轮箱总成（旧型号 GBX-100）", "GBX-100", 1, null, true, 1,
                "2023-01-01T00:00:00Z", "MOC-2023-052", "王磊");
        catalogAdmin.publish("CAT-2023.01", new ApiRequests.CatalogPublish("周工", "CAT-APPROVE-2023"));

        // Catalog 2025.01 -- filter and seal are alternative remedies (mutually exclusive).
        catalogAdmin.createVersion(new ApiRequests.CatalogVersionCreate("CAT-2025.01", "DRAFT",
                Instant.parse("2025-01-01T00:00:00Z"), null, null, "Adds seal and pump remedies"));
        addEntry("CAT-2025.01", "HF-900", "滤芯（10 微米）", Map.of("micron", 10),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null),
                        new ApiRequests.CompatSpec("HAIXIN", "HX-6", null, null)));
        addEntry("CAT-2025.01", "SK-220", "密封组件（V2.0+）", Map.of("material", "FKM"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", "V2.0", null),
                        new ApiRequests.CompatSpec("HAIXIN", "HX-6", "3.2", null)));
        addEntry("CAT-2025.01", "PUMP-77", "主液压泵总成", Map.of("flow", "45L/min"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", "V2.1", null)));
        addEntry("CAT-2025.01", "GBX-100", "齿轮箱总成（旧型号，仍在役）", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        addEntry("CAT-2025.01", "GBX-200", "低温型齿轮箱总成", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        disposition("HYD_LOW_PRESSURE", "CAT-2025.01", "HYD-FILTER-REPLACE", "REPLACE",
                "更换主液压滤芯并排气", "HF-900", 2, "hyd-remedy", true, 2,
                "2025-01-01T00:00:00Z", "MOC-2025-030", "周工");
        disposition("HYD_LOW_PRESSURE", "CAT-2025.01", "HYD-SEAL-REPAIR", "REPAIR",
                "更换液压密封组件并保压测试", "SK-220", 1, "hyd-remedy", true, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-031", "周工");
        disposition("HYD_PUMP_WEAR", "CAT-2025.01", "HYD-PUMP-REPLACE", "REPLACE",
                "更换主液压泵总成", "PUMP-77", 1, null, true, 1,
                "2025-03-20T00:00:00Z", "MOC-2025-032", "李工");
        disposition("GEARBOX_VIBRATION_HIGH", "CAT-2025.01", "GBX-INSPECT", "INSPECT",
                "停机检查齿轮箱轴承与润滑", null, 1, null, false, 1,
                "2023-01-01T00:00:00Z", "MOC-2023-051", "王磊");
        disposition("BLADE_IMBALANCE", "CAT-2025.01", "BLD-BALANCE", "ADJUST",
                "执行叶片配平与桨距校准", null, 1, null, false, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-033", "王磊");
        disposition("HYDRAULIC_LEAK", "CAT-2025.01", "LEAK-SEAL-REPAIR", "REPAIR",
                "定位泄漏点并更换密封", "SK-220", 1, null, true, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-034", "周工");
        disposition("GEARBOX_REPLACEMENT", "CAT-2025.01", "GBX-REPLACE-OLD", "REPLACE",
                "整体更换齿轮箱总成（旧型号 GBX-100）", "GBX-100", 1, null, true, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-035", "王磊");
        catalogAdmin.publish("CAT-2025.01", new ApiRequests.CatalogPublish("周工", "CAT-APPROVE-2025-01"));

        // Catalog 2025.02 -- two alternative pumps become mutually exclusive.
        catalogAdmin.createVersion(new ApiRequests.CatalogVersionCreate("CAT-2025.02", "DRAFT",
                Instant.parse("2025-05-01T00:00:00Z"), null, null, "Adds a second pump option"));
        addEntry("CAT-2025.02", "HF-900", "滤芯（10 微米）", Map.of("micron", 10),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null),
                        new ApiRequests.CompatSpec("HAIXIN", "HX-6", null, null)));
        addEntry("CAT-2025.02", "SK-220", "密封组件（V2.0+）", Map.of("material", "FKM"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", "V2.0", null),
                        new ApiRequests.CompatSpec("HAIXIN", "HX-6", "3.2", null)));
        addEntry("CAT-2025.02", "PUMP-77", "主液压泵总成", Map.of("flow", "45L/min"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", "V2.1", null)));
        addEntry("CAT-2025.02", "PUMP-99", "通用液压泵（替代件）", Map.of("flow", "45L/min"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", "V2.1", null)));
        // Gearbox family: the retired model and its catalog of conditional alternatives.
        addEntry("CAT-2025.02", "GBX-100", "齿轮箱总成（旧型号，已停用）", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        addEntry("CAT-2025.02", "GBX-200", "低温型齿轮箱总成", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        addEntry("CAT-2025.02", "GBX-300", "改装接口齿轮箱总成", Map.of("interface", "IF-B"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        addEntry("CAT-2025.02", "GBX-400", "齿轮箱替代件 400", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        addEntry("CAT-2025.02", "GBX-500", "齿轮箱替代件 500", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        addEntry("CAT-2025.02", "GBX-600", "常温通用齿轮箱总成", Map.of("interface", "IF-A"),
                List.of(new ApiRequests.CompatSpec("NORDWIND", "NW-8", null, null)));
        disposition("HYD_LOW_PRESSURE", "CAT-2025.02", "HYD-FILTER-REPLACE", "REPLACE",
                "更换主液压滤芯并排气", "HF-900", 2, "hyd-remedy", true, 2,
                "2025-01-01T00:00:00Z", "MOC-2025-030", "周工");
        disposition("HYD_LOW_PRESSURE", "CAT-2025.02", "HYD-SEAL-REPAIR", "REPAIR",
                "更换液压密封组件并保压测试", "SK-220", 1, "hyd-remedy", true, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-031", "周工");
        disposition("HYD_PUMP_WEAR", "CAT-2025.02", "HYD-PUMP-REPLACE", "REPLACE",
                "更换主液压泵（原厂）", "PUMP-77", 1, "pump-remedy", true, 2,
                "2025-03-20T00:00:00Z", "MOC-2025-032", "李工");
        disposition("HYD_PUMP_WEAR", "CAT-2025.02", "HYD-PUMP-REPLACE-GENERIC", "REPLACE",
                "更换通用液压泵（兼容件）", "PUMP-99", 1, "pump-remedy", true, 2,
                "2025-05-01T00:00:00Z", "MOC-2025-040", "李工");
        disposition("GEARBOX_VIBRATION_HIGH", "CAT-2025.02", "GBX-INSPECT", "INSPECT",
                "停机检查齿轮箱轴承与润滑", null, 1, null, false, 1,
                "2023-01-01T00:00:00Z", "MOC-2023-051", "王磊");
        disposition("BLADE_IMBALANCE", "CAT-2025.02", "BLD-BALANCE", "ADJUST",
                "执行叶片配平与桨距校准", null, 1, null, false, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-033", "王磊");
        disposition("HYDRAULIC_LEAK", "CAT-2025.02", "LEAK-SEAL-REPAIR", "REPAIR",
                "定位泄漏点并更换密封", "SK-220", 1, null, true, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-034", "周工");
        disposition("GEARBOX_REPLACEMENT", "CAT-2025.02", "GBX-REPLACE-OLD", "REPLACE",
                "整体更换齿轮箱总成（旧型号 GBX-100，已停用，仅保留历史处置）", "GBX-100", 1, null, true, 1,
                "2025-01-01T00:00:00Z", "MOC-2025-035", "王磊");

        // ---- Gearbox substitution scenario (the feature's acceptance set) ----
        seedGearboxSubstitutionScenario();

        catalogAdmin.publish("CAT-2025.02", new ApiRequests.CatalogPublish("周工", "CAT-APPROVE-2025-02"));

        // Inventory snapshots
        createInventory("INV-2023-06-01", "2023-06-01T00:00:00Z", "CAT-2023.01",
                List.of(new ApiRequests.InventoryLineSpec("HF-900", "北区备件库", 5),
                        new ApiRequests.InventoryLineSpec("SK-220", "北区备件库", 1)));
        createInventory("INV-2025-03-01", "2025-03-01T00:00:00Z", "CAT-2025.01",
                List.of(new ApiRequests.InventoryLineSpec("HF-900", "北区备件库", 12),
                        new ApiRequests.InventoryLineSpec("SK-220", "北区备件库", 4),
                        new ApiRequests.InventoryLineSpec("PUMP-77", "北区备件库", 0),
                        new ApiRequests.InventoryLineSpec("PUMP-99", "东区备件库", 0)));
        createInventory("INV-2025-05-10", "2025-05-10T00:00:00Z", "CAT-2025.02",
                List.of(new ApiRequests.InventoryLineSpec("HF-900", "北区备件库", 0),
                        new ApiRequests.InventoryLineSpec("SK-220", "北区备件库", 8),
                        new ApiRequests.InventoryLineSpec("PUMP-77", "北区备件库", 2),
                        new ApiRequests.InventoryLineSpec("PUMP-99", "东区备件库", 3),
                        new ApiRequests.InventoryLineSpec("GBX-100", "北区备件库", 0),
                        new ApiRequests.InventoryLineSpec("GBX-200", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-300", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-400", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-500", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-600", "北区备件库", 1)));
        // June stock refills the filter: now both filter + seal (and both pumps) are
        // satisfiable, which is exactly where an exclusive choice must stay pending.
        createInventory("INV-2025-06-01", "2025-06-01T00:00:00Z", "CAT-2025.02",
                List.of(new ApiRequests.InventoryLineSpec("HF-900", "北区备件库", 10),
                        new ApiRequests.InventoryLineSpec("SK-220", "北区备件库", 8),
                        new ApiRequests.InventoryLineSpec("PUMP-77", "北区备件库", 2),
                        new ApiRequests.InventoryLineSpec("PUMP-99", "东区备件库", 3),
                        new ApiRequests.InventoryLineSpec("GBX-100", "北区备件库", 0),
                        new ApiRequests.InventoryLineSpec("GBX-200", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-300", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-400", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-500", "北区备件库", 1),
                        new ApiRequests.InventoryLineSpec("GBX-600", "北区备件库", 1)));

        // ---------------------------------------------------------------
        // Alarms and work orders
        // ---------------------------------------------------------------
        // 2025 real-time alarms
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A01-20250410", "NW-A01", "NORDWIND_CTRL",
                "HYD-201", null, null, null,
                Instant.parse("2025-04-10T08:12:00Z"), Instant.parse("2025-04-10T08:12:20Z"),
                "REALTIME", Map.of("operatingMode", "POWER_PRODUCTION"), Map.of("raw", "HYD-201 p=88bar")));
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A02-20250410", "NW-A02", "NORDWIND_CTRL",
                "HYD-201", null, null, null,
                Instant.parse("2025-04-10T09:40:00Z"), Instant.parse("2025-04-10T09:40:30Z"),
                "REALTIME", Map.of("operatingMode", "POWER_PRODUCTION"), null));
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-HX-B01-20250411", "HX-B01", "HAIXIN_CTRL",
                "E4410", null, null, null,
                Instant.parse("2025-04-11T06:05:00Z"), Instant.parse("2025-04-11T06:05:10Z"),
                "REALTIME", Map.of(), null));
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-HX-B01-20250411-GBX", "HX-B01", "HAIXIN_CTRL",
                "E5510", null, null, null,
                Instant.parse("2025-04-11T11:20:00Z"), Instant.parse("2025-04-11T11:20:15Z"),
                "REALTIME", Map.of("axis", "drivetrain"), null));

        // Offline backfill: occurred 2024 while V1.0 was installed, uploaded 15 months later.
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A01-20240115", "NW-A01", "NORDWIND_CTRL",
                "HYD-201", null, null, null,
                Instant.parse("2024-01-15T03:40:00Z"), Instant.parse("2025-04-12T10:00:00Z"),
                "OFFLINE_BACKFILL", Map.of("operatingMode", "POWER_PRODUCTION"),
                Map.of("uploadedBy", "edge-gateway-3", "bufferDays", 453)));
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-HX-B02-20250201", "HX-B02", "HAIXIN_CTRL",
                "E4410", null, null, null,
                Instant.parse("2025-02-01T22:10:00Z"), Instant.parse("2025-04-12T10:05:00Z"),
                "OFFLINE_BACKFILL", Map.of(), null));

        // Vendor-term alarms (work-order terminology)
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A02-20250408-TERM", "NW-A02", "SERVICE_TICKET",
                null, "远海运维", "振动超限", null,
                Instant.parse("2025-04-08T04:00:00Z"), Instant.parse("2025-04-08T04:10:00Z"),
                "REALTIME", Map.of(), null));
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-HX-B01-20250409-TERM", "HX-B01", "SERVICE_TICKET",
                null, "远海运维", "振动超限", null,
                Instant.parse("2025-04-09T07:30:00Z"), Instant.parse("2025-04-09T07:35:00Z"),
                "REALTIME", Map.of(), null));

        // June alarms fall after both the generic pump and the filter refill came
        // into force: two mutually exclusive remedies are simultaneously available,
        // so the conclusion must stay pending an engineer.
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A01-20250615", "NW-A01", "NORDWIND_CTRL",
                "HYD-201", null, null, null,
                Instant.parse("2025-06-15T05:20:00Z"), Instant.parse("2025-06-15T05:20:12Z"),
                "REALTIME", Map.of("operatingMode", "POWER_PRODUCTION"), null));
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-HX-B01-20250616", "HX-B01", "HAIXIN_CTRL",
                "E4410", null, null, null,
                Instant.parse("2025-06-16T13:00:00Z"), Instant.parse("2025-06-16T13:00:08Z"),
                "REALTIME", Map.of(), null));

        // Work orders (vendor narrative evidence)
        evidence.ingestWorkOrder(new EvidenceService.NewWorkOrder("WO-2025-0412-NW", "NW-A02",
                "远海运维", "振动超限", "振动超限",
                Instant.parse("2025-04-09T00:00:00Z"), Instant.parse("2025-04-12T00:00:00Z"), "CLOSED",
                "齿轮箱侧振动报警，检查后判定为齿轮箱轴承磨损", Map.of("crew", "远海-3组")));
        evidence.ingestWorkOrder(new EvidenceService.NewWorkOrder("WO-2025-0413-HX", "HX-B01",
                "远海运维", "振动超限", "振动超限",
                Instant.parse("2025-04-11T00:00:00Z"), Instant.parse("2025-04-14T00:00:00Z"), "CLOSED",
                "现场复测为叶片质量不平衡，执行配平", Map.of("crew", "远海-1组")));
        evidence.ingestWorkOrder(new EvidenceService.NewWorkOrder("WO-2024-0116-NW", "NW-A01",
                "远海运维", "液压压力低", "液压压力低",
                Instant.parse("2024-01-16T00:00:00Z"), Instant.parse("2024-01-20T00:00:00Z"), "CLOSED",
                "更换液压滤芯，压力恢复；当时固件为 V1.0", Map.of("crew", "远海-2组")));

        // ---------------------------------------------------------------
        // Gearbox substitution evidence: historical work order, current
        // alarms (cold / warm / temperature-missing) and warehouse contention.
        // ---------------------------------------------------------------
        seedGearboxEvidenceAndContention();

        // ---------------------------------------------------------------
        // Releases (frozen snapshots)
        // ---------------------------------------------------------------
        releases.createDraft("REL-2025.01", Instant.parse("2025-01-15T00:00:00Z"),
                "CAT-2025.01", "INV-2025-03-01", "system", "Freeze before the 2025-05 catalog update");
        releases.publish("REL-2025.01", "周工", "REL-APPROVE-2025-01");
        releases.createDraft("REL-2025.02", Instant.parse("2025-05-15T00:00:00Z"),
                "CAT-2025.02", "INV-2025-05-10", "system", "Freeze after adding the generic pump");
        releases.publish("REL-2025.02", "周工", "REL-APPROVE-2025-02");

        // ---------------------------------------------------------------
        // Seeded runs
        // ---------------------------------------------------------------
        seedRuns();
    }

    private void seedGearboxSubstitutionScenario() {
        // The old gearbox is explicitly retired in the 2025.02 catalog.
        substitutions.createLifecycle(new ApiRequests.PartLifecycleCreate("CAT-2025.02", "GBX-100",
                "OBSOLETE", "原厂停产，替代型号需按低温条件或接口改装选用", "GBX-200",
                "王磊", "MOC-2025-060"));

        // Alternative 1: low-temperature unit only (ambient must stay at or below -10C).
        substitutions.createSubstitution(new ApiRequests.SubstitutionCreate("CAT-2025.02",
                "GBX-100", "GBX-200", "CONDITIONAL", "NW-8", null, new java.math.BigDecimal("-10"),
                null, false, Instant.parse("2025-05-01T00:00:00Z"), null,
                "MOC-2025-061", "王磊", "仅适用于低温机组，高温环境润滑裕度不足"));
        // Alternative 2: interface retrofit, engineering confirmation required.
        substitutions.createSubstitution(new ApiRequests.SubstitutionCreate("CAT-2025.02",
                "GBX-100", "GBX-300", "RETROFIT", "NW-8", null, null, "IF-B", true,
                Instant.parse("2025-05-01T00:00:00Z"), null,
                "MOC-2025-062", "王磊", "需将 IF-A 接口改装为 IF-B 后方可安装"));
        // Branch into the 400/500 pair whose edges form a catalog cycle.
        substitutions.createSubstitution(new ApiRequests.SubstitutionCreate("CAT-2025.02",
                "GBX-100", "GBX-400", "DIRECT", "NW-8", null, null, null, false,
                Instant.parse("2025-05-01T00:00:00Z"), null,
                "MOC-2025-063", "王磊", "目录维护错误：与 GBX-500 互相指向"));
        substitutions.createSubstitution(new ApiRequests.SubstitutionCreate("CAT-2025.02",
                "GBX-400", "GBX-500", "DIRECT", "NW-8", null, null, null, false,
                Instant.parse("2025-05-01T00:00:00Z"), null,
                "MOC-2025-064", "王磊", null));
        substitutions.createSubstitution(new ApiRequests.SubstitutionCreate("CAT-2025.02",
                "GBX-500", "GBX-400", "DIRECT", "NW-8", null, null, null, false,
                Instant.parse("2025-05-01T00:00:00Z"), null,
                "MOC-2025-065", "王磊", null));
        // Ambient-temperature general alternative; its last unit is contested in the warehouse.
        substitutions.createSubstitution(new ApiRequests.SubstitutionCreate("CAT-2025.02",
                "GBX-100", "GBX-600", "DIRECT", "NW-8", null, null, null, false,
                Instant.parse("2025-05-01T00:00:00Z"), null,
                "MOC-2025-066", "王磊", "常温通用替代件"));
    }

    private void seedGearboxEvidenceAndContention() {
        // Historical work order (2023): the old model was the valid recommendation then.
        // The record is immutable and must never be overwritten by today's alternatives.
        evidence.ingestWorkOrder(new EvidenceService.NewWorkOrder("WO-2023-0510-NW", "NW-A01",
                "远海运维", "齿轮箱更换", null,
                Instant.parse("2023-05-10T00:00:00Z"), Instant.parse("2023-05-15T00:00:00Z"), "CLOSED",
                "齿轮箱齿面剥落，整体更换为 GBX-100（当时唯一在役型号）",
                Map.of("crew", "远海-2组")));
        substitutions.recordRecommendation(new ApiRequests.WorkOrderRecommendationCreate(
                "WO-2023-0510-NW", "GEARBOX_REPLACEMENT", "GBX-100", "GBX-REPLACE-OLD",
                "CAT-2023.01", 1, "ISSUED",
                "2023 年目录下 GBX-100 为唯一在役齿轮箱型号，已领用装机",
                "王磊", "MOC-2023-052"), "system");

        // Cold-climate alarm: the low-temperature alternative is verifiable.
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A01-20250620-GBX", "NW-A01", "NORDWIND_CTRL",
                "GBX-REP-1", null, null, null,
                Instant.parse("2025-06-20T02:10:00Z"), Instant.parse("2025-06-20T02:10:10Z"),
                "REALTIME", Map.of("ambientTempC", -25, "interfaceCode", "IF-A"), null));
        // Warm-site alarm: low-temperature edge fails; the retrofit path stays pending engineering.
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A02-20250621-GBX", "NW-A02", "NORDWIND_CTRL",
                "GBX-REP-1", null, null, null,
                Instant.parse("2025-06-21T06:30:00Z"), Instant.parse("2025-06-21T06:30:12Z"),
                "REALTIME", Map.of("ambientTempC", 18, "interfaceCode", "IF-A"), null));
        // Temperature condition missing: the low-temperature alternative cannot be verified.
        ingestAlarm(new ApiRequests.AlarmCreate("ALT-NW-A02-20250622-GBX", "NW-A02", "NORDWIND_CTRL",
                "GBX-REP-1", null, null, null,
                Instant.parse("2025-06-22T07:00:00Z"), Instant.parse("2025-06-22T07:00:10Z"),
                "REALTIME", Map.of("interfaceCode", "IF-A"), null));

        // Warehouse contention for the last GBX-600 unit. In May the claim is still
        // pending (diagnosis may only flag the suggestion); by June the warehouse
        // confirms allocation to the competing work order, removing it from stock.
        substitutions.recordContention(new ApiRequests.StockContentionCreate("INV-2025-05-10",
                "GBX-600", "北区备件库", "CLAIM_PENDING",
                "WO-2025-0520-NW 同型机组抢修申领", null,
                "最后一台 GBX-600 存在并行申领，等待仓储裁定"), "system");
        substitutions.recordContention(new ApiRequests.StockContentionCreate("INV-2025-06-01",
                "GBX-600", "北区备件库", "CONFIRMED_ALLOCATED",
                "WO-2025-0520-NW 同型机组抢修申领", "仓储-林倩",
                "仓储确认库存划拨给 WO-2025-0520-NW，本诊断建议对应库存失效"), "system");
    }

    private void seedRuns() {
        // Latest view over all alarms: pins CAT-2025.02 + INV-2025-05-10.
        DiagnosisDtos.Run latest = diagnosis.run(new DiagnosisDtos.RunRequest(
                "ASSERTION", "LATEST", null, null, null, null, null, null, false,
                null, null, null), "system");

        // Occurs-time replay over the 2024 backfilled alarms.
        diagnosis.run(new DiagnosisDtos.RunRequest(
                "ASSERTION", "AS_OF", Instant.parse("2024-02-01T00:00:00Z"), null, null,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-02-01T00:00:00Z"),
                null, false, null, null, null), "system");

        // Frozen-release replay of the same 2025-04 alarms, so the release-vs-latest
        // diff is a like-for-like comparison rather than disjoint alarm sets.
        diagnosis.run(new DiagnosisDtos.RunRequest(
                "SNAPSHOT", "RELEASE", null, "REL-2025.01", null,
                Instant.parse("2025-04-01T00:00:00Z"), Instant.parse("2025-05-01T00:00:00Z"),
                null, false, null, null, null), "system");

        log.info("Seeded diagnosis run {} with {} results", latest.id(), latest.results().size());
    }

    // ---- helpers -------------------------------------------------------
    private void createConcept(String code, String name, String category, String severity,
                               String definition, String approvedBy, String approvalRef, String approvedAt) {
        referenceService.createConcept(new ApiRequests.ConceptCreate(code, name, category, severity,
                definition, approvedBy, approvalRef));
    }

    private UUID sourceCodeId(String system, String code) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(
                reference.sourceCodeBySystemAndCode(system, code), "SOURCE_CODE_NOT_FOUND",
                "Seed: missing code " + system + "/" + code), "id");
    }

    private UUID termId(String vendor, String text) {
        return ReferenceRepo.asUuid(ReferenceRepo.require(
                reference.termByVendorAndText(vendor, text), "TERM_NOT_FOUND",
                "Seed: missing term " + text), "id");
    }

    private void assertCode(UUID sourceCodeId, String conceptCode, int defVersion,
                            String manufacturerCode, String modelCode, String firmwareFrom, String firmwareTo,
                            String approvalRef, String approvedBy, String validFrom) {
        mappingService.createAssertion(new MappingService.CreateAssertion(sourceCodeId, null,
                conceptCode, defVersion, manufacturerCode, modelCode, firmwareFrom, firmwareTo,
                Map.of(), null, "ACTIVE", Instant.parse(validFrom), null, null,
                approvalRef, approvedBy, "Seeded mapping"));
    }

    private void assertTerm(UUID termId, String conceptCode, int defVersion,
                            String manufacturerCode, String modelCode, String firmwareFrom, String firmwareTo,
                            String approvalRef, String approvedBy, String validFrom) {
        mappingService.createAssertion(new MappingService.CreateAssertion(null, termId,
                conceptCode, defVersion, manufacturerCode, modelCode, firmwareFrom, firmwareTo,
                Map.of(), null, "ACTIVE", Instant.parse(validFrom), null, null,
                approvalRef, approvedBy, "Seeded term mapping"));
    }

    private void addEntry(String catalogLabel, String partNumber, String description,
                          Map<String, Object> attrs, List<ApiRequests.CompatSpec> compat) {
        catalogAdmin.addEntry(new ApiRequests.CatalogEntryCreate(catalogLabel, partNumber, description,
                attrs, compat));
    }

    private void disposition(String conceptCode, String catalogLabel, String code, String action,
                             String description, String partNumber, int quantity, String group,
                             boolean requiresStock, int minStock, String validFrom,
                             String approvalRef, String approvedBy) {
        catalogService.createDisposition(new CatalogService.NewDisposition(conceptCode, catalogLabel,
                code, action, description, partNumber, quantity, group, requiresStock, minStock,
                Instant.parse(validFrom), null, approvedBy, approvalRef));
    }

    private void createInventory(String label, String takenAt, String catalogLabel,
                                 List<ApiRequests.InventoryLineSpec> lines) {
        catalogAdmin.createSnapshot(new ApiRequests.InventorySnapshotCreate(label, Instant.parse(takenAt),
                "WMS export", catalogLabel, lines));
    }

    private void ingestAlarm(ApiRequests.AlarmCreate req) {
        evidence.ingestAlarm(new EvidenceService.NewAlarm(req.externalId(), req.unitCode(),
                req.sourceSystemCode(), req.sourceCode(), req.vendor(), req.vendorTerm(),
                req.firmwareVersion(), req.occurredAt(), req.receivedAt(), req.ingestType(),
                req.attributes(), req.rawPayload()));
    }
}
