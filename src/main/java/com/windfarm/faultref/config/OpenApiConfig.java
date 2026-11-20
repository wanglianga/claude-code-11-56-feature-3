package com.windfarm.faultref.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI faultRefOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Offshore Wind Fault-Code Reference Mapping & Diagnosis Replay API")
                        .version("1.0.0")
                        .description("""
                                Service that turns controller alarms, vendor work orders and spare-part catalogs into
                                replayable fault-diagnosis facts.

                                * Source codes, unified concepts and dispositions are kept in separate columns and tables.
                                * Mapping history is modelled two ways: time-validity assertions (Mode A) and immutable
                                  release snapshots (Mode B).
                                * Offline backfilled alarms are interpreted by occurrence time, not receipt time.
                                * Contradictory mappings are preserved as conflicts; diagnosis reports them instead of
                                  silently picking one.
                                * A diagnosis run pins one catalog/inventory context; mid-run catalog updates never leak
                                  into its results.
                                * Mutually exclusive dispositions leave a concept PENDING_ENGINEER.
                                """)
                        .contact(new Contact().name("Offshore Wind O&M Platform"))
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
