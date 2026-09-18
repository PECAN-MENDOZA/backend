package com.mvp.backend.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.mvp.backend.teacher.domain.repository.TeacherRepository;

import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            TeacherRepository teacherRepository,
            ObjectMapper objectMapper) throws Exception {
        // Se construye aqui (no es @Component) para que Boot no lo registre ademas como filtro
        // de servlet fuera de la cadena de seguridad.
        var temporaryPasswordFilter = new TemporaryPasswordFilter(teacherRepository, objectMapper);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Defensa en profundidad: los controladores repiten estas reglas con @PreAuthorize,
                        // pero un endpoint nuevo bajo estos prefijos queda protegido aunque olvide la anotacion.
                        .requestMatchers("/api/v1/research/**").hasRole("RESEARCHER")
                        .requestMatchers("/api/v1/teachers/**", "/api/v1/kpis/**", "/api/v1/reports/**")
                        .hasRole("TEACHER")
                        .requestMatchers("/api/v1/tests/**", "/api/v1/attempts/**", "/api/v1/students/**",
                                "/api/v1/corrections/**")
                        .hasRole("STUDENT")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // Tras la autorizacion: solo docentes ya autorizados pagan la lectura del flag.
                .addFilterAfter(temporaryPasswordFilter, AuthorizationFilter.class)
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(AppSecurityProperties properties) {
        return NimbusJwtEncoder.withSecretKey(jwtSecret(properties))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(AppSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecret(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        var issuerValidator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        var roleValidator = new JwtClaimValidator<String>("role", role -> role != null && !role.isBlank());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, roleValidator));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("role");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return authenticationConverter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppSecurityProperties properties) {
        List<String> allowedOrigins = properties.allowedOrigins() == null || properties.allowedOrigins().isEmpty()
                ? List.of("*")
                : properties.allowedOrigins();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private SecretKey jwtSecret(AppSecurityProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
