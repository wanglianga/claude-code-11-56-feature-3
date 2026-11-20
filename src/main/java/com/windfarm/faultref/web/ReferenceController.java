package com.windfarm.faultref.web;

import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.service.ReferenceService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.ReferenceDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reference")
@Tag(name = "Reference data", description = "Manufacturers, models, firmware timeline, units, sources, terms and concepts")
public class ReferenceController {

    private final ReferenceService reference;

    public ReferenceController(ReferenceService reference) {
        this.reference = reference;
    }

    // ---- manufacturers -------------------------------------------------
    @Operation(summary = "Create a manufacturer")
    @PostMapping("/manufacturers")
    public Map<String, Object> createManufacturer(@Valid @RequestBody ApiRequests.ManufacturerCreate req) {
        return reference.createManufacturer(req);
    }

    @Operation(summary = "List manufacturers")
    @GetMapping("/manufacturers")
    public List<ReferenceDtos.Manufacturer> manufacturers() {
        return reference.manufacturers();
    }

    // ---- models --------------------------------------------------------
    @Operation(summary = "Create a turbine model under a manufacturer")
    @PostMapping("/models")
    public Map<String, Object> createModel(@Valid @RequestBody ApiRequests.ModelCreate req) {
        return reference.createModel(req);
    }

    @Operation(summary = "List turbine models")
    @GetMapping("/models")
    public List<ReferenceDtos.Model> models() {
        return reference.models();
    }

    // ---- firmware ------------------------------------------------------
    @Operation(summary = "Create a firmware version (sequence orders firmware releases)")
    @PostMapping("/firmware")
    public Map<String, Object> createFirmware(@Valid @RequestBody ApiRequests.FirmwareCreate req) {
        return reference.createFirmware(req);
    }

    @Operation(summary = "List firmware versions")
    @GetMapping("/firmware")
    public List<ReferenceDtos.Firmware> firmware() {
        return reference.firmware();
    }

    // ---- units ---------------------------------------------------------
    @Operation(summary = "Create a wind turbine unit")
    @PostMapping("/units")
    public Map<String, Object> createUnit(@Valid @RequestBody ApiRequests.UnitCreate req) {
        return reference.createUnit(req);
    }

    @Operation(summary = "List units")
    @GetMapping("/units")
    public List<ReferenceDtos.Unit> units() {
        return reference.units();
    }

    @Operation(summary = "Record a firmware installation window on a unit")
    @PostMapping("/units/{code}/firmware-installs")
    public Map<String, Object> installFirmware(@PathVariable String code,
                                               @Valid @RequestBody ApiRequests.FirmwareInstall req) {
        return reference.installFirmware(code, req);
    }

    /** Firmware installed on a unit at a business instant; drives occurrence-time interpretation. */
    @Operation(summary = "Resolve the firmware installed on a unit at an instant")
    @GetMapping("/units/{code}/firmware-at")
    public Map<String, Object> firmwareAt(@PathVariable String code,
                                          @RequestParam String at) {
        return reference.firmwareAt(code, java.time.Instant.parse(at));
    }

    // ---- source systems / codes ---------------------------------------
    @Operation(summary = "Create a source system (CONTROLLER code table or VENDOR_TERM attachment)")
    @PostMapping("/source-systems")
    public Map<String, Object> createSourceSystem(@Valid @RequestBody ApiRequests.SourceSystemCreate req) {
        return reference.createSourceSystem(req);
    }

    @Operation(summary = "List source systems")
    @GetMapping("/source-systems")
    public List<ReferenceDtos.SourceSystem> sourceSystems() {
        return reference.sourceSystems();
    }

    @Operation(summary = "Register a controller source code")
    @PostMapping("/source-codes")
    public Map<String, Object> createSourceCode(@Valid @RequestBody ApiRequests.SourceCodeCreate req) {
        return reference.createSourceCode(req);
    }

    @Operation(summary = "List controller source codes")
    @GetMapping("/source-codes")
    public List<ReferenceDtos.SourceCode> sourceCodes() {
        return reference.sourceCodes();
    }

    @Operation(summary = "Register a vendor natural-language term attachment")
    @PostMapping("/terms")
    public Map<String, Object> createTerm(@Valid @RequestBody ApiRequests.TermCreate req) {
        return reference.createTerm(req);
    }

    @Operation(summary = "List vendor terms")
    @GetMapping("/terms")
    public List<ReferenceDtos.Term> terms() {
        return reference.terms();
    }

    // ---- concepts ------------------------------------------------------
    @Operation(summary = "Create a unified diagnostic concept with its first approved definition")
    @PostMapping("/concepts")
    public Map<String, Object> createConcept(@Valid @RequestBody ApiRequests.ConceptCreate req) {
        return reference.createConcept(req);
    }

    @Operation(summary = "List unified concepts with current definitions")
    @GetMapping("/concepts")
    public List<ReferenceDtos.Concept> concepts() {
        return reference.concepts();
    }

    @Operation(summary = "Get one concept with its definition history")
    @GetMapping("/concepts/{code}")
    public ReferenceDtos.ConceptDetail concept(@PathVariable String code) {
        return reference.concept(code);
    }

    @Operation(summary = "Append a new approved definition version to a concept")
    @PostMapping("/concepts/{code}/definitions")
    public Map<String, Object> addDefinition(@PathVariable String code,
                                             @Valid @RequestBody ApiRequests.ConceptDefinitionCreate req) {
        return reference.addDefinition(code, req);
    }
}
