package com.vizard.execution;

import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.growth.GrowthResponse;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 10: real runs at growing sizes agree with the textbook growth. */
@SpringBootTest
class GrowthTest {

    @Autowired
    private ExecutionService service;

    private final ExampleCatalog examples = new ExampleCatalog();

    private GrowthResponse growth(String id) {
        return service.growth(examples.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code(), "");
    }

    @Test
    void bubbleSortIsQuadratic() {
        GrowthResponse r = growth("bubble-sort");
        assertThat(r.execution().status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.measured()).isEqualTo("the array arr");
        assertThat(r.fits().get(0).curve()).isEqualTo("n²");
        assertThat(r.slope()).isBetween(1.9, 2.1);
        // bubble sort with the early-exit flag on random data: exactly n(n−1)/2 comparisons per full pass set
        assertThat(r.points().size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void mergeSortIsNLogN() {
        assertThat(growth("merge-sort").fits().get(0).curve()).isEqualTo("n log n");
    }

    @Test
    void naiveFibonacciIsExponential() {
        GrowthResponse r = growth("fibonacci");
        assertThat(r.measured()).isEqualTo("the value of n");
        assertThat(r.fits().get(0).base()).isBetween(1.5, 1.75);
    }

    @Test
    void programsWithoutInputInTheCodeAreExplained() {
        GrowthResponse r = growth("input");
        assertThat(r.points()).isEmpty();
        assertThat(r.reason()).contains("keyboard");
    }

    @Test
    void compileErrorsComeBackAsSuch() {
        GrowthResponse r = service.growth("public class Main { public static void main(String[] a) { int x = ; } }", "");
        assertThat(r.execution().status()).isEqualTo(ExecutionStatus.COMPILATION_ERROR);
    }
}
