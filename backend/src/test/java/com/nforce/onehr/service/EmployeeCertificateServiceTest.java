package com.nforce.onehr.service;

import com.nforce.onehr.dto.certificates.EmployeeCertificateRequest;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.entity.CertificateCategory;
import com.nforce.onehr.entity.EmployeeCertificate;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.CertificateCategoryRepository;
import com.nforce.onehr.repository.EmployeeCertificateRepository;
import com.nforce.onehr.repository.UserRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeCertificateServiceTest {

    @Mock private EmployeeCertificateRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private CertificateCategoryRepository categoryRepository;
    @InjectMocks private EmployeeCertificateService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();

    private EmployeeCertificateRequest req(String name, UUID categoryId) {
        EmployeeCertificateRequest r = new EmployeeCertificateRequest();
        r.setName(name);
        r.setCategoryId(categoryId);
        return r;
    }

    @Test
    void create_withACategory_resolvesTheCategoryName() {
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        CertificateCategory category = CertificateCategory.builder().id(UUID.randomUUID()).name("Cloud").build();
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));

        EmployeeCertificateResponse res = service.create("actor@test.com", req("AWS SAA", category.getId()));

        assertEquals(category.getId(), res.getCategoryId());
        assertEquals("Cloud", res.getCategoryName());
    }

    @Test
    void create_withoutACategory_leavesItNullRatherThanErroring() {
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));

        EmployeeCertificateResponse res = service.create("actor@test.com", req("Internal Training Cert", null));

        assertNull(res.getCategoryId());
        assertNull(res.getCategoryName());
    }

    @Test
    void create_rejectsACategoryIdThatDoesNotExist() {
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(User.builder().id(ACTOR_ID).build()));
        UUID bogusId = UUID.randomUUID();
        when(categoryRepository.findById(bogusId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.create("actor@test.com", req("AWS SAA", bogusId)));
    }

    @Test
    void listForEmployee_leavesUncategorizedCertificatesWithANullCategoryName() {
        EmployeeCertificate uncategorized = EmployeeCertificate.builder().id(UUID.randomUUID())
                .employeeUserId(ACTOR_ID).name("Old Cert").build();
        when(repository.findByEmployeeUserIdOrderByCreatedAtAsc(ACTOR_ID)).thenReturn(List.of(uncategorized));
        when(categoryRepository.findAll()).thenReturn(List.of());

        List<EmployeeCertificateResponse> result = service.listForEmployee(ACTOR_ID);

        assertNull(result.get(0).getCategoryName());
    }
}
