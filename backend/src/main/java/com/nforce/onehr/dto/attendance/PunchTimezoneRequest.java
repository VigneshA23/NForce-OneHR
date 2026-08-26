package com.nforce.onehr.dto.attendance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Optional body for Check-In/Check-Out and Web Clock-In/Clock-Out. The server still generates
 * the actual timestamp itself — a client-supplied TIME is never accepted (see
 * AttendanceController's class Javadoc) — this only tells the server which IANA zone
 * (e.g. "Australia/Adelaide", from the browser's {@code Intl.DateTimeFormat().resolvedOptions()
 * .timeZone}) to read its own clock in. Missing or invalid, and the employee's configured
 * Location.timezone (then the global business zone) is used instead — see
 * AttendanceService.resolveZone.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PunchTimezoneRequest {
    private String timezone;
}
