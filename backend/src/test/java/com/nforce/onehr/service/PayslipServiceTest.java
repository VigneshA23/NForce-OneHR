package com.nforce.onehr.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.PayslipLineItem;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.PayslipService.PayslipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US-C12: self-service payslip PDF. The access matrix is the point of this class — self, HR_ADMIN and
 * SUPER_ADMIN are allowed; a different employee and, explicitly, the employee's own MANAGER are
 * refused (403) before any payslip data is even looked up. Also: only APPROVED/DISBURSED runs are
 * ever queried, and employer-side line items never reach the PDF.
 */
@ExtendWith(MockitoExtension.class)
class PayslipServiceTest {

    @Mock private PayslipRepository payslipRepository;
    @Mock private PayslipLineItemRepository lineItemRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private UserRepository userRepository;

    private PayslipService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PayslipService(payslipRepository, lineItemRepository, employeeRepository, payGroupRepository,
                userRepository, new PayslipAccessPolicy(), new PayslipPdfService());
    }

    // ── access matrix ──

    @Test
    void self_canDownloadOwnPayslip() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        assertEquals("application/pdf", file.contentType());
        assertEquals("payslip-EMP001-2026-11.pdf", file.fileName());
        assertEquals("%PDF", new String(file.data(), 0, 4));
        assertTrue(text(file.data()).contains("Asha Rao"));
    }

    @Test
    void hrAdmin_canDownloadAnotherEmployeesPayslip() {
        stubUser("hr@x.com", UUID.randomUUID(), "HR_ADMIN");
        stubPayslip();

        assertDoesNotThrow(() -> service.getPayslipPdf("hr@x.com", employeeId, "2026-11"));
    }

    @Test
    void superAdmin_canDownloadAnotherEmployeesPayslip() {
        stubUser("root@x.com", UUID.randomUUID(), "SUPER_ADMIN");
        stubPayslip();

        assertDoesNotThrow(() -> service.getPayslipPdf("root@x.com", employeeId, "2026-11"));
    }

    @Test
    void differentEmployee_isRefused_beforeAnyPayslipLookup() {
        stubUser("peer@x.com", UUID.randomUUID(), "EMPLOYEE");

        assertThrows(AccessDeniedException.class, () -> service.getPayslipPdf("peer@x.com", employeeId, "2026-11"));

        verifyNoInteractions(payslipRepository, lineItemRepository, employeeRepository);
    }

    @Test
    void manager_ofTheEmployee_isRefused_evenForADirectReport() {
        // The employee's own line manager. Every other module would let this through; payslips must not.
        stubUser("manager@x.com", UUID.randomUUID(), "MANAGER", "EMPLOYEE");

        assertThrows(AccessDeniedException.class, () -> service.getPayslipPdf("manager@x.com", employeeId, "2026-11"));

        verifyNoInteractions(payslipRepository, lineItemRepository, employeeRepository);
    }

    // ── visibility and lookup ──

    @Test
    void onlyApprovedAndDisbursedRunsAreEverQueried() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();

        service.getMyPayslipPdf("emp@x.com", "2026-11");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(payslipRepository).findVisibleForEmployeeBetween(eq(employeeId), any(), any(), statuses.capture());
        assertEquals(Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED), Set.copyOf(statuses.getValue()));
    }

    @Test
    void periodIsTheMonthOfTheCycleEnd() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        stubPayslip();

        service.getMyPayslipPdf("emp@x.com", "2026-11");

        verify(payslipRepository).findVisibleForEmployeeBetween(eq(employeeId),
                eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 30)), anyCollection());
    }

    @Test
    void noPayslipForThePeriod_isNotFound() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        when(payslipRepository.findVisibleForEmployeeBetween(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(List.of());

        assertThrows(NoSuchElementException.class, () -> service.getMyPayslipPdf("emp@x.com", "2026-11"));
    }

    @Test
    void malformedPeriod_isRejected() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");

        assertThrows(IllegalArgumentException.class, () -> service.getMyPayslipPdf("emp@x.com", "2026-13"));
        assertThrows(IllegalArgumentException.class, () -> service.getMyPayslipPdf("emp@x.com", "November"));
        verifyNoInteractions(payslipRepository);
    }

    @Test
    void employerSideLineItems_neverReachThePdf() throws IOException {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        Payslip payslip = stubPayslip(
                item("Basic", SalaryComponentType.EARNING, "50000.00"),
                item("Employer PF", "CONTRIBUTION", "1800.00"),
                item("Professional Tax", SalaryComponentType.DEDUCTION, "200.00"));

        PayslipFile file = service.getMyPayslipPdf("emp@x.com", "2026-11");

        String text = text(file.data());
        assertTrue(text.contains("Basic") && text.contains("Professional Tax"), text);
        assertFalse(text.contains("Employer PF"), text);
        assertNotNull(payslip);
    }

    @Test
    void listMyPeriods_isNewestFirstAndDistinct() {
        stubUser("emp@x.com", employeeId, "EMPLOYEE");
        when(payslipRepository.findVisibleForEmployee(eq(employeeId), anyCollection())).thenReturn(List.of(
                payslipEndingOn(LocalDate.of(2026, 11, 25)),
                payslipEndingOn(LocalDate.of(2026, 10, 25)),
                payslipEndingOn(LocalDate.of(2026, 10, 25))));

        assertEquals(List.of("2026-11", "2026-10"), service.listMyPeriods("emp@x.com"));
    }

    // ── helpers ──

    private void stubUser(String email, UUID id, String... roleCodes) {
        User user = new User();
        user.setId(id);
        Set<Role> roles = new java.util.HashSet<>();
        for (String code : roleCodes) {
            roles.add(Role.builder().code(code).build());
        }
        user.setRoles(roles);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    }

    private Payslip stubPayslip(PayslipLineItem... items) {
        Payslip payslip = payslipEndingOn(LocalDate.of(2026, 11, 25));
        when(payslipRepository.findVisibleForEmployeeBetween(eq(employeeId), any(), any(), anyCollection()))
                .thenReturn(List.of(payslip));
        Employee employee = new Employee();
        employee.setUserId(employeeId);
        employee.setEmployeeCode("EMP001");
        employee.setFullName("Asha Rao");
        employee.setJoiningDate(LocalDate.of(2024, 4, 1));
        employee.setDepartment(Department.builder().name("Engineering").build());
        employee.setDesignation(Designation.builder().title("Engineer").build());
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(payGroupRepository.findById(payGroupId)).thenReturn(Optional.of(
                PayGroup.builder().name("Default pay group").legalEntity("NForce One Pvt Ltd").currency("INR").build()));
        when(lineItemRepository.findByPayslipId(payslip.getId()))
                .thenReturn(items.length == 0 ? List.of(item("Basic", SalaryComponentType.EARNING, "100.00")) : List.of(items));
        return payslip;
    }

    private Payslip payslipEndingOn(LocalDate cycleEnd) {
        PayrollRun run = PayrollRun.builder().payGroupId(payGroupId).cycleStart(cycleEnd.minusDays(30))
                .cycleEnd(cycleEnd).status(PayrollRunStatus.APPROVED).build();
        return Payslip.builder().id(UUID.randomUUID()).payrollRun(run).employeeId(employeeId)
                .ctc(new BigDecimal("1200000")).cycleDays(31).employedDays(31).lopDays(BigDecimal.ZERO)
                .prorationFactor(BigDecimal.ONE).grossPay(new BigDecimal("100.00"))
                .totalDeductions(BigDecimal.ZERO).netPay(new BigDecimal("100.00")).build();
    }

    private PayslipLineItem item(String name, String type, String amount) {
        return PayslipLineItem.builder().componentId(UUID.randomUUID()).componentName(name)
                .componentType(type).amount(new BigDecimal(amount)).build();
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            sb.append(extractor.getTextFromPage(p)).append('\n');
        }
        return sb.toString();
    }
}
