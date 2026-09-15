package com.mvp.backend.research.application.dto;

/**
 * Acuerdo entre RATER_1 y RATER_2. {@code weightedKappa} es la kappa de Cohen con pesos lineales;
 * {@code exactAgreement} la proporcion de filas con el mismo valor. Ambos son {@code null} mientras
 * alguna ranura este incompleta; la kappa ademas es {@code null} cuando esta indefinida (sin varianza).
 */
public record AgreementSummary(
        boolean complete,
        Double weightedKappa,
        Double exactAgreement) {

    public static AgreementSummary incomplete() {
        return new AgreementSummary(false, null, null);
    }
}
