package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.doc.ComplianceSummaryDto;
import com.nforce.onehr.dto.doc.DocumentAdminKpiDto;
import com.nforce.onehr.dto.doc.RequiredDocumentDto;
import com.nforce.onehr.service.DocumentService;
import com.nforce.onehr.service.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Document compliance - the caller's own required documents, and HR's organisation-wide queue.
 *
 * <p>Deliberately not {@code DocumentService#myDocuments}: despite the name it also sends
 * document-expiry reminder notifications as it goes, and the assistant is read-only. The
 * required-documents and compliance-summary reads below are the ones the compliance banner and
 * My Documents' checklist use, and they write nothing.
 */
public final class DocumentDataProviders {

    private DocumentDataProviders() {}

    /** The caller's required documents and where each stands - My Documents' compliance checklist. */
    @Component
    @RequiredArgsConstructor
    public static class MyDocumentCompliance implements AssistantDataProvider {

        private final DocumentService documentService;
        private final PolicyService policyService;

        @Override public String id() { return "document.my-compliance"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your required documents and compliance status"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("documents", "my-documents"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            String email = context.getActorEmail();
            long pendingPolicies = policyService.countPendingRequiredForEmployee(email);
            ComplianceSummaryDto s = documentService.getMyComplianceSummary(email, pendingPolicies);
            List<RequiredDocumentDto> required = documentService.myRequiredDocuments(email);

            StringBuilder out = new StringBuilder();
            if (s != null) {
                out.append("Required documents: %d in total - %d uploaded, %d verified, %d pending HR verification, %d rejected, %d missing, %d expiring soon."
                        .formatted(s.getTotalRequired(), s.getUploaded(), s.getVerified(), s.getPendingVerification(),
                                s.getRejected(), s.getMissing(), s.getExpiringSoon()));
                out.append("\nRequired policies still awaiting your acknowledgement: ").append(s.getPendingPolicies());
            }
            if (required != null) {
                for (RequiredDocumentDto d : required) {
                    out.append("\n- %s: %s%s".formatted(d.getDocumentTypeName(),
                            d.isUploaded() ? (d.getStatus() == null ? "uploaded" : d.getStatus()) : "MISSING (not uploaded)",
                            d.isExpiringSoon() ? ", expiring soon" : ""));
                }
            }
            String text = out.toString().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
    }

    /**
     * HR's document queue in figures - the Documents &amp; Compliance KPI tiles and the Super Admin
     * dashboard's document tile. Both reads check the caller is an HR Admin or Super Admin
     * themselves, on top of the audience gate.
     */
    @Component
    @RequiredArgsConstructor
    public static class OrgDocumentCompliance implements AssistantDataProvider {

        private final DocumentService documentService;
        private final PolicyService policyService;

        @Override public String id() { return "org-documents.compliance"; }
        @Override public DataScope scope() { return DataScope.ORGANISATION; }
        @Override public String title() { return "Organisation document verification and policy acknowledgement status"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.HR, AudienceBucket.ADMIN); }
        @Override public Set<String> modules() { return Set.of("documents-admin", "documents", "policies"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            DocumentAdminKpiDto k = documentService.getAdminKpis(context.getActorEmail());
            long pendingAcks = policyService.countAllPendingRequired();
            StringBuilder out = new StringBuilder();
            if (k != null) {
                out.append("Documents awaiting HR verification: %d, across %d employee(s).".formatted(k.getPendingVerification(), k.getEmployeesWithPending()));
                out.append("\nDocuments expiring within 30 days: ").append(k.getExpiringWithin30Days());
                out.append("\nTotal documents on file: ").append(k.getTotalDocuments());
            }
            out.append("\nRequired policy acknowledgements still outstanding across the organisation: ").append(pendingAcks);
            return Optional.of(out.toString().strip());
        }
    }
}
