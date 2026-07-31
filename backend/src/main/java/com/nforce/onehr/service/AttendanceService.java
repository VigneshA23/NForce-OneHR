package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceRecordResponse;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRecordRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> listMine(String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        return attendanceRepository.findByUserIdOrderByAttendanceDateDesc(actor.getId()).stream()
                .map(r -> AttendanceRecordResponse.builder()
                        .id(r.getId())
                        .attendanceDate(r.getAttendanceDate())
                        .checkIn(r.getCheckIn())
                        .checkOut(r.getCheckOut())
                        .status(r.getStatus())
                        .source(r.getSource())
                        .build())
                .toList();
    }
}
