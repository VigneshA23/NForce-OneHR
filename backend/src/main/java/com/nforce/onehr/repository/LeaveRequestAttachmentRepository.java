package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveRequestAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveRequestAttachmentRepository extends JpaRepository<LeaveRequestAttachment, UUID> {

    Optional<LeaveRequestAttachment> findByLeaveRequestId(UUID leaveRequestId);

    boolean existsByLeaveRequestId(UUID leaveRequestId);

    /** [leaveRequestId, fileName] of the requests that have an attachment — for list screens, without the bytes. */
    @Query("SELECT a.leaveRequestId, a.fileName FROM LeaveRequestAttachment a WHERE a.leaveRequestId IN :ids")
    List<Object[]> findNamesByRequestIds(@Param("ids") Collection<UUID> ids);
}
