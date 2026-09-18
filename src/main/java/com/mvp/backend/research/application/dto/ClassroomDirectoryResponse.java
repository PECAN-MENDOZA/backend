package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Directorio para asignar pruebas: solo usernames, nunca nombres reales. */
public record ClassroomDirectoryResponse(
        UUID id,
        String name,
        UUID teacherId,
        String teacherUsername,
        boolean archived,
        List<StudentEntry> students) {

    /** lastActivityAt: ultima sesion de correccion del alumno (null si nunca escribio). */
    public record StudentEntry(UUID studentId, String username, Instant lastActivityAt) {
    }
}
