package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.MappingRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.MappingDtos;
import com.windfarm.faultref.web.dto.MappingDtos.Candidate;
import com.windfarm.faultref.web.dto.MappingDtos.Resolution;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Time-validity assertion model (history model A).
 *
 * Resolution order for one source against one alarm context:
 *   1. time validity      [valid_from, valid_to)
 *   2. scope              model > manufacturer > global
 *   3. firmware range     inclusive sequence bounds when the assertion declares them
 *   4. alarm attributes   all qualifier key/values must match
 *   5. specificity        most specific tier wins; lower tiers are suppressed
 *   6. conflict           ties inside the winning tier with distinct concepts are PRESERVED
 *   7. adjudication       an approved, time-valid engineer decision breaks a tie explicitly
 */
@Service
public class MappingService {

    private final MappingRepo mappings;
    private final ReferenceRepo reference;

    public MappingService(MappingRepo mappings, ReferenceRepo reference) {
        this.mappings = mappings;
        this.reference = reference;
    }

    // ------------------------------------------------------------------
    // Assertion creation
    // ------------------------------------------------------------------

    @Transactional
    public UUID createAssertion(CreateAssertion cmd) {
        UUID conceptId = conceptId(cmd.conceptCode());
        if (cmd.sourceCodeId() == null && cmd.termAttachmentId() == null) {
            throw ApiException.badRequest("SOURCE_REQUIRED", "Either sourceCodeId or termAttachmentId is required");
        }
        if (cmd.sourceCodeId() != null && cmd.termAttachmentId() != null) {
            throw ApiException.badRequest("SOURCE_AMBIGUOUS", "Provide exactly one of sourceCodeId / termAttachmentId");
        }
        if (cmd.sourceCodeId() != null) {
            ReferenceRepo.require(reference.sourceCodeById(cmd.sourceCodeId()), "SOURCE_CODE_NOT_FOUND",
                    "Unknown source code " + cmd.sourceCodeId());
        }
        if (cmd.termAttachmentId() != null) {
            ReferenceRepo.require(reference.termById(cmd.termAttachmentId()), "TERM_NOT_FOUND",
                    "Unknown term attachment " + cmd.termAttachmentId());
        }

        UUID modelId = null;
        UUID manufacturerId = null;
        Integer fromSeq = null;
        Integer toSeq = null;
        if (cmd.manufacturerCode() != null) {
            Map<String, Object> man = ReferenceRepo.require(reference.manufacturerByCode(cmd.manufacturerCode()),
                    "MANUFACTURER_NOT_FOUND", "Unknown manufacturer " + cmd.manufacturerCode());
            manufacturerId = ReferenceRepo.asUuid(man, "id");
        }
        if (cmd.modelCode() != null) {
            String manCode = cmd.manufacturerCode();
            if (manCode == null) {
                throw ApiException.badRequest("MODEL_SCOPE_INVALID", "modelCode requires manufacturerCode");
            }
            Map<String, Object> model = ReferenceRepo.require(
                    reference.modelByCode(manCode, cmd.modelCode()),
                    "MODEL_NOT_FOUND", "Unknown model " + manCode + "/" + cmd.modelCode());
            modelId = ReferenceRepo.asUuid(model, "id");
            manufacturerId = ReferenceRepo.asUuid(model, "manufacturer_id");
        }

        if (cmd.firmwareFrom() != null || cmd.firmwareTo() != null) {
            if (modelId == null) {
                throw ApiException.badRequest("FIRMWARE_SCOPE_INVALID",
                        "Firmware bounds require a model scope");
            }
            if (cmd.firmwareFrom() != null) {
                Map<String, Object> fw = ReferenceRepo.require(
                        reference.firmwareByModelAndVersion(modelId, cmd.firmwareFrom()),
                        "FIRMWARE_NOT_FOUND", "Unknown firmware " + cmd.firmwareFrom());
                fromSeq = ReferenceRepo.asInt(fw, "sequence");
            }
            if (cmd.firmwareTo() != null) {
                Map<String, Object> fw = ReferenceRepo.require(
                        reference.firmwareByModelAndVersion(modelId, cmd.firmwareTo()),
                        "FIRMWARE_NOT_FOUND", "Unknown firmware " + cmd.firmwareTo());
                toSeq = ReferenceRepo.asInt(fw, "sequence");
            }
            if (fromSeq != null && toSeq != null && fromSeq > toSeq) {
                throw ApiException.unprocessable("FIRMWARE_RANGE_INVALID",
                        "firmwareFrom sequence " + fromSeq + " is after firmwareTo sequence " + toSeq);
            }
        }

        Instant validFrom = cmd.validFrom() == null ? Instant.now() : cmd.validFrom();
        Instant validTo = cmd.validTo();
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw ApiException.unprocessable("VALIDITY_INVALID", "validTo must be after validFrom");
        }

        BigDecimal confidence = cmd.confidence() == null ? null : BigDecimal.valueOf(cmd.confidence());
        if (confidence != null && (confidence.doubleValue() < 0 || confidence.doubleValue() > 1)) {
            throw ApiException.unprocessable("CONFIDENCE_INVALID", "confidence must be within [0,1]");
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("sourceCodeId", cmd.sourceCodeId())
                .addValue("termId", cmd.termAttachmentId())
                .addValue("conceptId", conceptId)
                .addValue("defVersion", cmd.definitionVersion())
                .addValue("scopeManufacturerId", manufacturerId)
                .addValue("scopeModelId", modelId)
                .addValue("firmwareFrom", cmd.firmwareFrom())
                .addValue("firmwareTo", cmd.firmwareTo())
                .addValue("firmwareFromSeq", fromSeq)
                .addValue("firmwareToSeq", toSeq)
                .addValue("qualifiers", Sql.jsonb(cmd.qualifiers() == null ? Map.of() : cmd.qualifiers()))
                .addValue("confidence", confidence)
                .addValue("state", cmd.state() == null ? "ACTIVE" : cmd.state())
                .addValue("validFrom", Sql.odt(validFrom))
                .addValue("validTo", Sql.odt(validTo))
                .addValue("supersedesId", cmd.supersedesId())
                .addValue("approvalRef", cmd.approvalRef())
                .addValue("approvedBy", cmd.approvedBy())
                .addValue("approvedAt", cmd.approvedBy() == null ? null : Sql.odt(Instant.now()))
                .addValue("notes", cmd.notes());

        UUID id = mappings.insertAssertion(p);

        if (cmd.supersedesId() != null) {
            Map<String, Object> prior = ReferenceRepo.require(mappings.assertionById(cmd.supersedesId()),
                    "ASSERTION_NOT_FOUND", "Unknown superseded assertion " + cmd.supersedesId());
            if (!"ACTIVE".equals(ReferenceRepo.asString(prior, "state"))) {
                throw ApiException.conflict("ASSERTION_NOT_ACTIVE",
                        "Assertion " + cmd.supersedesId() + " is not ACTIVE and cannot be superseded");
            }
            mappings.markSuperseded(cmd.supersedesId(), validFrom);
        }
        return id;
    }

    @Transactional
    public void closeAssertion(UUID id, Instant validTo, String state) {
        Map<String, Object> a = ReferenceRepo.require(mappings.assertionById(id), "ASSERTION_NOT_FOUND",
                "Unknown assertion " + id);
        Instant from = ReferenceRepo.asInstant(a, "valid_from");
        if (validTo == null) {
            validTo = Instant.now();
        }
        if (!validTo.isAfter(from)) {
            throw ApiException.unprocessable("VALIDITY_INVALID",
                    "Close instant must be after assertion valid_from (" + from + ")");
        }
        String newState = state == null ? "RETRACTED" : state;
        if (!Set.of("RETRACTED", "SUPERSEDED").contains(newState)) {
            throw ApiException.badRequest("STATE_INVALID", "Close state must be RETRACTED or SUPERSEDED");
        }
        mappings.closeAssertion(id, validTo, newState);
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * @param codeId   source code id (nullable if term)
     * @param termId   term attachment id (nullable if code)
     * @param context  alarm/unit context used for scope + firmware + qualifier evaluation
     * @param at       business instant (alarm occurrence time, or replay point)
     * @param cutoff   optional knowledge cutoff: ignore assertions recorded after it (replay honesty)
     */
    public Resolution resolve(UUID codeId, UUID termId, AlarmContext context, Instant at, Instant cutoff) {
        List<Map<String, Object>> rows = codeId != null
                ? mappings.activeCodeCandidates(codeId, at)
                : mappings.activeTermCandidates(termId, at);
        if (cutoff != null) {
            // Knowledge cutoff asks "what was approved by then", not "when did the
            // row land in this database". recorded_at is ingestion metadata and is
            // meaningless for historical audits.
            rows = rows.stream().filter(r -> {
                Instant approvedAt = ReferenceRepo.asInstant(r, "approved_at");
                Instant validFrom = ReferenceRepo.asInstant(r, "valid_from");
                Instant knownAt = approvedAt != null ? approvedAt : validFrom;
                return knownAt == null || !knownAt.isAfter(cutoff);
            }).toList();
        }

        List<Candidate> kept = new ArrayList<>();
        int excludedByScope = 0;
        int excludedByFirmware = 0;
        int excludedByQualifier = 0;

        for (Map<String, Object> r : rows) {
            UUID rowModel = ReferenceRepo.asUuid(r, "scope_model_id");
            UUID rowMan = ReferenceRepo.asUuid(r, "scope_manufacturer_id");
            if (!scopeMatches(rowModel, rowMan, context)) {
                excludedByScope++;
                continue;
            }
            Integer fromSeq = ReferenceRepo.asInt(r, "firmware_from_seq");
            Integer toSeq = ReferenceRepo.asInt(r, "firmware_to_seq");
            if ((fromSeq != null || toSeq != null) && !firmwareMatches(fromSeq, toSeq, context.firmwareSequence())) {
                excludedByFirmware++;
                continue;
            }
            Map<String, Object> qualifiers = Sql.readMap(r.get("qualifiers"));
            if (!qualifiersMatch(qualifiers, context.attributes())) {
                excludedByQualifier++;
                continue;
            }
            kept.add(toCandidate(r, specificity(rowModel, rowMan, fromSeq, toSeq, qualifiers)));
        }

        List<Candidate> candidates = kept;
        if (candidates.isEmpty()) {
            String reason = rows.isEmpty()
                    ? "No ACTIVE assertion covered the alarm occurrence time"
                    : "All " + rows.size() + " active assertion(s) were excluded (scope=" + excludedByScope
                      + ", firmware=" + excludedByFirmware + ", qualifiers=" + excludedByQualifier + ")";
            return new Resolution("NO_MAPPING", reason, List.of(), null, null, null, null, 0);
        }

        int best = candidates.stream().mapToInt(Candidate::specificity).max().orElse(0);
        List<Candidate> top = candidates.stream().filter(c -> c.specificity() == best).toList();
        List<Candidate> suppressed = candidates.stream().filter(c -> c.specificity() < best).toList();

        Set<UUID> concepts = new LinkedHashSet<>();
        top.forEach(c -> concepts.add(c.conceptId()));

        if (concepts.size() == 1) {
            return new Resolution("RESOLVED",
                    "Single concept " + top.get(0).conceptCode() + " at specificity " + best,
                    candidates, top.get(0), null, null, null, suppressed.size());
        }

        // Multiple distinct concepts at the winning specificity: preserve the conflict.
        List<Map<String, Object>> adjudications = codeId != null
                ? mappings.adjudicationsForCode(codeId)
                : mappings.adjudicationsForTerm(termId);
        Map<String, Object> chosenAdj = pickAdjudication(adjudications, top, context, at);
        if (chosenAdj != null) {
            UUID chosenConcept = ReferenceRepo.asUuid(chosenAdj, "chosen_concept_id");
            Candidate chosen = top.stream().filter(c -> c.conceptId().equals(chosenConcept)).findFirst().orElse(null);
            if (chosen != null) {
                return new Resolution("RESOLVED_BY_ADJUDICATION",
                        "Engineer adjudication resolved a preserved conflict to " + chosen.conceptCode(),
                        candidates, chosen,
                        ReferenceRepo.asUuid(chosenAdj, "id"),
                        ReferenceRepo.asString(chosenAdj, "approval_ref"),
                        ReferenceRepo.asString(chosenAdj, "decision"),
                        suppressed.size());
            }
        }

        return new Resolution("CONFLICT",
                "Preserved conflict: " + concepts.size() + " distinct concepts are equally specific at instant "
                        + at + " (" + top.stream().map(Candidate::conceptCode).distinct().toList() + ")",
                candidates, null, null, null, null, suppressed.size());
    }

    /** Candidate list for the conflict-oriented API (all currently active assertions). */
    private Map<String, Object> pickAdjudication(List<Map<String, Object>> adjudications,
                                                 List<Candidate> top,
                                                 AlarmContext context,
                                                 Instant at) {
        Set<UUID> concepts = new LinkedHashSet<>();
        top.forEach(c -> concepts.add(c.conceptId()));
        for (Map<String, Object> adj : adjudications) {
            UUID modelScope = ReferenceRepo.asUuid(adj, "scope_model_id");
            if (modelScope != null && !modelScope.equals(context.modelId())) {
                continue;
            }
            Instant from = ReferenceRepo.asInstant(adj, "valid_from");
            Instant to = ReferenceRepo.asInstant(adj, "valid_to");
            if (from != null && from.isAfter(at)) {
                continue;
            }
            if (to != null && !to.isAfter(at)) {
                continue;
            }
            UUID a = ReferenceRepo.asUuid(adj, "concept_a_id");
            UUID b = ReferenceRepo.asUuid(adj, "concept_b_id");
            if (concepts.contains(a) && concepts.contains(b)) {
                return adj;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Filtering helpers
    // ------------------------------------------------------------------

    private boolean scopeMatches(UUID rowModel, UUID rowManufacturer, AlarmContext ctx) {
        if (rowModel != null) {
            return ctx.modelId() != null && rowModel.equals(ctx.modelId());
        }
        if (rowManufacturer != null) {
            return ctx.manufacturerId() != null && rowManufacturer.equals(ctx.manufacturerId());
        }
        return true;
    }

    private boolean firmwareMatches(Integer fromSeq, Integer toSeq, Integer alarmSeq) {
        if (alarmSeq == null) {
            // A firmware-scoped assertion cannot be applied when the alarm has no
            // resolvable firmware context. Fail closed rather than guess.
            return false;
        }
        if (fromSeq != null && alarmSeq < fromSeq) {
            return false;
        }
        return toSeq == null || alarmSeq <= toSeq;
    }

    private boolean qualifiersMatch(Map<String, Object> qualifiers, Map<String, Object> attributes) {
        if (qualifiers == null || qualifiers.isEmpty()) {
            return true;
        }
        if (attributes == null) {
            return false;
        }
        for (Map.Entry<String, Object> e : qualifiers.entrySet()) {
            Object actual = attributes.get(e.getKey());
            if (actual == null || !normalise(actual).equals(normalise(e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private String normalise(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v).trim();
    }

    private int specificity(UUID model, UUID manufacturer, Integer fromSeq, Integer toSeq,
                            Map<String, Object> qualifiers) {
        int score = 0;
        if (model != null) {
            score += 1000;
        } else if (manufacturer != null) {
            score += 500;
        }
        if (fromSeq != null || toSeq != null) {
            score += 100;
        }
        score += Math.min(qualifiers == null ? 0 : qualifiers.size(), 20) * 5;
        return score;
    }

    /** Public view of one candidate row for the conflict API. */
    public Candidate toCandidateView(Map<String, Object> r) {
        UUID rowModel = ReferenceRepo.asUuid(r, "scope_model_id");
        UUID rowMan = ReferenceRepo.asUuid(r, "scope_manufacturer_id");
        Integer fromSeq = ReferenceRepo.asInt(r, "firmware_from_seq");
        Integer toSeq = ReferenceRepo.asInt(r, "firmware_to_seq");
        Map<String, Object> qualifiers = Sql.readMap(r.get("qualifiers"));
        return toCandidate(r, specificity(rowModel, rowMan, fromSeq, toSeq, qualifiers));
    }

    private Candidate toCandidate(Map<String, Object> r, int specificity) {        return new Candidate(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asUuid(r, "concept_id"),
                ReferenceRepo.asString(r, "concept_code"),
                ReferenceRepo.asString(r, "concept_name"),
                ReferenceRepo.asInt(r, "definition_version"),
                ReferenceRepo.asString(r, "definition"),
                ReferenceRepo.asString(r, "def_approval_ref"),
                specificity,
                Sql.decimal(r.get("confidence")),
                ReferenceRepo.asString(r, "model_code"),
                ReferenceRepo.asString(r, "manufacturer_code"),
                ReferenceRepo.asString(r, "firmware_from"),
                ReferenceRepo.asString(r, "firmware_to"),
                ReferenceRepo.asInstant(r, "valid_from"),
                ReferenceRepo.asInstant(r, "valid_to"),
                ReferenceRepo.asString(r, "approval_ref"));
    }

    private UUID conceptId(String code) {
        Map<String, Object> c = ReferenceRepo.require(reference.conceptByCode(code), "CONCEPT_NOT_FOUND",
                "Unknown concept " + code);
        return ReferenceRepo.asUuid(c, "id");
    }

    /** Context of one alarm/unit used to evaluate scope, firmware and qualifiers. */
    public record AlarmContext(UUID modelId, UUID manufacturerId, Integer firmwareSequence,
                               String firmwareVersion, Map<String, Object> attributes) {
        public AlarmContext {
            attributes = attributes == null ? new LinkedHashMap<>() : attributes;
        }

        public static AlarmContext of(UUID modelId, UUID manufacturerId) {
            return new AlarmContext(modelId, manufacturerId, null, null, new LinkedHashMap<>());
        }
    }

    public record CreateAssertion(
            UUID sourceCodeId,
            UUID termAttachmentId,
            String conceptCode,
            int definitionVersion,
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

    public record Adjudicate(
            UUID sourceCodeId,
            UUID termAttachmentId,
            String conceptACode,
            String conceptBCode,
            String chosenConceptCode,
            String modelCode,
            String manufacturerCode,
            Instant validFrom,
            Instant validTo,
            String decision,
            String adjudicatedBy,
            String approvalRef) {
    }

    @Transactional
    public UUID adjudicate(Adjudicate cmd) {
        if (cmd.sourceCodeId() == null && cmd.termAttachmentId() == null) {
            throw ApiException.badRequest("SOURCE_REQUIRED", "Either sourceCodeId or termAttachmentId is required");
        }
        UUID a = conceptId(cmd.conceptACode());
        UUID b = conceptId(cmd.conceptBCode());
        UUID chosen = conceptId(cmd.chosenConceptCode());
        if (!chosen.equals(a) && !chosen.equals(b)) {
            throw ApiException.unprocessable("ADJUDICATION_INVALID",
                    "Chosen concept must be one of the conflicting concepts");
        }
        if (Objects.equals(a, b)) {
            throw ApiException.unprocessable("ADJUDICATION_INVALID",
                    "Conflicting concepts must be distinct");
        }
        UUID modelId = null;
        if (cmd.modelCode() != null) {
            String man = cmd.manufacturerCode();
            if (man == null) {
                throw ApiException.badRequest("MODEL_SCOPE_INVALID", "modelCode requires manufacturerCode");
            }
            Map<String, Object> model = ReferenceRepo.require(reference.modelByCode(man, cmd.modelCode()),
                    "MODEL_NOT_FOUND", "Unknown model " + man + "/" + cmd.modelCode());
            modelId = ReferenceRepo.asUuid(model, "id");
        }
        Instant validFrom = cmd.validFrom() == null ? Instant.now() : cmd.validFrom();
        if (cmd.validTo() != null && !cmd.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("VALIDITY_INVALID", "validTo must be after validFrom");
        }
        return mappings.insertAdjudication(new MapSqlParameterSource()
                .addValue("sourceCodeId", cmd.sourceCodeId())
                .addValue("termId", cmd.termAttachmentId())
                .addValue("conceptA", a)
                .addValue("conceptB", b)
                .addValue("chosen", chosen)
                .addValue("scopeModelId", modelId)
                .addValue("validFrom", Sql.odt(validFrom))
                .addValue("validTo", Sql.odt(cmd.validTo()))
                .addValue("decision", cmd.decision())
                .addValue("by", cmd.adjudicatedBy())
                .addValue("at", Sql.odt(Instant.now()))
                .addValue("approvalRef", cmd.approvalRef()));
    }
}
