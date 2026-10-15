package com.vizard.execution.estimate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A big-O expression: a sum of terms, each a product of growth factors per size symbol
 * (n² log n · 2ᵐ ...). Constant factors are dropped, and a term that another term dominates is
 * dropped from a sum, so O(n² + n) is kept as O(n²) while O(n + m) stays a sum.
 *
 * <p>Symbols are opaque keys ("var:n", "len:arr"); {@link Symbols} turns them into letters.
 */
final class Cost {

    /** Growth of one symbol in one term, compared lexicographically: n! &gt; 3ⁿ &gt; 2ⁿ &gt; n² &gt; n log n &gt; n. */
    record Factor(boolean factorial, int expBase, int halfPower, int logs) implements Comparable<Factor> {
        static final Factor NONE = new Factor(false, 0, 0, 0);

        boolean isNone() {
            return !factorial && expBase == 0 && halfPower == 0 && logs == 0;
        }

        Factor times(Factor o) {
            int base = expBase == 0 ? o.expBase : o.expBase == 0 ? expBase : expBase * o.expBase;
            return new Factor(factorial || o.factorial, base, halfPower + o.halfPower, logs + o.logs);
        }

        @Override
        public int compareTo(Factor o) {
            if (factorial != o.factorial) {
                return factorial ? 1 : -1;
            }
            if (expBase != o.expBase) {
                return Integer.compare(expBase, o.expBase);
            }
            if (halfPower != o.halfPower) {
                return Integer.compare(halfPower, o.halfPower);
            }
            return Integer.compare(logs, o.logs);
        }
    }

    /** A product of factors, one per symbol. The empty term is O(1). */
    record Term(TreeMap<String, Factor> factors) {
        Term {
            factors = new TreeMap<>(factors);
            factors.values().removeIf(Factor::isNone);
        }

        Term times(Term o) {
            TreeMap<String, Factor> m = new TreeMap<>(factors);
            o.factors.forEach((k, f) -> m.merge(k, f, Factor::times));
            return new Term(m);
        }

        /** this ≤ other for every symbol. */
        boolean atMost(Term other) {
            TreeSet<String> keys = new TreeSet<>(factors.keySet());
            keys.addAll(other.factors.keySet());
            for (String k : keys) {
                if (factors.getOrDefault(k, Factor.NONE).compareTo(other.factors.getOrDefault(k, Factor.NONE)) > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    static final Cost ZERO = new Cost(List.of());
    static final Cost ONE = new Cost(List.of(new Term(new TreeMap<>())));

    private final List<Term> terms;

    private Cost(List<Term> terms) {
        this.terms = simplify(terms);
    }

    static Cost of(String symbol, Factor f) {
        TreeMap<String, Factor> m = new TreeMap<>();
        m.put(symbol, f);
        return new Cost(List.of(new Term(m)));
    }

    /** n */
    static Cost linear(String symbol) {
        return of(symbol, new Factor(false, 0, 2, 0));
    }

    List<Term> terms() {
        return terms;
    }

    boolean isZero() {
        return terms.isEmpty();
    }

    /** O(1) (or nothing at all). */
    boolean isConstant() {
        return terms.stream().allMatch(t -> t.factors().isEmpty());
    }

    Cost plus(Cost o) {
        List<Term> all = new ArrayList<>(terms);
        all.addAll(o.terms);
        return new Cost(all);
    }

    Cost times(Cost o) {
        List<Term> all = new ArrayList<>();
        for (Term a : terms) {
            for (Term b : o.terms) {
                all.add(a.times(b));
            }
        }
        return new Cost(all);
    }

    /** The larger of two costs (for if/else: either branch, so the sum is the same in big-O). */
    Cost max(Cost o) {
        return plus(o);
    }

    /** log of this cost: log(n² m) = log n + log m; log of a constant is a constant. */
    Cost log() {
        Cost out = ZERO;
        for (Term t : terms) {
            for (Map.Entry<String, Factor> e : t.factors().entrySet()) {
                Factor f = e.getValue();
                Factor l = f.factorial ? new Factor(false, 0, 2, 1)        // log n! = n log n
                        : f.expBase > 0 ? new Factor(false, 0, 2, 0)       // log 2ⁿ = n
                        : f.halfPower > 0 ? new Factor(false, 0, 0, 1)     // log nᵏ = log n
                        : new Factor(false, 0, 0, f.logs > 0 ? 0 : 0);
                if (f.halfPower == 0 && f.expBase == 0 && !f.factorial) {
                    continue; // log log n: too small to matter here
                }
                out = out.plus(of(e.getKey(), l));
            }
        }
        return out.isZero() ? ONE : out;
    }

    /** √ of this cost (only for a single term: √(n) for a loop like i * i &lt;= n). */
    Cost sqrt() {
        if (terms.size() != 1) {
            return this;
        }
        TreeMap<String, Factor> m = new TreeMap<>();
        terms.get(0).factors().forEach((k, f) -> m.put(k, f.halfPower % 2 == 0 && f.expBase == 0 && !f.factorial
                ? new Factor(false, 0, Math.max(1, f.halfPower / 2), f.logs) : f));
        return new Cost(List.of(new Term(m)));
    }

    /**
     * Replaces symbols (a callee's parameters) with costs (the caller's arguments). A symbol with no
     * replacement stays. Powers, logs, exponentials and factorials of a replaced symbol are applied
     * to its replacement when the replacement is a single symbol; otherwise to every term.
     */
    Cost substitute(Function<String, Cost> replacement) {
        Cost out = ZERO;
        for (Term t : terms) {
            Cost product = ONE;
            for (Map.Entry<String, Factor> e : t.factors().entrySet()) {
                Cost r = replacement.apply(e.getKey());
                product = product.times(r == null ? of(e.getKey(), e.getValue()) : apply(e.getValue(), r));
            }
            out = out.plus(product);
        }
        return out;
    }

    private static Cost apply(Factor f, Cost r) {
        if (r.isConstant()) {
            return ONE;
        }
        String single = r.singleSymbol();
        if (single != null) {
            Factor base = r.terms.get(0).factors().get(single);
            if (f.factorial || f.expBase > 0) {
                return of(single, new Factor(f.factorial, f.expBase, 0, 0)); // 2^(n−1) = 2ⁿ
            }
            // (n^a log^b n)^p = n^(a·p) log^(b·p)
            int half = base.halfPower() * f.halfPower / 2;
            int logs = base.logs() * f.halfPower / 2 + f.logs;
            return of(single, new Factor(base.factorial(), base.expBase(), half, logs));
        }
        Cost out = ONE;
        for (int i = 0; i < f.halfPower / 2; i++) {
            out = out.times(r);
        }
        for (int i = 0; i < f.logs; i++) {
            out = out.times(r.log());
        }
        return out;
    }

    /** The symbol of a cost that is exactly one linear-or-polynomial symbol, else null. */
    String singleSymbol() {
        if (terms.size() != 1 || terms.get(0).factors().size() != 1) {
            return null;
        }
        return terms.get(0).factors().firstKey();
    }

    /** Symbols used. */
    TreeSet<String> symbols() {
        TreeSet<String> s = new TreeSet<>();
        terms.forEach(t -> s.addAll(t.factors().keySet()));
        return s;
    }

    private static List<Term> simplify(List<Term> in) {
        List<Term> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Term t = in.get(i);
            boolean dominated = false;
            for (int j = 0; j < in.size() && !dominated; j++) {
                if (i == j) {
                    continue;
                }
                Term o = in.get(j);
                // dominated by a strictly larger term, or equal to an earlier one
                dominated = t.atMost(o) && (!o.atMost(t) || j < i);
            }
            if (!dominated) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** "O(n² log n + m)" */
    String render(Function<String, String> letter) {
        if (terms.isEmpty() || isConstant()) {
            return "O(1)";
        }
        List<String> parts = new ArrayList<>();
        for (Term t : terms) {
            if (t.factors().isEmpty()) {
                continue;
            }
            List<String> fs = new ArrayList<>();
            List<String> exps = new ArrayList<>();
            List<String> logs = new ArrayList<>();
            // order factors by their letter so n comes before m consistently
            TreeMap<String, Factor> byLetter = new TreeMap<>(Cost::letterOrder);
            t.factors().forEach((k, f) -> byLetter.put(letter.apply(k), f));
            byLetter.forEach((x, f) -> {
                if (f.halfPower > 0) {
                    fs.add(power(x, f.halfPower));
                }
                if (f.factorial) {
                    fs.add(x + "!");
                }
                if (f.logs > 0) {
                    logs.add(f.logs == 1 ? "log " + x : "log" + sup(f.logs) + " " + x);
                }
                if (f.expBase > 0) {
                    exps.add(f.expBase + sup(x));
                }
            });
            List<String> all = new ArrayList<>(fs);
            all.addAll(exps);
            String main = String.join(" · ", all);
            String l = String.join(" · ", logs);
            parts.add(main.isEmpty() ? l : l.isEmpty() ? main : main + " " + l);
        }
        return "O(" + String.join(" + ", parts) + ")";
    }

    private static int letterOrder(String a, String b) {
        int ra = rank(a);
        int rb = rank(b);
        return ra != rb ? Integer.compare(ra, rb) : a.compareTo(b);
    }

    private static int rank(String letter) {
        int i = List.of("n", "N", "V", "m", "M", "E", "k", "K").indexOf(letter);
        return i < 0 ? 100 : i;
    }

    private static String power(String x, int half) {
        if (half % 2 == 1) {
            String rest = half == 1 ? "" : power(x, half - 1) + " ";
            return rest + "√" + x;
        }
        int p = half / 2;
        return p == 1 ? x : x + sup(p);
    }

    static String sup(int n) {
        return sup(Integer.toString(n));
    }

    static String sup(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            int i = "0123456789".indexOf(c);
            if (i >= 0) {
                b.append("⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(i));
            } else if (c == 'n') {
                b.append('ⁿ');
            } else if (c == 'm') {
                b.append('ᵐ');
            } else if (c == 'k') {
                b.append('ᵏ');
            } else if (c == 'N') {
                b.append('ᴺ');
            } else if (c == 'V') {
                b.append('ⱽ');
            } else {
                return "^" + s;
            }
        }
        return b.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cost c && c.terms.equals(terms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(terms);
    }

    @Override
    public String toString() {
        return render(k -> k);
    }
}
