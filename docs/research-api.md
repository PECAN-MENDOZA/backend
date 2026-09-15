# API de investigación (backend)

Flujo completo de un estudio experimental con el backend solo: cuenta de investigador, estudio, protocolo,
participantes seudónimos, sesiones del alumno desde el teclado, anotación humana ciega, resultados y
evaluación técnica. Todo lo descrito aquí está cubierto de punta a punta por
`src/test/java/com/mvp/backend/research/presentation/ResearchApiIntegrationTests.java`
(`.\mvnw.cmd -q "-Dtest=ResearchApiIntegrationTests" test`), que recorre este mismo orden sobre la cadena de
seguridad real (JWT emitidos por los endpoints de login) y comprueba al final que ninguna respuesta de
investigación contiene identidad de alumnos, datos del docente ni códigos de acceso en claro.

## 1. Roles y límites

| Rol | Cómo se obtiene | Puede | No puede |
|---|---|---|---|
| `RESEARCHER` | Cuenta creada por variables de entorno (sección 2) + `POST /api/v1/auth/staff/login` | `/api/v1/research/**` | `/api/v1/experiments/**`, `/api/v1/corrections/**`, `/api/v1/teachers/**` (403) |
| `TEACHER` | Registro/login existentes (`/api/v1/auth/teachers/*`; `staff/login` también acepta docentes) | Lo de siempre | `/api/v1/research/**` (403) |
| `STUDENT` | `POST /api/v1/auth/students/login` con alias + PIN | `/api/v1/experiments/**`, `/api/v1/corrections/**` | `/api/v1/research/**` (403) |

Sin token: 401. Un estudio ajeno o inexistente responde siempre `404 {"message":"Study not found"}`, de modo que
los estudios no se pueden enumerar. El investigador solo ve seudónimos `P-nnn`: nunca nombre, alias, correo,
notas, institución ni la relación docente-alumno.

## 2. Cuenta de investigador (bootstrap)

No existe registro público. Al arrancar, `ResearcherAccountSeeder` crea la cuenta si ambas variables están
definidas (idempotente; falla el arranque si el correo ya pertenece a un docente):

```properties
RESEARCHER_EMAIL=authors@tesis.local
```

`RESEARCHER_PASSWORD` se entrega **únicamente por el entorno del proceso** (variable de entorno, `.env` local no
versionado o secreto del despliegue). Su valor no se escribe en esta documentación ni en ningún archivo del
repositorio. Internamente se mapean a `app.researcher.email` / `app.researcher.password`; la contraseña se guarda
con BCrypt.

```http
POST /api/v1/auth/staff/login
{"email":"authors@tesis.local","password":"<RESEARCHER_PASSWORD>"}
→ 200 {"userId":"…","token":"<JWT>","expiresAt":"…","role":"RESEARCHER"}
```

Todas las llamadas siguientes llevan `Authorization: Bearer <JWT>`.

## 3. Orden de llamadas

### 3.1 Estudio y protocolo (investigador)

```http
POST /api/v1/research/studies
{"code":"EXP-2026-01","title":"Piloto teclado adaptativo"}
→ 201 {"id":"<studyId>","code":"EXP-2026-01","title":"…","status":"DRAFT","activeProtocolVersion":null,"createdAt":"…"}
   409 si el código ya existe

POST /api/v1/research/studies/{studyId}/protocols
{"taskAPrompt":"Cuenta que hiciste el fin de semana","taskBPrompt":"Describe tu lugar favorito"}
→ 201 {"id":"<protocolId>","studyId":"…","version":1,"status":"DRAFT","taskAPrompt":"…","taskBPrompt":"…","createdAt":"…"}

POST /api/v1/research/studies/{studyId}/protocols/{protocolId}/activate
→ 200 {…,"status":"ACTIVE"}
```

Activar el primer protocolo activa el estudio (`GET /api/v1/research/studies` → `status: ACTIVE`,
`activeProtocolVersion: 1`). Antes de eso, crear participantes responde `400 "Study is not active"`.

### 3.2 Participantes y códigos (investigador)

```http
POST /api/v1/research/studies/{studyId}/participants
→ 201 {"id":"<participantId>","pseudonym":"P-001","sequence":"ASSISTED_FIRST","completedRuns":0,
       "protocolCompleted":false,"hasOpenRun":false,"nextSession":{"task":"TASK_A","condition":"ASSISTED"},"createdAt":"…"}
GET  /api/v1/research/studies/{studyId}/participants → 200 [ … ]
```

El servidor fija la secuencia: impares `ASSISTED_FIRST`, pares `UNASSISTED_FIRST`; la primera sesión es
`TASK_A` y la segunda `TASK_B`, cada una con la condición que toca. El alumno nunca elige consigna, condición ni
orden. Un participante no tiene alumno hasta que alguien canjea su primer código.

```http
POST /api/v1/research/studies/{studyId}/participants/{participantId}/access-code
→ 201 {"runId":"<runId>","participantId":"…","pseudonym":"P-001","code":"K7MP2XQ9","expiresAt":"…","task":"TASK_A","condition":"ASSISTED"}
   400 "Participant already has an open run" | "Participant already completed both conditions" | sin protocolo activo
```

El código (8 caracteres de `A-HJ-NP-Z2-9`, sin 0/O/1/I) se muestra **una sola vez**: el servidor guarda solo
su SHA-256 y caduca a los `RESEARCH_ACCESS_CODE_TTL` (30 min por defecto) si nadie lo canjea. Es la única
respuesta de investigación que contiene el código en claro. Mientras el participante tenga una ejecución
abierta (PENDING o ACTIVE) no se emite otro; tras completar ambas condiciones tampoco.

Gestión de ejecuciones (todas con `{"reason":"texto de 10 a 500 caracteres"}` cuando aplica):

```http
GET  /api/v1/research/studies/{studyId}/runs                       → 200 [ExperimentRunResponse]  (sin texto final ni alumno)
POST /api/v1/research/studies/{studyId}/access-codes/{runId}/revoke → 200 (solo PENDING; sin cuerpo)
POST /api/v1/research/studies/{studyId}/runs/{runId}/cancel         → 200 (PENDING o ACTIVE)
POST /api/v1/research/studies/{studyId}/runs/{runId}/exclude        → 200 (COMPLETED o TECHNICAL_FAILURE; una sola vez; nada se borra)
```

### 3.3 Sesión del alumno (teclado)

```http
POST /api/v1/experiments/access-code/redeem        {"code":"K7MP2XQ9"}
→ 201 {"id":"<runId>","participantCode":"P-001","condition":"ASSISTED","taskVariant":"TASK_A",
       "promptText":"Cuenta que hiciste el fin de semana","status":"PENDING","startedAt":null,"expiresAt":"…"}
   400 "Access code is invalid or unavailable" (desconocido, vencido, revocado, de otro alumno, ya iniciado)
   400 "Too many failed redemption attempts, try again later" (más de 5 fallos en 5 min por alumno)

GET  /api/v1/experiments/runs/active               → 200 (ACTIVE, o PENDING ya canjeada) | 404 "No experiment run to restore"
POST /api/v1/experiments/runs/{runId}/start        → 200 {…,"status":"ACTIVE","startedAt":"…"} (idempotente)

POST /api/v1/corrections/process                   (solo en ASSISTED, ver sección 6)
{"texto_original":"ola mundo","id_ejecucion":"<runId>"}
→ 201 {"id_sesion":"…","texto_original":"ola mundo","texto_corregido":"hola mundo","suggestions":["hola mundo","hola mundo!"],…}
   400 "Contextual correction is disabled for this experiment run"  (UNASSISTED: la IA nunca se contacta)
   400 "Experiment run is not active"                               (antes de start, o tras completar/cancelar)
   404 "Experiment run not found"                                   (ejecución ajena o inexistente)

PATCH /api/v1/corrections/sessions/{id_sesion}/feedback
{"sugerencia_elegida":"hola mundo","acepto_correccion":true,"texto_final":"hola mundo"}        (aceptar)
{"acepto_correccion":false,"motivo":"UNDO"}                                                     (deshacer)
→ 200 | 400 "Feedback is closed for this experiment run" | 400 "Feedback cannot re-accept a corrected text after undo"

PATCH /api/v1/experiments/runs/{runId}/complete
{"texto_final":"hola mundo que tal","duracion_ms":120000,"completion_key":"<uuid generado por el teclado>","app_version":"1.4.0"}
→ 200 {…,"status":"COMPLETED"}   (misma completion_key → 200 con el mismo cuerpo; otra clave → 400 "Run is not active";
                                  clave usada por otra ejecución → 409)
POST /api/v1/experiments/runs/{runId}/cancel        {"reason":"ABANDONED"|"TECHNICAL_PROBLEM"|"INTERRUPTED"} → 204
```

El código queda ligado al primer alumno que lo canjea (un alumno ocupa a lo sumo un participante por estudio);
ese alumno puede volver a canjearlo hasta `start`, cualquier otro recibe el error genérico. `duracion_ms` es la
duración monotónica medida por el teclado y se guarda tal cual: si supera lo transcurrido en el servidor + 5 min
se registra la incidencia `DURATION_INCONSISTENT`, nunca se rechaza. Para la segunda condición el investigador
emite un nuevo código (sección 3.2) y el teclado repite el ciclo.

### 3.4 Anotación ciega (investigador y evaluadores)

```http
POST /api/v1/research/studies/{studyId}/annotation-batches?kind=ORTHOGRAPHY|SEMANTIC
→ 201 {"id":"<batchId>","kind":"ORTHOGRAPHY","columns":["sample_code","text","score"],"rowCount":4,"exportSha256":"…",
       "createdAt":"…","completedSlots":[],"imports":[],"agreement":{"complete":false,"weightedKappa":null,"exactAgreement":null},
       "adjudicationCurrent":false}
GET  /api/v1/research/studies/{studyId}/annotation-batches                → 200 [ … ]
GET  /api/v1/research/studies/{studyId}/annotation-batches/{batchId}      → 200 resumen
GET  /api/v1/research/studies/{studyId}/annotation-batches/{batchId}/export
→ 200 text/csv;charset=UTF-8, Content-Disposition: attachment; filename="annotations-<kind>-<id8>.csv",
      Cache-Control: no-store, X-Content-SHA256: <exportSha256>
POST /api/v1/research/studies/{studyId}/annotation-batches/{batchId}/imports?slot=RATER_1|RATER_2|ADJUDICATED&rater=Ana
     multipart/form-data: file=<csv>
→ 201 resumen actualizado
```

Un lote congela las ejecuciones COMPLETED no excluidas que existen en ese momento (filas barajadas, códigos de
muestra `T-XXXXXXXX` aleatorios, `exportSha256` del CSV exacto). Si después se completan más ejecuciones se crea
otro lote; los resultados usan el más reciente que esté adjudicado (sección 5).

### 3.5 Resultados y evaluación técnica (investigador)

```http
GET /api/v1/research/studies/{studyId}/results        → 200 StudyResultsResponse (sección 5)
GET /api/v1/research/studies/{studyId}/analysis.csv   → 200 text/csv (una fila por ejecución completada × sugerencia evaluada)

POST /api/v1/research/technical-evaluations
{"modelVersion":"beto-lora-global-v1","datasetSha256":"<sha256 hex minúsculas del conjunto reservado>",
 "scorerVersion":"exact_token_edits_v1","precision":0.8,"recall":0.5,"fZeroFive":0.7142857,
 "truePositives":40,"falsePositives":10,"falseNegatives":40}
→ 201 {…,"fOne":0.6153846,"createdAt":"…"}
   400 "F0.5 does not match precision and recall (expected …)" | 400 "Precision/recall do not match TP/FP/FN"
   400 {"validationErrors":{"scorerVersion":…}} si el scorer no es exact_token_edits_v1 o el hash no es SHA-256
GET /api/v1/research/technical-evaluations[?modelVersion=…]         → 200 [ … ] (solo las del investigador)
GET /api/v1/research/technical-evaluations/latest[?modelVersion=…]  → 200 | 404
```

F0.5, precisión y recall viven solo aquí: el objeto de resultados del estudio nunca los incluye.

## 4. CSV de anotación y flujo de evaluadores

### Diseño del export

| Lote | Cabecera | Una fila por | Contenido de `score` en el export |
|---|---|---|---|
| `ORTHOGRAPHY` | `sample_code,text,score` | ejecución completada no excluida (`text` = texto final) | vacío |
| `SEMANTIC` | `sample_code,original_text,suggestion,score` | sugerencia evaluada de cada corrección ligada a una ejecución ASSISTED | vacío |

- RFC-4180 mínimo: se entrecomillan solo las celdas con `,`, `"`, CR o LF; finales de línea `\n`; UTF-8 sin BOM.
- **Guarda contra fórmulas**: una celda cuyo primer carácter útil es `=`, `+`, `-` o `@` (o empieza por
  tabulador/CR) se exporta con un espacio inicial (` =ke tal`) para que una hoja de cálculo no la ejecute. El
  hash `exportSha256` se calcula sobre estos bytes. La importación ignora las columnas de texto, así que el
  archivo se puede devolver tal cual con la columna `score` completada.
- El CSV no contiene condición, seudónimo, orden, versión de modelo, identificadores de ejecución/sesión ni
  ningún dato del alumno; tampoco las respuestas JSON de los lotes contienen filas ni códigos de muestra.
- Sugerencia evaluada en `SEMANTIC`: la que el alumno aceptó (`sugerencia_elegida` con `acepto_correccion=true`);
  si la rechazó, la deshizo (`motivo=UNDO`) o no dio feedback, la primera ofrecida (la recomendada por el
  teclado). La aceptación se congela al crear el lote (`sessionsChangedAfterExport` en la procedencia cuenta
  sesiones que cambiaron después; los resultados siguen usando el valor congelado).

### Escalas

- `ORTHOGRAPHY`: entero ≥ 0 = palabras con al menos un error ortográfico según la guía común. No puede superar
  el número de palabras del texto (`400 "Score exceeds the word count of sample T-…"`); el conteo de palabras es
  el mismo que usa PEO.
- `SEMANTIC`: `2` conserva completamente el significado, `1` cambio menor / matiz dudoso / ambigüedad,
  `0` cambia, elimina, contradice o agrega información importante (`Criterios_de_Exito_Teclado_Adaptativo.md`).

### Flujo de los evaluadores

1. Descargar el export y entregar una copia a cada evaluador; ninguno ve la condición ni el participante.
2. Cada evaluador completa la última columna. Se acepta la cabecera completa del export o la mínima
   `sample_code,score`; todas las filas deben tener puntaje (`400 "Missing scores for N sample(s): …"`), sin
   códigos desconocidos ni repetidos; máximo 5 MB, UTF-8 (BOM opcional).
3. Importar `slot=RATER_1&rater=<nombre>` y `slot=RATER_2&rater=<otro nombre>`. Los nombres deben ser distintos
   (`400 "RATER_1 and RATER_2 must be distinct raters"`).
4. Con ambas ranuras completas el resumen trae `agreement`: `weightedKappa` (kappa de Cohen con pesos lineales
   sobre el rango observado; `null` si está indefinida) y `exactAgreement` (proporción de filas iguales).
5. Resolver los desacuerdos fuera del panel (consenso o tercer evaluador) e importar `slot=ADJUDICATED`
   (`400 "Adjudication requires two complete rater imports"` si falta alguna ranura). Solo el archivo adjudicado
   alimenta PEO y TAS.
6. Re-importar una ranura crea una nueva versión (la anterior se conserva con `supersededAt`, nunca se borra).
   Volver a subir el archivo que ya es el vigente de esa ranura responde
   `409 "This file is already the current import for slot …"`. Una revisión de `RATER_1`/`RATER_2` invalida la
   adjudicación vigente (`adjudicationCurrent=false`, puntajes adjudicados vaciados, evento
   `ANNOTATION_ADJUDICATION_INVALIDATED`): hay que adjudicar de nuevo.

## 5. Resultados: `annotationStatus` y qué significa cada valor

`GET …/results` devuelve `sample`, `peo`, `ppm`, `tas`, `tasAccepted`, `orthographyAnnotation`,
`semanticAnnotation`, `participants[]` (solo `P-nnn`) y `provenance`. Toda métrica que depende de anotación
humana es `null` —nunca un número parcial— mientras su bloque de anotación no esté en `ADJUDICATED`.

| `status` | Significado | Qué hacer |
|---|---|---|
| `ADJUDICATED` | El lote más reciente del tipo tiene una adjudicación vigente (sobre las importaciones vigentes de ambos evaluadores) que cubre todas las ejecuciones/sugerencias incluidas. PEO (ortografía) o TAS/TAS aceptada (semántica) se calculan. | — |
| `NO_BATCH` | No existe ningún lote de ese tipo. | Crear el lote. |
| `NOT_ADJUDICATED` | El lote más reciente (`batchId`) no tiene adjudicación vigente. | Importar sus evaluadores y la adjudicación; no crear otro lote. |
| `INCOMPLETE_COVERAGE` | La adjudicación vigente del lote más reciente no cubre todas las ejecuciones/sugerencias incluidas (se completaron más después). | Crear y adjudicar un lote nuevo. |
| `NOT_APPLICABLE` | Nada que evaluar (ortografía: ningún participante con palabras contables en ambas condiciones; semántica: sin sugerencias en las ejecuciones ASSISTED incluidas). | — |
| `NO_SAMPLE` | Ningún participante con par completo. | Completar sesiones. |

Cada bloque incluye `batchId`, `exportSha256`, `adjudicationImportId` y `message`; `provenance.datasets[]` repite
lote, hash del export, `rowCount`, versión y hash de la adjudicación usada, junto con `protocolVersions`,
`modelVersions`, `backendVersions`, `appVersions` y `computedAt`.

Reglas de muestra (`sample`): un participante se incluye solo con un **par completo** (al menos una ejecución
COMPLETED no excluida en cada condición); `participantsTotal = participantsIncluded + participantsWithIncompletePair
+ participantsWithoutEligibleRun` y `runsCompleted = runsIncluded + runsExcluded + runsInIncompletePairs`. PPM usa
toda la cohorte incluida; PEO solo participantes con palabras contables en ambas condiciones; TAS solo
ejecuciones ASSISTED con al menos una sugerencia evaluada (TAS aceptada: aceptada).

`ppm.descriptive` y `tas.descriptive` son `true` mientras no se configuren `app.research.ppm-non-inferiority-margin`
(> 0) y `app.research.tas-limit` (0–100); entonces `nonInferior` y `upperCiBelowLimit` son `null`. Con margen,
no inferioridad = límite inferior del IC 95 % de ΔPPM > −margen; con límite, se compara el **límite superior**
del intervalo de Wilson agregado, nunca el promedio. `peo.upperCiBelowZero` es el criterio del IC, no una
declaración de éxito.

### Advertencias metodológicas

- **El intervalo de Wilson de TAS ignora el agrupamiento**: trata todas las sugerencias como independientes
  aunque varias provengan del mismo participante o de la misma ejecución. Con pocos participantes subestima la
  incertidumbre; reportarlo junto con `participantMean`/`participantSd` (descriptivos) y el `n` de participantes.
- Con `n < 2` o diferencias de varianza nula, los campos inferenciales de PEO/PPM (`ci95*`, `tStatistic`,
  `pValue`, `cohenDz`) son `null`; con `n` muy pequeño los intervalos t son muy anchos (con n = 2 el cuantil es
  12.7). El panel no declara éxito por un valor p.
- **`data/eval_gold.csv` y `pruebas.txt` NO son el conjunto de prueba final reservado**: ya se usaron para
  observar resultados y orientar cambios (`Criterios_de_Exito_Teclado_Adaptativo.md` §6.3). El backend solo valida
  el hash SHA-256 y el scorer de lo que se registra, no puede reconocer nombres de archivo; la evaluación técnica
  definitiva debe hacerse sobre un conjunto separado, versionado por su hash, y registrarse con ese hash.
- F0.5 se registra aparte y no se combina con los resultados intra-sujeto; el LoRA es global y el feedback de
  sesiones experimentales se guarda pero **no** se reenvía a la IA (no hay entrenamiento en línea).

## 6. Contrato del teclado (notas)

- `id_ejecucion` en `POST /api/v1/corrections/process` **solo** dentro de una ejecución `ASSISTED` y ACTIVE.
  En `UNASSISTED` el teclado no debe llamar a la IA (bloqueo local) y, si lo hiciera, el backend responde
  `400 "Contextual correction is disabled for this experiment run"` sin contactar a la IA. En uso normal (sin
  experimento) el campo va ausente o `null` y todo funciona como antes; esas sesiones nunca entran al estudio.
- Una corrección que llega cuando la ejecución ya no está ACTIVE (completada, cancelada, vencida) responde 400
  (`"Experiment run is not active"` o, si cambió mientras la IA respondía, `"… no longer active"`) y no deja rastro.
- **El 400 en `/feedback` tras completar es terminal**: `"Feedback is closed for this experiment run"`. La
  aceptación queda fija al completar (los lotes semánticos la congelan). El teclado debe enviar el feedback antes
  de `complete` y no reintentar después.
- Deshacer una sugerencia aplicada: `{"acepto_correccion":false,"motivo":"UNDO"}` (sin `sugerencia_elegida`; si
  llegara `acepto_correccion=true` con `motivo=UNDO`, el backend lo trata igualmente como rechazo). Tras un UNDO
  no se puede volver a aceptar (`400 "Feedback cannot re-accept a corrected text after undo"`); un reenvío idéntico
  es un no-op 200.
- `complete` es idempotente por `completion_key` (UUID generado por el teclado y conservado hasta recibir 200):
  reintentar con la misma clave devuelve el mismo cuerpo; `duracion_ms` es la duración monotónica de toda la
  tarea; `app_version` se registra en la ejecución.
- El código de acceso se envía en mayúsculas, 8 caracteres de `A-HJ-NP-Z2-9`; tras 5 fallos en 5 minutos el
  alumno queda bloqueado temporalmente. `GET /runs/active` restaura la pantalla correcta (PENDING → confirmación,
  ACTIVE → tarea) tras reinstalar o reiniciar.
