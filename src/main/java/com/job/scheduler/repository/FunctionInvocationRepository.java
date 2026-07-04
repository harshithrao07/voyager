package com.job.scheduler.repository;

import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FunctionInvocationRepository
        extends JpaRepository<FunctionInvocation, UUID> {
    List<FunctionInvocation> findByFunctionDefinitionOrderByStartedAtDesc(
            FunctionDefinition functionDefinition
    );
}
