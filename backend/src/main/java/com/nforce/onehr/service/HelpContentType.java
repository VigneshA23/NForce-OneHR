package com.nforce.onehr.service;

/**
 * Discriminator for {@code help_content} rows. Persisted as a plain {@code VARCHAR} column
 * (same convention as {@link TicketStatus}) rather than four separate entities/tables, since
 * FAQ/Quick Help/Guide/Document all share the same shape (title, description, body, optional
 * attachment, publish/active flags).
 */
public enum HelpContentType {
    FAQ,
    QUICK_HELP,
    GUIDE,
    DOCUMENT;

    public static HelpContentType from(String raw) {
        try {
            return HelpContentType.valueOf(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown help content type: " + raw);
        }
    }
}
