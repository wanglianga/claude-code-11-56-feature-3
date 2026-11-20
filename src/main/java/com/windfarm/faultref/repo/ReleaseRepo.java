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

@Repository
public class ReleaseRepo {

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

    public ReleaseRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- release header -------------------------------------------------
    public UUID insertRelease(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into release
                    (version_label, status, effective_from, catalog_version_label,
                     inventory_snapshot_label, content_hash, approved_by, approval_ref,
                     published_at, created_by, notes)
                values
                    (:label, :status, :effectiveFrom, :catalogLabel,
                     :inventoryLabel, :hash, :approvedBy, :approvalRef,
                     :publishedAt, :createdBy, :notes)
                returning id
                """, p, UUID.class);
    }

    public Optional<Map<String, Object>> releaseById(UUID id) {
        return jdbc.query("select * from release where id = :id", Map.of("id", id), MAP).stream().findFirst();
    }

    public Optional<Map<String, Object>> releaseByLabel(String label) {
        return jdbc.query("select * from release where version_label = :l",
                Map.of("l", label), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> listReleases() {
        return jdbc.query("select * from release order by effective_from desc, version_label desc", Map.of(), MAP);
    }

    public void publish(UUID id, String hash, String approvedBy, String approvalRef) {
        jdbc.update("""
                update release set status = 'PUBLISHED', content_hash = :h,
                       approved_by = :by, approval_ref = :ref, published_at = now()
                where id = :id
                """, new MapSqlParameterSource().addValue("h", hash).addValue("by", approvedBy)
                .addValue("ref", approvalRef).addValue("id", id));
    }

    public void retire(UUID id) {
        jdbc.update("update release set status = 'RETIRED' where id = :id", Map.of("id", id));
    }

    /** Published release selected by explicit label, else latest effective at the instant. */
    public Optional<Map<String, Object>> publishedReleaseAt(Instant at) {
        return jdbc.query("""
                select * from release
                where status = 'PUBLISHED' and effective_from <= :at
                order by effective_from desc, version_label desc
                limit 1
                """, Map.of("at", Sql.odt(at)), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> publishedReleases() {
        return jdbc.query("""
                select * from release where status = 'PUBLISHED'
                order by effective_from, version_label
                """, Map.of(), MAP);
    }

    // ---- bulk insertion of frozen content -------------------------------
    public int[] insertCodeEntries(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_code_entry
                    (release_id, source_system_code, source_system_kind, source_code, raw_text,
                     manufacturer_code, model_code, firmware_from, firmware_to,
                     firmware_from_seq, firmware_to_seq, qualifiers,
                     concept_code, concept_name, concept_category, concept_severity,
                     definition_version, definition_text, confidence, assertion_id,
                     valid_from, valid_to, approval_ref, approved_by, approved_at)
                values
                    (:releaseId, :systemCode, :systemKind, :sourceCode, :rawText,
                     :manufacturerCode, :modelCode, :firmwareFrom, :firmwareTo,
                     :firmwareFromSeq, :firmwareToSeq, :qualifiers::jsonb,
                     :conceptCode, :conceptName, :conceptCategory, :conceptSeverity,
                     :definitionVersion, :definitionText, :confidence, :assertionId,
                     :validFrom, :validTo, :approvalRef, :approvedBy, :approvedAt)
                """, batch);
    }

    public int[] insertTermEntries(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_term_entry
                    (release_id, vendor, term_text, manufacturer_code, model_code,
                     firmware_from, firmware_to, firmware_from_seq, firmware_to_seq, qualifiers,
                     concept_code, concept_name, concept_category, concept_severity,
                     definition_version, definition_text, confidence, assertion_id,
                     valid_from, valid_to, approval_ref, approved_by, approved_at)
                values
                    (:releaseId, :vendor, :termText, :manufacturerCode, :modelCode,
                     :firmwareFrom, :firmwareTo, :firmwareFromSeq, :firmwareToSeq, :qualifiers::jsonb,
                     :conceptCode, :conceptName, :conceptCategory, :conceptSeverity,
                     :definitionVersion, :definitionText, :confidence, :assertionId,
                     :validFrom, :validTo, :approvalRef, :approvedBy, :approvedAt)
                """, batch);
    }

    public int[] insertConcepts(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_concept
                    (release_id, concept_code, name, category, severity, definition_version,
                     definition_text, approved_by, approval_ref, approved_at)
                values
                    (:releaseId, :conceptCode, :name, :category, :severity, :definitionVersion,
                     :definitionText, :approvedBy, :approvalRef, :approvedAt)
                """, batch);
    }

    public int[] insertFirmware(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_firmware
                    (release_id, manufacturer_code, model_code, version, sequence, released_on)
                values (:releaseId, :manufacturerCode, :modelCode, :version, :sequence, :releasedOn)
                """, batch);
    }

    public int[] insertParts(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_part
                    (release_id, part_number, name, category, description, attributes, catalog_version_label)
                values (:releaseId, :partNumber, :name, :category, :description, :attributes::jsonb, :catalogLabel)
                """, batch);
    }

    public int[] insertCompat(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_part_compat
                    (release_part_id, manufacturer_code, model_code, firmware_from, firmware_to,
                     firmware_from_seq, firmware_to_seq)
                values (:releasePartId, :manufacturerCode, :modelCode, :firmwareFrom, :firmwareTo,
                        :firmwareFromSeq, :firmwareToSeq)
                """, batch);
    }

    public int[] insertInventory(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_inventory (release_id, part_number, warehouse, qty_available)
                values (:releaseId, :partNumber, :warehouse, :qty)
                """, batch);
    }

    public int[] insertDispositions(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_disposition
                    (release_id, concept_code, code, action, description, part_number, quantity,
                     exclusive_group, requires_stock, min_stock, catalog_version_label,
                     valid_from, valid_to, approved_by, approval_ref)
                values
                    (:releaseId, :conceptCode, :code, :action, :description, :partNumber, :quantity,
                     :exclusiveGroup, :requiresStock, :minStock, :catalogLabel,
                     :validFrom, :validTo, :approvedBy, :approvalRef)
                """, batch);
    }

    public int[] insertAdjudications(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_adjudication
                    (release_id, source_kind, source_system_code, source_code, vendor, term_text,
                     concept_a_code, concept_b_code, chosen_concept_code, model_code,
                     valid_from, valid_to, decision, adjudicated_by, adjudicated_at, approval_ref)
                values
                    (:releaseId, :sourceKind, :systemCode, :sourceCode, :vendor, :termText,
                     :conceptACode, :conceptBCode, :chosenConceptCode, :modelCode,
                     :validFrom, :validTo, :decision, :adjudicatedBy, :adjudicatedAt, :approvalRef)
                """, batch);
    }

    public int[] insertSourceCodes(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_source_code (release_id, source_system_code, code, raw_text, description)
                values (:releaseId, :systemCode, :code, :rawText, :description)
                """, batch);
    }

    public int[] insertTerms(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_term
                    (release_id, source_system_code, vendor, term_text, attachment_ref, notes)
                values (:releaseId, :systemCode, :vendor, :termText, :attachmentRef, :notes)
                """, batch);
    }

    // ---- reads used by snapshot diagnosis -------------------------------
    public List<Map<String, Object>> codeEntries(UUID releaseId, String systemCode, String sourceCode) {
        return jdbc.query("""
                select * from release_code_entry
                where release_id = :rid and source_system_code = :sys and source_code = :code
                """, Map.of("rid", releaseId, "sys", systemCode, "code", sourceCode), MAP);
    }

    public List<Map<String, Object>> termEntries(UUID releaseId, String vendor, String termText) {
        return jdbc.query("""
                select * from release_term_entry
                where release_id = :rid and vendor = :v and term_text = :t
                """, Map.of("rid", releaseId, "v", vendor, "t", termText), MAP);
    }

    public List<Map<String, Object>> concepts(UUID releaseId) {
        return jdbc.query("select * from release_concept where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }

    public Optional<Map<String, Object>> concept(UUID releaseId, String conceptCode) {
        return jdbc.query("""
                select * from release_concept where release_id = :rid and concept_code = :c
                order by definition_version desc limit 1
                """, Map.of("rid", releaseId, "c", conceptCode), MAP).stream().findFirst();
    }

    public List<Map<String, Object>> dispositions(UUID releaseId) {
        return jdbc.query("select * from release_disposition where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> inventory(UUID releaseId) {
        return jdbc.query("select * from release_inventory where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> parts(UUID releaseId) {
        return jdbc.query("""
                select p.*, c.model_code, c.manufacturer_code, c.firmware_from, c.firmware_to,
                       c.firmware_from_seq, c.firmware_to_seq
                from release_part p
                left join release_part_compat c on c.release_part_id = p.id
                where p.release_id = :rid
                order by p.part_number
                """, Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> firmware(UUID releaseId) {
        return jdbc.query("""
                select * from release_firmware where release_id = :rid
                order by manufacturer_code, model_code, sequence
                """, Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> sourceCodes(UUID releaseId) {
        return jdbc.query("select * from release_source_code where release_id = :rid order by source_system_code, code",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> terms(UUID releaseId) {
        return jdbc.query("select * from release_term where release_id = :rid order by vendor, term_text",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> adjudications(UUID releaseId) {
        return jdbc.query("select * from release_adjudication where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }

    public int countCodeEntries(UUID releaseId) {
        Integer n = jdbc.queryForObject("select count(*) from release_code_entry where release_id = :rid",
                Map.of("rid", releaseId), Integer.class);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> allCodeEntries(UUID releaseId) {
        return jdbc.query("select * from release_code_entry where release_id = :rid order by id",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> allTermEntries(UUID releaseId) {
        return jdbc.query("select * from release_term_entry where release_id = :rid order by id",
                Map.of("rid", releaseId), MAP);
    }

    // ---- source tables used while freezing a release --------------------
    /**
     * ACTIVE assertions that are valid at freeze time, joined with their source
     * identity, concept and approved definition. Used to build the frozen entries.
     */
    public List<Map<String, Object>> activeAssertionsForFreeze() {
        return jdbc.query("""
                select a.id, a.valid_from, a.valid_to, a.approval_ref, a.approved_by, a.approved_at,
                       a.confidence, a.qualifiers, a.definition_version as def_version,
                       a.firmware_from, a.firmware_to, a.firmware_from_seq, a.firmware_to_seq,
                       a.source_code_id, a.term_attachment_id as term_id,
                       sc.code as source_code, sc.raw_text, ss.code as source_system_code,
                       ss.kind as source_system_kind,
                       t.vendor, t.term_text,
                       c.code as concept_code, c.name as concept_name, c.category, c.severity,
                       d.definition,
                       m.code as model_code, man.code as manufacturer_code
                from mapping_assertion a
                join concept c on c.id = a.concept_id
                join concept_definition_version d
                  on d.concept_id = a.concept_id and d.version = a.definition_version
                left join source_code sc on sc.id = a.source_code_id
                left join source_system ss on ss.id = sc.source_system_id
                left join term_attachment t on t.id = a.term_attachment_id
                left join turbine_model m on m.id = a.scope_model_id
                left join manufacturer man on man.id = a.scope_manufacturer_id
                where a.state = 'ACTIVE'
                order by ss.code, sc.code, a.valid_from
                """, Map.of(), MAP);
    }

    public List<Map<String, Object>> partsInCatalog(UUID catalogVersionId) {
        return jdbc.query("""
                select distinct p.*, e.description, e.attributes
                from catalog_entry e
                join part p on p.id = e.part_id
                where e.catalog_version_id = :cv
                order by p.part_number
                """, Map.of("cv", catalogVersionId), MAP);
    }

    public List<Map<String, Object>> compatInCatalog(UUID catalogVersionId) {
        return jdbc.query("""
                select p.part_number, c.firmware_from, c.firmware_to,
                       c.firmware_from_seq, c.firmware_to_seq,
                       m.code as model_code, man.code as manufacturer_code
                from catalog_entry e
                join part p on p.id = e.part_id
                join catalog_entry_compat c on c.catalog_entry_id = e.id
                left join turbine_model m on m.id = c.model_id
                left join manufacturer man on man.id = m.manufacturer_id
                where e.catalog_version_id = :cv
                order by p.part_number
                """, Map.of("cv", catalogVersionId), MAP);
    }

    public List<Map<String, Object>> allAdjudications() {
        return jdbc.query("""
                select adj.*,
                       case when adj.source_code_id is not null then 'CODE' else 'TERM' end as source_kind,
                       ss.code as source_system_code, sc.code as source_code,
                       t.vendor, t.term_text,
                       ca.code as concept_a_code, cb.code as concept_b_code,
                       cc.code as chosen_concept_code, m.code as model_code
                from mapping_adjudication adj
                join concept ca on ca.id = adj.concept_a_id
                join concept cb on cb.id = adj.concept_b_id
                join concept cc on cc.id = adj.chosen_concept_id
                left join source_code sc on sc.id = adj.source_code_id
                left join source_system ss on ss.id = sc.source_system_id
                left join term_attachment t on t.id = adj.term_attachment_id
                left join turbine_model m on m.id = adj.scope_model_id
                order by adj.adjudicated_at
                """, Map.of(), MAP);
    }
}
