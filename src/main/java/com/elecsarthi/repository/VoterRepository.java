package com.elecsarthi.repository;

import com.elecsarthi.entity.Voter;
import com.elecsarthi.entity.VoterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoterRepository extends JpaRepository<Voter, VoterId> {
}
