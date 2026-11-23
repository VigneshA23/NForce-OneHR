package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * US-F10 / US-F11 — investment declarations, proofs and the two windows. Everything is keyed by employee
 * id; the self-service controller passes the id from the login token, the Super Admin controller passes the
 * path id (and {@code admin = true}, which skips the window checks and is audited).
 *
 * What counts towards tax ({@link #totals}): New Regime counts nothing but "other income". Old Regime counts
 * 80C (capped at 1.5L), other deductions, allowances and house-property (capped at 2L). An item counts its
 * accepted amount once decided, nothing if rejected, and its declared amount while undecided -- or zero once
 * the proof window has closed.
 */
@Service
@RequiredArgsConstructor
public class TaxDeclarationService {

    public static final BigDecimal CAP_80C = new BigDecimal("150000");
    public static final BigDecimal CAP_HOUSE = new BigDecimal("200000");
    static final Set<String> CATEGORIES = Set.of("SEC_80C", "OTHER_DEDUCTIONS", "ALLOWANCES", "HOUSE_PROPERTY", "OTHER_INCOME");
    private static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;

    public record WindowView(String kind, boolean open, LocalDate opensOn, LocalDate closesOn, boolean monthly) {}
    public record Item(UUID id, String category, String name, BigDecimal amount, String proofStatus, BigDecimal acceptedAmount,
                       BigDecimal rejectedAmount, String reviewComment, int proofCount, BigDecimal counted) {}
    public record Summary(int count, BigDecimal declared, int proofsSubmitted, BigDecimal rejected, BigDecimal accepted) {}
    public record Overview(int fyStartYear, String regime, WindowView declarationWindow, WindowView proofWindow, List<Item> items,
                           Summary summary, BigDecimal sec80cDeclared, boolean sec80cCapped, List<String> disabledCategories) {}
    public record Totals(BigDecimal otherIncome, BigDecimal deductions) {}
    public record Req(String category, String name, BigDecimal amount) {}
    public record ProofFile(String fileName, String contentType, byte[] data) {}
    public record QueueRow(UUID declarationId, UUID employeeId, String employeeCode, String employeeName, String category, String name,
                           BigDecimal amount, String status, BigDecimal acceptedAmount, String comment, List<ProofMeta> proofs) {}
    public record ProofMeta(UUID id, String fileName) {}

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final NotificationService notificationService;

    // ── windows ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public WindowView window(String kind) {
        List<WindowView> w = jdbc.query("SELECT opens_on, closes_on, monthly FROM tax_window WHERE kind = ?", (rs, i) -> {
            LocalDate o = rs.getObject(1, LocalDate.class), c = rs.getObject(2, LocalDate.class);
            boolean monthly = rs.getBoolean(3);
            LocalDate d = LocalDate.now();
            boolean open = !d.isBefore(o) && !d.isAfter(c) && (!monthly || d.getDayOfMonth() <= 22);
            return new WindowView(kind, open, o, c, monthly);
        }, kind);
        return w.isEmpty() ? new WindowView(kind, false, null, null, false) : w.get(0);
    }

    @Transactional
    public void setWindow(UUID actor, String kind, LocalDate opens, LocalDate closes, boolean monthly) {
        if (!"DECLARATION".equals(kind) && !"PROOF".equals(kind)) throw new IllegalArgumentException("kind must be DECLARATION or PROOF");
        if (opens == null || closes == null || closes.isBefore(opens)) throw new IllegalArgumentException("closesOn must be on or after opensOn");
        jdbc.update("INSERT INTO tax_window (kind, opens_on, closes_on, monthly) VALUES (?,?,?,?) ON CONFLICT (kind) DO UPDATE SET "
                + "opens_on = EXCLUDED.opens_on, closes_on = EXCLUDED.closes_on, monthly = EXCLUDED.monthly", kind, opens, closes, monthly);
        auditService.log(actor, "TAX_WINDOW_SET", actor, null, "{\"kind\":\"" + kind + "\",\"opensOn\":\"" + opens + "\",\"closesOn\":\"" + closes + "\",\"monthly\":" + monthly + "}");
    }

    private boolean proofWindowClosed() {
        WindowView w = window("PROOF");
        return w.closesOn() != null && LocalDate.now().isAfter(w.closesOn());
    }

    // ── reads ────────────────────────────────────────────────────────────────

    public String regimeOf(UUID employeeId) {
        List<String> r = jdbc.queryForList("SELECT regime FROM employee_tax_regime WHERE employee_user_id = ?", String.class, employeeId);
        return r.isEmpty() ? "NEW" : r.get(0);
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID employeeId, int fy) {
        String regime = regimeOf(employeeId);
        boolean proofClosed = proofWindowClosed();
        List<Item> items = jdbc.query("SELECT d.id, d.category, d.name, d.amount, d.proof_status, d.accepted_amount, d.review_comment, "
                + "(SELECT count(*) FROM tax_proof p WHERE p.declaration_id = d.id) FROM tax_declaration d "
                + "WHERE d.employee_user_id = ? AND d.fy_start_year = ? ORDER BY d.category, d.created_at", (rs, i) -> {
            BigDecimal amount = rs.getBigDecimal(4), accepted = rs.getBigDecimal(6);
            String status = rs.getString(5);
            BigDecimal rejected = "REJECTED".equals(status) ? amount : "PARTIAL".equals(status) ? amount.subtract(accepted) : BigDecimal.ZERO;
            return new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), amount, status, accepted, rejected,
                    rs.getString(7), rs.getInt(8), counted(amount, status, accepted, proofClosed));
        }, employeeId, fy);
        BigDecimal declared = items.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accepted = items.stream().map(Item::acceptedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejected = items.stream().map(Item::rejectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal c80 = items.stream().filter(x -> x.category().equals("SEC_80C")).map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> disabled = "NEW".equals(regime) ? List.of("SEC_80C", "OTHER_DEDUCTIONS", "ALLOWANCES", "HOUSE_PROPERTY") : List.of();
        return new Overview(fy, regime, window("DECLARATION"), window("PROOF"), items,
                new Summary(items.size(), declared, (int) items.stream().filter(x -> x.proofCount() > 0).count(), rejected, accepted),
                c80, c80.compareTo(CAP_80C) > 0, disabled);
    }

    static BigDecimal counted(BigDecimal amount, String status, BigDecimal accepted, boolean proofClosed) {
        return switch (status) {
            case "ACCEPTED", "PARTIAL" -> accepted;
            case "REJECTED" -> BigDecimal.ZERO;
            default -> proofClosed ? BigDecimal.ZERO : amount;
        };
    }

    /** What the income-tax computation uses. */
    @Transactional(readOnly = true)
    public Totals totals(UUID employeeId, int fy) {
        Overview o = overview(employeeId, fy);
        Map<String, BigDecimal> by = new HashMap<>();
        for (Item i : o.items()) by.merge(i.category(), i.counted(), BigDecimal::add);
        BigDecimal other = by.getOrDefault("OTHER_INCOME", BigDecimal.ZERO);
        if ("NEW".equals(o.regime())) return new Totals(other, BigDecimal.ZERO);
        BigDecimal ded = by.getOrDefault("SEC_80C", BigDecimal.ZERO).min(CAP_80C)
                .add(by.getOrDefault("OTHER_DEDUCTIONS", BigDecimal.ZERO))
                .add(by.getOrDefault("ALLOWANCES", BigDecimal.ZERO))
                .add(by.getOrDefault("HOUSE_PROPERTY", BigDecimal.ZERO).min(CAP_HOUSE));
        return new Totals(other, ded);
    }

    // ── declarations ─────────────────────────────────────────────────────────

    @Transactional
    public Overview add(UUID employeeId, int fy, Req req, UUID actor, boolean admin) {
        validate(employeeId, req, admin);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tax_declaration (id, employee_user_id, fy_start_year, category, name, amount) VALUES (?,?,?,?,?,?)",
                id, employeeId, fy, req.category(), req.name().trim(), req.amount());
        if (admin) audit(actor, employeeId, null, req);
        return overview(employeeId, fy);
    }

    @Transactional
    public Overview update(UUID employeeId, int fy, UUID id, Req req, UUID actor, boolean admin) {
        validate(employeeId, req, admin);
        Req before = current(employeeId, id);
        // an edit invalidates any earlier proof decision
        jdbc.update("UPDATE tax_declaration SET category = ?, name = ?, amount = ?, accepted_amount = 0, review_comment = NULL, reviewed_by = NULL, reviewed_at = NULL, "
                + "proof_status = CASE WHEN EXISTS (SELECT 1 FROM tax_proof WHERE declaration_id = ?) THEN 'PENDING' ELSE 'NONE' END "
                + "WHERE id = ? AND employee_user_id = ?", req.category(), req.name().trim(), req.amount(), id, id, employeeId);
        if (admin) audit(actor, employeeId, before, req);
        return overview(employeeId, fy);
    }

    @Transactional
    public Overview delete(UUID employeeId, int fy, UUID id, UUID actor, boolean admin) {
        if (!admin) requireOpen("DECLARATION");
        Req before = current(employeeId, id);
        jdbc.update("DELETE FROM tax_declaration WHERE id = ? AND employee_user_id = ?", id, employeeId);
        if (admin) audit(actor, employeeId, before, null);
        return overview(employeeId, fy);
    }

    private void validate(UUID employeeId, Req r, boolean admin) {
        if (!admin) requireOpen("DECLARATION");
        if (r == null || !CATEGORIES.contains(r.category())) throw new IllegalArgumentException("Unknown category");
        if (r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Name is required");
        if (r.amount() == null || r.amount().signum() < 0) throw new IllegalArgumentException("Amount must be zero or more");
        if ("NEW".equals(regimeOf(employeeId)) && !"OTHER_INCOME".equals(r.category()))
            throw new IllegalArgumentException("This category does not apply under the New Regime");
    }

    private void requireOpen(String kind) {
        if (!window(kind).open()) throw new AccessDeniedException("Window closed");
    }

    private Req current(UUID employeeId, UUID id) {
        List<Req> r = jdbc.query("SELECT category, name, amount FROM tax_declaration WHERE id = ? AND employee_user_id = ?",
                (rs, i) -> new Req(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)), id, employeeId);
        if (r.isEmpty()) throw new NoSuchElementException("Declaration not found");
        return r.get(0);
    }

    private void audit(UUID actor, UUID employeeId, Req before, Req after) {
        auditService.log(actor, "DECLARATION_EDITED_BY_ADMIN", employeeId, json(before), json(after));
    }

    private static String json(Req r) {
        return r == null ? null : "{\"category\":\"" + r.category() + "\",\"name\":\"" + r.name().replace("\"", "'") + "\",\"amount\":" + r.amount() + "}";
    }

    // ── proofs (US-F11) ──────────────────────────────────────────────────────

    @Transactional
    public Overview uploadProof(UUID employeeId, int fy, UUID declarationId, MultipartFile file) throws IOException {
        requireOpen("PROOF");
        current(employeeId, declarationId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a file");
        if (file.getSize() > MAX_PROOF_BYTES) throw new IllegalArgumentException("File is larger than 5 MB");
        jdbc.update("INSERT INTO tax_proof (declaration_id, file_name, content_type, data) VALUES (?,?,?,?)", declarationId,
                file.getOriginalFilename() == null ? "proof" : file.getOriginalFilename(), file.getContentType(), file.getBytes());
        jdbc.update("UPDATE tax_declaration SET proof_status = 'PENDING', accepted_amount = 0 WHERE id = ? AND proof_status IN ('NONE', 'REJECTED')", declarationId);
        return overview(employeeId, fy);
    }

    /** Owner or Super Admin (the controller decides which id to pass). */
    @Transactional(readOnly = true)
    public ProofFile proofFile(UUID proofId, UUID ownerOrNull) {
        List<ProofFile> f = jdbc.query("SELECT p.file_name, p.content_type, p.data FROM tax_proof p JOIN tax_declaration d ON d.id = p.declaration_id "
                + "WHERE p.id = ? AND (?::uuid IS NULL OR d.employee_user_id = ?::uuid)",
                (rs, i) -> new ProofFile(rs.getString(1), rs.getString(2), rs.getBytes(3)), proofId, ownerOrNull, ownerOrNull);
        if (f.isEmpty()) throw new NoSuchElementException("Proof not found");
        return f.get(0);
    }

    @Transactional(readOnly = true)
    public List<ProofMeta> proofsOf(UUID employeeId, UUID declarationId) {
        current(employeeId, declarationId);
        return jdbc.query("SELECT id, file_name FROM tax_proof WHERE declaration_id = ? ORDER BY uploaded_at",
                (rs, i) -> new ProofMeta(rs.getObject(1, UUID.class), rs.getString(2)), declarationId);
    }

    @Transactional(readOnly = true)
    public List<QueueRow> queue(UUID employeeId, String category, String status) {
        List<QueueRow> rows = jdbc.query("SELECT d.id, d.employee_user_id, e.employee_code, e.full_name, d.category, d.name, d.amount, d.proof_status, d.accepted_amount, d.review_comment "
                + "FROM tax_declaration d JOIN employees e ON e.user_id = d.employee_user_id "
                + "WHERE EXISTS (SELECT 1 FROM tax_proof p WHERE p.declaration_id = d.id) "
                + "AND (?::uuid IS NULL OR d.employee_user_id = ?::uuid) AND (?::text IS NULL OR d.category = ?::text) AND (?::text IS NULL OR d.proof_status = ?::text) "
                + "ORDER BY (d.proof_status = 'PENDING') DESC, e.full_name, d.name",
                (rs, i) -> new QueueRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBigDecimal(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10), new ArrayList<>()),
                employeeId, employeeId, category, category, status, status);
        List<QueueRow> out = new ArrayList<>();
        for (QueueRow r : rows) {
            out.add(new QueueRow(r.declarationId(), r.employeeId(), r.employeeCode(), r.employeeName(), r.category(), r.name(), r.amount(), r.status(),
                    r.acceptedAmount(), r.comment(), proofsOf(r.employeeId(), r.declarationId())));
        }
        return out;
    }

    /** decision: ACCEPT (full), PARTIAL (needs acceptedAmount < amount) or REJECT. A reviewer can never decide their own proofs. */
    @Transactional
    public void decide(UUID reviewer, UUID declarationId, String decision, BigDecimal acceptedAmount, String comment) {
        List<Object[]> d = jdbc.query("SELECT employee_user_id, amount, proof_status, name FROM tax_declaration WHERE id = ?",
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)}, declarationId);
        if (d.isEmpty()) throw new NoSuchElementException("Declaration not found");
        UUID owner = (UUID) d.get(0)[0];
        BigDecimal amount = (BigDecimal) d.get(0)[1];
        if (owner.equals(reviewer)) throw new AccessDeniedException("Another Super Admin must review your own proofs");
        if (jdbc.queryForObject("SELECT count(*) FROM tax_proof WHERE declaration_id = ?", Integer.class, declarationId) == 0)
            throw new IllegalStateException("No proof uploaded for this item");
        String status; BigDecimal accepted;
        switch (decision == null ? "" : decision) {
            case "ACCEPT" -> { status = "ACCEPTED"; accepted = amount; }
            case "REJECT" -> { status = "REJECTED"; accepted = BigDecimal.ZERO; }
            case "PARTIAL" -> {
                if (acceptedAmount == null || acceptedAmount.signum() <= 0 || acceptedAmount.compareTo(amount) >= 0)
                    throw new IllegalArgumentException("Accepted amount must be above 0 and below the declared amount");
                status = "PARTIAL"; accepted = acceptedAmount;
            }
            default -> throw new IllegalArgumentException("decision must be ACCEPT, PARTIAL or REJECT");
        }
        if (!"ACCEPTED".equals(status) && (comment == null || comment.isBlank())) throw new IllegalArgumentException("A comment is required");
        jdbc.update("UPDATE tax_declaration SET proof_status = ?, accepted_amount = ?, review_comment = ?, reviewed_by = ?, reviewed_at = now() WHERE id = ?",
                status, accepted, comment, reviewer, declarationId);
        auditService.log(reviewer, "TAX_PROOF_DECISION", owner, "{\"status\":\"" + d.get(0)[2] + "\"}",
                "{\"item\":\"" + String.valueOf(d.get(0)[3]).replace("\"", "'") + "\",\"status\":\"" + status + "\",\"accepted\":" + accepted + "}");
        notificationService.send(owner, "TAX_PROOF", "Tax proof " + status.toLowerCase(), "Your proof for \"" + d.get(0)[3] + "\" was " + status.toLowerCase() + ".", "/myfinances/tax/declaration");
    }
}
