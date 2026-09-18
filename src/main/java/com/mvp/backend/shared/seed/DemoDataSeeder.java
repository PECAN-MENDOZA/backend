package com.mvp.backend.shared.seed;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.shared.security.PersonalDataCipher;

@Component
@Order(100)
@ConditionalOnProperty(prefix = "app.demo-seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_TEST_CODE = "PRUEBA-DEMO";

    private final DemoSeedProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PersonalDataCipher personalDataCipher;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final TransactionTemplate transactionTemplate;
    private final String researcherEmail;

    public DemoDataSeeder(
            DemoSeedProperties properties,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            PersonalDataCipher personalDataCipher,
            ObjectMapper objectMapper,
            ApplicationContext applicationContext,
            PlatformTransactionManager transactionManager,
            @Value("${app.researcher.email:}") String researcherEmail) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.personalDataCipher = personalDataCipher;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.researcherEmail = researcherEmail;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.enabled()) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            try {
                if (properties.reset()) {
                    resetDatabase();
                } else if (countRows("teacher_users") > 0 || countRows("student_users") > 0) {
                    log.info("Demo seed skipped because data already exists. Use app.demo-seed.reset=true to rebuild it.");
                    return;
                }

                seedDemoData();
            } catch (Exception exception) {
                status.setRollbackOnly();
                throw new IllegalStateException("Demo seed failed", exception);
            }
        });
        shutdownIfRequested();
    }

    private void seedDemoData() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant teacherCreatedAt = now.minus(21, ChronoUnit.DAYS);

        UUID teacherId = UUID.randomUUID();
        String teacherUsername = "sofia_docente";
        String teacherEmail = "sofia.garcia@colegio.edu.pe";
        String teacherPhone = "+51987654321";
        String institution = "Colegio San Martin";

        insertTeacher(teacherId, teacherUsername, teacherEmail, teacherPhone, institution, teacherCreatedAt);
        UUID classroomId = UUID.randomUUID();
        insertClassroom(classroomId, teacherId, "3.º B", teacherCreatedAt.plus(30, ChronoUnit.MINUTES));

        List<StudentSeed> students = buildStudents();
        for (int index = 0; index < students.size(); index++) {
            StudentSeed student = students.get(index);
            Instant createdAt = teacherCreatedAt.plus(index + 1, ChronoUnit.HOURS);
            insertStudent(student.id(), student.username(), institution, createdAt);
            insertTeacherStudentLink(teacherId, classroomId, student, createdAt.plus(1, ChronoUnit.HOURS));
        }

        List<SessionSeed> sessions = buildSessions(students, now);
        for (SessionSeed session : sessions) {
            insertSession(session);
            // Las palabras corregidas solo existen cuando el alumno acepta la sugerencia.
            if (session.acceptedCorrection()) {
                for (WordCorrectionSeed wordCorrection : session.wordCorrections()) {
                    insertWordCorrection(wordCorrection, session.id());
                }
            }
        }

        seedDemoTest(now);

        log.info("Demo seed completed: 1 teacher, {} students, {} correction sessions.",
                students.size(),
                sessions.size());
        // Nunca se registran contrasenas en claro: viven en app.demo-seed.* (solo entornos de demo).
        log.info("Teacher login: {} (password: app.demo-seed.teacher-password)", teacherEmail);
        log.info("Student login example: {} (password: app.demo-seed.student-password)", students.getFirst().username());
    }

    /**
     * Siembra una prueba de oraciones DRAFT propiedad del investigador semilla, si existe
     * (lo crea {@code ResearcherAccountSeeder}, que corre antes por su {@code @Order}). Sin
     * investigador configurado o sembrado, se omite: la prueba nunca se asigna a nadie ni
     * necesita un investigador para el resto de la semilla.
     */
    private void seedDemoTest(Instant now) {
        if (researcherEmail == null || researcherEmail.isBlank()) {
            log.info("Demo test seed skipped: app.researcher.email is not set.");
            return;
        }
        List<UUID> researchers = jdbcTemplate.query(
                "select id from researcher_users where email = ?",
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                researcherEmail);
        if (researchers.isEmpty()) {
            log.info("Demo test seed skipped: no researcher account found for {}.", researcherEmail);
            return;
        }
        // Sin reset la prueba puede sobrevivir a una semilla anterior (uk de codigo): no se duplica.
        if (countRows("sentence_tests", "code = ?", DEMO_TEST_CODE) > 0) {
            log.info("Demo test seed skipped: {} already exists.", DEMO_TEST_CODE);
            return;
        }
        UUID testId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into sentence_tests (id, code, title, status, notes, created_by, created_at)
                values (?, ?, ?, 'DRAFT', ?, ?, ?)
                """,
                testId,
                DEMO_TEST_CODE,
                "Prueba de demostracion",
                "Prueba de ejemplo para explorar el flujo de investigador sin afectar datos reales.",
                researchers.getFirst(),
                Timestamp.from(now));
        List<DemoSentence> sentences = demoTestSentences();
        for (int index = 0; index < sentences.size(); index++) {
            insertTestSentence(testId, index + 1, sentences.get(index));
        }
        log.info("Demo test seeded: {} ({} sentences).", DEMO_TEST_CODE, sentences.size());
    }

    private void insertTestSentence(UUID testId, int position, DemoSentence sentence) {
        jdbcTemplate.update(
                """
                insert into test_sentences (id, test_id, position, kind, reference_text, assistance)
                values (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                testId,
                position,
                sentence.kind(),
                sentence.referenceText(),
                sentence.assistance());
    }

    private static List<DemoSentence> demoTestSentences() {
        return List.of(
                new DemoSentence("DICTATED", "El perro corre por el parque.", "ASSISTED"),
                new DemoSentence("DICTATED", "Mi abuela cocina arroz con pollo.", "ASSISTED"),
                new DemoSentence("DICTATED", "Los ninos juegan futbol en el patio.", "ASSISTED"),
                new DemoSentence("DICTATED", "El sol brilla sobre las montanas.", "UNASSISTED"),
                new DemoSentence("DICTATED", "Mi hermano lee un libro de aventuras.", "UNASSISTED"),
                new DemoSentence("FREE", "Escribe una oracion sobre tu mascota.", "UNASSISTED"));
    }

    private void shutdownIfRequested() {
        if (properties.exitAfterRun()) {
            log.info("Demo seed finished. Stopping application because app.demo-seed.exit-after-run=true.");
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }

    private void insertTeacher(UUID id, String username, String email, String phone, String institution, Instant createdAt) {
        jdbcTemplate.update(
                """
                insert into teacher_users
                    (id, username, email, phone, institution, password_hash, created_by, must_change_password, created_at)
                values (?, ?, ?, ?, ?, ?, null, false, ?)
                """,
                id,
                username,
                email,
                phone,
                institution,
                passwordEncoder.encode(properties.teacherPassword()),
                Timestamp.from(createdAt));
    }

    private void insertStudent(UUID id, String username, String institution, Instant createdAt) {
        jdbcTemplate.update(
                """
                insert into student_users (id, username, institution, password_hash, created_at)
                values (?, ?, ?, ?, ?)
                """,
                id,
                username,
                institution,
                passwordEncoder.encode(properties.studentPassword()),
                Timestamp.from(createdAt));
    }

    private void insertClassroom(UUID id, UUID teacherId, String name, Instant createdAt) {
        jdbcTemplate.update(
                "insert into classrooms (id, teacher_id, name, created_at) values (?, ?, ?, ?)",
                id,
                teacherId,
                name,
                Timestamp.from(createdAt));
    }

    private void insertTeacherStudentLink(UUID teacherId, UUID classroomId, StudentSeed student, Instant createdAt) {
        jdbcTemplate.update(
                """
                insert into teacher_student_links
                    (id, teacher_id, student_id, classroom_id, encrypted_student_real_name, notes, created_at, deleted_at, last_access_at)
                values (?, ?, ?, ?, ?, ?, ?, null, ?)
                """,
                UUID.randomUUID(),
                teacherId,
                student.id(),
                classroomId,
                personalDataCipher.encrypt(student.realName()),
                "Seguimiento de pruebas de escritura y aceptacion de sugerencias.",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(14, ChronoUnit.DAYS)));
    }

    private void insertSession(SessionSeed session) {
        jdbcTemplate.update(
                """
                insert into correction_sessions
                    (id, student_id, original_text, corrected_text, corrections_count, suggestions_json,
                     selected_suggestion, accepted_correction, response_time_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.id(),
                session.studentId(),
                session.originalText(),
                session.correctedText(),
                session.acceptedCorrection() ? session.wordCorrections().size() : 0,
                session.suggestionsJson(),
                session.acceptedCorrection() ? session.correctedText() : session.originalText(),
                session.acceptedCorrection(),
                session.responseTimeMs(),
                Timestamp.from(session.createdAt()));
    }

    private void insertWordCorrection(WordCorrectionSeed wordCorrection, UUID sessionId) {
        jdbcTemplate.update(
                """
                insert into word_corrections
                    (id, correction_session_id, original_word, corrected_word, start_position, end_position)
                values (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                sessionId,
                wordCorrection.originalWord(),
                wordCorrection.correctedWord(),
                wordCorrection.startPosition(),
                wordCorrection.endPosition());
    }

    private List<StudentSeed> buildStudents() {
        String[][] studentData = {
                {"student_001", "Mateo Rojas"},
                {"student_002", "Valeria Quispe"},
                {"student_003", "Thiago Huaman"},
                {"student_004", "Luciana Flores"},
                {"student_005", "Sebastian Paredes"},
                {"student_006", "Camila Vargas"},
                {"student_007", "Diego Salazar"},
                {"student_008", "Mia Torres"},
                {"student_009", "Adrian Mendoza"},
                {"student_010", "Renata Diaz"},
                {"student_011", "Joaquin Castro"},
                {"student_012", "Alessia Navarro"},
                {"student_013", "Gael Rivera"},
                {"student_014", "Antonella Lozano"},
                {"student_015", "Nicolas Herrera"}
        };

        int requested = properties.studentCount();
        int limit = requested <= 0 ? studentData.length : Math.min(requested, studentData.length);

        List<StudentSeed> students = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            String[] row = studentData[index];
            students.add(new StudentSeed(UUID.randomUUID(), row[0], row[1]));
        }
        return students;
    }

    /** Catorce dias de sesiones que terminan antes de {@code now}: ninguna queda en el futuro. */
    private List<SessionSeed> buildSessions(List<StudentSeed> students, Instant now) throws Exception {
        List<CorrectionTemplate> templates = correctionTemplates();
        Instant start = now.minus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<SessionSeed> sessions = new ArrayList<>();

        for (int day = 0; day < 14; day++) {
            Instant dayBase = start.plus(day, ChronoUnit.DAYS);
            for (int studentIndex = 0; studentIndex < students.size(); studentIndex++) {
                StudentSeed student = students.get(studentIndex);
                CorrectionTemplate template = templates.get((day + studentIndex) % templates.size());
                Instant createdAt = dayBase.plus(8 + (studentIndex % 5), ChronoUnit.HOURS)
                        .plus(studentIndex * 7L, ChronoUnit.MINUTES);
                if (createdAt.isAfter(now)) {
                    createdAt = now;
                }
                boolean accepted = ((day + studentIndex) % 5) != 0;
                long responseTime = 210 + (studentIndex * 9L) + (day * 6L);
                String suggestionsJson = objectMapper.writeValueAsString(List.of(
                        template.correctedText(),
                        template.alternateSuggestion()));
                sessions.add(new SessionSeed(
                        UUID.randomUUID(),
                        student.id(),
                        template.originalText(),
                        template.correctedText(),
                        suggestionsJson,
                        accepted,
                        responseTime,
                        createdAt,
                        template.wordCorrections()));
            }
        }
        return sessions;
    }

    private List<CorrectionTemplate> correctionTemplates() {
        return List.of(
                template(
                        "los ninos juegan en el patio",
                        "los ninos juegan en el patio",
                        "los ninos estan en el patio",
                        List.of(
                                word("ninos", "ninos", 4, 9))),
                template(
                        "mi mama me dijo que baya a clase",
                        "mi mama me dijo que vaya a clase",
                        "mi mama me dijo que vaya a estudiar",
                        List.of(
                                word("baya", "vaya", 19, 23))),
                template(
                        "ayer escrivi una historia sobre mi perro",
                        "ayer escribi una historia sobre mi perro",
                        "ayer escribi una historia de mi perro",
                        List.of(
                                word("escrivi", "escribi", 5, 13))),
                template(
                        "el tubo de ensayo estaba en la mesa",
                        "el tubo de ensayo estaba en la mesa",
                        "el tuvo de ensayo estaba en la mesa",
                        List.of(
                                word("tubo", "tuvo", 3, 7))),
                template(
                        "fuimos al zoolojico con la profesora",
                        "fuimos al zoologico con la profesora",
                        "fuimos al zoologico con la maestra",
                        List.of(
                                word("zoolojico", "zoologico", 10, 20))),
                template(
                        "yo bi una mariposa azul en el jardin",
                        "yo vi una mariposa azul en el jardin",
                        "yo vi una mariposa azul en casa",
                        List.of(
                                word("bi", "vi", 3, 5))),
                template(
                        "la ora del recreo fue muy corta",
                        "la hora del recreo fue muy corta",
                        "la hora del recreo fue corta",
                        List.of(
                                word("ora", "hora", 3, 6))),
                template(
                        "mi ermano trajo un cuaderno nuevo",
                        "mi hermano trajo un cuaderno nuevo",
                        "mi hermano llevo un cuaderno nuevo",
                        List.of(
                                word("ermano", "hermano", 3, 9))),
                template(
                        "el sol estaba triste y la ventana comio pan",
                        "el sol estaba brillante y yo comi pan junto a la ventana",
                        "el sol estaba brillante y yo comi pan",
                        List.of(
                                word("triste", "brillante", 14, 20),
                                word("comio", "comi", 34, 39))),
                template(
                        "mi lapis se callo en el suelo",
                        "mi lapiz se cayo en el suelo",
                        "mi lapiz se cayo al suelo",
                        List.of(
                                word("lapis", "lapiz", 3, 8),
                                word("callo", "cayo", 12, 17))));
    }

    private CorrectionTemplate template(
            String originalText,
            String correctedText,
            String alternateSuggestion,
            List<WordCorrectionSeed> wordCorrections) {
        return new CorrectionTemplate(originalText, correctedText, alternateSuggestion, wordCorrections);
    }

    private WordCorrectionSeed word(
            String originalWord,
            String correctedWord,
            int startPosition,
            int endPosition) {
        return new WordCorrectionSeed(originalWord, correctedWord, startPosition, endPosition);
    }

    private void resetDatabase() {
        jdbcTemplate.update("delete from word_corrections");
        // correction_sessions referencia test_responses: se borra antes que las tablas de pruebas.
        jdbcTemplate.update("delete from correction_sessions");
        jdbcTemplate.update("delete from test_responses");
        jdbcTemplate.update("delete from test_attempts");
        jdbcTemplate.update("delete from test_assignments");
        jdbcTemplate.update("delete from test_sentences");
        jdbcTemplate.update("delete from sentence_tests");
        jdbcTemplate.update("delete from teacher_student_links");
        jdbcTemplate.update("delete from classrooms");
        jdbcTemplate.update("delete from student_users");
        jdbcTemplate.update("delete from teacher_users");
    }

    private long countRows(String tableName) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
        return value == null ? 0 : value;
    }

    private long countRows(String tableName, String where, Object... args) {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from " + tableName + " where " + where, Long.class, args);
        return value == null ? 0 : value;
    }

    private record StudentSeed(UUID id, String username, String realName) {
    }

    private record WordCorrectionSeed(
            String originalWord,
            String correctedWord,
            int startPosition,
            int endPosition) {
    }

    private record CorrectionTemplate(
            String originalText,
            String correctedText,
            String alternateSuggestion,
            List<WordCorrectionSeed> wordCorrections) {
    }

    private record DemoSentence(String kind, String referenceText, String assistance) {
    }

    private record SessionSeed(
            UUID id,
            UUID studentId,
            String originalText,
            String correctedText,
            String suggestionsJson,
            boolean acceptedCorrection,
            long responseTimeMs,
            Instant createdAt,
            List<WordCorrectionSeed> wordCorrections) {
    }
}
