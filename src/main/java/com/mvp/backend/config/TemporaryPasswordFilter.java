package com.mvp.backend.config;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mvp.backend.shared.exception.ApiError;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Aplica must_change_password en el servidor: un docente con contrasena temporal solo puede
 * cambiarla; cualquier otra ruta responde 403 "Password change required". Corre despues de la
 * autorizacion, asi que solo consulta al docente (una lectura por peticion) cuando el JWT ya
 * paso las reglas de rol; sin autenticacion o con otro rol no toca la base de datos.
 */
public class TemporaryPasswordFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Password change required";
    private static final String TEACHER_AUTHORITY = "ROLE_TEACHER";
    private static final String AUTH_PREFIX = "/api/v1/auth/";

    private final TeacherRepository teacherRepository;
    private final ObjectMapper objectMapper;

    public TemporaryPasswordFilter(TeacherRepository teacherRepository, ObjectMapper objectMapper) {
        this.teacherRepository = teacherRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isTeacher(authentication) || isAuthRoute(request) || !mustChangePassword(authentication)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                MESSAGE,
                request.getRequestURI(),
                Map.of());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static boolean isTeacher(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> TEACHER_AUTHORITY.equals(authority.getAuthority()));
    }

    /**
     * Las rutas de autenticacion no se bloquean: el cambio de contrasena es la salida del estado
     * temporal y un login con un bearer viejo debe seguir emitiendo un token nuevo.
     */
    private static boolean isAuthRoute(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && path(request).startsWith(AUTH_PREFIX);
    }

    /** Ruta sin el context-path del servidor, para que la exencion no dependa de la configuracion. */
    private static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
    }

    /** Un subject que no es UUID o sin fila de docente no bloquea: el resto de la cadena responde como antes. */
    private boolean mustChangePassword(Authentication authentication) {
        UUID teacherId;
        try {
            teacherId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalidSubject) {
            return false;
        }
        return teacherRepository.findById(teacherId).map(Teacher::isMustChangePassword).orElse(false);
    }
}
