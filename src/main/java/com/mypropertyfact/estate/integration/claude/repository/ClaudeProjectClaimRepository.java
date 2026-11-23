package com.mypropertyfact.estate.integration.claude.repository;

import com.mypropertyfact.estate.integration.claude.entity.ClaudeProjectClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaudeProjectClaimRepository extends JpaRepository<ClaudeProjectClaim, Long> {

    Optional<ClaudeProjectClaim> findByClaimKey(String claimKey);

    List<ClaudeProjectClaim> findByClaimKeyIn(Collection<String> claimKeys);

    List<ClaudeProjectClaim> findByJobIdAndProjectId(String jobId, Integer projectId);
}
