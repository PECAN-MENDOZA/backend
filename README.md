# FlorisBoard Dysgraphia Backend

Spring Boot modular monolith for the adaptive FlorisBoard keyboard. The backend keeps student accounts pseudonymous, stores teacher data separately, encrypts real student names inside teacher-student links, delegates text correction to the AI container, and exposes teaching KPIs.

## Requirements

- Java 21
- PostgreSQL
- AI container exposing `POST /interno/corregir` (only when `AI_MODE=http`; the default `AI_MODE=stub` runs without it)

## Run locally

Create the PostgreSQL database and add a local `.env` file in the project root. The backend reads it from [application.properties](C:/Users/Dovamul/Desktop/TESIS%20PROYECTO/backend/src/main/resources/application.properties). You can start from `.env.example`.

Example `.env`:

```env
DB_URL=jdbc:postgresql://localhost:5432/florisboard
DB_USERNAME=postgres
DB_PASSWORD=postgres
JWT_ISSUER=florisboard-backend
JWT_TOKEN_TTL=PT5H
JWT_SECRET=replace-with-a-long-random-secret
PERSONAL_DATA_KEY_BASE64=replace-with-a-base64-encoded-32-byte-key
AI_MODE=stub
AI_BASE_URL=http://localhost:5000
AI_CORRECTION_PATH=/interno/corregir
```

`AI_MODE` selects the AI correction backend. `stub` (the default) returns simulated responses
without contacting any AI service —useful for deploying and integrating the keyboard and Angular
portal while the real AI (BETO) is not ready— and logs the request/response JSON at `INFO` for
inspection. Switch to `AI_MODE=http` to call the real AI service once it is available, with no code
changes.

For production also set `CORS_ALLOWED_ORIGINS` to the Angular portal domain(s) (comma-separated) and
`ALLOW_INSECURE_DEFAULTS=false` so the app refuses to start with the bundled development secrets.

Then run:

```powershell
./mvnw spring-boot:run
```

Flyway applies the schema automatically. Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

## Modules

- `auth`: teacher registration, student and teacher login, JWT creation
- `student`: pseudonymous student profiles and student self-service
- `teacher`: encrypted teacher-student links
- `correction`: correction sessions, word details, feedback, and AI adapter
- `kpi`: acceptance rate, top words, and combined teacher dashboard
- `report`: monthly report availability and PDF generation
- `shared`: reusable DTOs, security helpers, and global error handling
- `config`: JWT, CORS, typed properties, and HTTP client configuration

## Main endpoints

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/research/teachers` | Researcher |
| `POST` | `/api/v1/auth/teachers/login` | Public |
| `POST` | `/api/v1/auth/students/login` | Public |
| `POST` | `/api/v1/teachers/classrooms/{id}/students` | Teacher |
| `GET` | `/api/v1/teachers/students` | Teacher |
| `POST` | `/api/v1/teachers/students/{id}/reset-pin` | Teacher |
| `GET` | `/api/v1/students/me` | Student |
| `POST` | `/api/v1/corrections/process` | Student |
| `PATCH` | `/api/v1/corrections/sessions/{id}/feedback` | Student |
| `GET` | `/api/v1/kpis/students/{id}/summary?month=2026-05` | Teacher |
| `POST` | `/api/v1/research/tests` | Researcher |
| `GET` | `/api/v1/research/tests/{id}/results` | Researcher |
| `GET` | `/api/v1/research/tests/{id}/export.csv` | Researcher |
| `GET` | `/api/v1/tests/assigned` | Student |
| `POST` | `/api/v1/tests/{id}/attempts` | Student |
| `PUT` | `/api/v1/attempts/{id}/responses/{position}` | Student |

Teachers create students inside one of their classrooms through `POST /api/v1/teachers/classrooms/{id}/students`.
The backend generates a kid-friendly alias (`palabra-NN`, e.g. `tigre-07`) and a 4-digit PIN, inherits the teacher
institution, encrypts the real student name, and creates the teacher-student link in one transaction. The PIN is
returned only by the creation response so the teacher can hand it to the student. There is no forced password
change: the student logs in with the alias and PIN and uses the keyboard directly. If a student forgets the PIN,
the teacher resets it via `POST /api/v1/teachers/students/{id}/reset-pin`, which returns a new PIN once.

Teacher accounts have no public self-registration: a researcher creates them via `POST /api/v1/research/teachers`,
which returns a temporary password once. See [docs/research-api.md](docs/research-api.md) for the full endpoint
table, roles, and the sentence-test research workflow (drafting a test, assigning it, the student's
Start/Finish-per-sentence flow, contextual correction during a test, and the bootstrap-based results and CSV
export).

## Verify

```powershell
./mvnw "-Dmaven.repo.local=.m2" test
```

## Seed demo data

To populate a local database with 1 teacher, 15 students, linked records, monthly reports, and 2 weeks of correction sessions:

```powershell
.\scripts\seed-demo.ps1
```

Default demo credentials:

- teacher: `sofia.garcia@colegio.edu.pe` / `DemoTeacher123`
- students: `student_001` to `student_015` / `1234` (4-digit PIN, same format as real students)

If a researcher account already exists (see `RESEARCHER_EMAIL`/`RESEARCHER_PASSWORD` above), the seed also
creates a draft sentence test `PRUEBA-DEMO` with 6 sentences owned by that researcher; without one, this step is
skipped with a log line.
