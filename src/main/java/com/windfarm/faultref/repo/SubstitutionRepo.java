package com.windfarm.faultref.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence for the substitution graph, part lifecycle, historical advice and stock contention. */
@Repository
public class SubstitutionRepo {

    private static final RowMapper<Map<String, Object>> MAP = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        var md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp ts) {
                v = ts.toInstant();
            } else if (v instanceof java.sql.Date d) {
                v = d.toLocalDate();
            }
            m.put(md.getColumnLabel(i).toLowerCase(), v);
        }
        return m;
    };

    private final NamedParameterJdbcTemplate jdbc;

    public SubstitutionRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- part_substitution ---------------------------------------------
    public UUID insertSubstitution(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into part_substitution
                    (catalog_version_id, from_part_id, to_part_id, substitution_kind, scope_model_id,
                     temp_min_c, temp_max_c, interface_requirement, requires_engineering_confirm,
                     valid_from, valid_to, state, approval_ref, approved_by, approved_at, notes)
                values
                    (:catalogVersionId, :fromPartId, :toPartId, :kind, :modelId,
                     :tempMin, :tempMax, :interface, :confirm,
                     :validFrom, :validTo, :state, :approvalRef, :approvedBy, :approvedAt, :notes)
                returning id
                """, p, UUID.class);
    }

    public void retractSubstitution(UUID id) {
        jdbc.update("update part_substitution set state='RETRACTED' where id=:id",
                Map.of("id", id));
    }

    public List<Map<String, Object>> substitutionsForCatalog(UUID catalogVersionId) {
        return jdbc.query("""
                select s.*, pf.part_number as from_part_number, pt.part_number as to_part_number,
                       m.code as model_code, man.code as manufacturer_code
                from part_substitution s
                join part pf on pf.id = s.from_part_id
                join part pt on pt.id = s.to_part_id
                left join turbine_model m on m.id = s.scope_model_id
                left join manufacturer man on man.id = m.manufacturer_id
                where s.catalog_version_id = :cv
                order by pf.part_number, pt.part_number
                """, Map.of("cv", catalogVersionId), MAP);
    }

    public List<Map<String, Object>> activeEdgesFrom(UUID catalogVersionId, UUID fromPartId) {
        return jdbc.query("""
                select s.*, pf.part_number as from_part_number, pt.part_number as to_part_number,
                       pt.name as to_part_name, m.code as model_code
                from part_substitution s
                join part pf on pf.id = s.from_part_id
                join part pt on pt.id = s.to_part_id
                left join turbine_model m on m.id = s.scope_model_id
                where s.catalog_version_id = :cv and s.from_part_id = :from and s.state = 'ACTIVE'
                order by pt.part_number
                """, Map.of("cv", catalogVersionId, "from", fromPartId), MAP);
    }

    public List<Map<String, Object>> allActiveEdges(UUID catalogVersionId) {
        return jdbc.query("""
                select s.*, pf.part_number as from_part_number, pt.part_number as to_part_number,
                       m.code as model_code
                from part_substitution s
                join part pf on pf.id = s.from_part_id
                join part pt on pt.id = s.to_part_id
                left join turbine_model m on m.id = s.scope_model_id
                where s.catalog_version_id = :cv and s.state = 'ACTIVE'
                order by pf.part_number, pt.part_number
                """, Map.of("cv", catalogVersionId), MAP);
    }

    // ---- part_lifecycle -------------------------------------------------
    public UUID insertLifecycle(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into part_lifecycle
                    (catalog_version_id, part_id, state, obsolete_reason, superseded_by_part_id,
                     decided_at, approved_by, approval_ref)
                values
                    (:catalogVersionId, :partId, :state, :reason, :successorId,
                     :decidedAt, :approvedBy, :approvalRef)
                returning id
                """, p, UUID.class);
    }

    public Optional<Map<String, Object>> lifecycleOf(UUID catalogVersionId, UUID partId) {
        return jdbc.query("""
                select l.*, p.part_number, sp.part_number as superseded_by_part_number
                from part_lifecycle l
                join part p on p.id = l.part_id
                left join part sp on sp.id = l.superseded_by_part_id
                where l.catalog_version_id = :cv and l.part_id = :p
                """, Map.of("cv", catalogVersionId, "p", partId), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> lifecycleForCatalog(UUID catalogVersionId) {
        return jdbc.query("""
                select l.*, p.part_number, sp.part_number as superseded_by_part_number
                from part_lifecycle l
                join part p on p.id = l.part_id
                left join part sp on sp.id = l.superseded_by_part_id
                where l.catalog_version_id = :cv
                order by p.part_number
                """, Map.of("cv", catalogVersionId), MAP);
    }

    // ---- work order recommendations ------------------------------------
    public UUID insertWorkOrderRecommendation(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into work_order_part_recommendation
                    (work_order_id, concept_code, part_id, disposition_code, catalog_version_label,
                     quantity, recommendation_status, reason, recorded_by, approval_ref)
                values
                    (:workOrderId, :conceptCode, :partId, :dispositionCode, :catalogLabel,
                     :quantity, :status, :reason, :recordedBy, :approvalRef)
                returning id
                """, p, UUID.class);
    }

    public List<Map<String, Object>> recommendationsForWorkOrder(UUID workOrderId) {
        return jdbc.query("""
                select r.*, w.external_id as work_order_external_id, p.part_number
                from work_order_part_recommendation r
                join work_order w on w.id = r.work_order_id
                left join part p on p.id = r.part_id
                where r.work_order_id = :wo
                order by r.recorded_at, r.id
                """, Map.of("wo", workOrderId), MAP);
    }

    public List<Map<String, Object>> allWorkOrderRecommendations() {
        return jdbc.query("""
                select r.*, w.external_id as work_order_external_id, p.part_number
                from work_order_part_recommendation r
                join work_order w on w.id = r.work_order_id
                left join part p on p.id = r.part_id
                order by w.external_id, r.recorded_at
                """, Map.of(), MAP);
    }

    // ---- stock contention ----------------------------------------------
    public UUID insertContention(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into stock_contention
                    (inventory_snapshot_id, part_id, warehouse, status, competing_demand,
                     confirmed_by, confirmed_at, notes)
                values
                    (:snapshotId, :partId, :warehouse, :status, :demand,
                     :confirmedBy, :confirmedAt, :notes)
                returning id
                """, p, UUID.class);
    }

    public List<Map<String, Object>> contentionsForSnapshot(UUID snapshotId) {
        return jdbc.query("""
                select sc.*, p.part_number, s.label as inventory_snapshot_label
                from stock_contention sc
                join part p on p.id = sc.part_id
                join inventory_snapshot s on s.id = sc.inventory_snapshot_id
                where sc.inventory_snapshot_id = :s
                order by p.part_number, sc.created_at
                """, Map.of("s", snapshotId), MAP);
    }

    /** Latest open (non-released) contention per part in a snapshot. */
    public Optional<Map<String, Object>> openContention(UUID snapshotId, UUID partId) {
        return jdbc.query("""
                select * from stock_contention
                where inventory_snapshot_id = :s and part_id = :p and status <> 'RELEASED'
                order by created_at desc, id desc
                limit 1
                """, Map.of("s", snapshotId, "p", partId), MAP).stream().findFirst();
    }

    // ---- release freeze -------------------------------------------------
    public int[] insertReleaseSubstitutions(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_part_substitution
                    (release_id, from_part_number, to_part_number, substitution_kind, model_code,
                     temp_min_c, temp_max_c, interface_requirement, requires_engineering_confirm,
                     valid_from, valid_to, state, approval_ref, approved_by, approved_at, notes)
                values
                    (:releaseId, :fromPartNumber, :toPartNumber, :kind, :modelCode,
                     :tempMin, :tempMax, :interface, :confirm,
                     :validFrom, :validTo, :state, :approvalRef, :approvedBy, :approvedAt, :notes)
                """, batch);
    }

    public int[] insertReleaseLifecycle(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_part_lifecycle
                    (release_id, part_number, state, obsolete_reason, superseded_by_part_number,
                     decided_at, approved_by, approval_ref)
                values
                    (:releaseId, :partNumber, :state, :reason, :successor,
                     :decidedAt, :approvedBy, :approvalRef)
                """, batch);
    }

    public int[] insertReleaseRecommendations(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_work_order_recommendation
                    (release_id, work_order_external_id, concept_code, part_number, disposition_code,
                     catalog_version_label, quantity, recommendation_status, reason, recorded_at,
                     recorded_by, approval_ref)
                values
                    (:releaseId, :woExternalId, :conceptCode, :partNumber, :dispositionCode,
                     :catalogLabel, :quantity, :status, :reason, :recordedAt,
                     :recordedBy, :approvalRef)
                """, batch);
    }

    public int[] insertReleaseContentions(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_stock_contention
                    (release_id, part_number, warehouse, status, competing_demand,
                     confirmed_by, confirmed_at, notes, created_at)
                values
                    (:releaseId, :partNumber, :warehouse, :status, :demand,
                     :confirmedBy, :confirmedAt, :notes, :createdAt)
                """, batch);
    }

    public List<Map<String, Object>> releaseSubstitutions(UUID releaseId) {
        return jdbc.query("""
                select * from release_part_substitution where release_id = :rid
                order by from_part_number, to_part_number
                """, Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> releaseLifecycle(UUID releaseId) {
        return jdbc.query("""
                select * from release_part_lifecycle where release_id = :rid
                order by part_number
                """, Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> releaseRecommendations(UUID releaseId) {
        return jdbc.query("""
                select * from release_work_order_recommendation where release_id = :rid
                order by work_order_external_id, recorded_at
                """, Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> releaseInventory(UUID releaseId) {
        return jdbc.query("select * from release_inventory where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> releaseContentions(UUID releaseId) {
        return jdbc.query("""
                select * from release_stock_contention where release_id = :rid
                order by part_number, created_at
                """, Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> releaseDispositionsForConcept(UUID releaseId, String conceptCode) {
        return jdbc.query("""
                select * from release_disposition
                where release_id = :rid and concept_code = :c
                order by code
                """, Map.of("rid", releaseId, "c", conceptCode), MAP);
    }

    public static Instant instant(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : Sql.instant(v);
    }
}
