package com.mypropertyfact.estate.integration.claude.repository;

import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJob;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaudeIntegrationJobRepository extends JpaRepository<ClaudeIntegrationJob, Long> {

    Optional<ClaudeIntegrationJob> findByJobId(String jobId);

    Optional<ClaudeIntegrationJob> findByIdempotencyKey(String idempotencyKey);

    List<ClaudeIntegrationJob> findByJobStatusIn(Collection<ClaudeJobStatus> statuses);
}
