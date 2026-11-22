package ru.vsuet.schedule.admin.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.vsuet.schedule.admin.application.AdminProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminProperties properties;

    AdminWebConfiguration(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAccessInterceptor(properties)).addPathPatterns("/api/v1/admin/**");
    }
}
