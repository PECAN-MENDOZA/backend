package com.mvp.backend.teacher.application.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.application.dto.CreateLinkedStudentRequest;
import com.mvp.backend.teacher.application.dto.CreatedStudentAccountResponse;
import com.mvp.backend.teacher.application.dto.ResetStudentPinResponse;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@Service
public class TeacherStudentService {

    /**
     * Palabras amigables (en espanol, sin acentos ni enie) para los alias de alumno.
     * El alias resultante es del tipo "tigre-07": memorable y facil de teclear para
     * ninos de 6-12, y sin contener el nombre real (pseudonimia).
     */
    private static final List<String> ALIAS_WORDS = List.of(
            "tigre", "leon", "panda", "koala", "delfin", "ballena", "tortuga", "conejo",
            "zorro", "lobo", "gato", "perro", "caballo", "abeja", "mariposa", "buho",
            "aguila", "pinguino", "foca", "nutria", "ardilla", "erizo", "rana", "pez",
            "estrella", "cometa", "planeta", "luna", "sol", "nube", "rayo", "arcoiris",
            "rio", "lago", "montana", "bosque", "flor", "arbol", "hoja", "semilla",
            "manzana", "platano", "fresa", "uva", "limon", "cereza", "melon", "kiwi",
            "barco", "cohete", "tren", "globo", "faro", "puente", "castillo", "brujula");

    private static final int PIN_BOUND = 10000;

    private final TeacherRepository teacherRepository;
    private final ClassroomRepository classroomRepository;
    private final StudentRepository studentRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final PersonalDataCipher personalDataCipher;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public TeacherStudentService(
            TeacherRepository teacherRepository,
            ClassroomRepository classroomRepository,
            StudentRepository studentRepository,
            TeacherStudentLinkRepository linkRepository,
            PersonalDataCipher personalDataCipher,
            PasswordEncoder passwordEncoder) {
        this.teacherRepository = teacherRepository;
        this.classroomRepository = classroomRepository;
        this.studentRepository = studentRepository;
        this.linkRepository = linkRepository;
        this.personalDataCipher = personalDataCipher;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CreatedStudentAccountResponse createLinkedStudent(UUID teacherId, CreateLinkedStudentRequest request) {
        var teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NotFoundException("Teacher not found"));
        var classroom = classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacherId).stream()
                .filter(candidate -> !candidate.isArchived())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Teacher has no classroom"));

        String username = generateStudentAlias();
        String pin = generatePin();
        Student student = studentRepository.save(new Student(
                username,
                teacher.getInstitution(),
                passwordEncoder.encode(pin)));

        var link = new TeacherStudentLink(
                teacher,
                student,
                classroom,
                personalDataCipher.encrypt(request.studentRealName()),
                request.notes());
        TeacherStudentLink savedLink = linkRepository.save(link);
        return new CreatedStudentAccountResponse(
                savedLink.getId(),
                student.getId(),
                student.getUsername(),
                pin,
                request.studentRealName(),
                student.getInstitution(),
                savedLink.getNotes(),
                savedLink.getCreatedAt());
    }

    @Transactional
    public ResetStudentPinResponse resetPin(UUID teacherId, UUID studentId) {
        TeacherStudentLink link = linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this student"));
        Student student = link.getStudent();
        String pin = generatePin();
        student.changePassword(passwordEncoder.encode(pin));
        return new ResetStudentPinResponse(student.getId(), student.getUsername(), pin);
    }

    @Transactional
    public List<StudentLinkResponse> listStudents(UUID teacherId) {
        return linkRepository.findByTeacherIdAndDeletedAtIsNullOrderByCreatedAtDesc(teacherId).stream()
                .peek(TeacherStudentLink::registerAccess)
                .map(this::toResponse)
                .toList();
    }

    private String generateStudentAlias() {
        // Alias corto y memorable: palabra-NN (NN de 2 digitos). Reintenta si colisiona.
        for (int attempt = 0; attempt < 200; attempt++) {
            String alias = randomWord() + "-" + String.format(Locale.ROOT, "%02d", 1 + secureRandom.nextInt(99));
            if (!studentRepository.existsByUsername(alias)) {
                return alias;
            }
        }
        // Caso improbable (espacio de 2 digitos agotado): se amplia el numero para garantizar unicidad.
        String alias;
        do {
            alias = randomWord() + "-" + (1000 + secureRandom.nextInt(9000));
        } while (studentRepository.existsByUsername(alias));
        return alias;
    }

    private String randomWord() {
        return ALIAS_WORDS.get(secureRandom.nextInt(ALIAS_WORDS.size()));
    }

    private String generatePin() {
        // PIN de 4 digitos (0000-9999). No necesita ser unico: el alias es la clave de login.
        return String.format(Locale.ROOT, "%04d", secureRandom.nextInt(PIN_BOUND));
    }

    private StudentLinkResponse toResponse(TeacherStudentLink link) {
        return new StudentLinkResponse(
                link.getId(),
                link.getStudent().getId(),
                link.getStudent().getUsername(),
                personalDataCipher.decrypt(link.getEncryptedStudentRealName()),
                link.getNotes(),
                link.getCreatedAt(),
                link.getLastAccessAt());
    }
}
