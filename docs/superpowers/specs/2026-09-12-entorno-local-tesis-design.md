# Entorno local integrado de la tesis

**Fecha:** 2026-09-12

## Objetivo

Dejar operativo en Windows el flujo conectado completo de la tesis: teclado Android y portal Vue contra el backend Spring Boot, con persistencia PostgreSQL y correcciones reales ejecutadas por BETO/T5 en la GPU local. El OnePlus 6 conectado por USB será el dispositivo de prueba.

## Alcance

Se usarán las ramas remotas funcionales más recientes comprobadas el 12 de septiembre de 2026:

- `backend`: `origin/develop` (`434ec96`).
- `frontend-web`: `origin/develop` (`1772d24`).
- `keyboard-nlp`: `origin/mvp` (`ef1bea6`).
- `IA-Correcci-n-Contextual`: `origin/v6-fixlora` (`554370e`).

El modo sin conexión con FLAN-T5-small dentro del teléfono queda fuera del alcance. Las URL y el comportamiento de los builds de producción deben permanecer intactos.

## Arquitectura local

```text
OnePlus 6 --HTTP/JWT vía adb reverse--+
                                      +--> Spring Boot :8080 --> PostgreSQL :5432
Portal Vue :5173 --HTTP/JWT-----------+           |
                                                  +--> Flask IA :5000 --> BETO + T5 (RTX 5070)
```

Spring Boot es el único punto de acceso a datos y a la IA. El teclado y el portal nunca acceden directamente a PostgreSQL ni a Flask. La IA se ejecuta como un solo proceso para mantener una única copia de los modelos en los 12 GB de VRAM disponibles.

## Componentes

### PostgreSQL

PostgreSQL se ejecutará en Docker con un volumen persistente, una base `florisboard` y credenciales exclusivas de desarrollo local. Flyway, desde el backend, será responsable de crear y validar el esquema. Los datos de demostración se cargarán mediante el mecanismo ya incluido en `backend/develop`.

### Servicio de IA

La IA se ejecutará de forma nativa en Windows dentro de un entorno virtual Python 3.11 nuevo e ignorado por Git. Se instalará una distribución oficial de PyTorch con CUDA compatible con la RTX 5070 y se comprobarán `torch.cuda.is_available()` y el nombre del dispositivo antes de cargar modelos.

La rama no contiene los pesos entrenados. La preparación hará lo siguiente:

1. Descargar `dccuchile/bert-base-spanish-wwm-cased` y `vgaraujov/t5-base-spanish`.
2. Entrenar el adaptador LoRA gramatical con `data/training_pairs_clean.csv`.
3. Fusionar el adaptador en `models/t5_correction`.
4. Ejecutar la evaluación y una petición a `/interno/corregir`.

Se corregirá la discrepancia de rutas por la que el entrenamiento escribe `models/grammar_lora` y la fusión intenta leer `models/grammar_lora_v2`. El LoRA por alumno permanecerá desactivado por defecto; la rama documenta que degradaba las correcciones y no es necesario para demostrar el flujo conectado.

### Backend

El backend se ejecutará con Java 21 y Maven Wrapper. Su configuración local usará PostgreSQL local, `AI_MODE=http`, `AI_BASE_URL=http://localhost:5000` y los endpoints `/interno/corregir` y `/interno/feedback`.

Se añadirá Spring Boot Actuator porque el teclado comprueba `/actuator/health`, ruta que la configuración de seguridad ya permite pero que la rama no implementa al no incluir la dependencia. Los secretos locales serán valores de desarrollo no reutilizables en producción.

### Portal Vue

El portal conservará su configuración de producción y tendrá una configuración local ignorada por Git que apunte a `http://localhost:8080/api/v1`. Se usará el Node.js instalado, que satisface el rango declarado por el proyecto.

### Teclado Android

El build `debug` apuntará a `http://127.0.0.1:8080/api/v1`, mientras que `release` conservará la URL de Cloud Run. Solo el build de depuración permitirá tráfico HTTP sin cifrar.

Antes de probar se ejecutará:

```powershell
adb reverse tcp:8080 tcp:8080
```

De este modo el teléfono alcanza el backend mediante USB sin depender de la IP de la PC, reglas de firewall o que ambos dispositivos compartan Wi-Fi. El APK debug se instalará en el OnePlus y se habilitará como método de entrada; la selección final del teclado podrá requerir confirmación visible del usuario por seguridad de Android.

## Operación

La raíz `TESIS` contendrá scripts PowerShell pequeños con estas responsabilidades:

- Preparar dependencias, base de datos, modelos y configuraciones locales.
- Iniciar PostgreSQL, IA, backend y portal, guardando PID y logs por servicio.
- Comprobar CUDA, ADB y los endpoints de salud y corrección.
- Detener únicamente los procesos iniciados por esos scripts.

Los scripts serán idempotentes: una segunda ejecución reutilizará dependencias, pesos, base y artefactos válidos. No borrarán el `venv/` existente, bases, modelos ni cambios del usuario.

## Manejo de errores

- La preparación terminará con un mensaje específico si falta Internet, espacio, Docker, Java, Android SDK o CUDA.
- La IA no se marcará lista hasta que los modelos estén cargados y `/interno/corregir` responda.
- El backend no se iniciará como válido hasta conectar con PostgreSQL y exponer `/actuator/health`.
- Un fallo de IA deberá llegar al cliente como `502` mediante el manejo existente del backend.
- Los scripts de parada validarán los PID registrados y no terminarán procesos ajenos.
- Los logs no incluirán JWT, PIN, nombres reales ni textos completos escritos por estudiantes.

## Verificación

La entrega se considerará completa cuando se cumpla lo siguiente:

1. Las pruebas existentes de backend, frontend, IA y teclado pasan, salvo fallos previos que se documenten con evidencia.
2. PyTorch informa que usa la RTX 5070 y una inferencia real completa satisfactoriamente.
3. PostgreSQL arranca desde cero, Flyway aplica las migraciones y se cargan datos demo.
4. El portal abre localmente, permite iniciar sesión como docente y consulta datos del backend.
5. Una cuenta demo de estudiante inicia sesión desde el teclado instalado en el OnePlus.
6. Una oración enviada desde el teclado recorre Spring Boot y Flask, devuelve sugerencias y permite registrar aceptación o rechazo.
7. Reiniciar el entorno conserva la base de datos y los modelos preparados.

## Decisiones deliberadas

- Ejecución nativa de la IA en lugar del `docker-compose.yml` de producción: evita el PyTorch CPU fijado por error en el Dockerfile y tres réplicas innecesarias del modelo.
- PostgreSQL en Docker: ofrece persistencia reproducible sin añadir una instalación permanente al sistema.
- `adb reverse` en lugar de una IP LAN: reduce configuración local y mantiene la prueba limitada al dispositivo USB autorizado.
- Un único comando por etapa y pocas modificaciones específicas de entorno: la solución local debe ser fácil de inspeccionar y retirar.
