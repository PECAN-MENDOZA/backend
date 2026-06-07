# Diseño: Simplificación de la creación y el primer acceso de los alumnos

Fecha: 2026-06-06
Estado: aprobado (diseño), pendiente de implementación

## Problema

El flujo actual genera para cada alumno un alias pseudónimo largo
(`student_a1b2c3d4`) y una contraseña temporal de 12 caracteres aleatorios, y
**obliga** a cambiar la contraseña en el primer login (bloqueando las
correcciones con `403` hasta hacerlo). Para niños de 6–12 años que escriben sus
propias credenciales en su celular, esto es demasiado enrevesado y el flujo es
complicado.

## Objetivo

Credenciales memorables y fáciles de teclear, y un primer acceso sin fricción,
manteniendo intacta la **pseudonimia** (el alias nunca contiene el nombre real).

## Restricciones que se mantienen

- El alias **no** puede contener el nombre real (pilar de privacidad).
- El nombre real sigue cifrado (AES-GCM) y solo visible para el docente.
- No cambian: vínculo docente-alumno, KPIs, contrato con la IA, contraseñas con BCrypt.

## Decisiones (Q&A con el usuario)

1. El **niño** escribe sus credenciales la primera vez, en su propio celular.
2. **Alias** = palabra amigable + número: `tigre-07`.
3. **Contraseña** = **PIN de 4 dígitos**: `4821`.
4. Se **elimina** el cambio de contraseña obligatorio; el PIN es permanente.
5. El **profesor** puede **resetear** el PIN (el sistema genera uno nuevo).
6. Solo el profesor gestiona los PIN; se elimina el auto-cambio del alumno.

Decisión de seguridad consciente: PIN de 4 dígitos + sin cambio forzado es menos
robusto, pero aceptable para cuentas pseudónimas de bajo valor (el alumno solo
ve su propio historial y envía correcciones).

## Diseño

### Generación de credenciales

**Alias `palabra-NN`:**
- Palabra de una lista curada en español, apta para niños (animales, naturaleza),
  **sin acentos ni ñ** para teclear fácil (~60 palabras).
- Número de 2 dígitos (`01`–`99`).
- Único globalmente (constraint `username` existente). Si la combinación existe,
  se reintenta con otra; si el espacio se agota, se amplía el número a 3 dígitos.

**PIN de 4 dígitos:**
- Generado con `SecureRandom`. No requiere ser único entre alumnos (el alias es la
  clave de login). Se almacena con BCrypt.

### Creación de cuenta (profesor)

- `POST /api/v1/teachers/students/accounts` — request **sin cambios**
  (`studentRealName`, `notes`).
- La respuesta entrega `alias` y `pin` (se muestran una sola vez). Ya no incluye
  `passwordChangeRequired`.

### Primer acceso (alumno)

- `POST /api/v1/auth/students/login` con `alias` + `pin`.
- Desaparece `passwordChangeRequired`: sin pantalla de cambio, sin bloqueo `403`.
  El alumno inicia sesión y usa el teclado directamente.

### Reseteo de PIN (profesor) — nuevo endpoint

- `POST /api/v1/teachers/students/{studentId}/reset-pin` (rol `TEACHER`, solo
  alumnos vinculados al docente).
- Genera un PIN nuevo, lo guarda (BCrypt) y lo devuelve una sola vez:
  `{ studentId, username, pin }`.

### Eliminaciones

- Endpoint `PATCH /api/v1/students/me/password` y su DTO `ChangeStudentPasswordRequest`.
- Concepto `passwordChangeRequired` en todas sus apariciones:
  - Columna `password_change_required` en `student_users` (migración `V5` para borrarla).
  - Campo en la entidad `Student`.
  - Campo en `AuthResponse` y en `StudentResponse`.
  - Bloqueo en `CorrectionService` (la verificación de "listo"; se mantiene la
    carga del alumno para validar existencia, se quita el chequeo del flag).
  - Lógica asociada en `AuthService.loginStudent` y `StudentService`.

### Impacto colateral (a actualizar)

- **Migración `V5`**: `ALTER TABLE student_users DROP COLUMN password_change_required`.
- **DemoDataSeeder**: quitar la columna del insert de alumnos.
- **Tests**: `AuthServiceTests`, `StudentServiceTests`, `TeacherStudentServiceTests`
  (y cualquier referencia a `passwordChangeRequired`).
- **Docs**: `arquitectura-integracion.md` (secciones 5 y 6), guía del teclado
  (login, estados, eliminar cambio de contraseña), `README.md`.

## Fuera de alcance

- Cambios en la IA, KPIs, reportes o cifrado.
- Concepto de "aula/clase" (se evaluó para el alias y se descartó).
- Auto-gestión del PIN por el alumno.

## Componentes afectados (resumen)

| Componente | Cambio |
| --- | --- |
| `TeacherStudentService` | Generar `palabra-NN` + PIN; método de reseteo |
| `TeacherStudentController` | Nuevo endpoint `reset-pin` |
| `Student` (modelo) | Quitar `passwordChangeRequired` |
| `AuthService` / `AuthResponse` | Quitar `passwordChangeRequired` |
| `StudentService` / `StudentResponse` | Quitar auto-cambio y el flag |
| `CorrectionService` | Quitar el bloqueo por cambio pendiente |
| Migración `V5` | Borrar columna |
| Lista de palabras | Nuevo recurso/constante curada |
