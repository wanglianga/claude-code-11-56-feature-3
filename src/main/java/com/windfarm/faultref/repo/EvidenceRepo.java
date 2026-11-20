package com.windfarm.faultref.repo;

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
public class EvidenceRepo {

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

    public EvidenceRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- alarms --------------------------------------------------------
    public UUID insertAlarm(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into alarm
                    (external_id, unit_id, source_system_id, source_code_id, term_attachment_id,
                     raw_code, firmware_version_id, occurred_at, received_at, ingest_type,
                     attributes, raw_payload)
                values
                    (:externalId, :unitId, :systemId, :sourceCodeId, :termId,
                     :rawCode, :firmwareId, :occurredAt, :receivedAt, :ingestType,
                     :attributes::jsonb, :payload::jsonb)
                returning id
                """, p, UUID.class);
    }

    public Optional<Map<String, Object>> alarmById(UUID id) {
        return jdbc.query("select * from alarm where id = :id", Map.of("id", id), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> alarmByExternalId(String externalId) {
        return jdbc.query("select * from alarm where external_id = :e",
                Map.of("e", externalId), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> listAlarms() {
        return jdbc.query("""
                select a.*, u.code as unit_code, ss.code as source_system_code, ss.kind as source_system_kind
                from alarm a
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                order by a.occurred_at
                """, Map.of(), MAP);
    }

    /** Alarms whose occurrence time falls in [from, to). */
    public List<Map<String, Object>> alarmsOccurringBetween(Instant from, Instant to) {
        return jdbc.query("""
                select a.*, u.code as unit_code, ss.code as source_system_code, ss.kind as source_system_kind
                from alarm a
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                where (cast(:from as timestamptz) is null or a.occurred_at >= cast(:from as timestamptz))
                  and (cast(:to as timestamptz) is null or a.occurred_at < cast(:to as timestamptz))
                order by a.occurred_at, a.external_id
                """, new MapSqlParameterSource().addValue("from", Sql.odt(from))
                .addValue("to", Sql.odt(to)), MAP);
    }

    public List<Map<String, Object>> alarmsOccurringBetween(Instant from, Instant to, boolean offlineOnly) {
        return jdbc.query("""
                select a.*, u.code as unit_code, ss.code as source_system_code, ss.kind as source_system_kind
                from alarm a
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                where (cast(:from as timestamptz) is null or a.occurred_at >= cast(:from as timestamptz))
                  and (cast(:to as timestamptz) is null or a.occurred_at < cast(:to as timestamptz))
                  and (not :offlineOnly or a.ingest_type = 'OFFLINE_BACKFILL')
                order by a.occurred_at, a.external_id
                """, new MapSqlParameterSource().addValue("from", Sql.odt(from))
                .addValue("to", Sql.odt(to)).addValue("offlineOnly", offlineOnly), MAP);
    }

    public List<Map<String, Object>> alarmsByIds(List<UUID> ids) {
        return jdbc.query("""
                select a.*, u.code as unit_code, ss.code as source_system_code, ss.kind as source_system_kind
                from alarm a
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                where a.id in (:ids)
                order by a.occurred_at, a.external_id
                """, Map.of("ids", ids), MAP);
    }

    // ---- work orders ---------------------------------------------------
    public UUID insertWorkOrder(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into work_order
                    (external_id, unit_id, vendor, reported_term, term_attachment_id,
                     opened_at, closed_at, status, symptom, raw_payload)
                values
                    (:externalId, :unitId, :vendor, :term, :termId,
                     :openedAt, :closedAt, :status, :symptom, :payload::jsonb)
                returning id
                """, p, UUID.class);
    }

    public Optional<Map<String, Object>> workOrderById(UUID id) {
        return jdbc.query("select * from work_order where id = :id", Map.of("id", id), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> workOrderByExternalId(String externalId) {
        return jdbc.query("select * from work_order where external_id = :e",
                Map.of("e", externalId), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> listWorkOrders() {
        return jdbc.query("""
                select w.*, u.code as unit_code
                from work_order w join unit u on u.id = w.unit_id
                order by w.opened_at
                """, Map.of(), MAP);
    }

    public List<Map<String, Object>> workOrdersForUnitNear(UUID unitId, Instant from, Instant to) {
        return jdbc.query("""
                select w.*, u.code as unit_code
                from work_order w join unit u on u.id = w.unit_id
                where w.unit_id = :uid and w.opened_at >= :from and w.opened_at < :to
                order by w.opened_at
                """, new MapSqlParameterSource().addValue("uid", unitId)
                .addValue("from", Sql.odt(from)).addValue("to", Sql.odt(to)), MAP);
    }
}
