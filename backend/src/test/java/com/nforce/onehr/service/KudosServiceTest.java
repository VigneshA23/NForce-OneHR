package com.nforce.onehr.service;

import com.nforce.onehr.dto.SendKudosRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Kudos;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.KudosRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// ONEHR: Managers and HR/Super Admin Can Give Appreciation — extends the pre-existing
// manager/peer-only kudos eligibility rule (see KudosNotificationTest for the older,
// notification-shape-focused suite, which this leaves untouched).
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KudosServiceTest {

    @Mock private KudosRepository kudosRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private KudosService kudosService;

    private User sender;
    private User recipient;
    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final String senderEmail = "sender@nforceone.com";

    @BeforeEach
    void setUp() {
        when(kudosRepository.save(any(Kudos.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
    }

    private User userWithRole(UUID id, String roleCode) {
        return User.builder().id(id).email(id + "@nforceone.com").active(true)
                .roles(Set.of(Role.builder().id(1).code(roleCode).displayName(roleCode).build()))
                .build();
    }

    private void asSender(String roleCode) {
        sender = userWithRole(senderId, roleCode);
        when(userRepository.findByEmail(senderEmail)).thenReturn(Optional.of(sender));
    }

    private void asRecipient(boolean active, LocalDate lastWorkingDay) {
        recipient = User.builder().id(recipientId).email("recipient@nforceone.com").active(active).build();
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(employeeRepository.findById(recipientId)).thenReturn(Optional.of(
                Employee.builder().userId(recipientId).fullName("Recipient").lastWorkingDay(lastWorkingDay).build()));
    }

    private SendKudosRequest request() {
        SendKudosRequest req = new SendKudosRequest();
        req.setToUserId(recipientId);
        req.setCategory("Great Work");
        return req;
    }

    private void noRelationship() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(any(UUID.class))).thenReturn(Optional.empty());
        when(historyRepository.findCurrentPeerIds(any(UUID.class))).thenReturn(List.of());
    }

    private void recipientIsDirectReportOfSender() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(recipientId)).thenReturn(Optional.of(
                EmployeeManagerHistory.builder().employeeUserId(recipientId).managerUserId(senderId).build()));
        when(historyRepository.findCurrentPeerIds(senderId)).thenReturn(List.of());
    }

    private void recipientIsSendersManager() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(senderId)).thenReturn(Optional.of(
                EmployeeManagerHistory.builder().employeeUserId(senderId).managerUserId(recipientId).build()));
        when(historyRepository.findCurrentPeerIds(senderId)).thenReturn(List.of());
    }

    private void recipientIsPeerOfSender() {
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(senderId)).thenReturn(Optional.empty());
        when(historyRepository.findCurrentPeerIds(senderId)).thenReturn(List.of(recipientId));
    }

    // ── 1. Manager -> direct report succeeds ──────────────────────────────────────

    @Test
    @DisplayName("a Manager can appreciate their current direct report")
    void managerCanAppreciateDirectReport() {
        asSender("MANAGER");
        asRecipient(true, null);
        recipientIsDirectReportOfSender();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    // ── 2. Existing peer/manager relationship still succeeds ────────────────────────

    @Test
    @DisplayName("a Manager can still appreciate their own reporting manager")
    void managerCanAppreciateTheirOwnManager() {
        asSender("MANAGER");
        asRecipient(true, null);
        recipientIsSendersManager();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    @Test
    @DisplayName("a Manager can still appreciate a current peer")
    void managerCanAppreciatePeer() {
        asSender("MANAGER");
        asRecipient(true, null);
        recipientIsPeerOfSender();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    // ── 3 & 4. HR Admin / Super Admin -> any active employee succeeds ───────────────

    @Test
    @DisplayName("an HR Admin can appreciate any active employee, no relationship required")
    void hrAdminCanAppreciateAnyActiveEmployee() {
        asSender("HR_ADMIN");
        asRecipient(true, null);
        noRelationship();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    @Test
    @DisplayName("a Super Admin can appreciate any active employee, no relationship required")
    void superAdminCanAppreciateAnyActiveEmployee() {
        asSender("SUPER_ADMIN");
        asRecipient(true, null);
        noRelationship();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    // ── 5. Regular Employee: existing restriction remains unchanged ─────────────────

    @Test
    @DisplayName("a plain Employee still cannot appreciate someone with no relationship")
    void employeeCannotAppreciateUnrelatedPerson() {
        asSender("EMPLOYEE");
        asRecipient(true, null);
        noRelationship();

        assertThatThrownBy(() -> kudosService.send(request(), senderEmail))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a plain Employee with no direct reports gets no new capability from that clause")
    void employeeWithNoDirectReportsIsUnaffectedByTheNewRule() {
        asSender("EMPLOYEE");
        asRecipient(true, null);
        // recipient has no manager-history row naming this employee as their manager, and no peer
        // relationship either — isCurrentManagerOf and isManagerOrPeerOf both fall through.
        noRelationship();

        assertThatThrownBy(() -> kudosService.send(request(), senderEmail))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("reporting manager, a direct report, or a current peer");
    }

    @Test
    @DisplayName("a plain Employee can still appreciate their own reporting manager")
    void employeeCanStillAppreciateTheirManager() {
        asSender("EMPLOYEE");
        asRecipient(true, null);
        recipientIsSendersManager();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    @Test
    @DisplayName("a plain Employee can still appreciate a current peer")
    void employeeCanStillAppreciatePeer() {
        asSender("EMPLOYEE");
        asRecipient(true, null);
        recipientIsPeerOfSender();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    // ── 6. Self-kudos rejected for every role ────────────────────────────────────────

    @Test
    @DisplayName("self-kudos is rejected for a plain Employee")
    void selfKudosRejectedForEmployee() {
        selfKudosRejectedFor("EMPLOYEE");
    }

    @Test
    @DisplayName("self-kudos is rejected for a Manager")
    void selfKudosRejectedForManager() {
        selfKudosRejectedFor("MANAGER");
    }

    @Test
    @DisplayName("self-kudos is rejected for an HR Admin")
    void selfKudosRejectedForHrAdmin() {
        selfKudosRejectedFor("HR_ADMIN");
    }

    @Test
    @DisplayName("self-kudos is rejected for a Super Admin")
    void selfKudosRejectedForSuperAdmin() {
        selfKudosRejectedFor("SUPER_ADMIN");
    }

    private void selfKudosRejectedFor(String roleCode) {
        asSender(roleCode);
        SendKudosRequest req = new SendKudosRequest();
        req.setToUserId(senderId);
        req.setCategory("Great Work");

        assertThatThrownBy(() -> kudosService.send(req, senderEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You can't appreciate yourself");
    }

    // ── 7. Deactivated/offboarded recipient rejected ─────────────────────────────────

    @Test
    @DisplayName("a deactivated recipient is rejected even for an HR Admin sender")
    void deactivatedRecipientRejectedForHrAdmin() {
        asSender("HR_ADMIN");
        asRecipient(false, null);
        noRelationship();

        assertThatThrownBy(() -> kudosService.send(request(), senderEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deactivated or offboarded");
    }

    @Test
    @DisplayName("an offboarded (past lastWorkingDay) recipient is rejected even for a Super Admin sender")
    void offboardedRecipientRejectedForSuperAdmin() {
        asSender("SUPER_ADMIN");
        asRecipient(true, LocalDate.now().minusDays(1));
        noRelationship();

        assertThatThrownBy(() -> kudosService.send(request(), senderEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deactivated or offboarded");
    }

    @Test
    @DisplayName("a recipient still within their future notice period remains a valid recipient")
    void recipientWithFutureLastWorkingDayIsStillEligible() {
        asSender("HR_ADMIN");
        asRecipient(true, LocalDate.now().plusDays(10));
        noRelationship();

        assertThat(kudosService.send(request(), senderEmail)).isNotNull();
    }

    @Test
    @DisplayName("a deactivated recipient is rejected for a Manager sender too")
    void deactivatedRecipientRejectedForManager() {
        asSender("MANAGER");
        asRecipient(false, null);
        recipientIsDirectReportOfSender();

        assertThatThrownBy(() -> kudosService.send(request(), senderEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deactivated or offboarded");
    }

    // ── 8. HR/Super Admin -> employee outside their team succeeds and appears in received ──

    @Test
    @DisplayName("kudos from an HR Admin to an unrelated employee appears in that employee's received list")
    void hrAdminKudosToUnrelatedEmployeeAppearsInReceived() {
        asSender("HR_ADMIN");
        asRecipient(true, null);
        noRelationship();

        kudosService.send(request(), senderEmail);

        when(userRepository.findByEmail("recipient@nforceone.com")).thenReturn(Optional.of(recipient));
        when(kudosRepository.findByToUserIdOrderByCreatedAtDesc(recipientId)).thenReturn(List.of(
                Kudos.builder().id(1L).fromUserId(senderId).toUserId(recipientId).category("Great Work").build()));

        assertThat(kudosService.listReceived("recipient@nforceone.com"))
                .hasSize(1)
                .allSatisfy(k -> assertThat(k.getToUserId()).isEqualTo(recipientId.toString()));
    }

    // ── 9. Existing functionality / regression coverage ──────────────────────────────

    @Test
    @DisplayName("recipient-not-found is still reported distinctly from an authorization failure")
    void recipientNotFoundIsReportedDistinctly() {
        asSender("EMPLOYEE");
        when(userRepository.findById(recipientId)).thenReturn(Optional.empty());
        SendKudosRequest req = request();

        assertThatThrownBy(() -> kudosService.send(req, senderEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Recipient not found");
    }

    @Test
    @DisplayName("a saved kudos still carries the sender, recipient, category and note through")
    void sentKudosCarriesAllFieldsThrough() {
        asSender("MANAGER");
        asRecipient(true, null);
        recipientIsDirectReportOfSender();
        when(employeeRepository.findById(senderId)).thenReturn(Optional.of(
                Employee.builder().userId(senderId).fullName("Sender Name").build()));

        SendKudosRequest req = request();
        req.setNote("Great job on the release");

        var response = kudosService.send(req, senderEmail);

        assertThat(response.getFromUserId()).isEqualTo(senderId.toString());
        assertThat(response.getToUserId()).isEqualTo(recipientId.toString());
        assertThat(response.getCategory()).isEqualTo("Great Work");
        assertThat(response.getNote()).isEqualTo("Great job on the release");
    }
}
