package com.mvp.backend.student.infrastructure.specification;

import org.springframework.data.jpa.domain.Specification;

import com.mvp.backend.student.application.dto.StudentSearchCriteria;
import com.mvp.backend.student.domain.model.Student;

public final class StudentSpecifications {

    private StudentSpecifications() {
    }

    public static Specification<Student> withCriteria(StudentSearchCriteria criteria) {
        return usernameContains(criteria.username()).and(institutionEquals(criteria.institution()));
    }

    private static Specification<Student> usernameContains(String username) {
        return (root, query, builder) -> username == null || username.isBlank()
                ? builder.conjunction()
                : builder.like(builder.lower(root.get("username")), "%" + username.toLowerCase() + "%");
    }

    private static Specification<Student> institutionEquals(String institution) {
        return (root, query, builder) -> institution == null || institution.isBlank()
                ? builder.conjunction()
                : builder.equal(builder.lower(root.get("institution")), institution.toLowerCase());
    }
}
