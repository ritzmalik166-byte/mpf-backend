package com.mypropertyfact.estate.automation.discovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="mpf.discovery.enabled",havingValue="true")
public class DiscoverySchedulingConfig {}
