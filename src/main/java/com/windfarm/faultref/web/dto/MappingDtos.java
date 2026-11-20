package com.windfarm.faultref.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MappingDtos {

    private MappingDtos() {
    }

    public record AssertionView(
            UUID id,
            UUID sourceCodeId,
            UUID termAttachmentId,
            UUID conceptId,
            String conceptCode,
            String conceptName,
            int definitionVersion,
            String definition,
            String manufacturerCode,
            String modelCode,
            String firmwareFrom,
            String firmwareTo,
            Map<String, Object> qualifiers,
            String state,
            Instant validFrom,
            Instant validTo,
            Instant recordedAt,
            String approvalRef,
            String approvedBy,
            Instant approvedAt,
            BigDecimal confidence,
            String notes) {
    }

    /** One candidate assertion surviving scope + time + firmware + qualifier filtering. */
    public record Candidate(
            UUID assertionId,
            UUID conceptId,
            String conceptCode,
            String conceptName,
            int definitionVersion,
            String definition,
            String definitionApprovalRef,
            int specificity,
            BigDecimal confidence,
            String modelCode,
            String manufacturerCode,
            String firmwareFrom,
            String firmwareTo,
            Instant validFrom,
            Instant validTo,
            String approvalRef,
            UUID releaseEntryId) {

        /** Assertion-mode candidates have no frozen release entry. */
        public Candidate(UUID assertionId, UUID conceptId, String conceptCode, String conceptName,
                         int definitionVersion, String definition, String definitionApprovalRef,
                         int specificity, BigDecimal confidence, String modelCode, String manufacturerCode,
                         String firmwareFrom, String firmwareTo, Instant validFrom, Instant validTo,
                         String approvalRef) {
            this(assertionId, conceptId, conceptCode, conceptName, definitionVersion, definition,
                    definitionApprovalRef, specificity, confidence, modelCode, manufacturerCode,
                    firmwareFrom, firmwareTo, validFrom, validTo, approvalRef, null);
        }
    }

    public record Resolution(
            String outcome,
            String reason,
            List<Candidate> candidates,
            Candidate selected,
            UUID adjudicationId,
            String adjudicationRef,
            String adjudicationDecision,
            int suppressedBySpecificity,
            List<UUID> releaseEntryIds) {

        /** Assertion-mode resolutions carry no release entry ids. */
        public Resolution(String outcome, String reason, List<Candidate> candidates, Candidate selected,
                          UUID adjudicationId, String adjudicationRef, String adjudicationDecision,
                          int suppressedBySpecificity) {
            this(outcome, reason, candidates, selected, adjudicationId, adjudicationRef,
                    adjudicationDecision, suppressedBySpecificity, List.of());
        }
    }

    public record ConflictEntry(
            String sourceKind,
            String sourceSystemCode,
            String sourceCode,
            String vendor,
            String termText,
            String manufacturerCode,
            String modelCode,
            String firmwareFrom,
            String firmwareTo,
            List<Candidate> assertions,
            List<Map<String, Object>> adjudications) {
    }
}
