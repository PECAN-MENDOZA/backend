package com.mvp.backend.config;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mvp.backend.shared.exception.ApiError;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Corta en el servidor la sesion de un alumno que ya no debe entrar: responde 401 si el alumno no
 * tiene ningun vinculo activo (dado de baja) o si su token se emitio antes del ultimo reinicio de PIN
 * (credentials_changed_at, comparado en segundos). El teclado trata el 401 como "sesion terminada".
 * Igual que {@link TemporaryPasswordFilter}, corre despues de la autorizacion: solo los JWT de alumno
 * ya autorizados pagan las lecturas. Las rutas /api/v1/auth/** no se bloquean.
 */
public class StudentAccessFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Student session is no longer valid";
    private static final String STUDENT_AUTHORITY = "ROLE_STUDENT";
    private static final String AUTH_PREFIX = "/api/v1/auth/";

    private final StudentRepository studentRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final ObjectMapper objectMapper;

    public StudentAccessFilter(
            StudentRepository studentRepository,
            TeacherStudentLinkRepository linkRepository,
            ObjectMapper objectMapper) {
        this.studentRepository = studentRepository;
        this.linkRepository = linkRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isStudent(authentication) || path(request).startsWith(AUTH_PREFIX) || hasAccess(authentication)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                MESSAGE,
                request.getRequestURI(),
                Map.of());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static boolean isStudent(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> STUDENT_AUTHORITY.equals(authority.getAuthority()));
    }

    /** Ruta sin el context-path del servidor, para que la exencion no dependa de la configuracion. */
    private static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
    }

    /**
     * Un subject que no es UUID o sin fila de alumno no bloquea: el resto de la cadena responde como
     * antes (404 "Student not found"). Con fila: hace falta un vinculo activo y un token no anterior al
     * ultimo reinicio de PIN.
     */
    private boolean hasAccess(Authentication authentication) {
        UUID studentId;
        try {
            studentId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalidSubject) {
            return true;
        }
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return true;
        }
        if (student.getCredentialsChangedAt() != null
                && issuedBefore(authentication, student.getCredentialsChangedAt())) {
            return false;
        }
        return linkRepository.existsByStudentIdAndDeletedAtIsNull(studentId);
    }

    /** iat del JWT anterior al cambio, en segundos (el iat del JWT no tiene fraccion). Sin iat: se rechaza. */
    private static boolean issuedBefore(Authentication authentication, Instant changedAt) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt) || jwt.getIssuedAt() == null) {
            return true;
        }
        return jwt.getIssuedAt().getEpochSecond() < changedAt.getEpochSecond();
    }
}
