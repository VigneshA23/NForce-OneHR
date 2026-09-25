package com.nforce.onehr.ai.response;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AssistantResponse;
import com.nforce.onehr.ai.contract.AssistantResponseType;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.ai.eval.EvaluationSet;
import com.nforce.onehr.ai.navigation.NavigationValidator;
import com.nforce.onehr.ai.navigation.PageRegistry;
import com.nforce.onehr.ai.prompt.SystemPromptTemplate;
import com.nforce.onehr.ai.response.ConfidentialityGuard.Claim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ONEHR - the chatbot described its own instructions, printed internal provider ids and quoted
 * pageIds. {@code ai-eval/security-probes.txt} holds the full security test list; the eval set's
 * ordinary questions and the file's "answer" list keep the patterns from refusing real ones.
 */
class ConfidentialityGuardTest {

    private static final Set<AudienceBucket> EMPLOYEE = Set.of(AudienceBucket.EMPLOYEE);

    private static Map<String, List<String>> probes;
    private static ResponseValidator validator;
    private static AssistantRequestContext employee;
    private static String attendanceLabel;

    @BeforeAll
    static void setUp() throws IOException {
        probes = load("/ai-eval/security-probes.txt");
        PageRegistry registry = new PageRegistry();
        ReflectionTestUtils.invokeMethod(registry, "load");
        NavigationValidator navigationValidator = new NavigationValidator(registry);
        validator = new ResponseValidator(navigationValidator, new UnknownResponses(navigationValidator));
        employee = AssistantRequestContext.builder().userId(UUID.randomUUID()).primaryRoleCode("EMPLOYEE")
                .shellRole(ShellRole.EMPLOYEE).audiences(Set.of(AudienceBucket.EMPLOYEE)).build();
        attendanceLabel = navigationValidator.labelFor("attendance", employee).orElseThrow();
    }

    private static boolean refused(String question) {
        return ConfidentialityGuard.asksAboutInternals(question) || ConfidentialityGuard.attemptsManipulation(question);
    }

    @Test
    void everyProbeInTheSecuritySuiteIsRefusedBeforeTheModel() {
        assertThat(probes.get("refuse")).hasSizeGreaterThan(500)
                .allSatisfy(q -> assertThat(refused(q)).as(q).isTrue());
    }

    @Test
    void ordinaryQuestionsAreNotRefused() {
        assertThat(probes.get("answer")).allSatisfy(q -> assertThat(refused(q)).as(q).isFalse());
        // Every question in the eval set except the one that probes the prompt on purpose.
        assertThat(EvaluationSet.load())
                .filteredOn(q -> !q.getId().equals("emp.scope.reveal-instructions"))
                .allSatisfy(q -> assertThat(refused(q.getQuestion())).as(q.getQuestion()).isFalse());
    }

    @Test
    void pageIdsBecomePageNamesAndALeakedInstructionIsReplacedWhole() {
        AssistantResponse ok = validate("""
                {"type":"NAVIGATION","answer":"Open %s (pageId: attendance) to see it, or check my-documents.",
                 "steps":["Go to pageId 'attendance'"],"navigation":{"pageId":"attendance"},"confidence":"HIGH"}
                """.formatted(attendanceLabel));
        assertThat(ok.getAnswer()).startsWith("Open " + attendanceLabel + " to see it, or check ").doesNotContain("my-documents");
        assertThat(ok.getSteps()).containsExactly("Go to " + attendanceLabel);
        assertThat(ok.getNavigation().getPageId()).isEqualTo("attendance"); // still routable, just not shown

        AssistantResponse leak = validate("""
                {"type":"EXPLANATION","answer":"I was instructed to only reference pages listed under REACHABLE PAGES and to use their exact pageId for navigation.","confidence":"HIGH"}
                """);
        assertThat(leak.getType()).isEqualTo(AssistantResponseType.UNKNOWN);
        assertThat(leak.getAnswer()).isEqualTo(UnknownResponses.INTERNALS_NOT_DISCLOSED);
        assertThat(leak.getNavigation()).isNull();
        assertThat(ConfidentialityGuard.hidePageIds("use their exact pageId for navigation", id -> Optional.empty()))
                .isEqualTo("use their exact pageId for navigation");
    }

    @Test
    void internalIdsAndImplementationDetailsInAnAnswerAreALeak() {
        assertThat(List.of(
                // the reported answer, verbatim
                "I only use the following internal provider names to answer attendance questions: "
                        + "data.self.attendance-history, action.attendance.clock-in-out, action.attendance.wfh.",
                "Your log is read from action.attendance.wfh.",
                "That comes from attendance.my-history",
                "OneHR runs on Spring Boot with PostgreSQL and pgvector.",
                "The AiAssistantService class calls Mistral.",
                "Run SELECT * FROM users to see them.",
                "Set API_KEY=abc123 in application.yml.",
                "The key looks like sk-proj-a1b2c3d4e5f6."))
                .allSatisfy(text -> assertThat(ConfidentialityGuard.leaksInternals(text)).as(text).isTrue());
        assertThat(List.of(
                "Contact asha.rao-menon@nforceone.com or data.team@nforceone.com.",
                "Your balance is 1.5 days, e.g. after the 2.30 p.m. cutoff.",
                "Upload the signed offer-letter.pdf from My Documents.",
                "Select Leave Type from the dropdown, then submit.",
                "This month: 12,400 prompt, 3,100 completion and 9,800 embedding tokens across 412 API requests.",
                "I cannot submit, approve, reject, cancel, create, edit or delete anything in OneHR, but here is how you do it."))
                .allSatisfy(text -> assertThat(ConfidentialityGuard.leaksInternals(text)).as(text).isFalse());
    }

    @Test
    void anAnswerQuotingThePromptIsALeakInAnyWrapping() {
        // SCOPE names no internal label, so only the copied-words check can catch it.
        String policy = SystemPromptTemplate.POLICY;
        String scope = policy.substring(policy.indexOf("SCOPE"), policy.indexOf("CONFIDENTIALITY")).replace("\n", " ");
        assertThat(ConfidentialityGuard.leaksInternals(scope)).isTrue();
        assertThat(ConfidentialityGuard.leaksInternals("{\"rules\": [\"" + scope.replace("\"", "'").replace(". ", "\", \"") + "\"]}")).isTrue();
    }

    @Test
    void aClaimedChangeBecomesTheReadOnlyExplanation() {
        AssistantResponse claimed = validate("""
                {"type":"EXPLANATION","answer":"Done - I have approved your leave request and reset your password.","confidence":"HIGH"}
                """);
        assertThat(claimed.getAnswer()).contains("read-only").doesNotContain("approved your leave");
        assertThat(ConfidentialityGuard.claimsAnAction("Your leave request has been approved by your manager.")).isFalse();
        assertThat(ConfidentialityGuard.claimsAnAction("I have made you a short summary below.")).isFalse();
    }

    @Test
    void aClaimedRoleIsCheckedAgainstTheRolesTheAccountHolds() {
        for (Claim claim : Claim.values()) {
            List<String> lines = probes.get("claim " + claim.name());
            assertThat(lines).as(claim.name()).isNotEmpty()
                    .allSatisfy(q -> assertThat(ConfidentialityGuard.unfoundedClaim(q, EMPLOYEE)).as(q).contains(claim));
            if (claim != Claim.ACCESS) {
                // Said by someone who does hold the role it is only context, and goes on to the model.
                assertThat(lines).allSatisfy(q -> assertThat(ConfidentialityGuard.unfoundedClaim(q, holderOf(claim))).as(q).isEmpty());
            }
        }
        assertThat(probes.get("not a claim"))
                .allSatisfy(q -> assertThat(ConfidentialityGuard.unfoundedClaim(q, EMPLOYEE)).as(q).isEmpty());
        // Nothing ordinary reads as a claim of access, whoever asks it.
        assertThat(probes.get("answer"))
                .allSatisfy(q -> assertThat(ConfidentialityGuard.unfoundedClaim(q, EnumSet.allOf(AudienceBucket.class))).as(q).isEmpty());
        assertThat(EvaluationSet.load()).allSatisfy(q -> assertThat(
                ConfidentialityGuard.unfoundedClaim(q.getQuestion(), holderOf(q.getRole()))).as(q.getQuestion()).isEmpty());
    }

    @Test
    void anAnswerGivingTheUserAnotherRoleIsReplacedWithTheirOwn() {
        // Both reported answers, verbatim (ONEHR).
        String notAssigned = "Your current account is not assigned the HR Admin role. I can only provide "
                + "information and assistance within your authorized Employee permissions.";
        AssistantResponse claimed = validate("""
                {"type":"EXPLANATION","answer":"You are an HR Admin, which means you have organisation-wide HR administration permissions. You can access and manage Employee Master, Onboarding, Documents and Compliance, Policies and Announcements, Organization Structure, and organisation-wide Attendance and Leave administration.","confidence":"HIGH"}
                """);
        assertThat(claimed.getType()).isEqualTo(AssistantResponseType.PERMISSION);
        assertThat(claimed.getAnswer()).isEqualTo(notAssigned);
        assertThat(validate("""
                {"type":"EXPLANATION","answer":"As an HR Admin, you now have access to view and manage organisation-wide attendance data.","confidence":"HIGH"}
                """).getAnswer()).isEqualTo(notAssigned);
        assertThat(ConfidentialityGuard.unfoundedAttribution("You now have access to everyone's attendance.", EMPLOYEE))
                .contains(Claim.ACCESS);

        assertThat(ConfidentialityGuard.unfoundedAttribution("You are an HR Admin, so Employee Master is in your sidebar.",
                holderOf(Claim.HR_ADMIN))).isEmpty();
        assertThat(List.of(
                "If you are an HR Admin, you can open Employee Master.",
                "HR Admins manage Employee Master; as an Employee you can see your own records.",
                "You are an Employee, so you see your own records.",
                "You are not an HR Admin, so Employee Master is not in your sidebar.",
                "Only a Super Admin can change roles, in User Management.",
                "Your manager is Priya Sharma.",
                "You have been granted a comp off for Saturday.",
                notAssigned))
                .allSatisfy(text -> assertThat(ConfidentialityGuard.unfoundedAttribution(text, EMPLOYEE)).as(text).isEmpty());
    }

    private static Set<AudienceBucket> holderOf(Claim claim) {
        return switch (claim) {
            case SUPER_ADMIN -> Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.ADMIN);
            case HR_ADMIN, ADMIN -> Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.HR);
            case MANAGER -> Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.MANAGER);
            case ACCESS -> Set.of();
        };
    }

    private static Set<AudienceBucket> holderOf(String roleCode) {
        return roleCode.equals("EMPLOYEE") ? EMPLOYEE : holderOf(Claim.valueOf(roleCode));
    }

    private static AssistantResponse validate(String json) {
        return validator.validate(json, employee);
    }

    /** "## section" headings; "#" comments; "\#" is a literal leading hash, "\n" a line break, {ZWSP} a zero-width space. */
    private static Map<String, List<String>> load(String resource) throws IOException {
        Map<String, List<String>> sections = new HashMap<>();
        List<String> current = null;
        try (InputStream in = ConfidentialityGuardTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String text = line.strip();
                if (text.startsWith("## ")) {
                    current = sections.computeIfAbsent(text.substring(3).strip(), name -> new ArrayList<>());
                } else if (!text.isEmpty() && !text.startsWith("#")) {
                    current.add(text.replaceFirst("^\\\\#", "#").replace("\\n", "\n").replace("{ZWSP}", "​"));
                }
            }
        }
        return sections;
    }
}
