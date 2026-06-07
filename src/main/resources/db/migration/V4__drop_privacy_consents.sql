-- El consentimiento de privacidad se gestiona fuera del sistema (con los padres
-- de los alumnos), por lo que el modulo consent y su tabla salen del backend
-- (ver docs/arquitectura-integracion.md, decision D8).

DROP TABLE IF EXISTS privacy_consents;
