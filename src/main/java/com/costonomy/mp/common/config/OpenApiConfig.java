package com.costonomy.mp.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation. Required for every endpoint by doc 04 §23 and by the
 * definition of done in {@code 00-README.md} §9.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI costonomyMpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Costonomy MP API")
                        .version("v1")
                        .description("""
                                Backend for the Costonomy Marketplace (Mandi) — restaurant procurement.

                                Every response uses the envelope `{ data, error, meta }`. On failure,
                                `data` is null and `error.code` carries a stable code from the catalogue
                                in `ErrorCode` — clients should branch on that code, not on the message.

                                Mutating endpoints listed in `04-api-specification.md` §21 require an
                                `Idempotency-Key` header.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
