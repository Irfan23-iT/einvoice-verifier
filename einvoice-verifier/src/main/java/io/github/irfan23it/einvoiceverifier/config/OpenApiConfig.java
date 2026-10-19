package io.github.irfan23it.einvoiceverifier.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes human-readable metadata for the generated OpenAPI document at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI einvoiceOpenApi() {
        return new OpenAPI().info(new Info()
            .title("e-Invoice Pre-Flight Verifier API")
            .version("v1.1.0")
            .description("Gatekeeper that validates single e-invoice payloads and CSV billing "
                + "dumps against LHDN MyInvois rules before gateway submission."));
    }
}
