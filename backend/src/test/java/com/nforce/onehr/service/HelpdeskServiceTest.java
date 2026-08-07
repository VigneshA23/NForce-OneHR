package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpdesk.AssignTicketRequest;
import com.nforce.onehr.dto.helpdesk.CreateHelpdeskTicketRequest;
import com.nforce.onehr.dto.helpdesk.TicketDetailDto;
import com.nforce.onehr.dto.helpdesk.UpdateTicketStatusRequest;
import com.nforce.onehr.entity.HelpdeskCategory;
import com.nforce.onehr.entity.HelpdeskReply;
import com.nforce.onehr.entity.HelpdeskTicket;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HelpdeskCategoryRepository;
import com.nforce.onehr.repository.HelpdeskReplyRepository;
import com.nforce.onehr.repository.HelpdeskTicketRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — same isolation approach as LeaveServiceTest, since the repo's H2
 * test profile can't create schema for citext-typed entities. Focuses on the module's highest-risk
 * behaviors: employee ticket isolation, internal-note leakage, and the status transition guard —
 * everything else (pagination/search) is thin Spring Data plumbing already covered elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class HelpdeskServiceTest {

    @Mock private HelpdeskTicketRepository ticketRepo;
    @Mock private HelpdeskReplyRepository replyRepo;
    @Mock private HelpdeskCategoryRepository categoryRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private HelpdeskService helpdeskService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID otherEmployeeId = UUID.randomUUID();
    private final UUID hrAdminId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String otherEmployeeEmail = "other@test.com";
    private final String hrAdminEmail = "hr@test.com";

    private User employeeUser;
    private User hrAdminUser;
    private HelpdeskCategory category;

    @BeforeEach
    void setUp() {
        Role employeeRole = Role.builder().code("EMPLOYEE").build();
        Role hrRole = Role.builder().code("HR_ADMIN").build();

        employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        hrAdminUser = User.builder().id(hrAdminId).email(hrAdminEmail).roles(Set.of(hrRole)).build();
        category = HelpdeskCategory.builder().id(1).name("Leave").active(true).build();

        // employeeOrEmailName() falls back to the email when there's no Employee row.
        lenient().when(employeeRepo.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepo.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepo.findById(hrAdminId)).thenReturn(Optional.of(hrAdminUser));
        lenient().when(ticketRepo.save(any(HelpdeskTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(replyRepo.save(any(HelpdeskReply.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    private HelpdeskTicket openTicket() {
        return HelpdeskTicket.builder().id(UUID.randomUUID()).ticketNumber("HR-2026-000001")
                .employeeUserId(employeeId).category(category).description("Need help")
                .status(TicketStatus.OPEN.name()).priority(TicketPriority.MEDIUM.name()).build();
    }

    @Test
    void createTicket_defaultsToOpenStatus_andNotifiesEveryHrAdmin() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        when(ticketRepo.nextTicketSequence()).thenReturn(1L);
        when(userRepo.findAdminUserIds()).thenReturn(Set.of(hrAdminId));

        CreateHelpdeskTicketRequest req = new CreateHelpdeskTicketRequest();
        req.setCategoryId(1);
        req.setDescription("My laptop is broken");

        TicketDetailDto detail = helpdeskService.createTicket(req, employeeEmail);

        assertEquals("OPEN", detail.getStatus());
        assertTrue(detail.getTicketNumber().startsWith("HR-"));
        assertEquals(employeeId, detail.getEmployeeUserId());
        verify(notificationService).send(eq(hrAdminId), eq("HELPDESK_TICKET_CREATED"), any(), any(), any());
        verify(auditService).log(eq(employeeId), eq("HELPDESK_TICKET_CREATED"), any());
    }

    @Test
    void createTicket_withInactiveCategory_isRejected() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        HelpdeskCategory inactive = HelpdeskCategory.builder().id(2).name("Retired").active(false).build();
        when(categoryRepo.findById(2)).thenReturn(Optional.of(inactive));

        CreateHelpdeskTicketRequest req = new CreateHelpdeskTicketRequest();
        req.setCategoryId(2);
        req.setDescription("x");

        assertThrows(IllegalArgumentException.class, () -> helpdeskService.createTicket(req, employeeEmail));
        verify(ticketRepo, never()).save(any());
    }

    @Test
    void getDetail_anotherEmployeesTicket_isDeniedForEmployee() {
        HelpdeskTicket ticket = openTicket();
        when(userRepo.findByEmail(otherEmployeeEmail)).thenReturn(Optional.of(
                User.builder().id(otherEmployeeId).email(otherEmployeeEmail)
                        .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build()));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThrows(AccessDeniedException.class, () -> helpdeskService.getDetail(ticket.getId(), otherEmployeeEmail));
    }

    @Test
    void getDetail_hidesInternalNotes_fromEmployeeCaller() {
        HelpdeskTicket ticket = openTicket();
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        HelpdeskReply publicReply = HelpdeskReply.builder().id(UUID.randomUUID()).ticketId(ticket.getId())
                .senderId(hrAdminId).senderRole("HR").message("We're on it").internal(false).build();
        HelpdeskReply internalNote = HelpdeskReply.builder().id(UUID.randomUUID()).ticketId(ticket.getId())
                .senderId(hrAdminId).senderRole("HR").message("Escalate to payroll").internal(true).build();
        when(replyRepo.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of(publicReply, internalNote));

        TicketDetailDto detail = helpdeskService.getDetail(ticket.getId(), employeeEmail);

        assertEquals(1, detail.getReplies().size());
        assertFalse(detail.getReplies().get(0).isInternal());
    }

    @Test
    void getDetail_showsInternalNotes_toHrAdmin() {
        HelpdeskTicket ticket = openTicket();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        HelpdeskReply internalNote = HelpdeskReply.builder().id(UUID.randomUUID()).ticketId(ticket.getId())
                .senderId(hrAdminId).senderRole("HR").message("Escalate to payroll").internal(true).build();
        when(replyRepo.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of(internalNote));

        TicketDetailDto detail = helpdeskService.getDetail(ticket.getId(), hrAdminEmail);

        assertEquals(1, detail.getReplies().size());
        assertTrue(detail.getReplies().get(0).isInternal());
    }

    @Test
    void addReply_toAnotherEmployeesTicket_isDeniedForEmployee() {
        HelpdeskTicket ticket = openTicket();
        User stranger = User.builder().id(otherEmployeeId).email(otherEmployeeEmail)
                .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build();
        when(userRepo.findByEmail(otherEmployeeEmail)).thenReturn(Optional.of(stranger));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThrows(AccessDeniedException.class,
                () -> helpdeskService.addReply(ticket.getId(), "hi", false, null, otherEmployeeEmail));
        verify(replyRepo, never()).save(any());
    }

    @Test
    void addReply_employeeRequestingInternal_isSilentlyForcedToNonInternal() throws Exception {
        HelpdeskTicket ticket = openTicket();
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(userRepo.findAdminUserIds()).thenReturn(Set.of(hrAdminId));

        var reply = helpdeskService.addReply(ticket.getId(), "please help", true, null, employeeEmail);

        assertFalse(reply.isInternal());
    }

    @Test
    void addReply_onClosedTicket_isRejected() {
        HelpdeskTicket closed = openTicket();
        closed.setStatus(TicketStatus.CLOSED.name());
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(ticketRepo.findById(closed.getId())).thenReturn(Optional.of(closed));

        assertThrows(IllegalStateException.class,
                () -> helpdeskService.addReply(closed.getId(), "still there?", false, null, employeeEmail));
        verify(replyRepo, never()).save(any());
    }

    @Test
    void updateStatus_skippingToInvalidNextState_isRejected() {
        HelpdeskTicket ticket = openTicket(); // OPEN
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest req = new UpdateTicketStatusRequest();
        req.setStatus("RESOLVED"); // OPEN -> RESOLVED is not an allowed transition

        assertThrows(IllegalStateException.class, () -> helpdeskService.updateStatus(ticket.getId(), req, hrAdminEmail));
        verify(ticketRepo, never()).save(any());
    }

    @Test
    void updateStatus_byEmployee_isDenied() {
        HelpdeskTicket ticket = openTicket();
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        UpdateTicketStatusRequest req = new UpdateTicketStatusRequest();
        req.setStatus("IN_PROGRESS");

        assertThrows(AccessDeniedException.class, () -> helpdeskService.updateStatus(ticket.getId(), req, employeeEmail));
    }

    @Test
    void updateStatus_toResolved_stampsResolvedAtAndResolvedBy() {
        HelpdeskTicket ticket = openTicket();
        ticket.setStatus(TicketStatus.IN_PROGRESS.name());
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest req = new UpdateTicketStatusRequest();
        req.setStatus("RESOLVED");

        TicketDetailDto detail = helpdeskService.updateStatus(ticket.getId(), req, hrAdminEmail);

        assertEquals("RESOLVED", detail.getStatus());
        assertNotNull(ticket.getResolvedAt());
        assertEquals(hrAdminId, ticket.getResolvedBy());
        verify(notificationService).send(eq(employeeId), eq("HELPDESK_TICKET_STATUS_CHANGED"), any(), any(), any());
    }

    @Test
    void assignTicket_toNonAdminUser_isRejected() {
        HelpdeskTicket ticket = openTicket();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(userRepo.findAdminUserIds()).thenReturn(Set.of(hrAdminId)); // does not include the target

        AssignTicketRequest req = new AssignTicketRequest();
        req.setAssigneeUserId(otherEmployeeId);

        assertThrows(IllegalArgumentException.class, () -> helpdeskService.assignTicket(ticket.getId(), req, hrAdminEmail));
        verify(ticketRepo, never()).save(any());
    }

    @Test
    void assignTicket_whileOpen_autoTransitionsToAssigned() {
        HelpdeskTicket ticket = openTicket(); // OPEN
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(ticketRepo.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(userRepo.findAdminUserIds()).thenReturn(Set.of(hrAdminId));

        AssignTicketRequest req = new AssignTicketRequest();
        req.setAssigneeUserId(hrAdminId);

        TicketDetailDto detail = helpdeskService.assignTicket(ticket.getId(), req, hrAdminEmail);

        assertEquals("ASSIGNED", detail.getStatus());
        assertEquals(hrAdminId, detail.getAssignedTo());
    }

    @Test
    void getDetail_missingTicket_throwsNotFound() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        UUID missing = UUID.randomUUID();
        when(ticketRepo.findById(missing)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> helpdeskService.getDetail(missing, employeeEmail));
    }
}
