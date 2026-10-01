package com.mvp.backend.teacher.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * O bien un alumno con nombre, o bien {@code count} cuentas sin nombre (1-40).
 *
 * <p>Opcional, para agilizar una sesión de pruebas en aula: {@code usernamePrefix} genera
 * usuarios en secuencia ({@code alumno_01}, {@code alumno_02}... saltando los que ya existan)
 * en vez de alias aleatorios, y {@code pin} fija el mismo PIN de 4 dígitos para todas las
 * cuentas creadas en la petición. Sin ellos, alias y PIN aleatorios como siempre.
 */
public record CreateClassroomStudentsRequest(
        @Size(max = 160) String studentRealName,
        @Size(max = 2000) String notes,
        @Min(1) @Max(40) Integer count,
        @Pattern(regexp = "[a-z]{2,20}", message = "usernamePrefix: 2-20 letras minúsculas") String usernamePrefix,
        @Pattern(regexp = "\\d{4}", message = "pin: 4 dígitos") String pin) {

    public CreateClassroomStudentsRequest(String studentRealName, String notes, Integer count) {
        this(studentRealName, notes, count, null, null);
    }
}
