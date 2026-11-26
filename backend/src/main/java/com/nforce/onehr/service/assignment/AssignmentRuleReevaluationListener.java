package com.nforce.onehr.service.assignment;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * PN-04: "rules re-evaluate when an employee's attributes change, with effective dates recorded".
 * Runs synchronously inside the publisher's transaction, so it sees the just-changed employee, and
 * records the new outcome effective today. A failure here must never fail the employee save - the
 * admin screen's "Re-evaluate all" repairs any drift.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssignmentRuleReevaluationListener {

    private final AssignmentRuleEngine engine;
    private final EmployeeRepository employeeRepository;
    private final AttendanceProperties attendanceProperties;

    @EventListener
    public void onEmployeeAttributesChanged(EmployeeAttributesChangedEvent event) {
        try {
            employeeRepository.findById(event.employeeUserId()).ifPresent(e ->
                    engine.reevaluateAllDomains(e, LocalDate.now(ZoneId.of(attendanceProperties.getZone()))));
        } catch (RuntimeException ex) {
            log.warn("Assignment-rule re-evaluation failed for employee {} - use Re-evaluate all to repair: {}",
                    event.employeeUserId(), ex.getMessage());
        }
    }
}
