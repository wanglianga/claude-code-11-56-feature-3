package com.windfarm.faultref.repo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DiagnosisRepo {

    private static final RowMapper<Map<String, Object>> MAP = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
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

    public DiagnosisRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertRun(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into diagnosis_run
                    (mode, basis, release_id, as_of, pinned_catalog_version_id, pinned_inventory_snapshot_id,
                     evaluation_time, knowledge_cutoff, evaluated_at, status, created_by, request, summary)
                values
                    (:mode, :basis, :releaseId, :asOf, :catalogId, :inventoryId,
                     :evaluationTime, :knowledgeCutoff, :evaluatedAt, :status, :createdBy, :request::jsonb, :summary::jsonb)
                returning id
                """, p, UUID.class);
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbc.update("""
                update diagnosis_run set status = 'COMPLETED', summary = :summary::jsonb, finished_at = now()
                where id = :id
                """, new MapSqlParameterSource().addValue("summary", Sql.jsonb(summary)).addValue("id", runId));
    }

    public void failRun(UUID runId, String reason) {
        jdbc.update("""
                update diagnosis_run set status = 'FAILED', summary = :summary::jsonb, finished_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("summary", Sql.jsonb(Map.of("error", reason))).addValue("id", runId));
    }

    public Optional<Map<String, Object>> runById(UUID id) {
        return jdbc.query("select * from diagnosis_run where id = :id", Map.of("id", id), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> listRuns(int limit) {
        return jdbc.query("""
                select r.*, rel.version_label as release_label
                from diagnosis_run r left join release rel on rel.id = r.release_id
                order by r.created_at desc limit :limit
                """, Map.of("limit", limit), MAP);
    }

    public UUID insertResult(UUID runId, UUID alarmId, String status, UUID primaryConceptId, boolean adjudicated,
                             String explanation, int findingCount) {
        return jdbc.queryForObject("""
                insert into diagnosis_result
                    (run_id, alarm_id, status, primary_concept_id, adjudicated, explanation, finding_count)
                values (:runId, :alarmId, :status, :primaryConceptId, :adjudicated, :explanation, :findingCount)
                returning id
                """, new MapSqlParameterSource()
                .addValue("runId", runId).addValue("alarmId", alarmId).addValue("status", status)
                .addValue("primaryConceptId", primaryConceptId).addValue("adjudicated", adjudicated)
                .addValue("explanation", explanation).addValue("findingCount", findingCount), UUID.class);
    }

    public UUID insertFinding(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into diagnosis_finding
                    (result_id, concept_id, concept_code, concept_name, definition_version, definition_text,
                     confidence, disposition_status, disposition, mapping_chain,
                     evidence_assertion_ids, evidence_release_entry_ids)
                values
                    (:resultId, :conceptId, :conceptCode, :conceptName, :definitionVersion, :definitionText,
                     :confidence, :dispositionStatus, :disposition::jsonb, :mappingChain::jsonb,
                     :assertionIds, :releaseEntryIds)
                returning id
                """, p, UUID.class);
    }

    public List<Map<String, Object>> resultsForRun(UUID runId) {
        return jdbc.query("""
                select res.*, a.external_id, a.occurred_at, a.received_at, a.ingest_type,
                       a.raw_code, a.attributes, a.source_code_id, a.term_attachment_id,
                       u.code as unit_code, ss.code as source_system_code, ss.kind as source_system_kind,
                       c.code as primary_concept_code, c.name as primary_concept_name
                from diagnosis_result res
                join alarm a on a.id = res.alarm_id
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                left join concept c on c.id = res.primary_concept_id
                where res.run_id = :rid
                order by a.occurred_at, a.external_id
                """, Map.of("rid", runId), MAP);
    }

    public Optional<Map<String, Object>> resultById(UUID id) {
        return jdbc.query("""
                select res.*, a.external_id, a.occurred_at, a.received_at, a.ingest_type,
                       a.raw_code, a.attributes, a.source_code_id, a.term_attachment_id, a.unit_id,
                       u.code as unit_code, ss.code as source_system_code, ss.kind as source_system_kind,
                       c.code as primary_concept_code, c.name as primary_concept_name
                from diagnosis_result res
                join alarm a on a.id = res.alarm_id
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                left join concept c on c.id = res.primary_concept_id
                where res.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> findingsForResult(UUID resultId) {
        return jdbc.query("""
                select f.*, c.code as concept_code_ref, c.category
                from diagnosis_finding f
                left join concept c on c.id = f.concept_id
                where f.result_id = :rid
                order by f.concept_code
                """, Map.of("rid", resultId), MAP);
    }

    public Map<String, Object> statusCounts(UUID runId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.query("""
                select status, count(*) as n from diagnosis_result where run_id = :rid group by status
                """, Map.of("rid", runId), MAP)) {
            out.put(ReferenceRepo.asString(row, "status"), ((Number) row.get("n")).longValue());
        }
        return out;
    }

    public Integer countAdjudicated(UUID runId) {
        return jdbc.queryForObject("""
                select count(*) from diagnosis_result where run_id = :rid and adjudicated = true
                """, Map.of("rid", runId), Integer.class);
    }

    public Integer countOffline(UUID runId) {
        return jdbc.queryForObject("""
                select count(*) from diagnosis_result res join alarm a on a.id = res.alarm_id
                where res.run_id = :rid and a.ingest_type = 'OFFLINE_BACKFILL'
                """, Map.of("rid", runId), Integer.class);
    }

    public List<Map<String, Object>> conceptCounts(UUID runId) {
        return jdbc.query("""
                select c.code, c.name, c.category, count(*) as findings
                from diagnosis_finding f
                join diagnosis_result res on res.id = f.result_id
                join concept c on c.id = f.concept_id
                where res.run_id = :rid
                group by c.code, c.name, c.category
                order by findings desc, c.code
                """, Map.of("rid", runId), MAP);
    }

    public JsonNode requestNode(Map<String, Object> run) {
        return Sql.readNode(run.get("request"));
    }

    public Instant asInstant(Map<String, Object> row, String col) {
        return ReferenceRepo.asInstant(row, col);
    }
}
