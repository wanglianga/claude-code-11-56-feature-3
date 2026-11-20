package com.windfarm.faultref.repo;

import com.windfarm.faultref.error.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReferenceRepo {

    private static final RowMapper<Map<String, Object>> MAP = (rs, n) -> {
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

    public ReferenceRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- manufacturers -------------------------------------------------
    public UUID insertManufacturer(String code, String name) {
        return jdbc.queryForObject("""
                insert into manufacturer (code, name) values (:code, :name) returning id
                """, new MapSqlParameterSource().addValue("code", code).addValue("name", name), UUID.class);
    }

    public List<Map<String, Object>> listManufacturers() {
        return jdbc.query("select * from manufacturer order by code", Map.of(), MAP);
    }

    public Optional<Map<String, Object>> manufacturerByCode(String code) {
        return jdbc.query("select * from manufacturer where code = :code",
                Map.of("code", code), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> manufacturerById(UUID id) {
        return jdbc.query("select * from manufacturer where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    // ---- models --------------------------------------------------------
    public UUID insertModel(UUID manufacturerId, String code, String name) {
        return jdbc.queryForObject("""
                insert into turbine_model (manufacturer_id, code, name)
                values (:mid, :code, :name) returning id
                """, new MapSqlParameterSource()
                .addValue("mid", manufacturerId).addValue("code", code).addValue("name", name), UUID.class);
    }

    public List<Map<String, Object>> listModels() {
        return jdbc.query("""
                select m.*, man.code as manufacturer_code
                from turbine_model m join manufacturer man on man.id = m.manufacturer_id
                order by man.code, m.code
                """, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> modelById(UUID id) {
        return jdbc.query("""
                select m.*, man.code as manufacturer_code
                from turbine_model m join manufacturer man on man.id = m.manufacturer_id
                where m.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> modelByCode(String manufacturerCode, String code) {
        return jdbc.query("""
                select m.*, man.code as manufacturer_code
                from turbine_model m join manufacturer man on man.id = m.manufacturer_id
                where man.code = :mc and m.code = :code
                """, Map.of("mc", manufacturerCode, "code", code), MAP).stream().findFirst();
    }

    /** Resolve a model by its code alone (model codes are unique within the seeded fleet). */
    public Optional<Map<String, Object>> modelByCodeOnly(String code) {
        return jdbc.query("""
                select m.*, man.code as manufacturer_code
                from turbine_model m join manufacturer man on man.id = m.manufacturer_id
                where m.code = :code
                order by man.code limit 1
                """, Map.of("code", code), MAP).stream().findFirst();
    }

    // ---- firmware ------------------------------------------------------
    public UUID insertFirmware(UUID modelId, String version, int sequence, LocalDate releasedOn, String notes) {
        return jdbc.queryForObject("""
                insert into firmware_version (model_id, version, sequence, released_on, notes)
                values (:mid, :v, :seq, :rel, :notes) returning id
                """, new MapSqlParameterSource()
                .addValue("mid", modelId).addValue("v", version).addValue("seq", sequence)
                .addValue("rel", releasedOn == null ? null : java.sql.Date.valueOf(releasedOn))
                .addValue("notes", notes), UUID.class);
    }

    public List<Map<String, Object>> listFirmware() {
        return jdbc.query("""
                select f.*, m.code as model_code, man.code as manufacturer_code
                from firmware_version f
                join turbine_model m on m.id = f.model_id
                join manufacturer man on man.id = m.manufacturer_id
                order by man.code, m.code, f.sequence
                """, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> firmwareByModelAndVersion(UUID modelId, String version) {
        return jdbc.query("select * from firmware_version where model_id = :mid and version = :v",
                Map.of("mid", modelId, "v", version), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> firmwareById(UUID id) {
        return jdbc.query("""
                select f.*, m.code as model_code, m.manufacturer_id, m.manufacturer_id as mid
                from firmware_version f join turbine_model m on m.id = f.model_id
                where f.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    // ---- units ---------------------------------------------------------
    public UUID insertUnit(String code, String name, UUID modelId, LocalDate commissionedOn) {
        return jdbc.queryForObject("""
                insert into unit (code, name, model_id, commissioned_on)
                values (:code, :name, :mid, :comm) returning id
                """, new MapSqlParameterSource()
                .addValue("code", code).addValue("name", name).addValue("mid", modelId)
                .addValue("comm", commissionedOn == null ? null : java.sql.Date.valueOf(commissionedOn)),
                UUID.class);
    }

    public List<Map<String, Object>> listUnits() {
        return jdbc.query("""
                select u.*, m.code as model_code, man.code as manufacturer_code
                from unit u
                join turbine_model m on m.id = u.model_id
                join manufacturer man on man.id = m.manufacturer_id
                order by u.code
                """, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> unitByCode(String code) {
        return jdbc.query("""
                select u.*, m.code as model_code, m.manufacturer_id, man.code as manufacturer_code
                from unit u
                join turbine_model m on m.id = u.model_id
                join manufacturer man on man.id = m.manufacturer_id
                where u.code = :code
                """, Map.of("code", code), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> unitById(UUID id) {
        return jdbc.query("""
                select u.*, m.code as model_code, m.manufacturer_id, man.code as manufacturer_code
                from unit u
                join turbine_model m on m.id = u.model_id
                join manufacturer man on man.id = m.manufacturer_id
                where u.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    public UUID insertFirmwareInstall(UUID unitId, UUID firmwareId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                insert into unit_firmware_install (unit_id, firmware_version_id, installed_from, installed_to)
                values (:uid, :fid, :from, :to) returning id
                """, new MapSqlParameterSource()
                .addValue("uid", unitId).addValue("fid", firmwareId)
                .addValue("from", Sql.odt(from)).addValue("to", Sql.odt(to)), UUID.class);
    }

    /** Firmware installed on a unit at a business instant (occurrence-time semantics). */
    public Optional<Map<String, Object>> firmwareAt(UUID unitId, Instant at) {
        return jdbc.query("""
                select f.*, i.installed_from, i.installed_to
                from unit_firmware_install i
                join firmware_version f on f.id = i.firmware_version_id
                where i.unit_id = :uid
                  and i.installed_from <= :at
                  and (i.installed_to is null or i.installed_to > :at)
                order by i.installed_from desc
                limit 1
                """, new MapSqlParameterSource().addValue("uid", unitId).addValue("at", Sql.odt(at)),
                MAP).stream().findFirst();
    }

    // ---- source systems / codes ---------------------------------------
    public UUID insertSourceSystem(String code, String name, String kind, String description) {
        return jdbc.queryForObject("""
                insert into source_system (code, name, kind, description)
                values (:code, :name, :kind, :desc) returning id
                """, new MapSqlParameterSource().addValue("code", code).addValue("name", name)
                .addValue("kind", kind).addValue("desc", description), UUID.class);
    }

    public List<Map<String, Object>> listSourceSystems() {
        return jdbc.query("select * from source_system order by code", Map.of(), MAP);
    }

    public Optional<Map<String, Object>> sourceSystemByCode(String code) {
        return jdbc.query("select * from source_system where code = :code",
                Map.of("code", code), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> sourceSystemById(UUID id) {
        return jdbc.query("select * from source_system where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    public UUID insertSourceCode(UUID sourceSystemId, String code, String rawText, String description) {
        return jdbc.queryForObject("""
                insert into source_code (source_system_id, code, raw_text, description)
                values (:sid, :code, :raw, :desc) returning id
                """, new MapSqlParameterSource().addValue("sid", sourceSystemId).addValue("code", code)
                .addValue("raw", rawText).addValue("desc", description), UUID.class);
    }

    public List<Map<String, Object>> listSourceCodes() {
        return jdbc.query("""
                select sc.*, ss.code as source_system_code, ss.kind as source_system_kind
                from source_code sc join source_system ss on ss.id = sc.source_system_id
                order by ss.code, sc.code
                """, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> sourceCodeBySystemAndCode(String systemCode, String code) {
        return jdbc.query("""
                select sc.*, ss.code as source_system_code, ss.kind as source_system_kind
                from source_code sc join source_system ss on ss.id = sc.source_system_id
                where ss.code = :sys and sc.code = :code
                """, Map.of("sys", systemCode, "code", code), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> sourceCodeById(UUID id) {
        return jdbc.query("""
                select sc.*, ss.code as source_system_code, ss.kind as source_system_kind
                from source_code sc join source_system ss on ss.id = sc.source_system_id
                where sc.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    // ---- vendor terms --------------------------------------------------
    public UUID insertTerm(UUID sourceSystemId, String vendor, String termText, String language,
                           String attachmentRef, String notes) {
        return jdbc.queryForObject("""
                insert into term_attachment (source_system_id, vendor, term_text, language, attachment_ref, notes)
                values (:sid, :vendor, :term, :lang, :ref, :notes) returning id
                """, new MapSqlParameterSource()
                .addValue("sid", sourceSystemId).addValue("vendor", vendor).addValue("term", termText)
                .addValue("lang", language).addValue("ref", attachmentRef).addValue("notes", notes),
                UUID.class);
    }

    public List<Map<String, Object>> listTerms() {
        return jdbc.query("""
                select t.*, ss.code as source_system_code
                from term_attachment t left join source_system ss on ss.id = t.source_system_id
                order by t.vendor, t.term_text
                """, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> termById(UUID id) {
        return jdbc.query("""
                select t.*, ss.code as source_system_code
                from term_attachment t left join source_system ss on ss.id = t.source_system_id
                where t.id = :id
                """, Map.of("id", id), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> termByVendorAndText(String vendor, String termText) {
        return jdbc.query("select * from term_attachment where vendor = :v and term_text = :t",
                Map.of("v", vendor, "t", termText), MAP).stream().findFirst();
    }

    // ---- concepts ------------------------------------------------------
    public UUID insertConcept(String code, String name, String category, String severity) {
        return jdbc.queryForObject("""
                insert into concept (code, name, category, severity)
                values (:code, :name, :cat, :sev) returning id
                """, new MapSqlParameterSource().addValue("code", code).addValue("name", name)
                .addValue("cat", category).addValue("sev", severity), UUID.class);
    }

    public UUID insertConceptDefinition(UUID conceptId, int version, String definition,
                                        String approvedBy, String approvalRef, Instant approvedAt) {
        UUID id = jdbc.queryForObject("""
                insert into concept_definition_version
                    (concept_id, version, definition, approved_by, approval_ref, approved_at)
                values (:cid, :v, :def, :by, :ref, :at) returning id
                """, new MapSqlParameterSource().addValue("cid", conceptId).addValue("v", version)
                .addValue("def", definition).addValue("by", approvedBy).addValue("ref", approvalRef)
                .addValue("at", Sql.odt(approvedAt)), UUID.class);
        jdbc.update("update concept set current_definition_version = :v where id = :cid",
                Map.of("v", version, "cid", conceptId));
        return id;
    }

    public List<Map<String, Object>> listConcepts() {
        return jdbc.query("""
                select c.*, d.definition, d.approved_by, d.approval_ref, d.approved_at
                from concept c
                join concept_definition_version d
                  on d.concept_id = c.id and d.version = c.current_definition_version
                order by c.code
                """, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> conceptByCode(String code) {
        return jdbc.query("select * from concept where code = :code",
                Map.of("code", code), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> conceptById(UUID id) {
        return jdbc.query("select * from concept where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> conceptDefinitions(UUID conceptId) {
        return jdbc.query("""
                select version, definition, approved_by, approval_ref, approved_at
                from concept_definition_version where concept_id = :cid order by version
                """, Map.of("cid", conceptId), MAP);
    }

    public Optional<Map<String, Object>> conceptDefinition(UUID conceptId, int version) {
        return jdbc.query("""
                select * from concept_definition_version where concept_id = :cid and version = :v
                """, Map.of("cid", conceptId, "v", version), MAP).stream().findFirst();
    }

    // ---- small helpers -------------------------------------------------
    public static UUID asUuid(Map<String, Object> row, String col) {
        Object v = row.get(col);
        if (v == null) {
            return null;
        }
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    public static String asString(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : v.toString();
    }

    public static Integer asInt(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : ((Number) v).intValue();
    }

    public static Instant asInstant(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : Sql.instant(v);
    }

    public static <T> T require(T value, String code, String message) {
        if (value == null) {
            throw ApiException.notFound(code, message);
        }
        return value;
    }

    /** Optional overload: unwraps or throws the supplied 404-style error. */
    public static Map<String, Object> require(Optional<Map<String, Object>> value, String code, String message) {
        return require(value.orElse(null), code, message);
    }
}
