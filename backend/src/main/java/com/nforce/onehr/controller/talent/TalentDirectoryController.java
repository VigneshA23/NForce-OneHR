package com.nforce.onehr.controller.talent;

import com.nforce.onehr.dto.TeamGrowthResponse;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import com.nforce.onehr.dto.talent.TalentEmployeeSummary;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.talent.TalentDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Org-wide Talent & Skills Dashboard table / Employee Directory (org.talent.view) and CSV
 * export (org.talent.export) — both collapse to the same hasAnyRole check, per this codebase's
 * existing convention (see SkillMasterController's own note on why). */
@RestController
@RequestMapping("/api/org/talent/directory")
@RequiredArgsConstructor
public class TalentDirectoryController {

    private final TalentDirectoryService service;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public List<TalentEmployeeSummary> listAll() {
        return service.listAll();
    }

    @GetMapping("/{employeeUserId}")
    @PreAuthorize("hasAuthority('talent_skills:VIEW:ALL')")
    public TeamGrowthResponse getEmployeeDetail(@PathVariable UUID employeeUserId, Authentication auth) {
        return service.getEmployeeDetail(employeeUserId, auth.getName());
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('talent_skills:EXPORT:ALL')")
    public ResponseEntity<byte[]> export(Authentication auth) {
        List<TalentEmployeeSummary> rows = service.listAll();
        byte[] csv = toCsv(rows).getBytes(StandardCharsets.UTF_8);

        User actor = userRepository.findByEmail(auth.getName()).orElse(null);
        if (actor != null) {
            auditService.log(actor.getId(), "TALENT_EXPORTED", actor.getId());
        }

        String filename = "talent-skills-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    private String toCsv(List<TalentEmployeeSummary> rows) {
        StringBuilder sb = new StringBuilder("Employee Code,Employee Name,Department,Location,Designation,Manager,Skills,Certificates,Learning\n");
        for (TalentEmployeeSummary r : rows) {
            sb.append(csvEscape(r.getEmployeeCode())).append(',')
              .append(csvEscape(r.getEmployeeName())).append(',')
              .append(csvEscape(r.getDepartmentName())).append(',')
              .append(csvEscape(r.getLocationName())).append(',')
              .append(csvEscape(r.getDesignationName())).append(',')
              .append(csvEscape(r.getManagerName())).append(',')
              .append(csvEscape(joinSkills(r.getSkills()))).append(',')
              .append(csvEscape(joinCertificates(r.getCertificates()))).append(',')
              .append(csvEscape(joinLearning(r.getLearningEntries()))).append('\n');
        }
        return sb.toString();
    }

    private String joinSkills(List<EmployeeSkillResponse> skills) {
        return skills.stream().map(s -> s.getSkillName() + " (" + s.getProficiencyLevel() + ")")
                .collect(Collectors.joining("; "));
    }

    private String joinCertificates(List<EmployeeCertificateResponse> certs) {
        return certs.stream().map(EmployeeCertificateResponse::getName).collect(Collectors.joining("; "));
    }

    private String joinLearning(List<EmployeeLearningEntryResponse> entries) {
        return entries.stream().map(l -> l.getTitle() + " (" + l.getStatus() + ")").collect(Collectors.joining("; "));
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) ? "\"" + escaped + "\"" : escaped;
    }
}
