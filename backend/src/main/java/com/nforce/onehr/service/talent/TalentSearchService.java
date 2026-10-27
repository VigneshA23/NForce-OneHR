package com.nforce.onehr.service.talent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.talent.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.service.EmployeeCertificateService;
import com.nforce.onehr.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "Who knows Java?" (org.talent.view). Matches by skill_id, not name/alias text — aliases only
 * ever help the search BOX resolve what someone typed to a skillId client-side (per the spec's
 * own note), they play no further role once the request reaches here.
 *
 * <p>In-memory filter/sort/paginate over one bulk fetch, same scale assumption every other
 * org-wide Talent & Skills read in this phase already makes (TalentDirectoryService/
 * TalentDashboardService) — correct and simple for an org this size; a very large org would want
 * this pushed into SQL instead, but that's a scale problem to solve when it's actually a problem,
 * not speculatively now.
 */
@Service
@RequiredArgsConstructor
public class TalentSearchService {

    private final EmployeeService employeeService;
    private final EmployeeSkillRepository employeeSkillRepo;
    private final EmployeeCertificateRepository employeeCertificateRepo;
    private final EmployeeCertificateService employeeCertificateService;
    private final EmployeeLearningEntryRepository employeeLearningEntryRepo;
    private final SkillMasterRepository skillMasterRepo;
    private final ProficiencyLevelRepository proficiencyLevelRepo;
    private final SavedTalentSearchRepository savedSearchRepo;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public TalentSearchPageResponse search(TalentSearchRequest req) {
        List<DirectoryEntryDto> candidates = employeeService.listDirectory().stream()
                .filter(e -> matchesText(req.getDepartment(), e.getDepartmentName()))
                .filter(e -> matchesText(req.getLocation(), e.getLocationName()))
                .filter(e -> matchesText(req.getManagerName(), e.getManagerName()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return emptyPage(req);

        List<UUID> ids = candidates.stream().map(e -> UUID.fromString(e.getUserId())).collect(Collectors.toList());
        Map<UUID, List<EmployeeSkill>> skillsByEmployee = employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(ids)
                .stream().collect(Collectors.groupingBy(EmployeeSkill::getEmployeeUserId));
        Map<UUID, List<EmployeeCertificate>> certsByEmployee = employeeCertificateRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(ids)
                .stream().collect(Collectors.groupingBy(EmployeeCertificate::getEmployeeUserId));
        Map<UUID, List<EmployeeLearningEntry>> learningByEmployee = employeeLearningEntryRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(ids)
                .stream().collect(Collectors.groupingBy(EmployeeLearningEntry::getEmployeeUserId));
        Map<UUID, List<EmployeeCertificateResponse>> certResponsesByEmployee = employeeCertificateService.listForEmployees(ids);

        Map<UUID, Integer> proficiencySortOrderById = proficiencyLevelRepo.findAll().stream()
                .collect(Collectors.toMap(ProficiencyLevel::getId, ProficiencyLevel::getSortOrder));
        int minimumSortOrder = req.getMinimumProficiencyLevelId() == null ? Integer.MIN_VALUE
                : proficiencySortOrderById.getOrDefault(req.getMinimumProficiencyLevelId(), Integer.MIN_VALUE);
        Set<UUID> requestedSkillIds = req.getSkillIds() == null ? Set.of() : new HashSet<>(req.getSkillIds());
        boolean matchAll = "ALL".equals(req.getMatchMode());

        List<TalentSearchResultItem> matched = new ArrayList<>();
        for (DirectoryEntryDto entry : candidates) {
            UUID employeeId = UUID.fromString(entry.getUserId());
            List<EmployeeSkill> skills = skillsByEmployee.getOrDefault(employeeId, List.of());
            List<EmployeeCertificate> certs = certsByEmployee.getOrDefault(employeeId, List.of());
            List<EmployeeLearningEntry> learning = learningByEmployee.getOrDefault(employeeId, List.of());

            List<EmployeeSkill> matchingSkills = skills.stream()
                    .filter(s -> requestedSkillIds.isEmpty() || (s.getSkillId() != null && requestedSkillIds.contains(s.getSkillId())))
                    .filter(s -> s.getProficiencyLevelId() == null
                            || proficiencySortOrderById.getOrDefault(s.getProficiencyLevelId(), 0) >= minimumSortOrder)
                    .collect(Collectors.toList());

            if (!requestedSkillIds.isEmpty()) {
                Set<UUID> matchedIds = matchingSkills.stream().map(EmployeeSkill::getSkillId).collect(Collectors.toSet());
                boolean satisfiesSkillFilter = matchAll ? matchedIds.containsAll(requestedSkillIds) : !matchedIds.isEmpty();
                if (!satisfiesSkillFilter) continue;
            }

            if (req.getCertified() != null && req.getCertified() != !certs.isEmpty()) continue;

            if (req.getLearningStatus() != null
                    && learning.stream().noneMatch(l -> req.getLearningStatus().equals(l.getStatus()))) {
                continue;
            }

            String currentTrack = learning.stream()
                    .filter(l -> "In Progress".equals(l.getStatus()) || "Planned".equals(l.getStatus()))
                    .findFirst().map(EmployeeLearningEntry::getTitle).orElse(null);

            int score = matchingSkills.size() * 100
                    + matchingSkills.stream().mapToInt(s -> s.getProficiencyLevelId() == null ? 0
                            : proficiencySortOrderById.getOrDefault(s.getProficiencyLevelId(), 0) * 10).sum()
                    + (certs.isEmpty() ? 0 : 5);

            matched.add(TalentSearchResultItem.builder()
                    .employeeUserId(employeeId)
                    .employeeName(entry.getFullName())
                    .employeeCode(entry.getEmployeeCode())
                    .departmentName(entry.getDepartmentName())
                    .managerName(entry.getManagerName())
                    .matchingSkills(matchingSkills.stream()
                            .map(s -> new MatchedSkill(s.getSkillName(), s.getProficiencyLevel()))
                            .collect(Collectors.toList()))
                    .certificates(certResponsesByEmployee.getOrDefault(employeeId, List.of()))
                    .currentLearningTrack(currentTrack)
                    .matchScore(score)
                    .build());
        }

        Comparator<TalentSearchResultItem> order = "NAME".equals(req.getSortBy())
                ? Comparator.comparing(TalentSearchResultItem::getEmployeeName, String.CASE_INSENSITIVE_ORDER)
                : Comparator.comparingInt(TalentSearchResultItem::getMatchScore).reversed()
                        .thenComparing(TalentSearchResultItem::getEmployeeName, String.CASE_INSENSITIVE_ORDER);
        matched.sort(order);

        int safeSize = Math.max(req.getSize(), 1);
        int safePage = Math.max(req.getPage(), 0);
        int fromIndex = Math.min(safePage * safeSize, matched.size());
        int toIndex = Math.min(fromIndex + safeSize, matched.size());

        return TalentSearchPageResponse.builder()
                .items(matched.subList(fromIndex, toIndex))
                .page(safePage).size(safeSize)
                .totalElements(matched.size())
                .totalPages((int) Math.ceil(matched.size() / (double) safeSize))
                .build();
    }

    @Transactional
    public SavedTalentSearchResponse saveSearch(CreateSavedTalentSearchRequest req, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found: " + actorEmail));
        String json = writeJson(req.getQuery());
        SavedTalentSearch saved = SavedTalentSearch.builder()
                .ownerUserId(actor.getId()).name(req.getName()).queryJson(json).build();
        savedSearchRepo.save(saved);
        return SavedTalentSearchResponse.from(saved, req.getQuery());
    }

    @Transactional(readOnly = true)
    public List<SavedTalentSearchResponse> listMySavedSearches(String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found: " + actorEmail));
        return savedSearchRepo.findByOwnerUserIdOrderByCreatedAtDesc(actor.getId()).stream()
                .map(s -> SavedTalentSearchResponse.from(s, readJson(s.getQueryJson())))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteSavedSearch(UUID id, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found: " + actorEmail));
        SavedTalentSearch saved = savedSearchRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Saved search not found: " + id));
        if (!saved.getOwnerUserId().equals(actor.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Not your saved search");
        }
        savedSearchRepo.delete(saved);
    }

    // ── Helpers ──

    private boolean matchesText(String filter, String value) {
        if (filter == null || filter.isBlank()) return true;
        return value != null && value.toLowerCase().contains(filter.trim().toLowerCase());
    }

    private TalentSearchPageResponse emptyPage(TalentSearchRequest req) {
        return TalentSearchPageResponse.builder()
                .items(List.of()).page(Math.max(req.getPage(), 0)).size(Math.max(req.getSize(), 1))
                .totalElements(0).totalPages(0).build();
    }

    private String writeJson(TalentSearchRequest query) {
        try {
            return objectMapper.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize search query", e);
        }
    }

    private TalentSearchRequest readJson(String json) {
        try {
            return objectMapper.readValue(json, TalentSearchRequest.class);
        } catch (Exception e) {
            return new TalentSearchRequest();
        }
    }
}
