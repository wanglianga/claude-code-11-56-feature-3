package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.ReferenceDtos;
import com.windfarm.faultref.web.dto.ReferenceDtos.Concept;
import com.windfarm.faultref.web.dto.ReferenceDtos.ConceptDefinition;
import com.windfarm.faultref.web.dto.ReferenceDtos.ConceptDetail;
import com.windfarm.faultref.web.dto.ReferenceDtos.Firmware;
import com.windfarm.faultref.web.dto.ReferenceDtos.Manufacturer;
import com.windfarm.faultref.web.dto.ReferenceDtos.Model;
import com.windfarm.faultref.web.dto.ReferenceDtos.SourceCode;
import com.windfarm.faultref.web.dto.ReferenceDtos.SourceSystem;
import com.windfarm.faultref.web.dto.ReferenceDtos.Term;
import com.windfarm.faultref.web.dto.ReferenceDtos.Unit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReferenceService {

    private final ReferenceRepo repo;

    public ReferenceService(ReferenceRepo repo) {
        this.repo = repo;
    }

    // ---- manufacturers / models / firmware -----------------------------
    @Transactional
    public Map<String, Object> createManufacturer(ApiRequests.ManufacturerCreate req) {
        try {
            UUID id = repo.insertManufacturer(req.code(), req.name());
            return Map.of("id", id, "code", req.code(), "name", req.name());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("MANUFACTURER_EXISTS", "Manufacturer " + req.code() + " already exists");
        }
    }

    public List<Manufacturer> manufacturers() {
        return repo.listManufacturers().stream()
                .map(r -> new Manufacturer(ReferenceRepo.asUuid(r, "id"),
                        ReferenceRepo.asString(r, "code"), ReferenceRepo.asString(r, "name")))
                .toList();
    }

    @Transactional
    public Map<String, Object> createModel(ApiRequests.ModelCreate req) {
        Map<String, Object> man = ReferenceRepo.require(repo.manufacturerByCode(req.manufacturerCode()),
                "MANUFACTURER_NOT_FOUND", "Unknown manufacturer " + req.manufacturerCode());
        try {
            UUID id = repo.insertModel(ReferenceRepo.asUuid(man, "id"), req.code(), req.name());
            return Map.of("id", id, "manufacturerCode", req.manufacturerCode(), "code", req.code());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("MODEL_EXISTS",
                    "Model " + req.manufacturerCode() + "/" + req.code() + " already exists");
        }
    }

    public List<Model> models() {
        return repo.listModels().stream()
                .map(r -> new Model(ReferenceRepo.asUuid(r, "id"), ReferenceRepo.asString(r, "manufacturer_code"),
                        ReferenceRepo.asString(r, "code"), ReferenceRepo.asString(r, "name")))
                .toList();
    }

    @Transactional
    public Map<String, Object> createFirmware(ApiRequests.FirmwareCreate req) {
        Map<String, Object> model = ReferenceRepo.require(
                repo.modelByCode(req.manufacturerCode(), req.modelCode()),
                "MODEL_NOT_FOUND", "Unknown model " + req.manufacturerCode() + "/" + req.modelCode());
        try {
            UUID id = repo.insertFirmware(ReferenceRepo.asUuid(model, "id"), req.version(), req.sequence(),
                    req.releasedOn(), req.notes());
            return Map.of("id", id, "version", req.version(), "sequence", req.sequence());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("FIRMWARE_EXISTS",
                    "Firmware " + req.version() + " or sequence " + req.sequence() + " already exists for this model");
        }
    }

    public List<Firmware> firmware() {
        return repo.listFirmware().stream()
                .map(r -> new Firmware(ReferenceRepo.asUuid(r, "id"), ReferenceRepo.asString(r, "model_code"),
                        ReferenceRepo.asString(r, "version"), ReferenceRepo.asInt(r, "sequence"),
                        r.get("released_on") == null ? null
                                : LocalDate.parse(r.get("released_on").toString())))
                .toList();
    }

    // ---- units ---------------------------------------------------------
    @Transactional
    public Map<String, Object> createUnit(ApiRequests.UnitCreate req) {
        Map<String, Object> model = ReferenceRepo.require(
                repo.modelByCode(req.manufacturerCode(), req.modelCode()),
                "MODEL_NOT_FOUND", "Unknown model " + req.manufacturerCode() + "/" + req.modelCode());
        try {
            UUID id = repo.insertUnit(req.code(), req.name(), ReferenceRepo.asUuid(model, "id"),
                    req.commissionedOn());
            return Map.of("id", id, "code", req.code());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("UNIT_EXISTS", "Unit " + req.code() + " already exists");
        }
    }

    public List<Unit> units() {
        return repo.listUnits().stream()
                .map(r -> new Unit(ReferenceRepo.asUuid(r, "id"), ReferenceRepo.asString(r, "code"),
                        ReferenceRepo.asString(r, "name"), ReferenceRepo.asString(r, "manufacturer_code"),
                        ReferenceRepo.asString(r, "model_code")))
                .toList();
    }

    @Transactional
    public Map<String, Object> installFirmware(String unitCode, ApiRequests.FirmwareInstall req) {
        Map<String, Object> unit = ReferenceRepo.require(repo.unitByCode(unitCode),
                "UNIT_NOT_FOUND", "Unknown unit " + unitCode);
        Map<String, Object> fw = ReferenceRepo.require(
                repo.firmwareByModelAndVersion(ReferenceRepo.asUuid(unit, "model_id"), req.version()),
                "FIRMWARE_NOT_FOUND", "Unknown firmware " + req.version() + " for this unit's model");
        if (req.installedTo() != null && !req.installedTo().isAfter(req.installedFrom())) {
            throw ApiException.unprocessable("INSTALL_WINDOW_INVALID", "installedTo must be after installedFrom");
        }
        UUID id = repo.insertFirmwareInstall(ReferenceRepo.asUuid(unit, "id"),
                ReferenceRepo.asUuid(fw, "id"), req.installedFrom(), req.installedTo());
        return Map.of("id", id, "unitCode", unitCode, "version", req.version(),
                "installedFrom", req.installedFrom(),
                "installedTo", req.installedTo() == null ? "OPEN" : req.installedTo());
    }

    public Map<String, Object> firmwareAt(String unitCode, Instant at) {
        Map<String, Object> unit = ReferenceRepo.require(repo.unitByCode(unitCode),
                "UNIT_NOT_FOUND", "Unknown unit " + unitCode);
        Map<String, Object> fw = ReferenceRepo.require(
                repo.firmwareAt(ReferenceRepo.asUuid(unit, "id"), at),
                "NO_FIRMWARE_INSTALLED", "No firmware installed on " + unitCode + " at " + at);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unitCode", unitCode);
        out.put("at", at);
        out.put("version", ReferenceRepo.asString(fw, "version"));
        out.put("sequence", ReferenceRepo.asInt(fw, "sequence"));
        out.put("installedFrom", ReferenceRepo.asInstant(fw, "installed_from"));
        out.put("installedTo", ReferenceRepo.asInstant(fw, "installed_to"));
        return out;
    }

    // ---- source systems / codes / terms --------------------------------
    @Transactional
    public Map<String, Object> createSourceSystem(ApiRequests.SourceSystemCreate req) {
        try {
            UUID id = repo.insertSourceSystem(req.code(), req.name(), req.kind(), req.description());
            return Map.of("id", id, "code", req.code(), "kind", req.kind());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SOURCE_SYSTEM_EXISTS", "Source system " + req.code() + " already exists");
        }
    }

    public List<SourceSystem> sourceSystems() {
        return repo.listSourceSystems().stream()
                .map(r -> new SourceSystem(ReferenceRepo.asUuid(r, "id"), ReferenceRepo.asString(r, "code"),
                        ReferenceRepo.asString(r, "name"), ReferenceRepo.asString(r, "kind"),
                        ReferenceRepo.asString(r, "description")))
                .toList();
    }

    @Transactional
    public Map<String, Object> createSourceCode(ApiRequests.SourceCodeCreate req) {
        Map<String, Object> system = ReferenceRepo.require(repo.sourceSystemByCode(req.sourceSystemCode()),
                "SOURCE_SYSTEM_NOT_FOUND", "Unknown source system " + req.sourceSystemCode());
        if (!"CONTROLLER".equals(ReferenceRepo.asString(system, "kind"))) {
            throw ApiException.unprocessable("SOURCE_SYSTEM_KIND_INVALID",
                    "Source system " + req.sourceSystemCode() + " is not a CONTROLLER system");
        }
        try {
            UUID id = repo.insertSourceCode(ReferenceRepo.asUuid(system, "id"), req.code(), req.rawText(),
                    req.description());
            return Map.of("id", id, "sourceSystemCode", req.sourceSystemCode(), "code", req.code());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SOURCE_CODE_EXISTS",
                    "Code " + req.code() + " already exists in " + req.sourceSystemCode());
        }
    }

    public List<SourceCode> sourceCodes() {
        return repo.listSourceCodes().stream()
                .map(r -> new SourceCode(ReferenceRepo.asUuid(r, "id"),
                        ReferenceRepo.asString(r, "source_system_code"), ReferenceRepo.asString(r, "code"),
                        ReferenceRepo.asString(r, "raw_text"), ReferenceRepo.asString(r, "description")))
                .toList();
    }

    @Transactional
    public Map<String, Object> createTerm(ApiRequests.TermCreate req) {
        UUID systemId = null;
        if (req.sourceSystemCode() != null) {
            Map<String, Object> system = ReferenceRepo.require(repo.sourceSystemByCode(req.sourceSystemCode()),
                    "SOURCE_SYSTEM_NOT_FOUND", "Unknown source system " + req.sourceSystemCode());
            if (!"VENDOR_TERM".equals(ReferenceRepo.asString(system, "kind"))) {
                throw ApiException.unprocessable("SOURCE_SYSTEM_KIND_INVALID",
                        "Source system " + req.sourceSystemCode() + " is not a VENDOR_TERM system");
            }
            systemId = ReferenceRepo.asUuid(system, "id");
        }
        try {
            UUID id = repo.insertTerm(systemId, req.vendor(), req.termText(),
                    req.language() == null ? "zh-CN" : req.language(), req.attachmentRef(), req.notes());
            return Map.of("id", id, "vendor", req.vendor(), "termText", req.termText());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TERM_EXISTS",
                    "Term '" + req.termText() + "' already exists for vendor " + req.vendor());
        }
    }

    public List<Term> terms() {
        return repo.listTerms().stream()
                .map(r -> new Term(ReferenceRepo.asUuid(r, "id"), ReferenceRepo.asString(r, "vendor"),
                        ReferenceRepo.asString(r, "term_text"), ReferenceRepo.asString(r, "language"),
                        ReferenceRepo.asString(r, "attachment_ref"), ReferenceRepo.asString(r, "notes")))
                .toList();
    }

    // ---- concepts ------------------------------------------------------
    @Transactional
    public Map<String, Object> createConcept(ApiRequests.ConceptCreate req) {
        try {
            UUID id = repo.insertConcept(req.code(), req.name(), req.category(), req.severity());
            repo.insertConceptDefinition(id, 1, req.definition(), req.approvedBy(), req.approvalRef(),
                    Instant.now());
            return Map.of("id", id, "code", req.code(), "definitionVersion", 1);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CONCEPT_EXISTS", "Concept " + req.code() + " already exists");
        }
    }

    public List<Concept> concepts() {
        return repo.listConcepts().stream()
                .map(r -> new Concept(ReferenceRepo.asUuid(r, "id"), ReferenceRepo.asString(r, "code"),
                        ReferenceRepo.asString(r, "name"), ReferenceRepo.asString(r, "category"),
                        ReferenceRepo.asString(r, "severity"), ReferenceRepo.asInt(r, "current_definition_version"),
                        ReferenceRepo.asString(r, "definition"), ReferenceRepo.asString(r, "approved_by"),
                        ReferenceRepo.asString(r, "approval_ref"), ReferenceRepo.asInstant(r, "approved_at")))
                .toList();
    }

    public ConceptDetail concept(String code) {
        Map<String, Object> row = ReferenceRepo.require(repo.conceptByCode(code), "CONCEPT_NOT_FOUND",
                "Unknown concept " + code);
        UUID id = ReferenceRepo.asUuid(row, "id");
        int current = ReferenceRepo.asInt(row, "current_definition_version");
        List<Map<String, Object>> defs = repo.conceptDefinitions(id);
        List<ConceptDefinition> versions = defs.stream()
                .map(d -> new ConceptDefinition(ReferenceRepo.asInt(d, "version"),
                        ReferenceRepo.asString(d, "definition"), ReferenceRepo.asString(d, "approved_by"),
                        ReferenceRepo.asString(d, "approval_ref"), ReferenceRepo.asInstant(d, "approved_at")))
                .toList();
        Map<String, Object> currentDef = defs.stream()
                .filter(d -> ReferenceRepo.asInt(d, "version") == current).findFirst().orElse(Map.of());
        Concept c = new Concept(id, code, ReferenceRepo.asString(row, "name"),
                ReferenceRepo.asString(row, "category"), ReferenceRepo.asString(row, "severity"), current,
                ReferenceRepo.asString(currentDef, "definition"), ReferenceRepo.asString(currentDef, "approved_by"),
                ReferenceRepo.asString(currentDef, "approval_ref"), ReferenceRepo.asInstant(currentDef, "approved_at"));
        return new ConceptDetail(c, versions);
    }

    @Transactional
    public Map<String, Object> addDefinition(String code, ApiRequests.ConceptDefinitionCreate req) {
        Map<String, Object> row = ReferenceRepo.require(repo.conceptByCode(code), "CONCEPT_NOT_FOUND",
                "Unknown concept " + code);
        UUID id = ReferenceRepo.asUuid(row, "id");
        int next = ReferenceRepo.asInt(row, "current_definition_version") + 1;
        repo.insertConceptDefinition(id, next, req.definition(), req.approvedBy(), req.approvalRef(), Instant.now());
        return Map.of("conceptCode", code, "definitionVersion", next);
    }
}
