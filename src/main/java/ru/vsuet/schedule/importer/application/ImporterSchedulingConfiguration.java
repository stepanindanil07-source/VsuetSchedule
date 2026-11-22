package ru.vsuet.schedule.importer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Kept off the application class so that slice tests do not start a scheduler. Disable with vsuet.import.scheduler.enabled=false. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "vsuet.import.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class ImporterSchedulingConfiguration {
}
