package com.example.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a Jackson <b>2</b> ObjectMapper for internal use.
 *
 * <p>Spring Boot 4 serialises HTTP bodies with Jackson <b>3</b> ({@code tools.jackson}), so it
 * does not expose a Jackson 2 mapper bean. AgentScope's API, however, speaks Jackson 2
 * ({@code com.fasterxml.jackson.databind.JsonNode}), and its transitive Jackson 2 is on the
 * classpath. This bean is that mapper.
 *
 * <p>Boundary rule used throughout the code: HTTP DTOs use Jackson 3 / plain types; Jackson 2
 * appears only where AgentScope is called, and conversion goes through text
 * ({@code toString()} then {@code readTree}) because the two libraries share no types.
 */
@Configuration
public class Jackson2Config {

    @Bean
    public ObjectMapper agentScopeObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
