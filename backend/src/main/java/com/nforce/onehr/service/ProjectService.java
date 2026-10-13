package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.CreateProjectRequest;
import com.nforce.onehr.dto.allocation.ProjectResponse;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProjectResponse> listActive() {
        return projectRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest req) {
        Project project = Project.builder().name(req.getName().trim()).active(true).build();
        return toResponse(projectRepository.save(project));
    }

    private ProjectResponse toResponse(Project p) {
        return ProjectResponse.builder().id(p.getId()).name(p.getName()).active(p.isActive()).build();
    }
}
