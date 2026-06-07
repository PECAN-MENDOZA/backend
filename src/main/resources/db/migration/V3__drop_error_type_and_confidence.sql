-- El tipo de error y la confianza dejan de existir: la IA ya no clasifica errores
-- ni entrega un puntaje, y las palabras corregidas se derivan por diff
-- (ver docs/arquitectura-integracion.md, decisiones D2/D3).

DROP INDEX IF EXISTS idx_word_corrections_type;

ALTER TABLE word_corrections DROP COLUMN error_type;
ALTER TABLE word_corrections DROP COLUMN confidence;

ALTER TABLE correction_sessions DROP COLUMN confidence;
