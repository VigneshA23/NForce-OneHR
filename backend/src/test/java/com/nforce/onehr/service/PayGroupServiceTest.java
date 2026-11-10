package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreatePayGroupRequest;
import com.nforce.onehr.dto.payroll.PayGroupResponse;
import com.nforce.onehr.dto.payroll.UpdatePayGroupRequest;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as LeaveTypeServiceTest. */
@ExtendWith(MockitoExtension.class)
class PayGroupServiceTest {

    @Mock private PayGroupRepository payGroupRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private PayGroupService payGroupService;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(payGroupRepository.save(any(PayGroup.class))).thenAnswer(inv -> {
            PayGroup g = inv.getArgument(0);
            if (g.getId() == null) g.setId(UUID.randomUUID());
            return g;
        });
    }

    private CreatePayGroupRequest createReq(String name, int startDay, int endDay, int processingDay) {
        CreatePayGroupRequest req = new CreatePayGroupRequest();
        req.setName(name);
        req.setFrequency("MONTHLY");
        req.setCurrency("INR");
        req.setCycleStartDay(startDay);
        req.setCycleEndDay(endDay);
        req.setProcessingDay(processingDay);
        return req;
    }

    @Test
    void create_persistsAndReturnsIt() {
        PayGroupResponse resp = payGroupService.create(createReq("Default pay group", 26, 25, 26), actorEmail);

        assertEquals("Default pay group", resp.getName());
        assertEquals(26, resp.getCycleStartDay());
        assertEquals(25, resp.getCycleEndDay());
        assertEquals("MONTHLY", resp.getFrequency());
        assertEquals("INR", resp.getCurrency());
        assertTrue(resp.isActive());
        verify(auditService).log(eq(actorId), eq("PAY_GROUP_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_trimsNameAndLegalEntity() {
        CreatePayGroupRequest req = createReq("  Default pay group  ", 26, 25, 26);
        req.setLegalEntity("  Acme Corp  ");

        PayGroupResponse resp = payGroupService.create(req, actorEmail);

        assertEquals("Default pay group", resp.getName());
        assertEquals("Acme Corp", resp.getLegalEntity());
    }

    @Test
    void update_changesScheduleFields_andIsPersisted() {
        PayGroup existing = PayGroup.builder().id(UUID.randomUUID()).name("Default pay group")
                .frequency("MONTHLY").currency("INR").cycleStartDay(26).cycleEndDay(25).processingDay(26).active(true).build();
        when(payGroupRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdatePayGroupRequest req = new UpdatePayGroupRequest();
        req.setProcessingDay(27);
        PayGroupResponse resp = payGroupService.update(existing.getId(), req, actorEmail);

        assertEquals(27, resp.getProcessingDay());
        assertEquals(26, resp.getCycleStartDay());
        verify(auditService).log(eq(actorId), eq("PAY_GROUP_UPDATED"), eq(existing.getId()), any(), any());
    }

    @Test
    void update_deactivates() {
        PayGroup existing = PayGroup.builder().id(UUID.randomUUID()).name("Default pay group")
                .frequency("MONTHLY").currency("INR").cycleStartDay(26).cycleEndDay(25).processingDay(26).active(true).build();
        when(payGroupRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdatePayGroupRequest req = new UpdatePayGroupRequest();
        req.setActive(false);
        PayGroupResponse resp = payGroupService.update(existing.getId(), req, actorEmail);

        assertFalse(resp.isActive());
    }

    @Test
    void update_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(payGroupRepository.findById(missing)).thenReturn(Optional.empty());

        UpdatePayGroupRequest req = new UpdatePayGroupRequest();
        req.setActive(false);
        assertThrows(NoSuchElementException.class, () -> payGroupService.update(missing, req, actorEmail));
    }

    @Test
    void list_ordersByName() {
        PayGroup a = PayGroup.builder().id(UUID.randomUUID()).name("Default pay group")
                .frequency("MONTHLY").currency("INR").cycleStartDay(26).cycleEndDay(25).processingDay(26).build();
        when(payGroupRepository.findAllByOrderByNameAsc()).thenReturn(List.of(a));

        List<PayGroupResponse> result = payGroupService.list();

        assertEquals(1, result.size());
        assertEquals("Default pay group", result.get(0).getName());
    }

    @Test
    void get_unknownId_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(payGroupRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> payGroupService.get(missing));
    }
}
