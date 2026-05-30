package com.mvp.backend.teacher.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.application.dto.CreateStudentLinkRequest;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@Service
public class TeacherStudentService {

    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final PersonalDataCipher personalDataCipher;

    public TeacherStudentService(
            TeacherRepository teacherRepository,
            StudentRepository studentRepository,
            TeacherStudentLinkRepository linkRepository,
            PersonalDataCipher personalDataCipher) {
        this.teacherRepository = teacherRepository;
        this.studentRepository = studentRepository;
        this.linkRepository = linkRepository;
        this.personalDataCipher = personalDataCipher;
    }

    @Transactional
    public StudentLinkResponse linkStudent(UUID teacherId, CreateStudentLinkRequest request) {
        if (linkRepository.existsByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, request.studentId())) {
            throw new BusinessException("Student is already linked to this teacher");
        }
        var teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NotFoundException("Teacher not found"));
        var student = studentRepository.findById(request.studentId())
                .orElseThrow(() -> new NotFoundException("Student not found"));
        var link = new TeacherStudentLink(
                teacher,
                student,
                personalDataCipher.encrypt(request.studentRealName()),
                request.notes());
        return toResponse(linkRepository.save(link));
    }

    @Transactional
    public List<StudentLinkResponse> listStudents(UUID teacherId) {
        return linkRepository.findByTeacherIdAndDeletedAtIsNullOrderByCreatedAtDesc(teacherId).stream()
                .peek(TeacherStudentLink::registerAccess)
                .map(this::toResponse)
                .toList();
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
