package com.mvp.backend.research.application.service;

import java.util.List;

import com.mvp.backend.research.application.dto.AgreementSummary;

/**
 * Kappa de Cohen con pesos lineales entre dos vectores de puntajes enteros sobre la misma escala
 * ordinal (0-2 semantica, o el rango observado de conteos ortograficos). Con pesos lineales
 * {@code d(a,b) = |a-b| / (k-1)}: {@code kappa = 1 - Do/De}, donde Do es el desacuerdo observado
 * medio y De el esperado por azar a partir de las marginales de cada evaluador. Indefinida (null)
 * cuando ambos vectores no tienen varianza (una sola categoria observada).
 */
final class InterRaterAgreement {

    private InterRaterAgreement() {
    }

    static AgreementSummary of(List<Integer> first, List<Integer> second) {
        if (first.size() != second.size() || first.isEmpty()) {
            throw new IllegalArgumentException("Both raters must score the same non-empty set of items");
        }
        int n = first.size();
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int exact = 0;
        for (int i = 0; i < n; i++) {
            int a = first.get(i);
            int b = second.get(i);
            lo = Math.min(lo, Math.min(a, b));
            hi = Math.max(hi, Math.max(a, b));
            if (a == b) {
                exact++;
            }
        }
        double exactAgreement = (double) exact / n;
        int categories = hi - lo + 1;
        if (categories == 1) {
            return new AgreementSummary(true, null, exactAgreement);
        }
        double[] marginalFirst = new double[categories];
        double[] marginalSecond = new double[categories];
        double observed = 0;
        for (int i = 0; i < n; i++) {
            int a = first.get(i) - lo;
            int b = second.get(i) - lo;
            marginalFirst[a]++;
            marginalSecond[b]++;
            observed += Math.abs(a - b);
        }
        observed /= (double) n * (categories - 1);
        double expected = 0;
        for (int a = 0; a < categories; a++) {
            for (int b = 0; b < categories; b++) {
                expected += (marginalFirst[a] / n) * (marginalSecond[b] / n) * Math.abs(a - b);
            }
        }
        expected /= categories - 1;
        if (expected == 0) {
            return new AgreementSummary(true, null, exactAgreement);
        }
        return new AgreementSummary(true, 1 - observed / expected, exactAgreement);
    }
}
