package com.elecsarthi.repository;

import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.enums.ElectionTypeCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ElectionTypeRepository extends JpaRepository<ElectionType, Short> {
    Optional<ElectionType> findByCode(ElectionTypeCode code);
}
