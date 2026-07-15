package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AslJsonataEvaluatorConcurrencyTest {

    @Test
    @Timeout(20)
    void evaluatesChoiceConditionsConcurrentlyWithoutTransientFailures()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AslJsonataEvaluator evaluator =
                new AslJsonataEvaluator(objectMapper, 2000, 100);
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    var input = objectMapper.createObjectNode()
                            .put("approved", true);
                    var context = new StateExecutionContext(
                            input,
                            objectMapper.createObjectNode(),
                            objectMapper.createObjectNode()
                    );
                    return evaluator.evaluate(
                            objectMapper.getNodeFactory().textNode(
                                    "{% $states.input.approved = true %}"
                            ),
                            context
                    ).booleanValue();
                }));
            }

            ready.await();
            start.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
