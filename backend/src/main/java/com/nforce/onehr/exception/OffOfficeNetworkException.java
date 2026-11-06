package com.nforce.onehr.exception;

import lombok.Getter;

/**
 * Normal Check-In attempted from outside the employee's configured office network. The client
 * offers Work From Home (Web Clock-In with a reason) instead — see code OFF_OFFICE_NETWORK.
 * Carries the IP we resolved (and, once the controller adds it, how we got it) so a mismatch can
 * be diagnosed from the screen instead of guessed.
 */
@Getter
public class OffOfficeNetworkException extends RuntimeException {
    private final String detectedIp;
    private final String networkTrace;

    public OffOfficeNetworkException(String detectedIp) {
        this(detectedIp, null);
    }

    public OffOfficeNetworkException(String detectedIp, String networkTrace) {
        super("You're not connected to the office Wi-Fi. Check in as Work From Home and add a reason.");
        this.detectedIp = detectedIp;
        this.networkTrace = networkTrace;
    }
}
