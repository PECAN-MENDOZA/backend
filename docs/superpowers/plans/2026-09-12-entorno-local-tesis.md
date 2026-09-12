# Entorno Local Integrado de la Tesis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ejecutar localmente teclado Android, portal Vue, backend Spring Boot, PostgreSQL y la IA BETO/T5 acelerada por la RTX 5070, con una ruta reproducible de preparación, arranque y comprobación.

**Architecture:** El teclado y Vue consumen exclusivamente el backend en `:8080`; Spring Boot persiste en PostgreSQL `:5432` y llama al servicio Flask de IA en `:5000`. El teléfono alcanza `127.0.0.1:8080` mediante `adb reverse`; la IA corre como un único proceso nativo de Windows para no duplicar modelos en VRAM.

**Tech Stack:** PowerShell, Java 21, Spring Boot 4, PostgreSQL 16/Docker, Vue 3/Vite 8, Kotlin/Gradle/Android SDK, Python 3.11, Flask, PyTorch CUDA 13.0, Transformers, BETO y T5.

## Global Constraints

- Usar `backend/origin/develop@434ec96`, `frontend-web/origin/develop@1772d24`, `keyboard-nlp/origin/mvp@ef1bea6` e `IA-Correcci-n-Contextual/origin/v6-fixlora@554370e` como bases.
- Mantener intactas las URL y el comportamiento de producción.
- No implementar FLAN-T5-small ni modo móvil sin conexión.
- No borrar ni reutilizar `IA-Correcci-n-Contextual/venv`; crear `.venv` con Python 3.11.
- No registrar JWT, PIN, nombres reales ni textos completos del estudiante en scripts nuevos.
- Ejecutar una sola instancia de IA y mantener `ENABLE_USER_LORA=false`.
- No eliminar volúmenes, modelos ni bases al detener el entorno.

---

### Task 1: Alinear las ramas funcionales sin perder archivos locales

**Files:**
- Preserve: `IA-Correcci-n-Contextual/venv/`
- Preserve: todos los archivos ignorados y cambios existentes

**Interfaces:**
- Consumes: referencias remotas actualizadas el 2026-09-12.
- Produces: una rama `local/full-stack-setup` en cada repositorio, basada en la rama funcional correspondiente.

- [ ] **Step 1: Confirmar que no hay cambios versionados sin guardar**

Run desde `TESIS`:

```powershell
@('backend','frontend-web','IA-Correcci-n-Contextual','keyboard-nlp') | ForEach-Object {
    git -C $_ status --short --branch
}
```

Expected: solo `IA-Correcci-n-Contextual/venv/` puede aparecer como no versionado antes del cambio; detenerse si aparece cualquier otro cambio del usuario.

- [ ] **Step 2: Crear o reutilizar las ramas locales**

```powershell
git -C backend switch local/full-stack-setup
git -C frontend-web switch -c local/full-stack-setup origin/develop
git -C IA-Correcci-n-Contextual switch -c local/full-stack-setup origin/v6-fixlora
git -C keyboard-nlp switch -c local/full-stack-setup origin/mvp
```

Expected: los cuatro repositorios quedan en `local/full-stack-setup`; el `venv/` existente continúa presente e ignorado.

- [ ] **Step 3: Verificar los puntos de partida**

```powershell
git -C backend merge-base --is-ancestor 434ec96 HEAD
git -C frontend-web merge-base --is-ancestor 1772d24 HEAD
git -C IA-Correcci-n-Contextual merge-base --is-ancestor 554370e HEAD
git -C keyboard-nlp merge-base --is-ancestor ef1bea6 HEAD
```

Expected: los cuatro comandos terminan con código `0`.

---

### Task 2: Preparar la IA real y reconstruir el checkpoint T5

**Files:**
- Modify: `IA-Correcci-n-Contextual/scripts/merge_grammar_lora.py`
- Use: `IA-Correcci-n-Contextual/train_grammar_lora.py`
- Generate ignored: `IA-Correcci-n-Contextual/.venv/`
- Generate ignored: `IA-Correcci-n-Contextual/models/grammar_lora/`
- Generate ignored: `IA-Correcci-n-Contextual/models/t5_correction/`
- Test: `IA-Correcci-n-Contextual/test_lora_guards.py`
- Test: `IA-Correcci-n-Contextual/test_train_gating.py`

**Interfaces:**
- Consumes: Python 3.11, RTX 5070, driver CUDA 13.3, modelos Hugging Face y `data/training_pairs_clean.csv`.
- Produces: Flask en `http://127.0.0.1:5000`, `POST /interno/corregir` y `POST /interno/feedback`.

- [ ] **Step 1: Ejecutar las pruebas ligeras de la rama antes del cambio**

```powershell
cd IA-Correcci-n-Contextual
py -3.11 test_lora_guards.py
py -3.11 test_train_gating.py
```

Expected: ambas terminan con todos los checks `OK`.

- [ ] **Step 2: Corregir la ruta predeterminada del adaptador**

En `scripts/merge_grammar_lora.py`, reemplazar:

```python
ADAPTER = os.environ.get("ADAPTER", "models/grammar_lora_v2")
```

por:

```python
ADAPTER = os.environ.get("ADAPTER", "models/grammar_lora")
```

Esto alinea la fusión con `OUT_DIR` de `train_grammar_lora.py` sin introducir otra opción local.

- [ ] **Step 3: Validar y comprometer el arreglo reproducible**

```powershell
py -3.11 -m compileall scripts/merge_grammar_lora.py train_grammar_lora.py
git add scripts/merge_grammar_lora.py
git commit -m "fix: alinear ruta del adaptador gramatical"
```

Expected: compilación exitosa y un commit que modifica una sola línea funcional.

- [ ] **Step 4: Crear el entorno Python e instalar CUDA antes del resto**

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install torch --index-url https://download.pytorch.org/whl/cu130
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Expected: `torch` permanece como build CUDA; `requirements.txt` no lo reemplaza porque no declara `torch`.

- [ ] **Step 5: Probar CUDA con una operación real**

```powershell
.\.venv\Scripts\python.exe -c "import torch; assert torch.cuda.is_available(); print(torch.__version__, torch.version.cuda, torch.cuda.get_device_name(0)); print((torch.ones(1, device='cuda') + 1).item())"
```

Expected: imprime `NVIDIA GeForce RTX 5070` y `2.0` sin advertencia de arquitectura no soportada.

- [ ] **Step 6: Entrenar y fusionar el modelo solo si no existe un checkpoint válido**

```powershell
if (-not (Test-Path .\models\t5_correction\config.json)) {
    $env:OUT_DIR = 'models/grammar_lora'
    .\.venv\Scripts\python.exe train_grammar_lora.py
    Remove-Item Env:OUT_DIR
    .\.venv\Scripts\python.exe scripts\merge_grammar_lora.py
}
```

Expected: `models/t5_correction/config.json`, tokenizer y pesos `safetensors` existen; el sanity check no produce texto vacío ni repetitivo.

- [ ] **Step 7: Evaluar la línea base de reglas y BETO**

```powershell
.\.venv\Scripts\python.exe evaluate.py --dataset data/eval_gold.csv --out reports/local-eval.json
```

Expected: crea `reports/local-eval.json`; registrar WER, exactitud y duración frente a la línea base documentada de reglas+BETO (30/38).

Esta evaluación en proceso no carga T5; la evaluación HTTP del pipeline completo se ejecuta en Task 7 cuando Flask está levantado.

---

### Task 3: Exponer salud real del backend y configurar PostgreSQL/IA local

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/com/mvp/backend/config/HealthEndpointTests.java`
- Generate ignored: `backend/.env`

**Interfaces:**
- Consumes: PostgreSQL `jdbc:postgresql://localhost:5432/florisboard` y Flask `http://localhost:5000`.
- Produces: API `http://localhost:8080/api/v1` y salud pública `GET /actuator/health`.

- [ ] **Step 1: Escribir la prueba fallida del endpoint que usa el teclado**

Crear `src/test/java/com/mvp/backend/config/HealthEndpointTests.java`:

```java
package com.mvp.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class HealthEndpointTests {
    @Autowired MockMvc mvc;

    @Test
    void exposesHealthForTheKeyboardConnectivityCheck() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 2: Ejecutar la prueba y confirmar el fallo**

```powershell
.\mvnw.cmd "-Dtest=HealthEndpointTests" test
```

Expected: `FAIL`, porque Actuator todavía no registra `/actuator/health`.

- [ ] **Step 3: Añadir la dependencia mínima**

Añadir dentro de `<dependencies>` de `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 4: Ejecutar la prueba específica y luego toda la suite**

```powershell
.\mvnw.cmd "-Dtest=HealthEndpointTests" test
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS` en ambos comandos.

- [ ] **Step 5: Comprometer Actuator y su prueba**

```powershell
git add pom.xml src/test/java/com/mvp/backend/config/HealthEndpointTests.java
git commit -m "fix: exponer salud para el teclado"
```

- [ ] **Step 6: Crear la configuración local ignorada**

Crear `backend/.env` sin versionarlo:

```properties
DB_URL=jdbc:postgresql://localhost:5432/florisboard
DB_USERNAME=postgres
DB_PASSWORD=root
JWT_ISSUER=florisboard-backend
JWT_TOKEN_TTL=PT5H
JWT_SECRET=local-only-secret-with-at-least-thirty-two-bytes
PERSONAL_DATA_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
CORS_ALLOWED_ORIGINS=http://localhost:5173
ALLOW_INSECURE_DEFAULTS=true
AI_MODE=http
AI_BASE_URL=http://localhost:5000
AI_CORRECTION_PATH=/interno/corregir
AI_FEEDBACK_PATH=/interno/feedback
```

Expected: `git status --short` no lista `.env`.

---

### Task 4: Preparar el portal Vue para el backend local

**Files:**
- Generate ignored: `frontend-web/.env.local`

**Interfaces:**
- Consumes: backend `http://localhost:8080/api/v1`.
- Produces: portal docente `http://localhost:5173`.

- [ ] **Step 1: Crear el override local sin cambiar producción**

Crear `frontend-web/.env.local`:

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

- [ ] **Step 2: Verificar que Vite lo ignora en Git y lo incorpora al build local**

```powershell
git status --short
npm install
npm run build
```

Expected: `.env.local` no aparece en Git y `vite build` termina sin errores.

- [ ] **Step 3: Ejecutar validación estática sin reescribir archivos**

```powershell
npx oxlint .
npx eslint .
```

Expected: ambos comandos terminan con código `0`; no usar los scripts `lint:*` porque incluyen `--fix`.

---

### Task 5: Crear un build Android debug conectado por USB

**Files:**
- Modify: `keyboard-nlp/app/build.gradle.kts`
- Create: `keyboard-nlp/app/src/debug/AndroidManifest.xml`
- Test: `keyboard-nlp/app/src/test/kotlin/dev/patrickgold/florisboard/education/CorrectionSessionResponseTest.kt`

**Interfaces:**
- Consumes: `http://127.0.0.1:8080/api/v1` dentro del teléfono, reenviado por ADB.
- Produces: `keyboard-nlp/app/build/outputs/apk/debug/app-debug.apk` con package `com.mvptesis.keyboard.debug`.

- [ ] **Step 1: Añadir la URL únicamente al build debug**

Dentro de `buildTypes { named("debug") { ... } }` en `app/build.gradle.kts`, añadir:

```kotlin
buildConfigField(
    "String",
    "EDUCATION_BACKEND_BASE_URL",
    "\"http://127.0.0.1:8080/api/v1\"",
)
```

El valor de `defaultConfig` con Cloud Run permanece sin cambios para `release`.

- [ ] **Step 2: Permitir HTTP solo en el manifest debug**

Crear `app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>
```

- [ ] **Step 3: Ejecutar pruebas y compilar el APK**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` y existe `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4: Inspeccionar el manifest fusionado**

```powershell
rg -n "usesCleartextTraffic.*true" app\build\intermediates\merged_manifests\debug
```

Expected: una coincidencia en el manifest debug fusionado.

- [ ] **Step 5: Comprometer solo la configuración debug**

```powershell
git add app/build.gradle.kts app/src/debug/AndroidManifest.xml
git commit -m "feat: conectar build debug al backend local"
```

---

### Task 6: Añadir una operación local única y segura

**Files:**
- Create: `local.ps1`
- Generate ignored/unversioned: `.local/processes.json`
- Generate ignored/unversioned: `.local/logs/*.out.log`
- Generate ignored/unversioned: `.local/logs/*.err.log`

**Interfaces:**
- Consumes: los cuatro repositorios preparados, Docker Desktop y ADB.
- Produces: acciones `Start`, `Check`, `Stop`, `Seed` e `InstallKeyboard`.

- [ ] **Step 1: Crear el script raíz con rutas exactas y sin borrados**

Crear `TESIS/local.ps1` con esta estructura completa:

```powershell
param(
    [ValidateSet('Start','Check','Stop','Seed','InstallKeyboard')]
    [string]$Action = 'Check'
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$StateDir = Join-Path $Root '.local'
$LogDir = Join-Path $StateDir 'logs'
$StateFile = Join-Path $StateDir 'processes.json'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$IaPython = Join-Path $Root 'IA-Correcci-n-Contextual\.venv\Scripts\python.exe'
$Apk = Join-Path $Root 'keyboard-nlp\app\build\outputs\apk\debug\app-debug.apk'

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Wait-Http([string]$Url, [int]$Seconds = 180) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 $Url | Out-Null; return }
        catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    throw "No respondió: $Url"
}

function Wait-Ai([int]$Seconds = 600) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $body = @{ originalText='prueba local'; studentId='local-health' } | ConvertTo-Json
    do {
        try {
            $response = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:5000/interno/corregir' `
                -ContentType 'application/json' -Body $body -TimeoutSec 90
            if ($response.correctedText) { return }
        } catch { Start-Sleep -Seconds 3 }
    } while ((Get-Date) -lt $deadline)
    throw 'La IA no terminó de cargar en 600 segundos.'
}

function Start-Logged([string]$Name, [string]$File, [string[]]$Arguments, [string]$WorkingDirectory) {
    $out = Join-Path $LogDir "$Name.out.log"
    $err = Join-Path $LogDir "$Name.err.log"
    $process = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    return [pscustomobject]@{ Id=$process.Id; Name=$Name; Started=$process.StartTime.ToString('O') }
}

function Start-All {
    docker info | Out-Null
    docker inspect tesis-postgres *> $null
    if ($LASTEXITCODE -ne 0) {
        docker run --name tesis-postgres -e POSTGRES_DB=florisboard -e POSTGRES_USER=postgres `
            -e POSTGRES_PASSWORD=root -p 5432:5432 -v tesis-postgres-data:/var/lib/postgresql/data `
            -d postgres:16-alpine | Out-Null
    } else { docker start tesis-postgres | Out-Null }

    $processes = @()
    $env:PORT='5000'; $env:ROLE='all'; $env:ENABLE_USER_LORA='false'
    $processes += Start-Logged 'ia' $IaPython @('main.py') (Join-Path $Root 'IA-Correcci-n-Contextual')
    Remove-Item Env:PORT,Env:ROLE,Env:ENABLE_USER_LORA
    Wait-Ai

    $processes += Start-Logged 'backend' (Join-Path $Root 'backend\mvnw.cmd') @('spring-boot:run') (Join-Path $Root 'backend')
    $processes += Start-Logged 'frontend' 'npm.cmd' @('run','dev','--','--host','127.0.0.1') (Join-Path $Root 'frontend-web')
    $processes | ConvertTo-Json | Set-Content -Encoding UTF8 $StateFile

    Wait-Http 'http://127.0.0.1:8080/actuator/health'
    Wait-Http 'http://127.0.0.1:5173'
    & $Adb reverse tcp:8080 tcp:8080
}

function Check-All {
    $cuda = & $IaPython -c "import torch; print(torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')"
    if ($cuda -notmatch '^True') { throw "CUDA no disponible: $cuda" }
    Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' | ConvertTo-Json
    Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:5173' | Select-Object StatusCode
    & $Adb devices -l
    & $Adb reverse --list
}

function Stop-All {
    if (Test-Path $StateFile) {
        @(Get-Content -Raw $StateFile | ConvertFrom-Json) | ForEach-Object {
            $process = Get-Process -Id $_.Id -ErrorAction SilentlyContinue
            if ($process -and $process.StartTime.ToString('O') -eq $_.Started) {
                taskkill.exe /PID $_.Id /T /F | Out-Null
            }
        }
        Remove-Item -LiteralPath $StateFile
    }
    & $Adb reverse --remove tcp:8080
    docker stop tesis-postgres | Out-Null
}

switch ($Action) {
    'Start' { Start-All; Check-All }
    'Check' { Check-All }
    'Stop' { Stop-All }
    'Seed' { Push-Location (Join-Path $Root 'backend'); try { .\scripts\seed-demo.ps1 } finally { Pop-Location } }
    'InstallKeyboard' {
        if (-not (Test-Path $Apk)) { throw "APK no encontrado: $Apk" }
        & $Adb reverse tcp:8080 tcp:8080
        & $Adb install -r $Apk
        & $Adb shell ime enable com.mvptesis.keyboard.debug/dev.patrickgold.florisboard.FlorisImeService
        & $Adb shell ime set com.mvptesis.keyboard.debug/dev.patrickgold.florisboard.FlorisImeService
    }
}
```

- [ ] **Step 2: Validar sintaxis sin iniciar procesos**

```powershell
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\local.ps1), [ref]$null, [ref]$errors) | Out-Null
if ($errors.Count) { $errors | Format-List; exit 1 }
```

Expected: ningún error de parseo.

- [ ] **Step 3: Probar un ciclo completo de procesos**

```powershell
.\local.ps1 Start
.\local.ps1 Check
.\local.ps1 Stop
```

Expected: los tres servicios responden, ADB muestra el OnePlus y al detener no quedan los PID registrados; el contenedor queda detenido y el volumen permanece.

---

### Task 7: Sembrar datos y verificar el flujo extremo a extremo

**Files:**
- Use: `backend/scripts/seed-demo.ps1`
- Use: `local.ps1`
- Inspect: `.local/logs/*.log`

**Interfaces:**
- Consumes: todos los servicios iniciados y OnePlus autorizado por ADB.
- Produces: evidencia de login, corrección real, feedback, portal y teclado funcionales.

- [ ] **Step 1: Iniciar, sembrar y comprobar servicios**

```powershell
.\local.ps1 Start
.\local.ps1 Seed
.\local.ps1 Check
```

Expected: migraciones Flyway aplicadas, docente y 15 estudiantes creados.

- [ ] **Step 2: Probar el contrato real backend→IA con una cuenta demo**

```powershell
$login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/auth/students/login' `
    -ContentType 'application/json' -Body '{"username":"student_001","password":"1234"}'
$headers = @{ Authorization = "Bearer $($login.token)" }
$session = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/corrections/process' `
    -Headers $headers -ContentType 'application/json' -Body '{"texto_original":"los niños del colegio está cansados"}'
if (-not $session.id_sesion -or -not $session.suggestions.Count) { throw 'Respuesta de corrección incompleta' }
$feedback = @{ sugerencia_elegida=$session.suggestions[0]; acepto_correccion=$true } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "http://localhost:8080/api/v1/corrections/sessions/$($session.id_sesion)/feedback" `
    -Headers $headers -ContentType 'application/json' -Body $feedback
```

Expected: login `STUDENT`, sesión creada por la IA real y feedback aceptado con HTTP 200.

- [ ] **Step 3: Medir el pipeline completo por HTTP**

```powershell
Push-Location .\IA-Correcci-n-Contextual
try {
    .\.venv\Scripts\python.exe evaluate.py --source http --url http://127.0.0.1:5000 `
        --dataset data/eval_gold.csv --out reports/local-full-eval.json
} finally { Pop-Location }
```

Expected: `reports/local-full-eval.json` mide el pipeline Flask completo, incluido T5; registrar WER y exactitud frente al 37/38 documentado.

- [ ] **Step 4: Verificar el portal docente**

Abrir `http://localhost:5173`, iniciar sesión con `sofia.garcia@colegio.edu.pe / DemoTeacher123` y abrir el detalle de `student_001`.

Expected: se muestran estudiantes y métricas; la petición creada en el paso anterior aparece en los indicadores correspondientes.

- [ ] **Step 5: Instalar y seleccionar el teclado**

```powershell
.\local.ps1 InstallKeyboard
```

Expected: `adb install` devuelve `Success`; `adb shell ime list -s` incluye `com.mvptesis.keyboard.debug` y el teclado queda seleccionado o Android solicita confirmación visible.

- [ ] **Step 6: Probar la experiencia en el OnePlus**

En el teclado, iniciar sesión con `student_001 / 1234`, escribir una oración con error, solicitar corrección, aceptar una sugerencia y confirmar que el texto se reemplaza.

Expected: no hay error de red; la sugerencia proviene del recorrido OnePlus → Spring Boot → Flask → BETO/T5 y el feedback vuelve al backend.

- [ ] **Step 7: Comprobar persistencia y dejar el entorno utilizable**

```powershell
.\local.ps1 Stop
.\local.ps1 Start
.\local.ps1 Check
```

Expected: los modelos no se reentrenan, PostgreSQL conserva los datos demo y el teclado vuelve a conectar tras restaurar `adb reverse`.

- [ ] **Step 8: Revisar cambios y registrar resultados**

```powershell
@('backend','frontend-web','IA-Correcci-n-Contextual','keyboard-nlp') | ForEach-Object {
    git -C $_ status --short --branch
}
```

Expected: solo quedan cambios intencionales ya comprometidos y archivos locales ignorados. Documentar cualquier prueba previa fallida, WER medido y pasos manuales restantes; no subir secretos, modelos ni datos runtime.
