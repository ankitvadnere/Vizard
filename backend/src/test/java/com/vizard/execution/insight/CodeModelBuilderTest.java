package com.vizard.execution.insight;

import com.github.javaparser.StaticJavaParser;
import com.vizard.execution.insight.CodeModel.ArrayAccessSite;
import com.vizard.execution.insight.CodeModel.ConditionSite;
import com.vizard.execution.insight.CodeModel.LoopSite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The AST → CodeModel step on its own (no Spring, no JVM launch). */
class CodeModelBuilderTest {

    private static final String BUBBLE = """
            public class Main {
                public static void main(String[] args) {
                    int[] arr = {5, 2, 8, 1};
                    for (int i = 0; i < arr.length; i++) {
                        for (int j = 0; j < arr.length - i - 1; j++) {
                            if (arr[j] > arr[j + 1]) {
                                int temp = arr[j];
                                arr[j] = arr[j + 1];
                                arr[j + 1] = temp;
                            }
                        }
                    }
                }
            }
            """;

    private final CodeModel model = CodeModelBuilder.build(StaticJavaParser.parse(BUBBLE));

    @Test
    void findsConditionsWithBodyRangesInsideTheBraces() {
        ConditionSite outer = model.conditions().get(4).get(0);
        assertThat(outer.kind()).isEqualTo("for");
        assertThat(outer.expression().text()).isEqualTo("i < arr.length");
        assertThat(outer.branchStart()).isEqualTo(5);  // not 4, where the "{" is
        assertThat(outer.branchEnd()).isEqualTo(11);

        ConditionSite swapTest = model.conditions().get(6).get(0);
        assertThat(swapTest.kind()).isEqualTo("if");
        assertThat(swapTest.expression().text()).isEqualTo("arr[j] > arr[j + 1]");
        assertThat(swapTest.branchStart()).isEqualTo(7);
        assertThat(swapTest.branchEnd()).isEqualTo(9);
    }

    @Test
    void findsLoopsWithTheirVariables() {
        assertThat(model.loops()).extracting(LoopSite::kind, LoopSite::headerLine, LoopSite::variable)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("for", 4, "i"),
                        org.assertj.core.groups.Tuple.tuple("for", 5, "j"));
    }

    @Test
    void marksReadsWritesAndComparedAccesses() {
        List<ArrayAccessSite> line6 = model.accesses().get(6);
        assertThat(line6).allMatch(ArrayAccessSite::inCondition).noneMatch(ArrayAccessSite::write);

        List<ArrayAccessSite> line8 = model.accesses().get(8);
        assertThat(line8).extracting(ArrayAccessSite::text, ArrayAccessSite::write)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("arr[j]", true),
                        org.assertj.core.groups.Tuple.tuple("arr[j + 1]", false));
    }

    @Test
    void findsPointerVariables() {
        CodeModel.MethodSite main = model.methods().get(0);
        assertThat(main.name()).isEqualTo("main");
        assertThat(main.pointers()).hasSize(1);
        assertThat(main.pointers().get(0).array().text()).isEqualTo("arr");
        assertThat(main.pointers().get(0).variables()).containsExactly("j", "i");
    }

    @Test
    void pointerVariablesIncludeNamesAnIndexIsComputedFrom() {
        CodeModel search = CodeModelBuilder.build(StaticJavaParser.parse("""
                class Main {
                    static int find(int[] arr, int target) {
                        int low = 0, high = arr.length - 1;
                        while (low <= high) {
                            int mid = low + (high - low) / 2;
                            if (arr[mid] == target) return mid;
                            if (arr[mid] < target) low = mid + 1;
                            else high = mid - 1;
                        }
                        return -1;
                    }
                }
                """));
        assertThat(search.methods().get(0).pointers().get(0).variables())
                .containsExactlyInAnyOrder("mid", "low", "high");
    }

    @Test
    void sideEffectsAreNeverEvaluated() {
        CodeModel m = CodeModelBuilder.build(StaticJavaParser.parse("""
                class Main {
                    static void f(int[] a, int i) {
                        if (a[i++] > 0) { i = 0; }
                    }
                }
                """));
        Expr condition = m.conditions().get(3).get(0).expression();
        Expr.Binary binary = (Expr.Binary) condition;
        Expr.ArrayAccess access = (Expr.ArrayAccess) binary.left();
        assertThat(access.index()).isInstanceOf(Expr.Opaque.class);
    }
}
