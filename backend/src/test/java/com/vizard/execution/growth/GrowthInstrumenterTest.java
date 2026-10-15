package com.vizard.execution.growth;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What gets scaled, and what gets counted. */
class GrowthInstrumenterTest {

    private static GrowthInstrumenter.Outcome plan(String code) {
        return GrowthInstrumenter.instrument(StaticJavaParser.parse(code), "Main");
    }

    @Test
    void theLongestArrayAndItsParallelArraysAreScaled() {
        GrowthInstrumenter.Outcome o = plan("""
                public class Main {
                    public static void main(String[] args) {
                        int[] wt = {1, 3, 4, 5};
                        int[] val = {15, 20, 30, 35};
                        int[] small = {1, 2};
                        for (int i = 0; i < wt.length; i++) System.out.println(wt[i] + val[i] + small[0]);
                    }
                }""");
        assertThat(o.plan()).isNotNull();
        assertThat(o.plan().what()).isEqualTo("the arrays wt and val (all of length 4)");
        String src = o.plan().source();
        assertThat(src).contains("__VzGrowth.ints(1, 5, true)");       // sorted, small range kept
        assertThat(src).contains("__VzGrowth.ints(15, __VzGrowth.N * 10 + 15, true)");
        assertThat(src).contains("{ 1, 2 }");                             // the short array stays
        assertThat(src).contains("__vzMain(args)");
        assertThat(src).contains("System.setOut(");
        // one tick per loop iteration (the brace-less body got a block)
        assertThat(src.replaceAll("\\s+", " ")).contains("for (int i = 0; i < wt.length; i++) { __VzGrowth.tick();");
    }

    @Test
    void sizeVariablesAndStrings() {
        GrowthInstrumenter.Outcome n = plan("""
                public class Main {
                    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
                    public static void main(String[] args) { int n = 10; System.out.println(fib(n)); }
                }""");
        assertThat(n.plan().what()).isEqualTo("the value of n");
        assertThat(n.plan().source()).contains("int n = __VzGrowth.n();");

        GrowthInstrumenter.Outcome s = plan("""
                public class Main {
                    static int lcs(String a, String b) { return a.length() + b.length(); }
                    public static void main(String[] args) { System.out.println("LCS: " + lcs("ABCBDAB", "BDCABA")); }
                }""");
        assertThat(s.plan().kind()).isEqualTo("string");
        assertThat(s.plan().source()).contains("lcs(__VzGrowth.text(\"ABCD\"), __VzGrowth.text(\"ABCD\"))");
        assertThat(s.plan().source()).contains("\"LCS: \"");                // printed text is not input
    }

    @Test
    void keyboardInputCannotBeScaled() {
        GrowthInstrumenter.Outcome o = plan("""
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        int x = sc.nextInt();
                        System.out.println(x * 2);
                    }
                }""");
        assertThat(o.plan()).isNull();
        assertThat(o.reason()).contains("keyboard");
    }
}
