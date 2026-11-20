package com.windfarm.faultref.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request payloads. Kept separate from read models so validation stays explicit. */
public final class ApiRequests {

    private ApiRequests() {
    }

    public record ManufacturerCreate(
            @NotBlank String code,
            @NotBlank String name) {
    }

    public record ModelCreate(
            @NotBlank String manufacturerCode,
            @NotBlank String code,
            @NotBlank String name) {
    }

    public record FirmwareCreate(
            @NotBlank String manufacturerCode,
            @NotBlank String modelCode,
            @NotBlank String version,
            @NotNull Integer sequence,
            LocalDate releasedOn,
            String notes) {
    }

    public record UnitCreate(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String manufacturerCode,
            @NotBlank String modelCode,
            LocalDate commissionedOn) {
    }

    public record FirmwareInstall(
            @NotBlank String unitCode,
            @NotBlank String version,
            @NotNull Instant installedFrom,
            Instant installedTo) {
    }

    public record SourceSystemCreate(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "CONTROLLER|VENDOR_TERM") String kind,
            String description) {
    }

    public record SourceCodeCreate(
            @NotBlank String sourceSystemCode,
            @NotBlank String code,
            @NotBlank String rawText,
            String description) {
    }

    public record TermCreate(
            String sourceSystemCode,
            @NotBlank String vendor,
            @NotBlank String termText,
            String language,
            String attachmentRef,
            String notes) {
    }

    public record ConceptCreate(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String category,
            @NotBlank String severity,
            @NotBlank String definition,
            @NotBlank String approvedBy,
            @NotBlank String approvalRef) {
    }

    public record ConceptDefinitionCreate(
            @NotBlank String definition,
            @NotBlank String approvedBy,
            @NotBlank String approvalRef) {
    }

    public record AssertionCreate(
            UUID sourceCodeId,
            UUID termAttachmentId,
            @NotBlank String conceptCode,
            @NotNull Integer definitionVersion,
            String manufacturerCode,
            String modelCode,
            String firmwareFrom,
            String firmwareTo,
            Map<String, Object> qualifiers,
            Double confidence,
            String state,
            Instant validFrom,
            Instant validTo,
            UUID supersedesId,
            String approvalRef,
            String approvedBy,
            String notes) {
    }

    public record AssertionClose(Instant validTo, String state) {
    }

    public record AdjudicationCreate(
            UUID sourceCodeId,
            UUID termAttachmentId,
            @NotBlank String conceptACode,
            @NotBlank String conceptBCode,
            @NotBlank String chosenConceptCode,
            String manufacturerCode,
            String modelCode,
            Instant validFrom,
            Instant validTo,
            @NotBlank String decision,
            @NotBlank String adjudicatedBy,
            @NotBlank String approvalRef) {
    }

    public record CatalogVersionCreate(
            @NotBlank String versionLabel,
            String status,
            Instant effectiveFrom,
            String approvedBy,
            String approvalRef,
            String notes) {
    }

    public record CatalogPublish(String approvedBy, String approvalRef) {
    }

    public record PartCreate(
            @NotBlank String partNumber,
            @NotBlank String name,
            @NotBlank String category,
            String manufacturer) {
    }

    public record CatalogEntryCreate(
            @NotBlank String catalogVersionLabel,
            @NotBlank String partNumber,
            String description,
            Map<String, Object> attributes,
            List<CompatSpec> compatibility) {
    }

    public record CompatSpec(String manufacturerCode, String modelCode, String firmwareFrom, String firmwareTo) {
    }

    public record InventorySnapshotCreate(
            @NotBlank String label,
            Instant takenAt,
            String source,
            String catalogVersionLabel,
            List<InventoryLineSpec> lines) {
    }

    public record InventoryLineSpec(@NotBlank String partNumber, @NotBlank String warehouse, @NotNull Integer qtyAvailable) {
    }

    public record DispositionCreate(
            @NotBlank String conceptCode,
            @NotBlank String catalogVersionLabel,
            @NotBlank String code,
            @NotBlank @Pattern(regexp = "REPLACE|REPAIR|INSPECT|ADJUST") String action,
            @NotBlank String description,
            String partNumber,
            Integer quantity,
            String exclusiveGroup,
            Boolean requiresStock,
            Integer minStock,
            Instant validFrom,
            Instant validTo,
            String approvedBy,
            String approvalRef) {
    }

    public record AlarmCreate(
            @NotBlank String externalId,
            @NotBlank String unitCode,
            @NotBlank String sourceSystemCode,
            String sourceCode,
            String vendor,
            String vendorTerm,
            String firmwareVersion,
            @NotNull Instant occurredAt,
            Instant receivedAt,
            String ingestType,
            Map<String, Object> attributes,
            Map<String, Object> rawPayload) {
    }

    public record WorkOrderCreate(
            @NotBlank String externalId,
            @NotBlank String unitCode,
            @NotBlank String vendor,
            @NotBlank String reportedTerm,
            String vendorTerm,
            @NotNull Instant openedAt,
            Instant closedAt,
            String status,
            String symptom,
            Map<String, Object> rawPayload) {
    }

    public record ReleaseCreate(
            @NotBlank String versionLabel,
            Instant effectiveFrom,
            @NotBlank String catalogVersionLabel,
            String inventorySnapshotLabel,
            String notes) {
    }

    public record ReleasePublish(@NotBlank String approvedBy, @NotBlank String approvalRef) {
    }

    // ---- spare-part substitution graph ---------------------------------

    public record SubstitutionCreate(
            @NotBlank String catalogVersionLabel,
            @NotBlank String fromPartNumber,
            @NotBlank String toPartNumber,
            @NotBlank @Pattern(regexp = "DIRECT|CONDITIONAL|RETROFIT") String substitutionKind,
            String modelCode,
            BigDecimal tempMinC,
            BigDecimal tempMaxC,
            String interfaceRequirement,
            Boolean requiresEngineeringConfirm,
            Instant validFrom,
            Instant validTo,
            String approvalRef,
            String approvedBy,
            String notes) {
    }

    public record PartLifecycleCreate(
            @NotBlank String catalogVersionLabel,
            @NotBlank String partNumber,
            @NotBlank @Pattern(regexp = "ACTIVE|OBSOLETE") String state,
            String obsoleteReason,
            String supersededByPartNumber,
            String approvedBy,
            String approvalRef) {
    }

    public record WorkOrderRecommendationCreate(
            @NotBlank String workOrderExternalId,
            String conceptCode,
            String partNumber,
            String dispositionCode,
            String catalogVersionLabel,
            Integer quantity,
            @NotBlank @Pattern(regexp = "RECOMMENDED|ISSUED|NOT_AVAILABLE|SUBSTITUTED") String recommendationStatus,
            String reason,
            String recordedBy,
            String approvalRef) {
    }

    public record StockContentionCreate(
            @NotBlank String inventorySnapshotLabel,
            @NotBlank String partNumber,
            String warehouse,
            @NotBlank @Pattern(regexp = "CLAIM_PENDING|CONFIRMED_ALLOCATED|RELEASED") String status,
            @NotBlank String competingDemand,
            String confirmedBy,
            String notes) {
    }

    /**
     * On-demand advice request. The four time/identity axes are explicit:
     * device (unit), environment (ambient temp + interface), work-order time
     * (occurredAt / workOrderExternalId) and inventory point (snapshot label,
     * or the snapshot effective at the evaluation time).
     */
    public record SubstitutionAdviceRequest(
            String conceptCode,
            String workOrderExternalId,
            String alarmExternalId,
            String unitCode,
            BigDecimal ambientTempC,
            String interfaceCode,
            Instant occurredAt,
            String catalogVersionLabel,
            String inventorySnapshotLabel) {
    }
}
