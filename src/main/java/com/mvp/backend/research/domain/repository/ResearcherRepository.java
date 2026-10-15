package com.mvp.backend.research.domain.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.research.domain.model.Researcher;

public interface ResearcherRepository extends JpaRepository<Researcher, UUID> {

    Optional<Researcher> findByEmail(String email);

    // El correo no distingue mayusculas: login, alta de docentes y seeder.
    Optional<Researcher> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
