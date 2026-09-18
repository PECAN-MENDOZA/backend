# API de investigación (backend)

Flujo de pruebas de oraciones con el backend solo: cuenta de investigador, docentes y salones, redacción y
asignación de una prueba, la sesión del alumno desde el teclado (Comenzar/Terminar por oración), la corrección
contextual ligada a la oración en curso, resultados con intervalos bootstrap deterministas y exportación CSV; al
final, el panel descriptivo del docente por periodo (sección 7).
Cubierto de punta a punta por `src/test/java/com/mvp/backend/sentencetest/presentation/ResearchTestApiTests.java`
y `StudentTestAuthorizationTests.java` (`.\mvnw.cmd -q "-Dtest=ResearchTestApiTests" test`), que recorren este
mismo orden sobre la cadena de seguridad real (JWT emitidos por los endpoints de login) y comprueban que ninguna
respuesta de investigación contiene el nombre real de un alumno.

## 1. Roles y límites

| Rol | Cómo se obtiene | Puede | No puede |
|---|---|---|---|
| `RESEARCHER` | Cuenta creada por variables de entorno (sección 2) + `POST /api/v1/auth/staff/login` | `/api/v1/research/**` | `/api/v1/corrections/**`, `/api/v1/students/**`, `/api/v1/teachers/**`, `/api/v1/kpis/**`, `/api/v1/reports/**`, `/api/v1/tests/**`, `/api/v1/attempts/**` (403) |
| `TEACHER` | Alta por un investigador + `POST /api/v1/auth/teachers/login`; `staff/login` también acepta docentes | `/api/v1/teachers/**`, `/api/v1/kpis/**`, `/api/v1/reports/**` | `/api/v1/research/**`, `/api/v1/students/**`, `/api/v1/corrections/**`, `/api/v1/tests/**`, `/api/v1/attempts/**` (403) |
| `STUDENT` | `POST /api/v1/auth/students/login` con alias + PIN | `/api/v1/students/**`, `/api/v1/tests/**`, `/api/v1/attempts/**`, `/api/v1/corrections/**` | `/api/v1/research/**`, `/api/v1/teachers/**`, `/api/v1/kpis/**`, `/api/v1/reports/**` (403) |

Estas reglas por prefijo viven en `SecurityConfig` además del `@PreAuthorize` de cada controlador: un rol ajeno
recibe 403 incluso en una ruta inexistente bajo esos prefijos.

Sin token: 401. Un docente con contraseña temporal (`mustChangePassword = true` en la respuesta de login, es decir,
recién creado o con contraseña restablecida por el investigador) recibe `403 {"message":"Password change required"}`
en **toda** ruta hasta que llame a `POST /api/v1/auth/teachers/change-password`; ese es el único endpoint que se le
permite mientras tanto (el filtro se evalúa después de las reglas de rol, así que un 401/403 de rol prevalece). Del lado del investigador, un intento de otra prueba se reporta como `404 "Attempt not found"`
igual que uno inexistente (no distingue "ajeno" de "inexistente", para no filtrar su existencia). Del lado del
alumno, un intento o una respuesta de otro alumno responde `403` (existe, pero no es suyo) mientras que uno
inexistente responde `404`. El investigador nunca ve el nombre real de un alumno: solo su `username` pseudónimo.

## 2. Cuenta de investigador (bootstrap)

No existe registro público. Al arrancar, `ResearcherAccountSeeder` crea la cuenta si ambas variables están
definidas (idempotente; falla el arranque si el correo ya pertenece a un docente):

```properties
RESEARCHER_EMAIL=authors@tesis.local
```

`RESEARCHER_PASSWORD` se entrega **únicamente por el entorno del proceso** (variable de entorno, `.env` local no
versionado o secreto del despliegue). Su valor no se escribe en esta documentación ni en ningún archivo del
repositorio. Internamente se mapean a `app.researcher.email` / `app.researcher.password`; la contraseña se guarda
con BCrypt. `ResearcherAccountSeeder` corre antes que `DemoDataSeeder` (`@Order(50)` contra `@Order(100)`), así
que si ambas semillas están activas el investigador ya existe cuando se siembra la prueba demo (sección 3.5).

```http
POST /api/v1/auth/staff/login
{"email":"authors@tesis.local","password":"<RESEARCHER_PASSWORD>"}
→ 200 {"userId":"…","token":"<JWT>","expiresAt":"…","role":"RESEARCHER"}
```

Las llamadas del investigador llevan `Authorization: Bearer <JWT>`.

## 2.1 Docentes y salones

El registro público de docentes no existe. Un investigador crea la cuenta, entrega la contraseña temporal una
sola vez y el docente debe cambiarla. Los alumnos se crean siempre dentro de un salón.

| Método | Ruta | Rol | Cuerpo | Respuesta |
|---|---|---|---|---|
| `POST` | `/api/v1/research/teachers` | `RESEARCHER` | `{fullName, email, institution}` | `201 CreatedTeacherResponse{id, username, fullName, email, institution, temporaryPassword}` (la contraseña temporal solo se entrega aquí) |
| `GET` | `/api/v1/research/teachers` | `RESEARCHER` | — | `200 List<TeacherSummaryResponse{id, username, fullName, email, institution, createdAt, mustChangePassword, classroomCount, studentCount}>` |
| `POST` | `/api/v1/research/teachers/{teacherId}/reset-password` | `RESEARCHER` | — | `200 TemporaryPasswordResponse` |
| `GET` | `/api/v1/research/classrooms` | `RESEARCHER` | — | `200 List<ClassroomDirectoryResponse{id, name, teacherId, teacherUsername, archived, students[{studentId, username, lastActivityAt}]}>` sin nombres reales; `lastActivityAt` = fecha de la última sesión de corrección del alumno (`null` si nunca escribió) |
| `POST` | `/api/v1/auth/teachers/change-password` | `TEACHER` | `{currentPassword, newPassword}` | `204` |
| `POST` | `/api/v1/teachers/classrooms` | `TEACHER` | `{name}` | `201 ClassroomResponse`; `409 "Classroom name is already in use"` solo si otro salón **activo** del docente lleva ese nombre (un archivado no bloquea el nombre) |
| `GET` | `/api/v1/teachers/classrooms` | `TEACHER` | — | `200 List<ClassroomResponse>` |
| `PATCH` | `/api/v1/teachers/classrooms/{classroomId}` | `TEACHER` | `{name?, archived?}` | `200 ClassroomResponse`; `409` si el nombre nuevo (o el del salón al desarchivarlo) choca con uno activo |
| `GET` | `/api/v1/teachers/classrooms/{classroomId}/students` | `TEACHER` | — | `200 List<StudentLinkResponse>` |
| `POST` | `/api/v1/teachers/classrooms/{classroomId}/students` | `TEACHER` | `{studentRealName?, notes?, count?}` | `201 List<CreatedStudentAccountResponse>` |
| `GET` | `/api/v1/teachers/students` | `TEACHER` | — | `200 List<StudentLinkResponse>` |
| `PATCH` | `/api/v1/teachers/students/{studentId}` | `TEACHER` | `{studentRealName, notes}` | `200 StudentLinkResponse` |
| `PATCH` | `/api/v1/teachers/students/{studentId}/classroom` | `TEACHER` | `{classroomId}` | `200 StudentLinkResponse` |
| `POST` | `/api/v1/teachers/students/{studentId}/reset-pin` | `TEACHER` | — | `200 ResetStudentPinResponse` |
| `POST` | `/api/v1/teachers/students/{studentId}/deactivate` | `TEACHER` | — | `204` |

## 3. Pruebas de oraciones (investigador)

Una prueba (`sentence_tests`) nace en `DRAFT`, se redacta con sus oraciones, se activa, se asigna a alumnos o
salones y, cuando ya no debe recibir más intentos, se cierra. `SentenceInput.kind` es `DICTATED` (el alumno
transcribe `referenceText`) o `FREE` (escribe libremente sobre un enunciado); `assistance` es `ASSISTED`
(corrección contextual disponible) o `UNASSISTED` (bloqueada). Ambos se fijan a mano por oración: el diseño
nunca es aleatorio (`designManual` en los resultados es siempre `true`).

```http
POST /api/v1/research/tests
{"code":"PRUEBA-01","title":"Prueba piloto","notes":"opcional, hasta 2000 caracteres"}
→ 201 TestSummaryResponse   (code: ^[A-Z0-9-]{3,40}$, único; 400 si no matchea; 409 si ya existe)

GET  /api/v1/research/tests                        → 200 List<TestSummaryResponse> (más reciente primero)
GET  /api/v1/research/tests/{testId}                → 200 TestDetailResponse (oraciones en orden y conteos)

PUT  /api/v1/research/tests/{testId}
{"title":"…","notes":"…","sentences":[{"kind":"DICTATED","referenceText":"…","assistance":"ASSISTED"}, …]}
→ 200 TestDetailResponse   (reemplaza título, notas y TODAS las oraciones; position = índice + 1;
                             1 a 60 oraciones; 409 "Test is not editable once activated" fuera de DRAFT)

POST /api/v1/research/tests/{testId}/activate       → 200 TestDetailResponse (status ACTIVE; 400 sin oraciones;
                                                        409 si no está en DRAFT)
POST /api/v1/research/tests/{testId}/close          → 200 TestDetailResponse (status CLOSED; 409 si no está ACTIVE)
```

### 3.1 Asignación

```http
POST /api/v1/research/tests/{testId}/assignments
{"classroomId":"<uuid>"}            (o bien)   {"studentIds":["<uuid>", …]}
→ 200 List<AssignmentStatusResponse>   (exactamente uno de los dos; 400 si vienen ambos o ninguno;
                                         400 "Cannot assign an archived classroom"; 404 "Classroom not found";
                                         409 si la prueba no está ACTIVE; idempotente: ya asignados se ignoran)
GET  /api/v1/research/tests/{testId}/assignments → 200 List<AssignmentStatusResponse>
```

`AssignmentStatusResponse.attemptStatus` es `PENDING` (sin intento), `IN_PROGRESS`, `COMPLETED` o `CANCELLED`
según el intento más reciente de ese alumno en esa prueba; `currentPosition` (oraciones terminadas + 1) solo
mientras está en curso; `startedAt`/`completedAt` son los del intento (nulos sin intento).

### 3.2 Intentos, exclusión y anotación

```http
GET  /api/v1/research/tests/{testId}/attempts/{attemptId}     → 200 AttemptDetailResponse
POST /api/v1/research/tests/{testId}/attempts/{attemptId}/exclude
{"reason":"texto de 10 a 500 caracteres"}
→ 200 AttemptDetailResponse   (marca excludedAt/excludedBy/exclusionReason; no borra nada)
   409 "Attempt already excluded"   (la primera exclusión es definitiva: motivo y autor no se sobrescriben)

PUT  /api/v1/research/responses/{responseId}/annotation
{"errorCount":2}
→ 200 AttemptDetailResponse.ResponseRow
   400 "Annotation only applies to free sentences"   (solo FREE; DICTATED se cuenta automáticamente)
   409 "Attempt is not completed"                    (solo sobre intentos COMPLETED)
```

`AttemptDetailResponse.ResponseRow.wordCount` cuenta sobre `referenceText` en dictadas y sobre `finalText` en
libres (misma tokenización que la exportación). `errorSource` es `AUTO` (dictada, del alineador),
`ANNOTATED` (libre ya anotada) o `PENDING` (libre sin anotar); `effectiveErrorCount` es el que usan las
métricas (`annotatedErrorCount` si existe, si no `autoErrorCount`).

### 3.3 Resultados y exportación

Ver sección 5.

## 4. Pruebas de oraciones (alumno)

El alumno solo ve las pruebas que tiene asignadas; las oraciones nunca exponen `referenceText` antes de
terminarlas (solo `position` y `assistance`).

```http
GET  /api/v1/tests/assigned → 200 List<AssignedTestResponse>
     status: PENDING (sin intento), IN_PROGRESS o COMPLETED. Una prueba CLOSED sin intento no se lista;
     tampoco una CLOSED cuyos únicos intentos estén CANCELLED (ya no puede iniciarse).

POST /api/v1/tests/{testId}/attempts
{"appVersion":"1.4.0"}
→ 201 AttemptResponse   (se creó un intento nuevo)
   200 AttemptResponse   (ya había un intento IN_PROGRESS de esa misma prueba: se devuelve tal cual)
   403 "Test is not assigned to this student"
   409 "Another test is in progress"       (intento en curso de OTRA prueba: un alumno solo tiene uno a la vez)
   409 "Test is not active"                (prueba no ACTIVE)
   409 "Test already completed"            (ya hay un intento COMPLETED de esta prueba)

POST /api/v1/attempts/{attemptId}/responses/{position}/start
→ 200 StartSentenceResponse   (alreadyStarted=true si ya estaba comenzada y sin terminar: idempotente)
   409 "Sentence out of order: expected N"   (position != siguiente pendiente)

PUT  /api/v1/attempts/{attemptId}/responses/{position}
{"finalText":"…","firstKeyOffsetMs":1200,"finishedOffsetMs":9000,
 "suggestionsOffered":2,"suggestionsAccepted":1,"suggestionsRejected":0,"suggestionsUndone":0,
 "skipped":false,"completionKey":"<uuid generado por el teclado>"}
→ 200 FinishSentenceResponse   (nextPosition null si el intento ya no está en curso: completado o cancelado)
   409 "Sentence not started"           | 409 "Sentence out of order"
   409 "Sentence already finished"      (completionKey distinta a la que ya cerró esa oración)
   (misma completionKey sobre una oración ya terminada → 200 con el mismo cuerpo: reintento del teclado)

POST /api/v1/attempts/{attemptId}/cancel
{"reason":"ABANDONED"|"TECHNICAL_PROBLEM"|"INTERRUPTED"}
→ 204
```

Los offsets (`firstKeyOffsetMs`, `finishedOffsetMs`) son milisegundos desde Comenzar según el reloj monotónico
del teléfono. Al terminar la última oración pendiente el intento pasa a `COMPLETED` automáticamente; si la
oración es `DICTATED`, el backend calcula `autoErrorCount`/`autoErrorDetail` con el alineador palabra a
palabra contra `referenceText` en el mismo `finish`. Un intento en curso serializa sus transiciones (Comenzar,
Terminar, cancelar, corrección) bajo el bloqueo de fila del intento; arrancar dos intentos a la vez para el
mismo alumno se serializa por el bloqueo de la fila del alumno, con el índice único parcial de la base de datos
como último resguardo.

## 5. Corrección durante una prueba

`POST /api/v1/corrections/process` acepta un campo opcional `id_respuesta` (`ProcessCorrectionRequest.testResponseId`)
que el teclado envía **solo** mientras el alumno escribe la oración en curso de una prueba:

```http
POST /api/v1/corrections/process
{"texto_original":"ola mundo","id_respuesta":"<responseId>"}
→ 201 CorrectionSessionResponse
   400 "Contextual correction is disabled for this sentence"   (la oración es UNASSISTED)
   400 "A sentence test is in progress"    (sin id_respuesta pero el alumno tiene un intento IN_PROGRESS:
                                             toda corrección durante una prueba debe venir ligada a su oración)
   409 "Sentence is not open"              (la oración ya se comenzó y terminó, o aún no se comenzó)
   404 "Sentence response not found"       (id_respuesta inexistente)
   403 "Sentence response belongs to another student"
```

Sin `id_respuesta` (uso normal, fuera de una prueba) todo funciona como siempre. La validación ocurre en dos
fases: una lectura (valida alumno y, si hay `id_respuesta`, que la oración sea `ASSISTED` y esté abierta) antes
de llamar a la IA, y una escritura después de la respuesta de la IA que vuelve a bloquear el intento y
revalida — si el teclado terminó la oración mientras la IA respondía, la corrección no deja rastro.

**`MODEL_VERSION_CHANGED`**: la primera corrección asistida de un intento congela `modelVersion` en el intento;
si una corrección posterior de ese mismo intento llega con otra versión del modelo, se incrementa
`incidentCount` y se añade el código `MODEL_VERSION_CHANGED` al array `incidents` del `auto_error_detail` JSON
de la respuesta (nunca se rechaza la corrección). Es la única incidencia que se audita para pruebas de
oraciones: **`AI_REQUEST_FAILED` no se registra a propósito** — un fallo de la IA durante una prueba responde
simplemente `502` (`AiServiceException`) sin dejar incidencia, a diferencia del experimento anterior.

`PATCH /api/v1/corrections/sessions/{id}/feedback` se cierra en cuanto la oración termina: si la sesión está
ligada a una respuesta (`session.isInTest()`) y esa respuesta ya tiene `finishedAt`, cualquier feedback responde
`400 "Feedback is closed for this sentence"`. Los contadores de sugerencias (`suggestionsOffered/Accepted/
Rejected/Undone`) que importan a las métricas son los que el teclado envía en `Terminar`
(`FinishSentenceRequest`), no el feedback en sí. El feedback de una sesión ligada a una prueba se guarda pero
**no** se reenvía a la IA (`session.isInTest()`): el LoRA es global y las decisiones de una prueba no deben
alterar el modelo en caliente.

## 6. Resultados y exportación

```http
GET /api/v1/research/tests/{testId}/results     → 200 TestResultsResponse
GET /api/v1/research/tests/{testId}/export.csv  → 200 text/csv;charset=UTF-8, UTF-8 con BOM, CRLF,
                                                    Content-Disposition: attachment; filename="test-<code>-responses.csv",
                                                    X-Dataset-Sha256: <sha256 hex minúsculas>
```

Solo se analizan intentos `COMPLETED` **no excluidos** (`sample.completed` incluye los excluidos;
`sample.excluded` los cuenta aparte; `completedCount` en `TestSummaryResponse`/`TestDetailResponse` también
cuenta **todos** los intentos completados, incluidos los excluidos). El CSV, en cambio, incluye una fila por
cada respuesta de **todo** intento `COMPLETED` (también los excluidos, marcados con `excluded=true`; los
`CANCELLED` no aparecen).

### 6.1 `error_source` y oraciones omitidas

- `error_source` por respuesta: `AUTO` (dictada, conteo del alineador), `ANNOTATED` (libre ya anotada por el
  investigador) o `PENDING` (libre sin anotar). `TestResultsResponse.incomplete = true` mientras exista alguna
  libre **no omitida** sin anotar; una libre omitida (`skipped=true`) sale como `PENDING` en el CSV y en
  `AttemptDetailResponse` pero **no** marca `incomplete`, porque no entra en ninguna métrica.
- Una respuesta con `skipped=true` **queda excluida de toda métrica**: no participa en `errorsPer100Words`,
  `wordsPerMinute`, el conteo de `participants`, la diferencia pareada ni los contadores de sugerencias
  (`offered`/`accepted` agregados de `acceptanceRate`). Sigue apareciendo en el CSV y en `AttemptDetailResponse`
  con `skipped=true` y en `SentenceStat.skippedCount`.

### 6.2 Intervalos bootstrap deterministas

`MetricInterval` (media de n valores por alumno con IC 95 % bootstrap percentil): PRNG **SplitMix64**, semilla
**42**, **2000** remuestreos; por remuestreo se toman n índices en orden con `nextIndex(n) = nextLong() mod n`
(resto sin signo) y se promedian; las 2000 medias se ordenan ascendentemente y se interpola con el cuantil
**R-7** (`idx = p*(k-1)`, `lo = floor(idx)`, `frac = idx - lo`, `value = s[lo] + frac*(s[min(lo+1,k-1)] - s[lo])`)
en `p = 0.025` y `p = 0.975`. Una instancia del PRNG por llamada; `n < 2` ⇒ `lower`/`upper` nulos; `n = 0` ⇒
`MetricInterval.EMPTY` (mean también nulo). Los valores por alumno se ordenan por `student_username` ascendente
(orden lexicográfico) antes de remuestrear, para que el resultado sea reproducible byte a byte frente al script
Python de verificación (`scripts/analyze_sentence_tests.py`).

Por alumno y condición: `errorsPer100Words = 100.0 * Σerrores / Σpalabras` (solo respuestas con `wordCount > 0`
y `effectiveErrorCount` conocido) y `wordsPerMinute = Σpalabras / (Σduration_first_key_ms / 60000.0)` (solo
respuestas con `durationFromFirstKeyMs > 0`); ambas sumas son secuenciales en el orden en que se recorren las
respuestas del alumno. `MetricInterval.mean` es la media aritmética simple de esos valores por alumno (no
ponderada por palabras).

`PairedDelta` (con ayuda − sin ayuda) se calcula solo sobre alumnos con valor en ambas condiciones:
`bootstrapLower/Upper` son el mismo bootstrap percentil sobre las diferencias por alumno; `tLower/tUpper`, `t`,
`p` y `dz` son un resumen t pareado clásico (solo con n ≥ 2 y varianza no nula).

`acceptanceRate` (solo condición `ASSISTED`; `null` en `UNASSISTED`) usa el intervalo de Wilson con
`Z_95 = 1.959964` (cuantil 0.975 de la normal estándar): centro = `(p + Z²/2n) / (1 + Z²/n)`, semiancho =
`Z/(1+Z²/n) * sqrt(p(1-p)/n + Z²/4n²)` con `p = accepted/offered`, acotado a `[0, 100]`; sin `offered` no hay
intervalo ni `ratePct`.

### 6.3 Las 21 columnas del CSV, en orden

```
test_code, student_username, attempt_id, position, kind, assistance, reference_text, final_text, skipped,
word_count, error_count, error_source, duration_first_key_ms, duration_start_ms, suggestions_offered,
suggestions_accepted, suggestions_rejected, suggestions_undone, model_version, app_version, excluded
```

`error_count` es `effectiveErrorCount` (vacío si aún no se conoce, p. ej. libre sin anotar). Comillas RFC 4180
(solo si la celda contiene `,`, `"`, CR o LF); `X-Dataset-Sha256` de la respuesta del `GET …/results`
(`datasetSha256`) coincide siempre con el SHA-256 del CSV de exportación de esa misma prueba: ambos se calculan
sobre la misma cohorte y el mismo `TestExportCsv.build`.

### 6.4 Umbral de muestra y procedencia

`sampleInsufficient = analyzed.size() < minSample`, con `minSample = app.tests.min-sample` (por defecto `8`,
`TESTS_MIN_SAMPLE`). `provenance` trae las versiones distintas y ordenadas de modelo, app y backend vistas en
los intentos analizados (no incluye los excluidos). `computedAt` es la hora del cálculo, no la de cierre de la
prueba.

## 7. Panel del docente (periodo)

El panel del docente es una **instantánea descriptiva**: qué escribió cada alumno, en qué se equivocó y qué hizo
con la ayuda del teclado, por salón o por alumno, en un día o en un rango de fechas. **Describe, no evalúa**: ninguna
respuesta de estas rutas trae series temporales, comparaciones entre periodos, porcentajes de cambio ni estados
evaluativos ("mejoró", "en riesgo"); solo cuenta y lista. Todas exigen `TEACHER` y comprueban la propiedad del salón
(`classrooms.teacher_id`) o el vínculo activo con el alumno (`teacher_student_links.deleted_at IS NULL`); si no,
`403`. El nombre real del alumno (`realName`) se descifra únicamente a través de ese vínculo.

**Periodo.** `from` y `to` son fechas `YYYY-MM-DD` inclusivas en `America/Lima` (`Period`): `from` ausente ⇒ hoy;
`to` ausente ⇒ `from`; `to < from` o más de 92 días ⇒ `400`. Se convierten a `[inicio, fin)` en UTC para las
consultas. Las correcciones pedidas durante una prueba de oraciones **sí cuentan** y se marcan `inTest = true`
con la `assistance` de la oración (`ASSISTED`/`UNASSISTED`).

**Desenlace** (`Outcome`) de cada corrección, derivado de columnas existentes de `correction_sessions`:
`accepted_correction = true` y `was_edited` ⇒ `EDITED` ("Resolvió solo"); `true` ⇒ `ACCEPTED` ("Aceptó");
`false` con `feedback_reason = UNDO` ⇒ `UNDONE` ("Deshizo"); `false` ⇒ `REJECTED` ("Rechazó"); `null` ⇒
`UNANSWERED` ("Sin respuesta"). `finalText` es `final_text` si existe; si no, la sugerencia elegida cuando la
aceptó; si no, el texto original.

| Método | Ruta | Parámetros | Respuesta |
|---|---|---|---|
| `GET` | `/api/v1/teachers/classrooms/{classroomId}/activity` | `from?`, `to?` | `200 ClassroomActivityResponse{classroomId, classroomName, from, to, students[{studentId, username, realName, lastActivityAt, correctionsInPeriod, outcomes{edited, accepted, rejected, undone, unanswered}}]}`; más correcciones primero, luego actividad más reciente; `lastActivityAt` no se limita al periodo |
| `GET` | `/api/v1/teachers/classrooms/{classroomId}/corrections/recent` | `from?`, `to?`, `limit?` (50; 1–200) | `200 List<RecentCorrectionItem{sessionId, studentId, username, realName, createdAt, originalText, correctedText, finalText, outcome, outcomeLabel, inTest, assistance}>` más recientes primero |
| `GET` | `/api/v1/teachers/classrooms/{classroomId}/errors` | `from?`, `to?` | `200 ClassroomErrorsResponse{classroomId, from, to, total, types[{type, label, count, topWords[{original, corrected, count}] (máx. 5)}]}` |
| `GET` | `/api/v1/teachers/students/{studentId}/errors` | `from?`, `to?` | `200 StudentErrorsResponse{studentId, from, to, total, types[{type, label, count, examples[{original, corrected, count}] (máx. 5)}], practiceWords[{original, corrected, count}]}`; `practiceWords` = pares con `count ≥ 2`, máx. 20, por `count` desc |
| `GET` | `/api/v1/teachers/students/{studentId}/help` | `from?`, `to?` | `200 StudentHelpResponse{studentId, from, to, total, edited, accepted, rejected, undone, unanswered, editedPct, acceptedPct, rejectedPct, undonePct, unansweredPct}`; porcentajes sobre `total` (2 decimales), `0` si `total = 0` |
| `GET` | `/api/v1/teachers/students/{studentId}/writings` | `from?`, `to?`, `limit?` (100; 1–200) | `200 List<StudentWritingItem{sessionId, createdAt, originalText, finalText, outcome, outcomeLabel, inTest, assistance, testCode}>` más recientes primero |
| `GET` | `/api/v1/teachers/students/{studentId}/tests` | — | `200 List<StudentTestSummary{attemptId, testCode, testTitle, completedAt, excluded, sentences[{position, kind, assistance, referenceText, finalText, skipped, errorCount, errorSource, edits[{type, expected, written}], durationFromFirstKeyMs}]}>`; solo intentos `COMPLETED` (los excluidos por el investigador con `excluded = true`; los cancelados no aparecen), terminados más recientemente primero |
| `GET` | `/api/v1/teachers/tests/live` | — | `200 List<LiveAttemptItem{attemptId, studentId, username, realName, classroomId, classroomName, testCode, testTitle, currentPosition, sentenceCount, startedAt}>`: intentos `IN_PROGRESS` de los alumnos vinculados activos del docente, quien empezó antes primero; `currentPosition` = min(oraciones terminadas + 1, `sentenceCount`) |
| `GET` | `/api/v1/reports/students/{studentId}/pdf` | `from?`, `to?` | `200 application/pdf` (`Content-Disposition: attachment; filename="reporte-<username>-<from>_<to>.pdf"`): "Reporte del periodo" con nombre del alumno, periodo, resumen de ayuda, errores por tipo con hasta 3 ejemplos, palabras para practicar y las últimas 20 escrituras (`original -> final · desenlace`). Sin gráficos ni tendencias |
| `GET` | `/api/v1/kpis/students/{studentId}/summary`, `/acceptance-rate`, `/top-words`, `/error-types` | `from?`, `to?` | KPIs históricos del alumno en el mismo periodo (`from`/`to` en la respuesta) |

En `/tests`, `errorCount` es el efectivo (automático en dictado, anotado en libre; `null` si falta anotar),
`errorSource` es `AUTO`/`ANNOTATED`/`PENDING` (sección 6.1) y `edits` son las operaciones del alineador leídas de
`test_responses.auto_error_detail` (`SUSTITUCION`, `OMISION`, `INSERCION`, `UNION`, `SEPARACION`).

**Escala.** `activity` carga las sesiones del salón del periodo y agrega los desenlaces en memoria (a lo sumo 92
días de un salón, la escala de la tesis); `help` hace lo mismo por alumno. Si el volumen creciera, ambas pueden
pasar a una agregación JPQL sin cambiar la respuesta. La disponibilidad de reportes mensuales
(`GET /api/v1/reports/students/{id}?month=`) y la tabla `monthly_reports` se retiraron (`V14`): el PDF se genera
siempre a partir de las mismas consultas del panel.
