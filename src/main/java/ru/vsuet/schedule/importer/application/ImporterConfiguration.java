package ru.vsuet.schedule.importer.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ImportProperties.class, DownloadProperties.class})
class ImporterConfiguration {
}
