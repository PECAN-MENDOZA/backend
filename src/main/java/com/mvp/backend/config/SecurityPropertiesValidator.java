package com.mvp.backend.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Impide arrancar la aplicacion con los secretos de desarrollo por defecto.
 * En produccion los secretos deben provenir del entorno; para ello hay que
 * desactivar el modo permisivo con app.security.allow-insecure-defaults=false.
 */
@Component
public class SecurityPropertiesValidator implements InitializingBean {

    static final String DEFAULT_JWT_SECRET = "change-this-development-secret-before-production-123456";
    static final String DEFAULT_ENCRYPTION_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final AppSecurityProperties properties;

    public SecurityPropertiesValidator(AppSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.allowInsecureDefaults()) {
            return;
        }
        if (DEFAULT_JWT_SECRET.equals(properties.jwtSecret())
                || DEFAULT_ENCRYPTION_KEY_BASE64.equals(properties.encryptionKeyBase64())) {
            throw new IllegalStateException(
                    "Insecure default security secrets detected. Provide JWT_SECRET and "
                            + "PERSONAL_DATA_KEY_BASE64 from the environment, or set "
                            + "app.security.allow-insecure-defaults=true for local development.");
        }
    }
}
