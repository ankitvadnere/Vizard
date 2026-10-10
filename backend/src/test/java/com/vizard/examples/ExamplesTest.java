package com.vizard.examples;

import com.vizard.api.dto.Example;
import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.execution.ExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every program in the Example menu compiles, traces completely, and is recognised correctly. */
@SpringBootTest
class ExamplesTest {

    @Autowired
    private ExampleCatalog catalog;

    @Autowired
    private ExecutionService service;

    @Test
    void catalogHasAllGroups() {
        assertThat(catalog.all()).extracting(Example::group)
                .contains("Basics", "Searching", "Sorting", "Data structures");
        assertThat(catalog.all()).allMatch(e -> e.code().contains("public static void main"));
    }

    @Test
    void everyExampleTracesAndIsRecognised() {
        for (Example example : catalog.all()) {
            TraceResponse r = service.trace(example.code(), example.stdin() == null ? "" : example.stdin());
            ExecutionStatus expectedStatus = example.id().equals("runtime-error")
                    ? ExecutionStatus.RUNTIME_ERROR : ExecutionStatus.SUCCESS;

            assertThat(r.execution().status()).as(example.id()).isEqualTo(expectedStatus);
            assertThat(r.truncated()).as(example.id() + " fits in the step limit").isFalse();
            assertThat(r.steps()).as(example.id()).isNotEmpty();

            List<String> found = r.analysis().algorithms().stream().map(AlgorithmMatch::id).toList();
            assertThat(found).as(example.id())
                    .isEqualTo(example.algorithms());
        }
    }
}
