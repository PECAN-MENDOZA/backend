package com.mvp.backend.research.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

/**
 * Bootstraps a researcher account from environment variables. There is no public
 * registration endpoint for researchers: the only way to create one is by setting
 * {@code RESEARCHER_EMAIL} and {@code RESEARCHER_PASSWORD} before startup. The seed
 * is idempotent (skipped once the account already exists) and fails startup instead
 * of silently colliding with an existing teacher email.
 *
 * <p>Runs before {@code DemoDataSeeder} (order 100) so the demo seed can look up
 * the researcher account it seeds the demo test with.
 */
@Component
@Order(50)
public class ResearcherAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ResearcherAccountSeeder.class);

    private final ResearcherRepository researcherRepository;
    private final TeacherRepository teacherRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public ResearcherAccountSeeder(
            ResearcherRepository researcherRepository,
            TeacherRepository teacherRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.researcher.email:}") String email,
            @Value("${app.researcher.password:}") String password) {
        this.researcherRepository = researcherRepository;
        this.teacherRepository = teacherRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (researcherRepository.findByEmail(email).isPresent()) {
            log.info("Researcher account already seeded for {}", email);
            return;
        }
        if (teacherRepository.existsByEmail(email)) {
            throw new IllegalStateException(
                    "Cannot seed researcher account: email " + email + " is already registered as a teacher");
        }
        researcherRepository.save(new Researcher(email, passwordEncoder.encode(password)));
        log.info("Researcher account seeded for {}", email);
    }
}
