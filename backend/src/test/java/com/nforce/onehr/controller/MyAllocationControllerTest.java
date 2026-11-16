package com.nforce.onehr.controller;

import com.nforce.onehr.dto.allocation.MyAllocationResponse;
import com.nforce.onehr.dto.allocation.MyAllocationResponse.Status;
import com.nforce.onehr.exception.GlobalExceptionHandler;
import com.nforce.onehr.service.MyAllocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-B08 HTTP contract: GET /api/allocations/me returns the caller's allocations and the controller
 * offers no way to create, edit or delete one or to name another employee. (An unauthenticated call
 * is rejected by the security filter chain — {@code anyRequest().authenticated()} — which a
 * standalone controller test does not load.)
 */
@ExtendWith(MockitoExtension.class)
class MyAllocationControllerTest {

    @Mock private MyAllocationService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Spring Boot writes dates as ISO strings (2026-10-01), not timestamp arrays; mirror that here.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new MyAllocationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void get_returnsTheCallersAllocationsWithOnlyTheDisplayedFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.myCurrentAndUpcoming("emp@x.com")).thenReturn(List.of(MyAllocationResponse.builder()
                .id(id).projectName("Apollo").clientName("Acme Corp").capacityPercent(60)
                .startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2027, 3, 31)).status(Status.CURRENT).build()));

        mvc.perform(get("/api/allocations/me").principal(() -> "emp@x.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].projectName").value("Apollo"))
                .andExpect(jsonPath("$[0].clientName").value("Acme Corp"))
                .andExpect(jsonPath("$[0].capacityPercent").value(60))
                .andExpect(jsonPath("$[0].startDate").value("2026-10-01"))
                .andExpect(jsonPath("$[0].endDate").value("2027-03-31"))
                .andExpect(jsonPath("$[0].status").value("CURRENT"))
                // nothing about anyone else or about capacity totals leaks into the projection
                .andExpect(jsonPath("$[0].employeeId").doesNotExist())
                .andExpect(jsonPath("$[0].employeeName").doesNotExist());
    }

    @Test
    void get_withNoAllocations_returnsAnEmptyArray() throws Exception {
        when(service.myCurrentAndUpcoming("emp@x.com")).thenReturn(List.of());

        mvc.perform(get("/api/allocations/me").principal(() -> "emp@x.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void mutatingVerbs_areNotAllowed() throws Exception {
        mvc.perform(post("/api/allocations/me").principal(() -> "emp@x.com")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/allocations/me").principal(() -> "emp@x.com")).andExpect(status().isMethodNotAllowed());
        mvc.perform(patch("/api/allocations/me").principal(() -> "emp@x.com")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/allocations/me").principal(() -> "emp@x.com")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void controllerHasExactlyOneHandler_aGet_thatTakesOnlyThePrincipal() {
        Method[] handlers = java.util.Arrays.stream(MyAllocationController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(PatchMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(RequestMapping.class))
                .toArray(Method[]::new);

        assertEquals(1, handlers.length);
        assertTrue(handlers[0].isAnnotationPresent(GetMapping.class));
        // No employee id, path variable or body can be supplied: the only parameter is the caller's Principal.
        assertArrayEquals(new Class<?>[] {Principal.class}, handlers[0].getParameterTypes());
    }
}
