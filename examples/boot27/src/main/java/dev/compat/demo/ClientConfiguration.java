package dev.compat.demo;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Provides a client whose auto-selected request factory depends on the classpath. */
@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {
    /** Builds the HTTP client without sending requests or contacting external services. */
    @Bean
    public RestTemplate shopClient(RestTemplateBuilder builder) {
        return builder.build();
    }
}
