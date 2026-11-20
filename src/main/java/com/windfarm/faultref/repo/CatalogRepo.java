package com.windfarm.faultref.repo;

import com.windfarm.faultref.error.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogRepo {

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

    public CatalogRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- catalog versions ---------------------------------------------
    public UUID insertCatalogVersion(String label, String status, Instant effectiveFrom,
                                     String approvedBy, String approvalRef, String notes) {
        return jdbc.queryForObject("""
                insert into catalog_version (version_label, status, effective_from, published_at,
                                             approved_by, approval_ref, notes)
                values (:label, :status, :eff,
                        case when :status = 'PUBLISHED' then now() else null end, :by, :ref, :notes)
                returning id
                """, new MapSqlParameterSource()
                .addValue("label", label).addValue("status", status).addValue("eff", Sql.odt(effectiveFrom))
                .addValue("by", approvedBy).addValue("ref", approvalRef).addValue("notes", notes), UUID.class);
    }

    public List<Map<String, Object>> listCatalogVersions() {
        return jdbc.query("select * from catalog_version order by effective_from desc, version_label", Map.of(), MAP);
    }

    public Optional<Map<String, Object>> catalogVersionByLabel(String label) {
        return jdbc.query("select * from catalog_version where version_label = :l",
                Map.of("l", label), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> catalogVersionById(UUID id) {
        return jdbc.query("select * from catalog_version where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    /** Latest PUBLISHED catalog effective at or before an instant. */
    public Optional<Map<String, Object>> publishedCatalogAt(Instant at) {
        return jdbc.query("""
                select * from catalog_version
                where status = 'PUBLISHED' and effective_from <= :at
                order by effective_from desc, version_label desc
                limit 1
                """, Map.of("at", Sql.odt(at)), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> latestPublishedCatalog() {
        return jdbc.query("""
                select * from catalog_version where status = 'PUBLISHED'
                order by effective_from desc, version_label desc limit 1
                """, Map.of(), MAP).stream().findFirst();
    }

    public void publishCatalogVersion(UUID id, String approvedBy, String approvalRef) {
        jdbc.update("""
                update catalog_version
                set status = 'PUBLISHED', published_at = now(), approved_by = :by, approval_ref = :ref
                where id = :id
                """, new MapSqlParameterSource().addValue("by", approvedBy).addValue("ref", approvalRef)
                .addValue("id", id));
    }

    // ---- parts ---------------------------------------------------------
    public UUID insertPart(String partNumber, String name, String category, String manufacturer) {
        return jdbc.queryForObject("""
                insert into part (part_number, name, category, manufacturer)
                values (:pn, :name, :cat, :man) returning id
                """, new MapSqlParameterSource().addValue("pn", partNumber).addValue("name", name)
                .addValue("cat", category).addValue("man", manufacturer), UUID.class);
    }

    public Optional<Map<String, Object>> partByNumber(String number) {
        return jdbc.query("select * from part where part_number = :n",
                Map.of("n", number), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> listParts() {
        return jdbc.query("select * from part order by part_number", Map.of(), MAP);
    }

    // ---- catalog entries ----------------------------------------------
    public UUID insertEntry(UUID catalogVersionId, UUID partId, String description, Map<String, Object> attrs) {
        return jdbc.queryForObject("""
                insert into catalog_entry (catalog_version_id, part_id, description, attributes)
                values (:cv, :pid, :desc, :attrs::jsonb) returning id
                """, new MapSqlParameterSource().addValue("cv", catalogVersionId).addValue("pid", partId)
                .addValue("desc", description).addValue("attrs", Sql.jsonb(attrs == null ? Map.of() : attrs)),
                UUID.class);
    }

    public UUID insertCompat(UUID entryId, UUID modelId, String fwFrom, String fwTo,
                             Integer fwFromSeq, Integer fwToSeq) {
        return jdbc.queryForObject("""
                insert into catalog_entry_compat
                    (catalog_entry_id, model_id, firmware_from, firmware_to, firmware_from_seq, firmware_to_seq)
                values (:e, :m, :ff, :ft, :ffs, :fts) returning id
                """, new MapSqlParameterSource().addValue("e", entryId).addValue("m", modelId)
                .addValue("ff", fwFrom).addValue("ft", fwTo)
                .addValue("ffs", fwFromSeq).addValue("fts", fwToSeq), UUID.class);
    }

    public List<Map<String, Object>> entriesForPart(UUID partId) {
        return jdbc.query("""
                select e.*, cv.version_label, p.part_number, p.name as part_name, p.category
                from catalog_entry e
                join catalog_version cv on cv.id = e.catalog_version_id
                join part p on p.id = e.part_id
                where e.part_id = :pid
                order by cv.effective_from
                """, Map.of("pid", partId), MAP);
    }

    public List<Map<String, Object>> compatForEntry(UUID entryId) {
        return jdbc.query("""
                select ce.*, m.code as model_code, man.code as manufacturer_code
                from catalog_entry_compat ce
                left join turbine_model m on m.id = ce.model_id
                left join manufacturer man on man.id = m.manufacturer_id
                where ce.catalog_entry_id = :e
                """, Map.of("e", entryId), MAP);
    }

    // ---- inventory -----------------------------------------------------
    public UUID insertInventorySnapshot(String label, Instant takenAt, String source,
                                        UUID catalogVersionId) {
        return jdbc.queryForObject("""
                insert into inventory_snapshot (catalog_version_id, label, taken_at, source)
                values (:cv, :label, :at, :src) returning id
                """, new MapSqlParameterSource().addValue("cv", catalogVersionId).addValue("label", label)
                .addValue("at", Sql.odt(takenAt)).addValue("src", source), UUID.class);
    }

    public UUID insertInventoryLevel(UUID snapshotId, UUID partId, String warehouse, int qty) {
        return jdbc.queryForObject("""
                insert into inventory_level (inventory_snapshot_id, part_id, warehouse, qty_available)
                values (:s, :p, :w, :q) returning id
                """, new MapSqlParameterSource().addValue("s", snapshotId).addValue("p", partId)
                .addValue("w", warehouse).addValue("q", qty), UUID.class);
    }

    public Optional<Map<String, Object>> inventorySnapshotByLabel(String label) {
        return jdbc.query("select * from inventory_snapshot where label = :l",
                Map.of("l", label), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> inventorySnapshotById(UUID id) {
        return jdbc.query("select * from inventory_snapshot where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> latestInventorySnapshotAt(Instant at) {
        return jdbc.query("""
                select * from inventory_snapshot where taken_at <= :at
                order by taken_at desc limit 1
                """, Map.of("at", Sql.odt(at)), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> listInventorySnapshots() {
        return jdbc.query("select * from inventory_snapshot order by taken_at desc", Map.of(), MAP);
    }

    /** Total available quantity of a part across warehouses in one snapshot. */
    public int availableQuantity(UUID snapshotId, UUID partId) {
        Integer q = jdbc.queryForObject("""
                select coalesce(sum(qty_available), 0) from inventory_level
                where inventory_snapshot_id = :s and part_id = :p
                """, Map.of("s", snapshotId, "p", partId), Integer.class);
        return q == null ? 0 : q;
    }

    public List<Map<String, Object>> inventoryLevels(UUID snapshotId) {
        return jdbc.query("""
                select il.*, p.part_number, p.name as part_name
                from inventory_level il join part p on p.id = il.part_id
                where il.inventory_snapshot_id = :s order by p.part_number, il.warehouse
                """, Map.of("s", snapshotId), MAP);
    }

    // ---- dispositions --------------------------------------------------
    public UUID insertDisposition(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into disposition_option
                    (concept_id, catalog_version_id, code, action, description, part_id, quantity,
                     exclusive_group, requires_stock, min_stock, valid_from, valid_to,
                     approved_by, approval_ref, approved_at)
                values
                    (:conceptId, :catalogVersionId, :code, :action, :description, :partId, :quantity,
                     :exclusiveGroup, :requiresStock, :minStock, :validFrom, :validTo,
                     :approvedBy, :approvalRef, :approvedAt)
                returning id
                """, p, UUID.class);
    }

    public List<Map<String, Object>> dispositionsForConcept(UUID conceptId) {
        return jdbc.query("""
                select d.*, cv.version_label, p.part_number, p.name as part_name
                from disposition_option d
                join catalog_version cv on cv.id = d.catalog_version_id
                left join part p on p.id = d.part_id
                where d.concept_id = :cid
                order by d.catalog_version_id, d.code
                """, Map.of("cid", conceptId), MAP);
    }

    public List<Map<String, Object>> dispositionsForCatalog(UUID catalogVersionId) {
        return jdbc.query("""
                select d.*, c.code as concept_code, c.name as concept_name, p.part_number
                from disposition_option d
                join concept c on c.id = d.concept_id
                left join part p on p.id = d.part_id
                where d.catalog_version_id = :cv
                order by c.code, d.code
                """, Map.of("cv", catalogVersionId), MAP);
    }

    /** Canonical list of concepts referenced by a published catalog's dispositions. */
    public List<Map<String, Object>> conceptsInCatalog(UUID catalogVersionId) {
        return jdbc.query("""
                select distinct c.id, c.code, c.name, c.category, c.severity, c.current_definition_version
                from disposition_option d join concept c on c.id = d.concept_id
                where d.catalog_version_id = :cv
                """, Map.of("cv", catalogVersionId), MAP);
    }

    public static UUID uuid(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : (v instanceof UUID u ? u : UUID.fromString(v.toString()));
    }

    public static String str(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : v.toString();
    }

    public static Integer integer(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : ((Number) v).intValue();
    }

    public static Boolean bool(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : (Boolean) v;
    }

    public static Instant instant(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : Sql.instant(v);
    }

    public static BigDecimal decimal(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : Sql.decimal(v);
    }
}
