package ru.vsuet.schedule.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI vsuetScheduleOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("adminToken", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("Shared admin token (vsuet.admin.token). The admin endpoints are switched off when it is not set.")))
                .info(new Info()
                .title("VSUET Schedule API")
                .version("v1")
                .description("Class schedule of Voronezh State University of Engineering Technologies. "
                        + "The mobile client stores the data locally: check /schedule/version first and download "
                        + "the schedule only when the version has changed. Errors are RFC 9457 problem+json. "
                        + "Compatibility: new fields may be added at any time and clients must ignore fields they do not "
                        + "know; clients should also tolerate new values of enumerations (week type, lesson type, term)."));
    }
}
