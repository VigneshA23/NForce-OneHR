package com.nforce.onehr.exception;

/**
 * Normal Check-In attempted from outside the employee's configured office network. The client
 * offers Work From Home (Web Clock-In with a reason) instead — see code OFF_OFFICE_NETWORK.
 */
public class OffOfficeNetworkException extends RuntimeException {
    public OffOfficeNetworkException() {
        super("You're not connected to the office Wi-Fi. Check in as Work From Home and add a reason.");
    }
}
