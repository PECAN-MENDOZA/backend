# Despliegue del backend a Cloud Run desde el codigo fuente (buildpacks, sin Docker).
# Requiere: gcloud autenticado y el proyecto correcto activo.
# Los secretos (DB_PASSWORD, JWT_SECRET, PERSONAL_DATA_KEY_BASE64) viven en Secret Manager.
# Uso:  .\scripts\deploy-cloudrun.ps1

$ErrorActionPreference = "Stop"

$Region   = "us-central1"
$Service  = "backend"
$Conn     = "project-0e0647d7-dc4f-4c52-82f:us-central1:pecan-mendoza-postgres"
$DbUrl    = "jdbc:postgresql:///teclado-mvp?cloudSqlInstance=$Conn&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

# IA: el backend usa la IA real (T5+BETO) desplegada en la VM GCE servidor-tesis (IP estatica).
# Para volver al modo simulado, cambiar AI_MODE=http por AI_MODE=stub (AI_BASE_URL se ignora en stub).
# Rollback a la IA vieja de Cloud Run (solo BETO): https://api-correccion-887695300669.us-central1.run.app
$AiBaseUrl = "http://35.224.215.77"

# CORS: cambiar "*" por el dominio del portal Angular cuando este desplegado.
$EnvVars  = "^@^DB_URL=$DbUrl@DB_USERNAME=postgres@DB_SCHEMA=public@AI_MODE=http@AI_BASE_URL=$AiBaseUrl@ALLOW_INSECURE_DEFAULTS=false@CORS_ALLOWED_ORIGINS=*@JWT_ISSUER=florisboard-backend"
$Secrets  = "DB_PASSWORD=florisboard-db-password:latest,JWT_SECRET=florisboard-jwt-secret:latest,PERSONAL_DATA_KEY_BASE64=florisboard-encryption-key:latest"

gcloud run deploy $Service `
  --source=. `
  --region=$Region `
  --platform=managed `
  --allow-unauthenticated `
  --memory=1Gi `
  --cpu=1 `
  --timeout=300 `
  --port=8080 `
  --add-cloudsql-instances=$Conn `
  --set-env-vars=$EnvVars `
  --set-secrets=$Secrets `
  --quiet
