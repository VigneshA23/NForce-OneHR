package com.nforce.onehr.util;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * Shared size/type validation for user-uploaded attachments, originally written for
 * HelpContentService (FAQ/Guide attachments) and reused as-is by HelpdeskService (ticket-reply
 * attachments) rather than duplicating the limit/allow-list — see the Help &amp; Guidance defect
 * inventory (D13): ticket replies previously accepted any file of any size, unlike HelpContent's
 * always-enforced 10MB/allow-list rule.
 */
public final class AttachmentValidator {

    public static final long MAX_ATTACHMENT_SIZE_BYTES = 10L * 1024 * 1024; // mirrors spring.servlet.multipart.max-file-size
    public static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "png", "jpg", "jpeg", "gif", "txt", "csv");

    private AttachmentValidator() {}

    /** Throws IllegalArgumentException if the file is empty, too large, or an unsupported type. */
    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment file is empty");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new IllegalArgumentException("\"" + file.getOriginalFilename() + "\" exceeds the "
                    + (MAX_ATTACHMENT_SIZE_BYTES / (1024 * 1024)) + "MB attachment size limit");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: ." + ext
                    + " (allowed: " + String.join(", ", ALLOWED_ATTACHMENT_EXTENSIONS) + ")");
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
