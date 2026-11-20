package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingest of alarms and vendor work orders.
 *
 * Occurrence-time rule: an alarm is always interpreted at its {@code occurredAt}.
 * {@code receivedAt} exists only to record lateness. Offline backfill is a
 * transport fact, never a semantic one.
 */
@Service
public class EvidenceService {

    private final EvidenceRepo evidence;
    private final ReferenceRepo reference;

    public EvidenceService(EvidenceRepo evidence, ReferenceRepo reference) {
        this.evidence = evidence;
        this.reference = reference;
    }

    @Transactional
    public Map<String, Object> ingestAlarm(NewAlarm cmd) {
        if (cmd.externalId() == null || cmd.externalId().isBlank()) {
            throw ApiException.badRequest("EXTERNAL_ID_REQUIRED", "externalId is required");
        }
        if (evidence.alarmByExternalId(cmd.externalId()).isPresent()) {
            throw ApiException.conflict("ALARM_EXISTS", "Alarm " + cmd.externalId() + " already ingested");
        }
        if (cmd.occurredAt() == null) {
            throw ApiException.badRequest("OCCURRED_AT_REQUIRED", "occurredAt is required");
        }
        Instant receivedAt = cmd.receivedAt() == null ? Instant.now() : cmd.receivedAt();
        if (receivedAt.isBefore(cmd.occurredAt())) {
            throw ApiException.unprocessable("RECEIVED_BEFORE_OCCURRED",
                    "receivedAt must not be earlier than occurredAt");
        }
        Map<String, Object> unit = ReferenceRepo.require(reference.unitByCode(cmd.unitCode()),
                "UNIT_NOT_FOUND", "Unknown unit " + cmd.unitCode());
        UUID unitId = ReferenceRepo.asUuid(unit, "id");
        UUID modelId = ReferenceRepo.asUuid(unit, "model_id");

        UUID systemId;
        UUID sourceCodeId = null;
        UUID termId = null;

        if (cmd.sourceCode() != null) {
            Map<String, Object> system = ReferenceRepo.require(reference.sourceSystemByCode(cmd.sourceSystemCode()),
                    "SOURCE_SYSTEM_NOT_FOUND", "Unknown source system " + cmd.sourceSystemCode());
            if (!"CONTROLLER".equals(ReferenceRepo.asString(system, "kind"))) {
                throw ApiException.unprocessable("SOURCE_SYSTEM_KIND_INVALID",
                        "Source system " + cmd.sourceSystemCode() + " is not a CONTROLLER system");
            }
            systemId = ReferenceRepo.asUuid(system, "id");
            Map<String, Object> code = ReferenceRepo.require(
                    reference.sourceCodeBySystemAndCode(cmd.sourceSystemCode(), cmd.sourceCode()),
                    "SOURCE_CODE_NOT_FOUND",
                    "Unknown code " + cmd.sourceCode() + " in system " + cmd.sourceSystemCode());
            sourceCodeId = ReferenceRepo.asUuid(code, "id");
        } else if (cmd.vendorTerm() != null) {
            Map<String, Object> system = reference.sourceSystemByCode(cmd.sourceSystemCode())
                    .orElse(null);
            if (system == null) {
                throw ApiException.notFound("SOURCE_SYSTEM_NOT_FOUND",
                        "Unknown source system " + cmd.sourceSystemCode());
            }
            if (!"VENDOR_TERM".equals(ReferenceRepo.asString(system, "kind"))) {
                throw ApiException.unprocessable("SOURCE_SYSTEM_KIND_INVALID",
                        "Source system " + cmd.sourceSystemCode() + " is not a VENDOR_TERM system");
            }
            systemId = ReferenceRepo.asUuid(system, "id");
            Map<String, Object> term = ReferenceRepo.require(
                    reference.termByVendorAndText(cmd.vendor(), cmd.vendorTerm()),
                    "TERM_NOT_FOUND", "Unknown vendor term '" + cmd.vendorTerm() + "' for " + cmd.vendor());
            termId = ReferenceRepo.asUuid(term, "id");
        } else {
            throw ApiException.badRequest("SOURCE_REQUIRED",
                    "Provide either sourceCode or vendorTerm");
        }

        // Firmware: prefer the firmware the controller reported; otherwise resolve
        // the firmware installed on the unit at the alarm's *occurrence* instant.
        UUID firmwareId = null;
        if (cmd.firmwareVersion() != null) {
            Map<String, Object> fw = ReferenceRepo.require(
                    reference.firmwareByModelAndVersion(modelId, cmd.firmwareVersion()),
                    "FIRMWARE_NOT_FOUND", "Unknown firmware " + cmd.firmwareVersion());
            firmwareId = ReferenceRepo.asUuid(fw, "id");
        } else {
            firmwareId = reference.firmwareAt(unitId, cmd.occurredAt())
                    .map(r -> ReferenceRepo.asUuid(r, "id")).orElse(null);
        }

        String ingestType = cmd.ingestType() == null
                ? (receivedAt.isAfter(cmd.occurredAt().plusSeconds(3600)) ? "OFFLINE_BACKFILL" : "REALTIME")
                : cmd.ingestType();
        if (!List.of("REALTIME", "OFFLINE_BACKFILL").contains(ingestType)) {
            throw ApiException.badRequest("INGEST_TYPE_INVALID", "ingestType must be REALTIME or OFFLINE_BACKFILL");
        }

        UUID id = evidence.insertAlarm(new MapSqlParameterSource()
                .addValue("externalId", cmd.externalId())
                .addValue("unitId", unitId)
                .addValue("systemId", systemId)
                .addValue("sourceCodeId", sourceCodeId)
                .addValue("termId", termId)
                .addValue("rawCode", cmd.sourceCode() != null ? cmd.sourceCode() : cmd.vendorTerm())
                .addValue("firmwareId", firmwareId)
                .addValue("occurredAt", Sql.odt(cmd.occurredAt()))
                .addValue("receivedAt", Sql.odt(receivedAt))
                .addValue("ingestType", ingestType)
                .addValue("attributes", Sql.jsonb(cmd.attributes() == null ? Map.of() : cmd.attributes()))
                .addValue("payload", cmd.rawPayload() == null ? null : Sql.jsonb(cmd.rawPayload())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("externalId", cmd.externalId());
        out.put("unitCode", cmd.unitCode());
        out.put("ingestType", ingestType);
        out.put("occurredAt", cmd.occurredAt());
        out.put("receivedAt", receivedAt);
        out.put("interpretationInstant", cmd.occurredAt());
        out.put("lateBySeconds", receivedAt.getEpochSecond() - cmd.occurredAt().getEpochSecond());
        return out;
    }

    @Transactional
    public Map<String, Object> ingestWorkOrder(NewWorkOrder cmd) {
        if (cmd.externalId() == null || cmd.externalId().isBlank()) {
            throw ApiException.badRequest("EXTERNAL_ID_REQUIRED", "externalId is required");
        }
        if (evidence.workOrderByExternalId(cmd.externalId()).isPresent()) {
            throw ApiException.conflict("WORK_ORDER_EXISTS",
                    "Work order " + cmd.externalId() + " already ingested");
        }
        Map<String, Object> unit = ReferenceRepo.require(reference.unitByCode(cmd.unitCode()),
                "UNIT_NOT_FOUND", "Unknown unit " + cmd.unitCode());
        UUID termId = null;
        if (cmd.vendorTerm() != null) {
            termId = reference.termByVendorAndText(cmd.vendor(), cmd.vendorTerm())
                    .map(r -> ReferenceRepo.asUuid(r, "id")).orElse(null);
        }
        UUID id = evidence.insertWorkOrder(new MapSqlParameterSource()
                .addValue("externalId", cmd.externalId())
                .addValue("unitId", ReferenceRepo.asUuid(unit, "id"))
                .addValue("vendor", cmd.vendor())
                .addValue("term", cmd.reportedTerm())
                .addValue("termId", termId)
                .addValue("openedAt", Sql.odt(cmd.openedAt()))
                .addValue("closedAt", cmd.closedAt() == null ? null : Sql.odt(cmd.closedAt()))
                .addValue("status", cmd.status() == null ? "CLOSED" : cmd.status())
                .addValue("symptom", cmd.symptom())
                .addValue("payload", cmd.rawPayload() == null ? null : Sql.jsonb(cmd.rawPayload())));
        return Map.of("id", id, "externalId", cmd.externalId(), "termAttachmentId",
                termId == null ? "UNLINKED" : termId);
    }

    /** Build the resolution context for an alarm row, at its occurrence instant. */
    public MappingService.AlarmContext contextForAlarm(Map<String, Object> alarm) {
        Map<String, Object> unit = ReferenceRepo.require(
                reference.unitById(ReferenceRepo.asUuid(alarm, "unit_id")),
                "UNIT_NOT_FOUND", "Alarm references a missing unit");
        UUID modelId = ReferenceRepo.asUuid(unit, "model_id");
        UUID manufacturerId = ReferenceRepo.asUuid(unit, "manufacturer_id");
        Integer sequence = null;
        String version = null;
        UUID firmwareId = ReferenceRepo.asUuid(alarm, "firmware_version_id");
        if (firmwareId != null) {
            Map<String, Object> fw = reference.firmwareById(firmwareId).orElse(null);
            if (fw != null) {
                sequence = ReferenceRepo.asInt(fw, "sequence");
                version = ReferenceRepo.asString(fw, "version");
            }
        }
        Map<String, Object> attributes = Sql.readMap(alarm.get("attributes"));
        return new MappingService.AlarmContext(modelId, manufacturerId, sequence, version, attributes);
    }

    public record NewAlarm(
            String externalId,
            String unitCode,
            String sourceSystemCode,
            String sourceCode,
            String vendor,
            String vendorTerm,
            String firmwareVersion,
            Instant occurredAt,
            Instant receivedAt,
            String ingestType,
            Map<String, Object> attributes,
            Map<String, Object> rawPayload) {
    }

    public record NewWorkOrder(
            String externalId,
            String unitCode,
            String vendor,
            String reportedTerm,
            String vendorTerm,
            Instant openedAt,
            Instant closedAt,
            String status,
            String symptom,
            Map<String, Object> rawPayload) {
    }
}
