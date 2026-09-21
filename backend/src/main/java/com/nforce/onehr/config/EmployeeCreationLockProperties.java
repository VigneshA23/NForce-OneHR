package com.nforce.onehr.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Temporary migration safety switch, consulted by {@link com.nforce.onehr.service.EmployeeCodeGenerator#claim}.
 * While {@code locked} is {@code true}, every new-employee request (both {@code POST /api/employees}
 * and {@code POST /api/users}, which converge on {@code claim}) is rejected before it advances
 * {@code employee_code_seq} or does any other creation-related work.
 *
 * <p>Off by default so normal operation is unaffected. Enable only for the duration of a live
 * Employee ID migration window via {@code EMPLOYEE_CREATION_LOCKED=true}, then unset it once the
 * migration is verified complete.
 */
@Component
@ConfigurationProperties(prefix = "app.employee-creation")
@Getter @Setter
public class EmployeeCreationLockProperties {

    private boolean locked = false;
}
