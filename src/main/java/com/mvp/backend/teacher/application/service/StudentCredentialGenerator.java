package com.mvp.backend.teacher.application.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Alias y PIN de alumno. El alias es del tipo "tigre-07": memorable y facil de teclear para
 * ninos de 6-12, sin contener el nombre real (pseudonimia). El PIN es de 4 digitos.
 */
@Component
public class StudentCredentialGenerator {

    private static final List<String> ALIAS_WORDS = List.of(
            "tigre", "leon", "panda", "koala", "delfin", "ballena", "tortuga", "conejo",
            "zorro", "lobo", "gato", "perro", "caballo", "abeja", "mariposa", "buho",
            "aguila", "pinguino", "foca", "nutria", "ardilla", "erizo", "rana", "pez",
            "estrella", "cometa", "planeta", "luna", "sol", "nube", "rayo", "arcoiris",
            "rio", "lago", "montana", "bosque", "flor", "arbol", "hoja", "semilla",
            "manzana", "platano", "fresa", "uva", "limon", "cereza", "melon", "kiwi",
            "barco", "cohete", "tren", "globo", "faro", "puente", "castillo", "brujula");

    private static final int PIN_BOUND = 10000;

    private final StudentRepository studentRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public StudentCredentialGenerator(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    public String newAlias() {
        for (int attempt = 0; attempt < 200; attempt++) {
            String alias = randomWord() + "-" + String.format(Locale.ROOT, "%02d", 1 + secureRandom.nextInt(99));
            if (!studentRepository.existsByUsername(alias)) {
                return alias;
            }
        }
        String alias;
        do {
            alias = randomWord() + "-" + (1000 + secureRandom.nextInt(9000));
        } while (studentRepository.existsByUsername(alias));
        return alias;
    }

    public String newPin() {
        return String.format(Locale.ROOT, "%04d", secureRandom.nextInt(PIN_BOUND));
    }

    private String randomWord() {
        return ALIAS_WORDS.get(secureRandom.nextInt(ALIAS_WORDS.size()));
    }
}
