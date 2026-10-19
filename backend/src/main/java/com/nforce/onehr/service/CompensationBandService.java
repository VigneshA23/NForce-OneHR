package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.CompensationBandDtos.AssignmentRequest;
import com.nforce.onehr.dto.bench.CompensationBandDtos.AssignmentResponse;
import com.nforce.onehr.dto.bench.CompensationBandDtos.BandRequest;
import com.nforce.onehr.dto.bench.CompensationBandDtos.BandResponse;
import com.nforce.onehr.entity.CompensationBand;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeCompensationBand;
import com.nforce.onehr.repository.CompensationBandRepository;
import com.nforce.onehr.repository.EmployeeCompensationBandRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * US-A13 inputs: average-CTC bands and each employee's band label. Deliberately standalone — it
 * never reads the individual compensation data of another module, so the bench cost view cannot
 * become a back door to personal pay.
 */
@Service
@RequiredArgsConstructor
public class CompensationBandService {

    private final CompensationBandRepository bandRepository;
    private final EmployeeCompensationBandRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<BandResponse> bands() {
        return bandRepository.findAll().stream().sorted(Comparator.comparing(CompensationBand::getAvgCtc))
                .map(b -> new BandResponse(b.getId(), b.getBandLabel(), b.getAvgCtc())).toList();
    }

    @Transactional
    public BandResponse addBand(BandRequest req) {
        String label = req.bandLabel().trim();
        if (bandRepository.existsByBandLabelIgnoreCase(label)) {
            throw new IllegalStateException("Band \"" + label + "\" already exists");
        }
        CompensationBand saved = bandRepository.save(CompensationBand.builder().bandLabel(label).avgCtc(req.avgCtc()).build());
        return new BandResponse(saved.getId(), saved.getBandLabel(), saved.getAvgCtc());
    }

    /** Assigns the employee to a band, or clears it when bandId is null. */
    @Transactional
    public void assign(AssignmentRequest req) {
        if (!employeeRepository.existsById(req.employeeId())) throw new NoSuchElementException("Employee not found");
        if (req.bandId() == null) {
            assignmentRepository.deleteById(req.employeeId());
            return;
        }
        if (!bandRepository.existsById(req.bandId())) throw new NoSuchElementException("Band not found");
        assignmentRepository.save(EmployeeCompensationBand.builder()
                .employeeUserId(req.employeeId()).bandId(req.bandId()).build());
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> assignments() {
        Map<UUID, CompensationBand> bands = bandRepository.findAll().stream()
                .collect(Collectors.toMap(CompensationBand::getId, Function.identity()));
        List<EmployeeCompensationBand> rows = assignmentRepository.findAll();
        Map<UUID, String> names = employeeRepository.findAllById(
                rows.stream().map(EmployeeCompensationBand::getEmployeeUserId).toList()).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
        return rows.stream().filter(r -> bands.containsKey(r.getBandId()))
                .map(r -> new AssignmentResponse(r.getEmployeeUserId(), names.get(r.getEmployeeUserId()),
                        r.getBandId(), bands.get(r.getBandId()).getBandLabel())).toList();
    }
}
