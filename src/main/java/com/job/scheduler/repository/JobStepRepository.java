package com.job.scheduler.repository;

import com.job.scheduler.entity.Job;
import com.job.scheduler.entity.JobStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobStepRepository extends JpaRepository<JobStep, UUID> {
    List<JobStep> findByJobAndEnabledTrueOrderByStepOrderAsc(Job job);

    List<JobStep> findByJobOrderByStepOrderAsc(Job job);

    Optional<JobStep> findFirstByJobOrderByStepOrderAsc(Job job);
}
