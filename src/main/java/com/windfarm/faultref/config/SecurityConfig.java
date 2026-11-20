package com.windfarm.faultref.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP basic auth with three roles:
 *   - viewer      read-only access
 *   - engineer    read + diagnose + propose mappings
 *   - admin       everything, including approvals and release publishing
 *
 * Swagger UI and the OpenAPI document are public so the API stays explorable.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.security.enabled:true}")
    private boolean securityEnabled;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("viewer")
                        .password(encoder.encode("viewer123"))
                        .roles("VIEWER")
                        .build(),
                User.withUsername("engineer")
                        .password(encoder.encode("engineer123"))
                        .roles("ENGINEER")
                        .build(),
                User.withUsername("admin")
                        .password(encoder.encode("admin123"))
                        .roles("ADMIN")
                        .build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (!securityEnabled) {
                        auth.anyRequest().permitAll();
                        return;
                    }
                    auth
                            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                    "/actuator/health", "/error").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("VIEWER", "ENGINEER", "ADMIN")
                            .requestMatchers(HttpMethod.POST, "/api/mappings/conflicts/*/adjudications")
                                .hasAnyRole("ADMIN")
                            .requestMatchers(HttpMethod.POST, "/api/releases/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ENGINEER", "ADMIN")
                            .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("ENGINEER", "ADMIN")
                            .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ENGINEER", "ADMIN")
                            .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                            .anyRequest().authenticated();
                })
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    new ObjectMapper().writeValue(res.getOutputStream(), new java.util.LinkedHashMap<>() {{
                        put("status", 401);
                        put("code", "UNAUTHENTICATED");
                        put("message", "HTTP Basic credentials required");
                        put("path", req.getRequestURI());
                    }});
                }));
        return http.build();
    }
}
