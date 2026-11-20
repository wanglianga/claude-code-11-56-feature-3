package com.windfarm.faultref.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read models exposed by the reference-data API. */
public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    public record Manufacturer(UUID id, String code, String name) {
    }

    public record Model(UUID id, String manufacturerCode, String code, String name) {
    }

    public record Firmware(UUID id, String modelCode, String version, int sequence, LocalDate releasedOn) {
    }

    public record Unit(UUID id, String code, String name, String manufacturerCode, String modelCode) {
    }

    public record SourceSystem(UUID id, String code, String name, String kind, String description) {
    }

    public record SourceCode(UUID id, String sourceSystemCode, String code, String rawText, String description) {
    }

    public record Term(UUID id, String vendor, String termText, String language, String attachmentRef, String notes) {
    }

    public record Concept(UUID id, String code, String name, String category, String severity,
                          int currentDefinitionVersion, String definition, String approvedBy,
                          String approvalRef, Instant approvedAt) {
    }

    public record ConceptDefinition(int version, String definition, String approvedBy,
                                    String approvalRef, Instant approvedAt) {
    }

    public record ConceptDetail(Concept concept, List<ConceptDefinition> definitions) {
    }
}
