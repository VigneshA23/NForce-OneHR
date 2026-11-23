package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * US-F14 / US-F17 — what the Super Admin configures: tax partners and the ITR due date, the per-FY slabs and
 * rebate, and the finance audit log. Employees only ever get {@link #filing}, which hides disabled partners.
 */
@Service
@RequiredArgsConstructor
public class TaxConfigService {

    public record Partner(UUID id, String kind, String name, String description, String steps, String url, String couponCode, boolean featured, boolean enabled) {}
    public record Filing(int fyStartYear, LocalDate itrDueDate, List<Partner> filing, List<Partner> saving) {}
    public record Slab(BigDecimal from, BigDecimal to, BigDecimal rate) {}
    public record Rules(int fyStartYear, String regime, BigDecimal standardDeduction, BigDecimal rebateThreshold, BigDecimal rebateMax,
                        BigDecimal cessRate, List<Slab> slabs) {}
    public record AuditRow(long id, String action, String actor, String employee, String before, String after, java.time.OffsetDateTime at) {}

    /** Every action the finance stories write; the Finance Audit Log shows exactly these. */
    public static final List<String> FINANCE_ACTIONS = List.of("EMPLOYEE_FINANCIAL_DETAILS_UPDATED", "DOCUMENT_VERIFIED", "DOCUMENT_REJECTED",
            "TAX_REGIME_CHANGED", "TAX_REGIME_UNLOCKED", "TAX_REGIME_WINDOW_SET", "TAX_WINDOW_SET", "DECLARATION_EDITED_BY_ADMIN",
            "TAX_PROOF_DECISION", "PREVIOUS_INCOME_ENTERED", "PREVIOUS_INCOME_APPROVED", "FORM130_PUBLISHED", "TAX_PARTNER_CHANGED",
            "TAX_RULES_UPDATED", "TAX_FILING_DUE_DATE_SET");

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    // ── partners ─────────────────────────────────────────────────────────────

    private static Partner partner(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Partner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getBoolean(8), rs.getBoolean(9));
    }

    private static final String PARTNER_COLS = "SELECT id, kind, name, description, steps, url, coupon_code, featured, enabled FROM tax_partner ";

    @Transactional(readOnly = true)
    public List<Partner> allPartners() {
        return jdbc.query(PARTNER_COLS + "ORDER BY kind, featured DESC, name", (rs, i) -> partner(rs));
    }

    /** Enabled partners and the due date; the front end hides the tab for an empty list. */
    @Transactional(readOnly = true)
    public Filing filing(int fy) {
        List<Partner> on = jdbc.query(PARTNER_COLS + "WHERE enabled ORDER BY featured DESC, name", (rs, i) -> partner(rs));
        List<LocalDate> due = jdbc.queryForList("SELECT itr_due_date FROM tax_filing_config WHERE fy_start_year = ?", LocalDate.class, fy);
        return new Filing(fy, due.isEmpty() ? null : due.get(0), on.stream().filter(p -> p.kind().equals("FILING")).toList(),
                on.stream().filter(p -> p.kind().equals("SAVING")).toList());
    }

    @Transactional
    public Partner savePartner(UUID actor, UUID id, Partner p) {
        if (!"FILING".equals(p.kind()) && !"SAVING".equals(p.kind())) throw new IllegalArgumentException("kind must be FILING or SAVING");
        if (p.name() == null || p.name().isBlank() || p.url() == null || !p.url().matches("(?i)^https://.+"))
            throw new IllegalArgumentException("Name and an https:// link are required");
        UUID key = id != null ? id : UUID.randomUUID();
        if (id != null && jdbc.queryForObject("SELECT count(*) FROM tax_partner WHERE id = ?", Integer.class, id) == 0) throw new NoSuchElementException("Partner not found");
        jdbc.update("INSERT INTO tax_partner (id, kind, name, description, steps, url, coupon_code, featured, enabled) VALUES (?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (id) DO UPDATE SET kind = EXCLUDED.kind, name = EXCLUDED.name, description = EXCLUDED.description, steps = EXCLUDED.steps, "
                + "url = EXCLUDED.url, coupon_code = EXCLUDED.coupon_code, featured = EXCLUDED.featured, enabled = EXCLUDED.enabled",
                key, p.kind(), p.name().trim(), p.description(), p.steps(), p.url().trim(), p.couponCode(), p.featured(), p.enabled());
        auditService.log(actor, "TAX_PARTNER_CHANGED", actor, null, "{\"partner\":\"" + p.name().replace("\"", "'") + "\",\"enabled\":" + p.enabled() + "}");
        return jdbc.query(PARTNER_COLS + "WHERE id = ?", (rs, i) -> partner(rs), key).get(0);
    }

    @Transactional
    public void deletePartner(UUID actor, UUID id) {
        jdbc.update("DELETE FROM tax_partner WHERE id = ?", id);
        auditService.log(actor, "TAX_PARTNER_CHANGED", actor, null, "{\"deleted\":\"" + id + "\"}");
    }

    @Transactional
    public void setDueDate(UUID actor, int fy, LocalDate due) {
        if (due == null) throw new IllegalArgumentException("Due date is required");
        jdbc.update("INSERT INTO tax_filing_config (fy_start_year, itr_due_date) VALUES (?, ?) ON CONFLICT (fy_start_year) DO UPDATE SET itr_due_date = EXCLUDED.itr_due_date", fy, due);
        auditService.log(actor, "TAX_FILING_DUE_DATE_SET", actor, null, "{\"fy\":" + fy + ",\"dueDate\":\"" + due + "\"}");
    }

    // ── slabs / rebate ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Rules rules(int fy, String regime) {
        List<Slab> slabs = jdbc.query("SELECT from_amount, to_amount, rate FROM tax_slabs WHERE fy_start_year = ? AND regime = ? ORDER BY from_amount",
                (rs, i) -> new Slab(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), fy, regime);
        List<Rules> cfg = jdbc.query("SELECT standard_deduction, rebate_threshold, rebate_max, cess_rate FROM tax_year_config WHERE fy_start_year = ? AND regime = ?",
                (rs, i) -> new Rules(fy, regime, rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), slabs), fy, regime);
        BigDecimal z = BigDecimal.ZERO;
        return cfg.isEmpty() ? new Rules(fy, regime, z, z, z, z, slabs) : cfg.get(0);
    }

    /** Replaces the slabs and rebate for one FY + regime. Surcharge bands are not editable here. */
    @Transactional
    public Rules saveRules(UUID actor, Rules r) {
        if (!"OLD".equals(r.regime()) && !"NEW".equals(r.regime())) throw new IllegalArgumentException("regime must be OLD or NEW");
        if (r.slabs() == null || r.slabs().isEmpty()) throw new IllegalArgumentException("At least one slab is required");
        BigDecimal expect = BigDecimal.ZERO;
        for (int i = 0; i < r.slabs().size(); i++) {
            Slab s = r.slabs().get(i);
            if (s.from().compareTo(expect) != 0) throw new IllegalArgumentException("Slabs must be continuous, starting from 0");
            if (s.rate().signum() < 0 || s.rate().compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Rates are fractions between 0 and 1 (0.05 = 5%)");
            if (s.to() == null != (i == r.slabs().size() - 1)) throw new IllegalArgumentException("Only the last slab has no upper limit");
            if (s.to() != null) { if (s.to().compareTo(s.from()) <= 0) throw new IllegalArgumentException("Slab upper limit must exceed its lower limit"); expect = s.to(); }
        }
        jdbc.update("DELETE FROM tax_slabs WHERE fy_start_year = ? AND regime = ?", r.fyStartYear(), r.regime());
        for (Slab s : r.slabs())
            jdbc.update("INSERT INTO tax_slabs (fy_start_year, regime, from_amount, to_amount, rate) VALUES (?,?,?,?,?)", r.fyStartYear(), r.regime(), s.from(), s.to(), s.rate());
        jdbc.update("INSERT INTO tax_year_config (fy_start_year, regime, standard_deduction, rebate_threshold, rebate_max, cess_rate) VALUES (?,?,?,?,?,?) "
                + "ON CONFLICT (fy_start_year, regime) DO UPDATE SET standard_deduction = EXCLUDED.standard_deduction, rebate_threshold = EXCLUDED.rebate_threshold, "
                + "rebate_max = EXCLUDED.rebate_max, cess_rate = EXCLUDED.cess_rate",
                r.fyStartYear(), r.regime(), r.standardDeduction(), r.rebateThreshold(), r.rebateMax(), r.cessRate());
        auditService.log(actor, "TAX_RULES_UPDATED", actor, null, "{\"fy\":" + r.fyStartYear() + ",\"regime\":\"" + r.regime() + "\",\"slabs\":" + r.slabs().size() + "}");
        return rules(r.fyStartYear(), r.regime());
    }

    // ── finance audit log (US-F18) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AuditRow> auditLog(UUID employeeId, String action, int limit) {
        String in = String.join(",", FINANCE_ACTIONS.stream().map(a -> "'" + a + "'").toList());
        return jdbc.query("SELECT a.id, a.action, COALESCE(ae.full_name, au.email), COALESCE(te.full_name, tu.email), a.before_state::text, a.after_state::text, a.occurred_at "
                + "FROM audit_log a LEFT JOIN users au ON au.id = a.actor_id LEFT JOIN employees ae ON ae.user_id = a.actor_id "
                + "LEFT JOIN users tu ON tu.id = a.target_id LEFT JOIN employees te ON te.user_id = a.target_id "
                + "WHERE a.action IN (" + in + ") AND (?::uuid IS NULL OR a.target_id = ?::uuid) AND (?::text IS NULL OR a.action = ?::text) "
                + "ORDER BY a.occurred_at DESC LIMIT ?",
                (rs, i) -> new AuditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, java.time.OffsetDateTime.class)),
                employeeId, employeeId, action, action, Math.min(Math.max(limit, 1), 500));
    }
}
