package com.kssasarma.confluencebot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import com.kssasarma.confluencebot.config.WebConfig;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final UserDetailsService userDetailsService;
    private final WebConfig corsProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, UserDetailsService userDetailsService,
                          WebConfig corsProperties, ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.userDetailsService = userDetailsService;
        this.corsProperties = corsProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * The application's chain, and deliberately the last one consulted.
     *
     * <p>Single sign-on registers a chain ahead of this one for the two OAuth URLs it needs a
     * session on (see {@code SsoSecurityConfig}); everything else lands here and is authenticated
     * by bearer token with no session at all. Spelling the order out rather than leaning on the
     * default keeps that relationship readable from either end.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Only the flows a signed-out visitor can actually reach: signing in,
                        // rotating a refresh token, revoking one on the way out, and resetting a
                        // forgotten password by OTP. Everything else under /api/auth/** — /me,
                        // /change-password, /name — needs a signed-in principal, and used to be
                        // swept into this same permitAll: a missing or expired bearer token still
                        // reached those controller methods with a null @AuthenticationPrincipal
                        // instead of being stopped here with a clean 401.
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/forgot-password/**",
                                // A signed-out visitor asks whether there is a directory to sign in
                                // through, and redeems the code the directory sent them back with.
                                "/api/auth/sso",
                                "/api/auth/sso/exchange",
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // Method security on AdminController is the real gate per endpoint; this
                        // must admit everyone it admits, or a read-only admin is bounced here
                        // before that finer-grained check ever runs.
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "ADMIN_READ_ONLY")
                        .anyRequest().authenticated()
                )
                // Without this, a request with no (or an expired) bearer token that reaches an
                // authenticated endpoint gets the servlet container's default error page instead
                // of the same ProblemDetail JSON every other error on this API returns.
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
                    problem.setTitle("Authentication Failed");
                    problem.setType(URI.create("urn:confluencebot:error:authentication"));
                    problem.setDetail("A valid access token is required to access this resource.");
                    objectMapper.writeValue(response.getWriter(), problem);
                }))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
