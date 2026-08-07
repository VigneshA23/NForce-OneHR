package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpdeskReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HelpdeskReplyRepository extends JpaRepository<HelpdeskReply, UUID> {

    List<HelpdeskReply> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    long countByTicketId(UUID ticketId);
}
