package io.codetriage.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The student portal is public by necessity. Everything else holds student
 * names, uploaded work and review flags, so it sits behind a login.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AppProperties props, PasswordEncoder encoder) {
        String hash = props.getAdmin().getPasswordHash();

        if (hash == null || hash.isBlank()) {
            // Better a one-off password printed to the log than an app that
            // either locks you out or ships with a guessable default.
            String generated = randomPassword();
            hash = encoder.encode(generated);
            log.warn("""

                    ============================================================
                     ADMIN_PASSWORD_HASH is not set.
                     A temporary password has been generated for this run only:

                         username: {}
                         password: {}

                     It changes on every restart. Set ADMIN_PASSWORD_HASH to a
                     bcrypt hash to make your login permanent (see README).
                    ============================================================
                    """, props.getAdmin().getUsername(), generated);
        }

        return new InMemoryUserDetailsManager(
                User.withUsername(props.getAdmin().getUsername())
                        .password(hash)
                        .roles("LECTURER")
                        .build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/submit", "/submitted", "/css/**",
                                "/favicon.ico", "/error").permitAll()
                        // The generated login page renders from a filter that runs
                        // before authorization, so it appears even unpermitted. The
                        // POST does not get that courtesy, so say it explicitly
                        // rather than relying on filter ordering.
                        .requestMatchers("/login", "/login?error", "/logout").permitAll()
                        .requestMatchers("/admin/**").hasRole("LECTURER")
                        .anyRequest().authenticated())
                .formLogin(form -> form.permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/"))
                // Uploads are large; the default CSRF repository is fine, but the
                // H2 console and frames stay off entirely.
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }

    private String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
