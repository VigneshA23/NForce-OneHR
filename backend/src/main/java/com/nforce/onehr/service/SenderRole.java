package com.nforce.onehr.service;

/**
 * Who authored a {@code HelpdeskReply} — resolved server-side from the caller's actual role
 * (which controller route they hit + their JWT-derived roles), never accepted from the client.
 * Persisted as the plain VARCHAR helpdesk_replies.sender_role column — see {@link TicketStatus}.
 */
public enum SenderRole {
    EMPLOYEE, HR
}
