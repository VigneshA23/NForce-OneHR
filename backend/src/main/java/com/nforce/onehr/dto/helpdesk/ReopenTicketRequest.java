package com.nforce.onehr.dto.helpdesk;

import lombok.Data;

/** Backs the employee's "Reopen" action on a CLOSED ticket — the reason is optional but, when
 *  given, is recorded as the first reply on the reopened ticket so HR sees why without having
 *  to ask. */
@Data
public class ReopenTicketRequest {
    private String reason;
}
