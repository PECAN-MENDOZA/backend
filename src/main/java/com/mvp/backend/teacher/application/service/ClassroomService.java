package com.mvp.backend.teacher.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.application.dto.ClassroomResponse;
import com.mvp.backend.teacher.application.dto.CreateClassroomRequest;
import com.mvp.backend.teacher.application.dto.CreateClassroomStudentsRequest;
import com.mvp.backend.teacher.application.dto.CreatedStudentAccountResponse;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.application.dto.UpdateClassroomRequest;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/** Salones del docente y alta de alumnos dentro de un salon (unico camino para crear alumnos). */
@Service
public class ClassroomService {

    private final TeacherRepository teacherRepository;
    private final ClassroomRepository classroomRepository;
    private final StudentRepository studentRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final StudentCredentialGenerator credentials;
    private final PersonalDataCipher personalDataCipher;
    private final PasswordEncoder passwordEncoder;

    public ClassroomService(
            TeacherRepository teacherRepository,
            ClassroomRepository classroomRepository,
            StudentRepository studentRepository,
            TeacherStudentLinkRepository linkRepository,
            StudentCredentialGenerator credentials,
            PersonalDataCipher personalDataCipher,
            PasswordEncoder passwordEncoder) {
        this.teacherRepository = teacherRepository;
        this.classroomRepository = classroomRepository;
        this.studentRepository = studentRepository;
        this.linkRepository = linkRepository;
        this.credentials = credentials;
        this.personalDataCipher = personalDataCipher;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ClassroomResponse createClassroom(UUID teacherId, CreateClassroomRequest request) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NotFoundException("Teacher not found"));
        String name = request.name().trim();
        if (classroomRepository.existsByTeacherIdAndName(teacherId, name)) {
            throw new ConflictException("Classroom name is already in use");
        }
        Classroom saved = classroomRepository.save(new Classroom(teacher, name));
        return toResponse(saved, 0);
    }

    @Transactional(readOnly = true)
    public List<ClassroomResponse> listClassrooms(UUID teacherId) {
        return classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacherId).stream()
                .map(c -> toResponse(c, linkRepository.countByClassroomIdAndDeletedAtIsNull(c.getId())))
                .toList();
    }

    @Transactional
    public ClassroomResponse updateClassroom(UUID teacherId, UUID classroomId, UpdateClassroomRequest request) {
        Classroom classroom = ownedClassroom(teacherId, classroomId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equals(classroom.getName()) && classroomRepository.existsByTeacherIdAndName(teacherId, name)) {
                throw new ConflictException("Classroom name is already in use");
            }
            classroom.rename(name);
        }
        if (request.archived() != null) {
            if (request.archived()) {
                classroom.archive();
            } else {
                classroom.unarchive();
            }
        }
        return toResponse(classroom, linkRepository.countByClassroomIdAndDeletedAtIsNull(classroomId));
    }

    @Transactional
    public List<StudentLinkResponse> listStudents(UUID teacherId, UUID classroomId) {
        ownedClassroom(teacherId, classroomId);
        return linkRepository.findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(classroomId).stream()
                .peek(TeacherStudentLink::registerAccess)
                .map(link -> TeacherStudentService.toResponse(link, personalDataCipher))
                .toList();
    }

    @Transactional
    public List<CreatedStudentAccountResponse> createStudents(
            UUID teacherId, UUID classroomId, CreateClassroomStudentsRequest request) {
        Classroom classroom = ownedClassroom(teacherId, classroomId);
        if (classroom.isArchived()) {
            throw new BusinessException("Cannot add students to an archived classroom");
        }
        boolean named = request.studentRealName() != null && !request.studentRealName().isBlank();
        if (!named && request.count() == null) {
            throw new BusinessException("Provide studentRealName or count");
        }
        int count = named ? 1 : request.count();
        String realName = named ? request.studentRealName().trim() : "";
        List<CreatedStudentAccountResponse> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            created.add(createStudent(classroom, realName, named ? request.notes() : null));
        }
        return created;
    }

    private CreatedStudentAccountResponse createStudent(Classroom classroom, String realName, String notes) {
        Teacher teacher = classroom.getTeacher();
        String username = credentials.newAlias();
        String pin = credentials.newPin();
        Student student = studentRepository.save(
                new Student(username, teacher.getInstitution(), passwordEncoder.encode(pin)));
        TeacherStudentLink link = linkRepository.save(new TeacherStudentLink(
                teacher, student, classroom, personalDataCipher.encrypt(realName), notes));
        return new CreatedStudentAccountResponse(
                link.getId(), classroom.getId(), student.getId(), student.getUsername(), pin,
                realName, student.getInstitution(), link.getNotes(), link.getCreatedAt());
    }

    Classroom ownedClassroom(UUID teacherId, UUID classroomId) {
        return classroomRepository.findByIdAndTeacherId(classroomId, teacherId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this classroom"));
    }

    private static ClassroomResponse toResponse(Classroom classroom, long studentCount) {
        return new ClassroomResponse(
                classroom.getId(), classroom.getName(), studentCount, classroom.getCreatedAt(), classroom.getArchivedAt());
    }
}
