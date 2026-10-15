package com.vizard.execution.estimate;

import com.github.javaparser.StaticJavaParser;
import com.vizard.execution.estimate.ComplexityEstimator.Estimate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Static estimates: loops, nesting, calls, library costs and recurrences (master theorem, unrolling). */
class ComplexityEstimatorTest {

    private static List<Estimate> all(String methods) {
        return ComplexityEstimator.estimate(StaticJavaParser.parse(
                "import java.util.*;\nclass Main {\n" + methods + "\npublic static void main(String[] a) { }\n}"), Set.of());
    }

    private static Estimate of(String methods, String name) {
        return all(methods).stream().filter(e -> e.method().equals(name)).findFirst().orElseThrow();
    }

    private static String time(String methods, String name) {
        return of(methods, name).time();
    }

    @Test
    void nestedLoopsMultiply() {
        Estimate e = of("""
                static int pairs(int[] a) {
                    int c = 0;
                    for (int i = 0; i < a.length; i++)
                        for (int j = i + 1; j < a.length; j++)
                            if (a[i] + a[j] == 0) c++;
                    return c;
                }""", "pairs");
        assertThat(e.time()).isEqualTo("O(n²)");
        assertThat(e.legend()).containsExactly("n = a.length");
        assertThat(e.steps().get(1)).startsWith("  Line 6");
    }

    @Test
    void loopsOneAfterAnotherAdd() {
        assertThat(time("static int f(int n, int m) { int s = 0; for (int i = 0; i < n; i++) s++; "
                + "for (int j = 0; j < m; j++) s++; return s; }", "f")).isEqualTo("O(n + m)");
        assertThat(time("static int f(int n) { int s = 0; for (int i = 0; i < n; i++) s++; "
                + "for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) s++; return s; }", "f")).isEqualTo("O(n²)");
    }

    @Test
    void halvingDoublingAndSquareRootLoops() {
        assertThat(time("static int d(int n) { int c = 0; while (n > 0) { n /= 10; c++; } return c; }", "d"))
                .isEqualTo("O(log n)");
        assertThat(time("static int d(int n) { int c = 0; for (int i = 1; i < n; i *= 2) for (int j = 0; j < n; j++) c++; "
                + "return c; }", "d")).isEqualTo("O(n log n)");
        assertThat(time("static boolean p(int n) { for (int i = 2; i * i <= n; i++) if (n % i == 0) return false; "
                + "return true; }", "p")).isEqualTo("O(√n)");
        assertThat(time("""
                static int bs(int[] a, int x) {
                    int lo = 0, hi = a.length - 1;
                    while (lo <= hi) { int mid = (lo + hi) / 2; if (a[mid] < x) lo = mid + 1; else hi = mid - 1; }
                    return lo;
                }""", "bs")).isEqualTo("O(log n)");
    }

    @Test
    void constantLoopsAreNotReported() {
        assertThat(all("static int f() { int s = 0; for (int i = 0; i < 100; i++) s += i; return s; }")).isEmpty();
    }

    @Test
    void callsCostWhatTheCalleeCosts() {
        String code = """
                static int inner(int[] a) { int s = 0; for (int x : a) s += x; return s; }
                static int outer(int[] b) { int s = 0; for (int i = 0; i < b.length; i++) s += inner(b); return s; }""";
        assertThat(time(code, "outer")).isEqualTo("O(n²)");
        assertThat(time("static void s(int[] a) { Arrays.sort(a); }", "s")).isEqualTo("O(n log n)");
        assertThat(time("static int u(List<Integer> xs) { List<Integer> out = new ArrayList<>(); "
                + "for (int x : xs) if (!out.contains(x)) out.add(x); return out.size(); }", "u")).isEqualTo("O(n · m)");
    }

    @Test
    void masterTheoremCases() {
        Estimate ms = of("""
                static void ms(int[] a, int lo, int hi) {
                    if (lo >= hi) return;
                    int mid = (lo + hi) / 2;
                    ms(a, lo, mid);
                    ms(a, mid + 1, hi);
                    merge(a, lo, mid, hi);
                }
                static void merge(int[] a, int lo, int mid, int hi) {
                    int[] t = new int[hi - lo + 1];
                    int i = lo, j = mid + 1, k = 0;
                    while (i <= mid && j <= hi) t[k++] = a[i] <= a[j] ? a[i++] : a[j++];
                    while (i <= mid) t[k++] = a[i++];
                    while (j <= hi) t[k++] = a[j++];
                    for (k = 0; k < t.length; k++) a[lo + k] = t[k];
                }""", "ms");
        assertThat(ms.time()).isEqualTo("O(n log n)");
        assertThat(ms.recurrence()).isEqualTo("T(n) = 2T(n/2) + O(n)");
        assertThat(ms.rule()).isEqualTo("Master theorem, case 2");
        assertThat(ms.space()).isEqualTo("O(n)");

        Estimate pow = of("static long p(long x, int n) { if (n == 0) return 1; long h = p(x, n / 2); "
                + "return n % 2 == 0 ? h * h : h * h * x; }", "p");
        assertThat(pow.time()).isEqualTo("O(log n)");
        assertThat(pow.recurrence()).isEqualTo("T(n) = T(n/2) + O(1)");

        Estimate three = of("static int t(int n) { if (n <= 1) return 1; return t(n / 2) + t(n / 2) + t(n / 2); }", "t");
        assertThat(three.time()).isEqualTo("O(n^1.58)");
        assertThat(three.rule()).isEqualTo("Master theorem, case 1");

        Estimate top = of("static int q(int n) { if (n <= 1) return 1; int s = 0; for (int i = 0; i < n; i++) "
                + "for (int j = 0; j < n; j++) s++; return s + q(n / 2) + q(n / 2); }", "q");
        assertThat(top.time()).isEqualTo("O(n²)");
        assertThat(top.rule()).isEqualTo("Master theorem, case 3");
    }

    @Test
    void subtractAndConquerIsUnrolled() {
        assertThat(time("static int f(int n) { if (n <= 1) return 1; return n * f(n - 1); }", "f")).isEqualTo("O(n)");
        Estimate fib = of("static int fib(int n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); }", "fib");
        assertThat(fib.time()).isEqualTo("O(2ⁿ)");
        assertThat(fib.space()).isEqualTo("O(n)");
        assertThat(String.join(" ", fib.steps())).contains("φ");
        assertThat(time("static int h(int n, int a, int b, int c) { if (n == 0) return 0; "
                + "return h(n - 1, a, c, b) + 1 + h(n - 1, c, b, a); }", "h")).isEqualTo("O(2ⁿ)");
        assertThat(time("static int s(int[] a, int i) { if (i == a.length) return 0; return a[i] + s(a, i + 1); }", "s"))
                .isEqualTo("O(n)");
        assertThat(time("""
                static void perm(int[] a, int k) {
                    if (k == a.length) { System.out.println(Arrays.toString(a)); return; }
                    for (int i = k; i < a.length; i++) { perm(a, k + 1); }
                }""", "perm")).isEqualTo("O(n · n!)");
    }

    @Test
    void whatCannotBeReadIsSaidSo() {
        Estimate c = of("static int c(int n) { int s = 0; while (n != 1) { n = n % 2 == 0 ? n / 2 : 3 * n + 1; s++; } "
                + "return s; }", "c");
        assertThat(c.time()).isEqualTo("O(k)");
        assertThat(c.legend()).containsExactly("k = the number of iterations of the loop on line 3");
        Estimate gcd = of("static int g(int a, int b) { if (b == 0) return a; return g(b, a % b); }", "g");
        assertThat(gcd.time()).isNull();
        assertThat(gcd.steps().get(0)).contains("does not shrink");
    }

    @Test
    void linkedListsAndWorklists() {
        assertThat(of("static class Node { int v; Node next; }\n"
                + "static int len(Node head) { int c = 0; for (Node x = head; x != null; x = x.next) c++; return c; }",
                "len").legend()).containsExactly("n = the number of nodes reachable from head");
        assertThat(time("static int w(Queue<Integer> q) { int s = 0; while (!q.isEmpty()) s += q.poll(); return s; }", "w"))
                .isEqualTo("O(k)");
    }

    @Test
    void mainIsEstimatedOnlyWhenTheWorkIsThere() {
        List<Estimate> onlyMain = ComplexityEstimator.estimate(StaticJavaParser.parse("""
                class Main {
                    public static void main(String[] args) {
                        int[] a = {5, 1, 4};
                        int n = a.length;
                        int best = 0;
                        for (int i = 0; i < n; i++) for (int j = i; j < n; j++) for (int k = i; k <= j; k++) best++;
                    }
                }"""), Set.of());
        assertThat(onlyMain).hasSize(1);
        assertThat(onlyMain.get(0).time()).isEqualTo("O(n³)");
        assertThat(onlyMain.get(0).legend()).containsExactly("n = a.length");

        List<Estimate> withHelper = ComplexityEstimator.estimate(StaticJavaParser.parse("""
                class Main {
                    static int sum(int[] a) { int s = 0; for (int x : a) s += x; return s; }
                    public static void main(String[] args) { int[] a = {1}; for (int i = 0; i < a.length; i++) sum(a); }
                }"""), Set.of());
        assertThat(withHelper).extracting(Estimate::method).containsExactly("sum");
        // recognised methods keep their catalogue entry instead
        assertThat(ComplexityEstimator.estimate(StaticJavaParser.parse("""
                class Main {
                    static int sum(int[] a) { int s = 0; for (int x : a) s += x; return s; }
                    public static void main(String[] args) { }
                }"""), Set.of("sum"))).isEmpty();
    }
}
