package com.mvp.backend.research.application.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mvp.backend.research.application.dto.AgreementSummary;

/**
 * Kappa de Cohen con pesos lineales entre dos vectores de puntajes enteros sobre la misma escala
 * ordinal (0-2 semantica, o el rango observado de conteos ortograficos). Con pesos lineales
 * {@code d(a,b) = |a-b| / (k-1)}, donde {@code k-1 = max - min} observado: {@code kappa = 1 - Do/De},
 * Do es el desacuerdo observado medio y De el esperado por azar a partir de las marginales de cada
 * evaluador. Se calcula solo sobre los valores observados (mapas valor -> frecuencia), nunca sobre
 * el rango completo, asi un conteo aislado enorme no cuesta memoria ni tiempo. Indefinida (null)
 * cuando no hay rango observado ({@code k-1 == 0}) o De es cero.
 */
final class InterRaterAgreement {

    private InterRaterAgreement() {
    }

    static AgreementSummary of(List<Integer> first, List<Integer> second) {
        if (first.size() != second.size() || first.isEmpty()) {
            throw new IllegalArgumentException("Both raters must score the same non-empty set of items");
        }
        int n = first.size();
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        int exact = 0;
        double observedDistance = 0;
        Map<Integer, Integer> countsFirst = new HashMap<>();
        Map<Integer, Integer> countsSecond = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int a = first.get(i);
            int b = second.get(i);
            lo = Math.min(lo, Math.min(a, b));
            hi = Math.max(hi, Math.max(a, b));
            if (a == b) {
                exact++;
            }
            observedDistance += Math.abs((long) a - b);
            countsFirst.merge(a, 1, Integer::sum);
            countsSecond.merge(b, 1, Integer::sum);
        }
        double exactAgreement = (double) exact / n;
        long range = hi - lo;
        if (range == 0) {
            return new AgreementSummary(true, null, exactAgreement);
        }
        double observed = observedDistance / ((double) n * range);
        double expectedDistance = 0;
        for (Map.Entry<Integer, Integer> a : countsFirst.entrySet()) {
            for (Map.Entry<Integer, Integer> b : countsSecond.entrySet()) {
                expectedDistance += ((double) a.getValue() / n) * ((double) b.getValue() / n)
                        * Math.abs((long) a.getKey() - b.getKey());
            }
        }
        double expected = expectedDistance / range;
        if (expected == 0) {
            return new AgreementSummary(true, null, exactAgreement);
        }
        return new AgreementSummary(true, 1 - observed / expected, exactAgreement);
    }
}
