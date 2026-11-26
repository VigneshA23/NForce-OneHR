package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/**
 * YE-02: HR bulk upload of leave opening balances for a leave year (the current one or the next),
 * for carry-forward from another system, joiner proration or corrections.
 *
 * <p>The template lists every active employee x every active regular paid type that applies to them,
 * with today's credited and carried-forward days. HR fills "New Credited" / "New Carried Forward"
 * (blank = unchanged) and a reason; values are absolute, so uploading the same file twice gives the
 * same result. Every row is validated before anything is saved ({@link #validate}); {@link #apply}
 * saves only a fully clean file, in one transaction, creating a missing balance row first.
 *
 * <p>Each change goes through {@link LeaveTypeService#adjustBalance} — the same audited path as a
 * single manual adjustment (old value, new value, reason, administrator) — so a balance ledger can
 * later be added there without changing this upload flow.
 */
@Service
@RequiredArgsConstructor
public class LeaveOpeningBalanceService {

    private static final BigDecimal MAX_DAYS = new BigDecimal("999");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final EmployeeRepository employeeRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveEligibilityService eligibilityService;
    private final LeaveTypeService leaveTypeService;

    /** One template row: an employee's current position for one type (0 when no balance row exists yet). */
    public record TemplateRow(String employeeCode, String employeeName, String leaveType, BigDecimal usedDays,
                              BigDecimal currentCredited, BigDecimal currentCarriedForward) {}

    /**
     * One uploaded row, cells as the spreadsheet holds them (so "ten" is a row error, not a bad request);
     * {@code newCredited}/{@code newCarriedForward} blank = leave unchanged.
     */
    public record UploadRow(Integer rowNumber, String employeeCode, String leaveType, String newCredited,
                            String newCarriedForward, String reason) {}

    /** The before -> after of one row, or why it can't be applied. */
    public record RowResult(Integer rowNumber, String employeeCode, String employeeName, String leaveType,
                            BigDecimal currentCredited, BigDecimal newCredited,
                            BigDecimal currentCarriedForward, BigDecimal newCarriedForward,
                            boolean changed, List<String> errors, List<String> warnings) {}

    public record Validation(int year, int rows, int changes, int errorRows, List<RowResult> results) {}

    public record ApplyResult(int year, int changes) {}

    // ── Template ────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TemplateRow> template(Integer year) {
        int y = requireYear(year);
        List<LeaveType> types = annualTypes();
        List<Object[]> employees = employeeRepository.findActiveCodeRows();
        Map<String, LeaveBalance> rows = existingRows(employees.stream().map(r -> (UUID) r[0]).toList(), y);
        List<TemplateRow> out = new ArrayList<>();
        for (Object[] e : employees) {
            UUID userId = (UUID) e[0];
            for (LeaveType t : eligibilityService.eligibleTypes(userId, types)) {
                LeaveBalance b = rows.get(userId + ":" + t.getId());
                out.add(new TemplateRow((String) e[1], (String) e[2], t.getName(),
                        b == null ? ZERO : b.getUsedDays().add(b.getCarriedForwardUsedDays()),
                        b == null ? ZERO : b.getTotalDays(),
                        b == null ? ZERO : b.getCarriedForwardDays()));
            }
        }
        return out;
    }

    // ── Validate / apply ────────────────────────────────────────────────────────────────────────

    /** Checks every row; never writes. */
    @Transactional(readOnly = true)
    public Validation validate(Integer year, List<UploadRow> upload) {
        int y = requireYear(year);
        if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("The file has no rows.");
        List<LeaveType> types = annualTypes();
        Map<String, UUID> userByCode = new HashMap<>();
        Map<UUID, String> nameById = new HashMap<>();
        for (Object[] e : employeeRepository.findActiveCodeRows()) {
            if (e[1] != null) userByCode.put(((String) e[1]).trim().toUpperCase(Locale.ROOT), (UUID) e[0]);
            nameById.put((UUID) e[0], (String) e[2]);
        }
        Map<String, LeaveBalance> rows = existingRows(userByCode.values(), y);
        Set<String> seen = new HashSet<>();
        List<RowResult> results = new ArrayList<>();
        int changes = 0, errorRows = 0;

        for (UploadRow r : upload) {
            List<String> errors = new ArrayList<>();
            String code = r.employeeCode() == null ? "" : r.employeeCode().trim();
            UUID userId = userByCode.get(code.toUpperCase(Locale.ROOT));
            if (code.isEmpty()) errors.add("Employee Code is required");
            else if (userId == null) errors.add("No active employee with code " + code);

            LeaveType type = findType(types, r.leaveType());
            if (r.leaveType() == null || r.leaveType().isBlank()) errors.add("Leave Type is required");
            else if (type == null) errors.add("'" + r.leaveType().trim() + "' is not an active paid leave type with a yearly balance");
            else if (userId != null && !eligibilityService.isEligible(userId, type)) {
                errors.add(type.getName() + " does not apply to this employee");
            }
            if (userId != null && type != null && !seen.add(userId + ":" + type.getId())) {
                errors.add("Duplicate row for this employee and leave type");
            }

            LeaveBalance b = userId != null && type != null ? rows.get(userId + ":" + type.getId()) : null;
            BigDecimal curCredited = b == null ? ZERO : b.getTotalDays();
            BigDecimal curCf = b == null ? ZERO : b.getCarriedForwardDays();
            BigDecimal used = b == null ? ZERO : b.getUsedDays();
            BigDecimal cfFloor = b == null ? ZERO : b.getCarriedForwardUsedDays().add(b.getCarriedForwardExpiredDays());
            BigDecimal givenCredited = parseDays("New Credited", r.newCredited(), errors);
            BigDecimal givenCf = parseDays("New Carried Forward", r.newCarriedForward(), errors);
            BigDecimal newCredited = givenCredited == null ? curCredited : givenCredited;
            BigDecimal newCf = givenCf == null ? curCf : givenCf;
            boolean creditedOk = checkDays("New Credited", givenCredited, errors);
            boolean cfOk = checkDays("New Carried Forward", givenCf, errors);
            boolean changed = newCredited.compareTo(curCredited) != 0 || newCf.compareTo(curCf) != 0;
            if (creditedOk && givenCredited != null && newCredited.compareTo(curCredited) != 0 && newCredited.compareTo(used) < 0) {
                errors.add("New Credited can't be less than the " + LeaveBalanceService.formatDays(used) + " day(s) already used in " + y);
            }
            if (cfOk && givenCf != null && newCf.compareTo(curCf) != 0 && newCf.compareTo(cfFloor) < 0) {
                errors.add("New Carried Forward can't be less than the " + LeaveBalanceService.formatDays(cfFloor)
                        + " carried-forward day(s) already used or expired");
            }
            if (changed && (r.reason() == null || r.reason().isBlank())) errors.add("Reason is required for a change");
            else if (r.reason() != null && r.reason().trim().length() > 500) errors.add("Reason must be at most 500 characters");

            List<String> warnings = new ArrayList<>();
            int current = LeaveBalanceService.leaveYearOf(leaveBalanceService.today());
            if (type != null && y > current && type.isCarryForwardEnabled() && newCf.compareTo(curCf) != 0) {
                warnings.add("Carry-forward is automatic for " + type.getName() + ": on 1 January the unused " + current
                        + " days are added on top of this figure");
            }

            if (!errors.isEmpty()) errorRows++;
            else if (changed) changes++;
            results.add(new RowResult(r.rowNumber(), code, userId != null ? nameById.get(userId) : null,
                    type != null ? type.getName() : r.leaveType(), curCredited, newCredited, curCf, newCf, changed, errors, warnings));
        }
        return new Validation(y, upload.size(), changes, errorRows, results);
    }

    /**
     * Saves a clean file: every changed row, in one transaction (all or nothing). Re-validates first,
     * so a file that changed or went stale since the preview is refused rather than half-applied.
     */
    @Transactional
    public ApplyResult apply(Integer year, List<UploadRow> upload, String actorEmail) {
        Validation v = validate(year, upload);
        if (v.errorRows() > 0) {
            throw new IllegalArgumentException(v.errorRows() + " row(s) have errors. Nothing was saved — fix them and upload again.");
        }
        Map<String, UUID> userByCode = new HashMap<>();
        for (Object[] e : employeeRepository.findActiveCodeRows()) {
            if (e[1] != null) userByCode.put(((String) e[1]).trim().toUpperCase(Locale.ROOT), (UUID) e[0]);
        }
        List<LeaveType> types = annualTypes();
        Map<Integer, UploadRow> byRow = new LinkedHashMap<>();
        for (UploadRow r : upload) byRow.put(r.rowNumber(), r);
        int changes = 0;
        for (RowResult res : v.results()) {
            if (!res.changed()) continue;
            UploadRow r = byRow.get(res.rowNumber());
            UUID userId = userByCode.get(res.employeeCode().toUpperCase(Locale.ROOT));
            LeaveType type = findType(types, res.leaveType());
            // A missing row is created the same way any other first use creates it (and brought up to date).
            LeaveBalance b = leaveBalanceService.ensureBalance(userId, type, v.year());
            leaveTypeService.adjustBalance(b.getId(),
                    new BalanceAdjustmentRequest(res.newCredited(), res.newCarriedForward(), "Opening balance upload: " + r.reason().trim()),
                    actorEmail);
            changes++;
        }
        return new ApplyResult(v.year(), changes);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private int requireYear(Integer year) {
        int current = LeaveBalanceService.leaveYearOf(leaveBalanceService.today());
        if (year == null) return current;
        if (year != current && year != current + 1) {
            throw new IllegalArgumentException("Opening balances can be uploaded for " + current + " or " + (current + 1) + " only.");
        }
        return year;
    }

    private List<LeaveType> annualTypes() {
        return leaveTypeRepository.findByActiveTrueOrderByNameAsc().stream().filter(LeaveType::isAnnualBalance).toList();
    }

    /** By name or code, ignoring case and surrounding spaces. */
    static LeaveType findType(List<LeaveType> types, String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return types.stream().filter(t -> v.equalsIgnoreCase(t.getName()) || v.equalsIgnoreCase(t.getCode())).findFirst().orElse(null);
    }

    private Map<String, LeaveBalance> existingRows(java.util.Collection<UUID> employeeIds, int year) {
        Map<String, LeaveBalance> out = new HashMap<>();
        if (employeeIds.isEmpty()) return out;
        for (LeaveBalance b : leaveBalanceRepository.findByEmployeeUserIdInAndYear(employeeIds, year)) {
            out.put(b.getEmployeeUserId() + ":" + b.getLeaveType().getId(), b);
        }
        return out;
    }

    /** The number in a cell; null when blank (unchanged) or not a number (the reason is added). */
    private static BigDecimal parseDays(String column, String cell, List<String> errors) {
        if (cell == null || cell.isBlank()) return null;
        try {
            return new BigDecimal(cell.trim());
        } catch (NumberFormatException e) {
            errors.add(column + " must be a number");
            return null;
        }
    }

    /** False (with the reason added) when a given value is out of range or not a multiple of 0.5. */
    private static boolean checkDays(String column, BigDecimal value, List<String> errors) {
        if (value == null) return true;
        String problem = value.signum() < 0 ? column + " can't be negative"
                : value.compareTo(MAX_DAYS) > 0 ? column + " can't be more than 999"
                : value.remainder(HALF).signum() != 0 ? column + " must be a multiple of 0.5"
                : null;
        if (problem != null) errors.add(problem);
        return problem == null;
    }
}
