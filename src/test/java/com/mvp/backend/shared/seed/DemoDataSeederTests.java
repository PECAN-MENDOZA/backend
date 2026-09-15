package com.mvp.backend.shared.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.shared.security.PersonalDataCipher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class DemoDataSeederTests {

    private static final String TEACHER_PASSWORD = "SuperSecretTeacher1";
    private static final String STUDENT_PASSWORD = "7391";

    private final Logger logger = (Logger) LoggerFactory.getLogger(DemoDataSeeder.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    @Test
    void seedNeverLogsPlaintextPasswords() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PersonalDataCipher cipher = mock(PersonalDataCipher.class);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(cipher.encrypt(anyString())).thenReturn("enc");
        DemoDataSeeder seeder = new DemoDataSeeder(
                new DemoSeedProperties(true, false, false, 2, TEACHER_PASSWORD, STUDENT_PASSWORD),
                jdbcTemplate, passwordEncoder, cipher, new ObjectMapper(), mock(ApplicationContext.class),
                new NoOpTransactionManager());

        seeder.run(new DefaultApplicationArguments());

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("Teacher login: sofia.garcia@colegio.edu.pe"))
                .anySatisfy(message -> assertThat(message).contains("Student login example: student_001"))
                .allSatisfy(message -> assertThat(message).doesNotContain(TEACHER_PASSWORD, STUDENT_PASSWORD));
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
