package com.mvp.backend.shared.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
                new NoOpTransactionManager(), "");

        seeder.run(new DefaultApplicationArguments());

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("Teacher login: sofia.garcia@colegio.edu.pe"))
                .anySatisfy(message -> assertThat(message).contains("Student login example: student_001"))
                .anySatisfy(message -> assertThat(message).contains("Demo test seed skipped"))
                .allSatisfy(message -> assertThat(message).doesNotContain(TEACHER_PASSWORD, STUDENT_PASSWORD));
    }

    @Test
    void demoTestSeedIsSkippedWhenNoResearcherAccountExists() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PersonalDataCipher cipher = mock(PersonalDataCipher.class);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(cipher.encrypt(anyString())).thenReturn("enc");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("researcher@example.com")))
                .thenReturn(List.of());
        DemoDataSeeder seeder = new DemoDataSeeder(
                new DemoSeedProperties(true, false, false, 2, TEACHER_PASSWORD, STUDENT_PASSWORD),
                jdbcTemplate, passwordEncoder, cipher, new ObjectMapper(), mock(ApplicationContext.class),
                new NoOpTransactionManager(), "researcher@example.com");

        seeder.run(new DefaultApplicationArguments());

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("Demo test seed skipped")
                        .contains("researcher@example.com"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).update(sql.capture(), params.capture());
        assertThat(sql.getAllValues()).noneMatch(statement -> statement.contains("sentence_tests")
                || statement.contains("test_sentences"));
    }

    @Test
    void demoTestIsSeededWithSixSentencesWhenResearcherAccountExists() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PersonalDataCipher cipher = mock(PersonalDataCipher.class);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(cipher.encrypt(anyString())).thenReturn("enc");
        UUID researcherId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("researcher@example.com")))
                .thenReturn(List.of(researcherId));
        DemoDataSeeder seeder = new DemoDataSeeder(
                new DemoSeedProperties(true, false, false, 2, TEACHER_PASSWORD, STUDENT_PASSWORD),
                jdbcTemplate, passwordEncoder, cipher, new ObjectMapper(), mock(ApplicationContext.class),
                new NoOpTransactionManager(), "researcher@example.com");

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).update(sql.capture(), params.capture());
        boolean testInsertFound = false;
        int sentenceInsertCount = 0;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            String statement = sql.getAllValues().get(i);
            if (statement.contains("insert into sentence_tests")) {
                testInsertFound = true;
                assertThat(params.getAllValues().get(i)).contains("PRUEBA-DEMO", researcherId);
            }
            if (statement.contains("insert into test_sentences")) {
                sentenceInsertCount++;
            }
        }
        assertThat(testInsertFound).isTrue();
        assertThat(sentenceInsertCount).isEqualTo(6);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("Demo test seeded").contains("PRUEBA-DEMO")
                        .contains("6"));
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
