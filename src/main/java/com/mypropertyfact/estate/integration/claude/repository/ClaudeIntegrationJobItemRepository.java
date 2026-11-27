package com.mypropertyfact.estate.integration.claude.repository;

import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJobItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaudeIntegrationJobItemRepository extends JpaRepository<ClaudeIntegrationJobItem, Long> {

    List<ClaudeIntegrationJobItem> findByJob_IdOrderByExcelRowAsc(Long jobRefId);
}
