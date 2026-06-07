# Contexto del Proyecto — Teclado Adaptativo

## ¿Qué es esto?

Un teclado Android para estudiantes de primaria (6–12 años) con dislexia y
disgrafía. El estudiante escribe normalmente en su celular y cuando quiere
ayuda presiona un botón. El sistema le muestra una corrección contextual en
español y él decide si la acepta o la ignora. Nada es automático, todo es
manual para no interrumpir al estudiante mientras escribe.

El problema que resuelve es que no existen herramientas de corrección
contextual en español para esta condición. Todo lo que existe está en inglés
y no considera las particularidades del español, como palabras que suenan
igual pero se escriben diferente (tubo/tuvo, casa/caza).

---

## Los dos usuarios

**Estudiante**: usa el teclado en su celular Android. Escribe en cualquier
aplicación (WhatsApp, notas, evaluaciones) y puede pedir corrección cuando
quiera. No tiene nombre real en el sistema, solo un alias como
`student_a1b2c3d4` que le entrega su profesor.

**Profesor**: accede desde un navegador web a un dashboard donde puede ver
las métricas de escritura de sus alumnos. No ve el contenido de los textos,
solo estadísticas: cuántos errores comete cada alumno, de qué tipo son y
cómo evoluciona con el tiempo.

---

## Qué puede hacer el estudiante

- Escribir en cualquier app del celular con el teclado instalado.
- Presionar el botón IA para pedir corrección del texto que escribió.
- Ver la corrección sugerida y una explicación breve del error.
- Aceptar la corrección (el texto se reemplaza) o ignorarla (no pasa nada).
- Configurar el teclado según sus necesidades: fuente especial para dislexia
  (OpenDyslexic), alto contraste, teclas más grandes, vibración al escribir.
- Elegir si comparte su progreso con el docente o prefiere privacidad total.
- Usar el teclado sin internet con un motor más básico instalado en el
  dispositivo.

---

## Qué puede hacer el profesor

- Ver un resumen mensual de cada alumno.
- Saber qué porcentaje de correcciones acepta cada alumno.
- Ver qué tipo de errores comete más: ortográficos, fonológicos o semánticos.
- Ver las 10 palabras que más le cuesta escribir bien a cada alumno.
- Crear cuentas para sus alumnos desde el portal web y entregarles un alias
  y contraseña temporal.

---

## Cómo funciona por dentro

El teclado está construido sobre FlorisBoard, un teclado Android de código
abierto. Le agregamos el botón de corrección y toda la lógica de IA.

Cuando el estudiante pide corrección, el texto va al backend en Java/Spring
Boot, que lo envía a un servidor de IA con el modelo BETO, que es BERT
entrenado específicamente en español. Ese modelo entiende el contexto de la
oración y puede detectar errores que un corrector ortográfico normal no
detectaría.

Cuando no hay internet, el teclado usa un modelo más pequeño instalado
directamente en el celular que hace correcciones básicas sin necesidad de
conexión.

Todo el sistema está desplegado en Google Cloud. El portal del profesor
es una aplicación web en Angular.

---

## Privacidad

La privacidad de los menores es una prioridad de diseño. El teclado nunca
conoce el nombre real del estudiante. El nombre real solo existe encriptado
en la base de datos y solo el docente puede verlo.

Los textos que escribe el alumno sí se almacenan en el backend (texto
original y corregido de cada sesión) para sostener su historial personal de
correcciones. El docente no tiene acceso a esos textos: solo ve métricas
agregadas. El consentimiento de uso de datos se gestiona fuera del sistema,
directamente con los padres de los alumnos, por lo que no forma parte del
backend.

---

## Estado actual

El backend y el portal web del docente están en desarrollo avanzado. El
modelo BETO está siendo entrenado por un compañero del equipo. La app
móvil del teclado es la siguiente prioridad de desarrollo.

---

## Documentos técnicos de referencia

- `arquitectura-integracion.md` — documento maestro: arquitectura, contratos
  (backend↔IA, teclado, portal web), modelo de datos, KPIs y decisiones.
- `keyboard-app-integration-guide.md` — cómo conecta el teclado con el backend.
