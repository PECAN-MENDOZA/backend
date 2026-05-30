# FlorisBoard Dysgraphia Backend

Spring Boot modular monolith for the adaptive FlorisBoard keyboard. The backend keeps student accounts pseudonymous, stores teacher data separately, encrypts real student names inside teacher-student links, delegates text correction to the AI container, and exposes teaching KPIs.

## Requirements

- Java 21
- PostgreSQL
- AI container exposing `POST /interno/corregir`

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
AI_BASE_URL=http://localhost:5000
AI_CORRECTION_PATH=/interno/corregir
```

Then run:

```powershell
./mvnw spring-boot:run
```

Flyway applies the schema automatically. Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

## Modules

- `auth`: teacher registration, student and teacher login, JWT creation
- `student`: teacher-managed pseudonymous student accounts and dynamic search
- `teacher`: encrypted teacher-student links
- `correction`: correction sessions, word details, feedback, and AI adapter
- `kpi`: acceptance rate, errors by type, top words, and combined teacher dashboard
- `consent`: privacy consent audit records
- `shared`: reusable DTOs, security helpers, and global error handling
- `config`: JWT, CORS, typed properties, and HTTP client configuration

## Main endpoints

| Method | Path | Role |
| --- | --- | --- |
| `POST` | `/api/v1/auth/teachers/register` | Public |
| `POST` | `/api/v1/auth/teachers/login` | Public |
| `POST` | `/api/v1/auth/students/login` | Public |
| `POST` | `/api/v1/students` | Teacher |
| `POST` | `/api/v1/teachers/students` | Teacher |
| `POST` | `/api/v1/corrections/process` | Student |
| `PATCH` | `/api/v1/corrections/sessions/{id}/feedback` | Student |
| `GET` | `/api/v1/kpis/students/{id}/summary?month=2026-05` | Teacher |
| `POST` | `/api/v1/consents` | Authenticated |

## Verify

```powershell
./mvnw "-Dmaven.repo.local=.m2" test
```

## Seed demo data

To populate a local database with 1 teacher, 15 students, linked records, consents, monthly reports, and 2 weeks of correction sessions:

```powershell
.\scripts\seed-demo.ps1
```

Default demo credentials:

- teacher: `sofia.garcia@colegio.edu.pe` / `DemoTeacher123`
- students: `student_001` to `student_015` / `DemoStudent123`
