package com.mvp.backend.research.application.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mvp.backend.shared.exception.BusinessException;

class AnnotationCsvTests {

    // ------------------------------------------------------------------ escape

    @Test
    void formulaTriggersGetALeadingSpace() {
        assertThat(AnnotationCsv.escape("=1+1")).isEqualTo(" =1+1");
        assertThat(AnnotationCsv.escape("+34")).isEqualTo(" +34");
        assertThat(AnnotationCsv.escape("-hola")).isEqualTo(" -hola");
        assertThat(AnnotationCsv.escape("@x")).isEqualTo(" @x");
        assertThat(AnnotationCsv.escape("\t=cmd")).isEqualTo(" \t=cmd");
        assertThat(AnnotationCsv.escape("  =cmd")).isEqualTo("   =cmd");
        assertThat(AnnotationCsv.escape("-x")).isEqualTo(" -x");
        // Los saltos siguen entrecomillados; el espacio va dentro de las comillas.
        assertThat(AnnotationCsv.escape("\r=cmd")).isEqualTo("\" \r=cmd\"");
        assertThat(AnnotationCsv.escape("=HYPERLINK(\"x\")")).isEqualTo("\" =HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    void ordinaryCellsAreUnchanged() {
        assertThat(AnnotationCsv.escape("Hola mundo")).isEqualTo("Hola mundo");
        assertThat(AnnotationCsv.escape("T-ABCD2345")).isEqualTo("T-ABCD2345");
        assertThat(AnnotationCsv.escape("Hola, mundo")).isEqualTo("\"Hola, mundo\"");
        assertThat(AnnotationCsv.escape("texto sin\nayuda")).isEqualTo("\"texto sin\nayuda\"");
        assertThat(AnnotationCsv.escape("dijo \"si\"")).isEqualTo("\"dijo \"\"si\"\"\"");
        assertThat(AnnotationCsv.escape("12")).isEqualTo("12");
        assertThat(AnnotationCsv.escape("")).isEmpty();
        assertThat(AnnotationCsv.escape(null)).isEmpty();
    }

    @Test
    void encodeAppliesTheGuardToTextCellsOnly() {
        String csv = AnnotationCsv.encode(List.of("sample_code", "text", "score"),
                List.of(List.of("T-ABCD2345", "=1+1", ""), List.of("T-ABCD2346", "normal", "")));

        assertThat(csv).isEqualTo("sample_code,text,score\nT-ABCD2345, =1+1,\nT-ABCD2346,normal,\n");
        // Lo exportado se relee tal cual: el espacio protector queda en la celda de texto.
        assertThat(AnnotationCsv.parse(csv).get(1)).containsExactly("T-ABCD2345", " =1+1", "");
    }

    // ------------------------------------------------------------------- parse

    @Test
    void quotedFieldKeepsAnEmbeddedCrLf() {
        List<List<String>> rows = AnnotationCsv.parse("sample_code,text,score\r\nT-A,\"linea uno\r\nlinea dos\",2\r\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("T-A", "linea uno\r\nlinea dos", "2");
    }

    @Test
    void headerOnlyFileParsesToASingleRow() {
        assertThat(AnnotationCsv.parse("sample_code,score\n")).containsExactly(List.of("sample_code", "score"));
        assertThat(AnnotationCsv.parse("sample_code,score")).containsExactly(List.of("sample_code", "score"));
    }

    @Test
    void decodeRejectsInvalidUtf8AndBlankFiles() {
        assertThatThrownBy(() -> AnnotationCsv.decode(new byte[] {(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> AnnotationCsv.decode("  \n".getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("empty");
        assertThat(AnnotationCsv.decode("\uFEFFa,b\n".getBytes(UTF_8))).isEqualTo("a,b\n");
    }
}
