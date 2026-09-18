package com.mvp.backend.sentencetest.domain.model;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manipulacion del JSON de {@code test_responses.auto_error_detail}: un objeto con el detalle del
 * alineador ({@code word_count}, {@code error_count}, {@code edits}) y un array {@code incidents}
 * con los codigos de incidencia registrados sobre la respuesta (p. ej. {@code MODEL_VERSION_CHANGED}).
 */
public final class AutoErrorDetail {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INCIDENTS = "incidents";
    private static final String EDITS = "edits";

    /** Una operacion del alineador: tipo (SUSTITUCION, OMISION, ...), palabra esperada y palabra escrita. */
    public record Edit(String type, String expected, String written) {
    }

    private AutoErrorDetail() {
    }

    /** Devuelve el detalle con {@code code} anadido a {@code incidents}; con detalle nulo crea {@code {"incidents":[code]}}. */
    public static String withIncident(String detail, String code) {
        ObjectNode root = parse(detail);
        incidentsOf(root).add(code);
        return MAPPER.writeValueAsString(root);
    }

    /** Codigos de incidencia presentes en el detalle (vacio si es nulo o no tiene el array). */
    public static List<String> incidents(String detail) {
        if (detail == null) {
            return List.of();
        }
        return incidentsOf(parse(detail)).valueStream().map(JsonNode::asString).toList();
    }

    /** Operaciones del alineador presentes en el detalle (vacio si es nulo o aun no se alineo la oracion). */
    public static List<Edit> edits(String detail) {
        if (detail == null) {
            return List.of();
        }
        JsonNode edits = parse(detail).get(EDITS);
        if (!(edits instanceof ArrayNode array)) {
            return List.of();
        }
        return array.valueStream()
                .map(node -> new Edit(text(node, "type"), text(node, "expected"), text(node, "written")))
                .toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Detalle del alineador mas las incidencias que ya tuviera la respuesta (no se pierden al terminar). */
    public static String merge(String alignmentDetail, String previousDetail) {
        List<String> previous = incidents(previousDetail);
        String result = alignmentDetail;
        for (String code : previous) {
            result = withIncident(result, code);
        }
        return result;
    }

    private static ObjectNode parse(String detail) {
        if (detail == null || detail.isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode node = MAPPER.readTree(detail);
        return node instanceof ObjectNode object ? object : MAPPER.createObjectNode();
    }

    private static ArrayNode incidentsOf(ObjectNode root) {
        JsonNode existing = root.get(INCIDENTS);
        return existing instanceof ArrayNode array ? array : root.putArray(INCIDENTS);
    }
}
