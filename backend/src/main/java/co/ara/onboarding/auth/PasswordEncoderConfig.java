package co.ara.onboarding.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    /**
     * Argon2id with OWASP's recommended baseline: 64 MiB memory, 3 iterations,
     * parallelism 1. Memory cost is the parameter that matters against GPU attack,
     * which is why it is high while the iteration count stays modest.
     *
     * Arguments are (saltLength, hashLength, parallelism, memoryKb, iterations) —
     * an easy signature to transpose, and transposing memory and iterations
     * produces a hash that still verifies while being trivially cheap to crack.
     *
     * Requires BouncyCastle on the classpath; Spring's encoder delegates to its
     * Argon2BytesGenerator.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 16, 3);
    }
}
