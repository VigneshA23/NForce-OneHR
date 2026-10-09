package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as LeaveServiceTest. */
@ExtendWith(MockitoExtension.class)
class LeaveTypeServiceTest {

    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private LeaveTypeService leaveTypeService;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private User actor;

    @BeforeEach
    void setUp() {
        actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(leaveTypeRepository.save(any(LeaveType.class))).thenAnswer(inv -> {
            LeaveType t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
    }

    private CreateLeaveTypeRequest createReq(String code, String name, String classification) {
        CreateLeaveTypeRequest req = new CreateLeaveTypeRequest();
        req.setCode(code);
        req.setName(name);
        req.setClassification(classification);
        return req;
    }

    @Test
    void create_withPaidClassification_persistsAndReturnsIt() {
        LeaveTypeResponse resp = leaveTypeService.create(createReq("ANNUAL2", "Annual Leave 2", "PAID"), actorEmail);

        assertEquals("PAID", resp.getClassification());
        assertEquals("ANNUAL2", resp.getCode());
        verify(auditService).log(eq(actorId), eq("LEAVE_TYPE_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_withUnpaidClassification_persistsAndReturnsIt() {
        LeaveTypeResponse resp = leaveTypeService.create(createReq("LOP", "Loss of Pay", "UNPAID"), actorEmail);

        assertEquals("UNPAID", resp.getClassification());
        verify(auditService).log(eq(actorId), eq("LEAVE_TYPE_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_normalizesCodeToUppercaseAndTrims() {
        ArgumentCaptor<LeaveType> captor = ArgumentCaptor.forClass(LeaveType.class);

        leaveTypeService.create(createReq(" lop ", "Loss of Pay", "UNPAID"), actorEmail);

        verify(leaveTypeRepository).save(captor.capture());
        assertEquals("LOP", captor.getValue().getCode());
    }

    @Test
    void create_duplicateCode_isRejected() {
        when(leaveTypeRepository.existsByCodeIgnoreCase("ANNUAL")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> leaveTypeService.create(createReq("ANNUAL", "Annual Leave", "PAID"), actorEmail));
        assertTrue(ex.getMessage().contains("ANNUAL"));
        verify(leaveTypeRepository, never()).save(any());
    }

    @Test
    void update_changesClassificationFromPaidToUnpaid_andIsPersisted() {
        LeaveType annual = LeaveType.builder().id(UUID.randomUUID()).code("ANNUAL").name("Annual Leave")
                .classification(LeaveTypeClassification.PAID).build();
        when(leaveTypeRepository.findById(annual.getId())).thenReturn(Optional.of(annual));

        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setClassification("UNPAID");
        LeaveTypeResponse resp = leaveTypeService.update(annual.getId(), req, actorEmail);

        assertEquals("UNPAID", resp.getClassification());
        assertEquals(LeaveTypeClassification.UNPAID, annual.getClassification());
        verify(auditService).log(eq(actorId), eq("LEAVE_TYPE_UPDATED"), eq(annual.getId()), any(), any());
    }

    @Test
    void update_changesClassificationFromUnpaidToPaid_andIsPersisted() {
        LeaveType lop = LeaveType.builder().id(UUID.randomUUID()).code("LOP").name("Loss of Pay")
                .classification(LeaveTypeClassification.UNPAID).build();
        when(leaveTypeRepository.findById(lop.getId())).thenReturn(Optional.of(lop));

        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setClassification("PAID");
        LeaveTypeResponse resp = leaveTypeService.update(lop.getId(), req, actorEmail);

        assertEquals("PAID", resp.getClassification());
    }

    @Test
    void update_withNullClassification_leavesExistingClassificationUnchanged() {
        LeaveType annual = LeaveType.builder().id(UUID.randomUUID()).code("ANNUAL").name("Annual Leave")
                .classification(LeaveTypeClassification.PAID).build();
        when(leaveTypeRepository.findById(annual.getId())).thenReturn(Optional.of(annual));

        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setName("Annual Leave (renamed)");
        LeaveTypeResponse resp = leaveTypeService.update(annual.getId(), req, actorEmail);

        assertEquals("Annual Leave (renamed)", resp.getName());
        assertEquals("PAID", resp.getClassification());
    }

    @Test
    void update_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(leaveTypeRepository.findById(missing)).thenReturn(Optional.empty());

        UpdateLeaveTypeRequest req = new UpdateLeaveTypeRequest();
        req.setClassification("UNPAID");
        assertThrows(NoSuchElementException.class, () -> leaveTypeService.update(missing, req, actorEmail));
    }
}
