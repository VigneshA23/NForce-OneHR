package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.StatutoryRuleRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-F04 / US-F06 self-service reads and the regime switch. Identity always comes from the login
 * token. No row in {@code employee_tax_regime} means New Regime; a missing/empty window row means
 * the window is closed (HR sets it).
 */
@Service
@RequiredArgsConstructor
public class MyTaxProfileService {

    public record RegimeView(String regime, boolean isDefault, boolean windowOpen, LocalDate opensOn, LocalDate closesOn) {}

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StatutoryRuleRepository statutoryRuleRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Monthly gross above this makes the caller "Not Eligible" for ESI; null when no ESI rule is configured. */
    @Transactional(readOnly = true)
    public BigDecimal esiWageCeiling(String actorEmail) {
        UUID id = user(actorEmail).getId();
        return employeeRepository.findById(id)
                .filter(e -> e.getLocation() != null)
                .flatMap(e -> statutoryRuleRepository.findEffectiveAt(e.getLocation().getCountry(), "ESI", LocalDate.now()))
                .map(r -> {
                    try { return new BigDecimal(objectMapper.readTree(r.getSlabConfig()).get("wageCeiling").asText()); }
                    catch (Exception ex) { return null; }
                })
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public RegimeView regime(String actorEmail) {
        UUID id = user(actorEmail).getId();
        List<String> own = jdbc.queryForList("SELECT regime FROM employee_tax_regime WHERE employee_user_id = ?", String.class, id);
        List<LocalDate[]> w = jdbc.query("SELECT opens_on, closes_on FROM tax_regime_window WHERE id = 1",
                (rs, i) -> new LocalDate[]{ rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class) });
        LocalDate opens = w.isEmpty() ? null : w.get(0)[0], closes = w.isEmpty() ? null : w.get(0)[1];
        LocalDate today = LocalDate.now();
        boolean open = opens != null && closes != null && !today.isBefore(opens) && !today.isAfter(closes);
        return new RegimeView(own.isEmpty() ? "NEW" : own.get(0), own.isEmpty(), open, opens, closes);
    }

    @Transactional
    public RegimeView setRegime(String actorEmail, String regime) {
        if (!"OLD".equals(regime) && !"NEW".equals(regime)) throw new IllegalArgumentException("regime must be OLD or NEW");
        RegimeView before = regime(actorEmail);
        if (!before.windowOpen()) throw new AccessDeniedException("The tax regime window is closed");
        UUID id = user(actorEmail).getId();
        jdbc.update("INSERT INTO employee_tax_regime (employee_user_id, regime) VALUES (?, ?) "
                + "ON CONFLICT (employee_user_id) DO UPDATE SET regime = EXCLUDED.regime, updated_at = now()", id, regime);
        auditService.log(id, "TAX_REGIME_CHANGED", id, "{\"regime\":\"" + before.regime() + "\"}", "{\"regime\":\"" + regime + "\"}");
        return regime(actorEmail);
    }

    @Transactional
    public void setWindow(String actorEmail, LocalDate opensOn, LocalDate closesOn) {
        if (opensOn == null || closesOn == null || closesOn.isBefore(opensOn))
            throw new IllegalArgumentException("closesOn must be on or after opensOn");
        jdbc.update("INSERT INTO tax_regime_window (id, opens_on, closes_on) VALUES (1, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET opens_on = EXCLUDED.opens_on, closes_on = EXCLUDED.closes_on", opensOn, closesOn);
        UUID id = user(actorEmail).getId();
        auditService.log(id, "TAX_REGIME_WINDOW_SET", id, null, "{\"opensOn\":\"" + opensOn + "\",\"closesOn\":\"" + closesOn + "\"}");
    }

    private User user(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
