package com.windfarm.faultref.repo;

import com.windfarm.faultref.web.dto.MappingDtos.AssertionView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MappingRepo {

    private static final org.springframework.jdbc.core.RowMapper<Map<String, Object>> MAP =
            (rs, n) -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof java.sql.Timestamp ts) {
                        v = ts.toInstant();
                    }
                    m.put(md.getColumnLabel(i).toLowerCase(), v);
                }
                return m;
            };

    private final NamedParameterJdbcTemplate jdbc;

    public MappingRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertAssertion(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into mapping_assertion
                    (source_code_id, term_attachment_id, concept_id, definition_version,
                     scope_manufacturer_id, scope_model_id, firmware_from, firmware_to,
                     firmware_from_seq, firmware_to_seq, qualifiers, confidence, state,
                     valid_from, valid_to, supersedes_id, approval_ref, approved_by, approved_at, notes)
                values
                    (:sourceCodeId, :termId, :conceptId, :defVersion,
                     :scopeManufacturerId, :scopeModelId, :firmwareFrom, :firmwareTo,
                     :firmwareFromSeq, :firmwareToSeq, :qualifiers::jsonb, :confidence, :state,
                     :validFrom, :validTo, :supersedesId, :approvalRef, :approvedBy, :approvedAt, :notes)
                returning id
                """, p, UUID.class);
    }

    public void markSuperseded(UUID id, Instant validTo) {
        jdbc.update("""
                update mapping_assertion set state = 'SUPERSEDED', valid_to = :vt where id = :id
                """, new MapSqlParameterSource().addValue("vt", Sql.odt(validTo)).addValue("id", id));
    }

    /**
     * Groups of currently-active assertions that preserve a conflict: same source,
     * same effective scope, more than one distinct concept. Nothing is collapsed.
     */
    public List<Map<String, Object>> activeConflictGroups() {
        return jdbc.query("""
                with active as (
                    select a.id, a.source_code_id, a.term_attachment_id, a.concept_id,
                           coalesce(a.scope_manufacturer_id::text, '') as man,
                           coalesce(a.scope_model_id::text, '') as mdl,
                           coalesce(a.firmware_from_seq::text, '') as fw
                    from mapping_assertion a
                    where a.state = 'ACTIVE' and (a.valid_to is null or a.valid_to > now())
                )
                select source_code_id, term_attachment_id, man, mdl, fw,
                       count(*) as assertion_count, count(distinct concept_id) as concept_count
                from active
                group by source_code_id, term_attachment_id, man, mdl, fw
                having count(distinct concept_id) > 1
                order by source_code_id, term_attachment_id
                """, Map.of(), MAP);
    }

    /** All active assertions within one (man, mdl, fw) scope group. */
    public List<Map<String, Object>> activeCandidatesForGroup(UUID codeId, UUID termId,
                                                              String man, String mdl, String fw) {
        return jdbc.query("""
                select a.*, c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition, d.approval_ref as def_approval_ref,
                       m.code as model_code, man2.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man2 on man2.id = a.scope_manufacturer_id
                where a.state = 'ACTIVE' and (a.valid_to is null or a.valid_to > now())
                  and ((cast(:codeId as uuid) is not null and a.source_code_id = cast(:codeId as uuid))
                       or (cast(:termId as uuid) is not null and a.term_attachment_id = cast(:termId as uuid)))
                  and coalesce(a.scope_manufacturer_id::text, '') = :man
                  and coalesce(a.scope_model_id::text, '') = :mdl
                  and coalesce(a.firmware_from_seq::text, '') = :fw
                order by a.valid_from
                """, new MapSqlParameterSource().addValue("codeId", codeId).addValue("termId", termId)
                .addValue("man", man == null ? "" : man).addValue("mdl", mdl == null ? "" : mdl)
                .addValue("fw", fw == null ? "" : fw), MAP);
    }

    public void closeAssertion(UUID id, Instant validTo, String state) {
        jdbc.update("""
                update mapping_assertion set state = :state, valid_to = :vt where id = :id
                """, new MapSqlParameterSource().addValue("state", state)
                .addValue("vt", Sql.odt(validTo)).addValue("id", id));
    }

    public Optional<Map<String, Object>> assertionById(UUID id) {
        return jdbc.query("""
                select a.*, c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition, d.approval_ref as def_approval_ref, d.approved_by as def_approved_by,
                       d.approved_at as def_approved_at,
                       m.code as model_code, man.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man on man.id = a.scope_manufacturer_id
                where a.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    /** All assertions for a source code, regardless of state (history view). */
    public List<Map<String, Object>> assertionsForCode(UUID codeId) {
        return jdbc.query("""
                select a.*, c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition, d.approval_ref as def_approval_ref,
                       m.code as model_code, man.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man on man.id = a.scope_manufacturer_id
                where a.source_code_id = :id
                order by a.valid_from, a.recorded_at
                """, Map.of("id", codeId), MAP);
    }

    public List<Map<String, Object>> assertionsForTerm(UUID termId) {
        return jdbc.query("""
                select a.*, c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition, d.approval_ref as def_approval_ref,
                       m.code as model_code, man.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man on man.id = a.scope_manufacturer_id
                where a.term_attachment_id = :id
                order by a.valid_from, a.recorded_at
                """, Map.of("id", termId), MAP);
    }

    /**
     * ACTIVE assertions applicable at the given business instant for a code.
     * Validity is half-open [valid_from, valid_to).
     */
    public List<Map<String, Object>> activeCodeCandidates(UUID codeId, Instant at) {
        return jdbc.query("""
                select a.*, c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition, d.approval_ref as def_approval_ref, d.approved_by as def_approved_by,
                       d.approved_at as def_approved_at,
                       m.code as model_code, man.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man on man.id = a.scope_manufacturer_id
                where a.source_code_id = :id
                  and a.state = 'ACTIVE'
                  and a.valid_from <= :at
                  and (a.valid_to is null or a.valid_to > :at)
                """, new MapSqlParameterSource().addValue("id", codeId).addValue("at", Sql.odt(at)), MAP);
    }

    public List<Map<String, Object>> activeTermCandidates(UUID termId, Instant at) {
        return jdbc.query("""
                select a.*, c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition, d.approval_ref as def_approval_ref, d.approved_by as def_approved_by,
                       d.approved_at as def_approved_at,
                       m.code as model_code, man.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man on man.id = a.scope_manufacturer_id
                where a.term_attachment_id = :id
                  and a.state = 'ACTIVE'
                  and a.valid_from <= :at
                  and (a.valid_to is null or a.valid_to > :at)
                """, new MapSqlParameterSource().addValue("id", termId).addValue("at", Sql.odt(at)), MAP);
    }


    public UUID insertAdjudication(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into mapping_adjudication
                    (source_code_id, term_attachment_id, concept_a_id, concept_b_id, chosen_concept_id,
                     scope_model_id, valid_from, valid_to, decision, adjudicated_by, adjudicated_at, approval_ref)
                values
                    (:sourceCodeId, :termId, :conceptA, :conceptB, :chosen,
                     :scopeModelId, :validFrom, :validTo, :decision, :by, :at, :approvalRef)
                returning id
                """, p, UUID.class);
    }

    public List<Map<String, Object>> adjudicationsForCode(UUID codeId) {
        return jdbc.query("""
                select adj.*, ca.code as concept_a_code, cb.code as concept_b_code, cc.code as chosen_concept_code,
                       m.code as model_code
                from mapping_adjudication adj
                join concept ca on ca.id = adj.concept_a_id
                join concept cb on cb.id = adj.concept_b_id
                join concept cc on cc.id = adj.chosen_concept_id
                left join turbine_model m on m.id = adj.scope_model_id
                where adj.source_code_id = :id
                order by adj.adjudicated_at
                """, Map.of("id", codeId), MAP);
    }

    public List<Map<String, Object>> adjudicationsForTerm(UUID termId) {
        return jdbc.query("""
                select adj.*, ca.code as concept_a_code, cb.code as concept_b_code, cc.code as chosen_concept_code,
                       m.code as model_code
                from mapping_adjudication adj
                join concept ca on ca.id = adj.concept_a_id
                join concept cb on cb.id = adj.concept_b_id
                join concept cc on cc.id = adj.chosen_concept_id
                left join turbine_model m on m.id = adj.scope_model_id
                where adj.term_attachment_id = :id
                order by adj.adjudicated_at
                """, Map.of("id", termId), MAP);
    }

    public Optional<Map<String, Object>> adjudicationById(UUID id) {
        return jdbc.query("""
                select adj.*, ca.code as concept_a_code, cb.code as concept_b_code, cc.code as chosen_concept_code,
                       m.code as model_code
                from mapping_adjudication adj
                join concept ca on ca.id = adj.concept_a_id
                join concept cb on cb.id = adj.concept_b_id
                join concept cc on cc.id = adj.chosen_concept_id
                left join turbine_model m on m.id = adj.scope_model_id
                where adj.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    /** Adjudications that apply to a source at an instant under a scope. */
    public List<Map<String, Object>> applicableAdjudications(UUID codeId, UUID termId, UUID modelId, Instant at) {
        return jdbc.query("""
                select adj.*, ca.code as concept_a_code, cb.code as concept_b_code, cc.code as chosen_concept_code,
                       m.code as model_code
                from mapping_adjudication adj
                join concept ca on ca.id = adj.concept_a_id
                join concept cb on cb.id = adj.concept_b_id
                join concept cc on cc.id = adj.chosen_concept_id
                left join turbine_model m on m.id = adj.scope_model_id
                where ((cast(:codeId as uuid) is null and adj.term_attachment_id = cast(:termId as uuid))
                       or (cast(:termId as uuid) is null and adj.source_code_id = cast(:codeId as uuid)))
                  and (adj.scope_model_id is null or adj.scope_model_id = :modelId)
                  and adj.valid_from <= :at
                  and (adj.valid_to is null or adj.valid_to > :at)
                order by adj.adjudicated_at desc
                """, new MapSqlParameterSource()
                .addValue("codeId", codeId).addValue("termId", termId)
                .addValue("modelId", modelId).addValue("at", Sql.odt(at)), MAP);
    }

    public List<AssertionView> toAssertionViews(List<Map<String, Object>> rows) {
        return rows.stream().map(this::toAssertionView).toList();
    }

    public AssertionView toAssertionView(Map<String, Object> r) {
        return new AssertionView(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asUuid(r, "source_code_id"),
                ReferenceRepo.asUuid(r, "term_attachment_id"),
                ReferenceRepo.asUuid(r, "concept_id"),
                ReferenceRepo.asString(r, "concept_code"),
                ReferenceRepo.asString(r, "concept_name"),
                ReferenceRepo.asInt(r, "definition_version"),
                ReferenceRepo.asString(r, "definition"),
                ReferenceRepo.asString(r, "manufacturer_code"),
                ReferenceRepo.asString(r, "model_code"),
                ReferenceRepo.asString(r, "firmware_from"),
                ReferenceRepo.asString(r, "firmware_to"),
                Sql.readMap(r.get("qualifiers")),
                ReferenceRepo.asString(r, "state"),
                ReferenceRepo.asInstant(r, "valid_from"),
                ReferenceRepo.asInstant(r, "valid_to"),
                ReferenceRepo.asInstant(r, "recorded_at"),
                ReferenceRepo.asString(r, "approval_ref"),
                ReferenceRepo.asString(r, "approved_by"),
                ReferenceRepo.asInstant(r, "approved_at"),
                Sql.decimal(r.get("confidence")),
                ReferenceRepo.asString(r, "notes"));
    }
}
