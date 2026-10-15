package com.vizard.execution.growth;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Rewrites a program so it can be run at different input sizes and report how much work it did:
 * <ul>
 *   <li>the input written in the code is replaced by generated input of size n (read from the
 *       system property vizard.n): the largest array literal (and arrays of the same length next
 *       to it), else an {@code int n = 10}, else the String literals in main;</li>
 *   <li>every loop iteration and every call of one of your methods adds one to a counter;</li>
 *   <li>main runs with its printing switched off, then the count is written to stderr.</li>
 * </ul>
 * The source passed in has already passed the safety policy; nothing added here reads files,
 * opens sockets or starts threads.
 */
public final class GrowthInstrumenter {

    static final String HELPER = "__VzGrowth";
    static final String STOP = "__VzStop";
    static final String RESULT_MARK = "__VZ_RESULT";

    /** @param source the rewritten program; @param what what is scaled ("the array arr"); @param kind array/variable/string */
    public record Plan(String source, String what, String kind, int originalSize) {
    }

    /** Either a plan or the reason there is none. */
    public record Outcome(Plan plan, String reason) {
    }

    private GrowthInstrumenter() {
    }

    public static Outcome instrument(CompilationUnit original, String mainClassName) {
        CompilationUnit cu = original.clone();
        String simple = mainClassName.substring(mainClassName.lastIndexOf('.') + 1);
        Optional<MethodDeclaration> main = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("main") && m.isStatic() && m.getParameters().size() == 1
                        && m.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c.getNameAsString().equals(simple))
                        .orElse(false))
                .findFirst();
        if (main.isEmpty()) {
            return new Outcome(null, "No main method found.");
        }
        Plan scaled = scale(cu, main.get());
        if (scaled == null) {
            boolean keyboard = original.toString().contains("System.in");
            return new Outcome(null, keyboard
                    ? "This program reads its input from the keyboard. To measure growth, Vizard needs the input "
                    + "written in the code: an array like {5, 3, 8}, an int n = 10, or a String."
                    : "Vizard could not find an input to make bigger: an array written in the code (like "
                    + "{5, 3, 8}), an int n = 10 in main, or a String in main.");
        }
        count(cu);
        main.get().setName("__vzMain");
        ClassOrInterfaceDeclaration mainClass = main.get().findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
        mainClass.addMember(StaticJavaParser.parseBodyDeclaration("""
                public static void main(String[] args) {
                    java.io.PrintStream err = System.err;
                    System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                    String outcome = "ok";
                    long t0 = System.nanoTime();
                    try {
                        __vzMain(args);
                    } catch (__VzStop e) {
                        outcome = "limit";
                    } catch (Throwable e) {
                        outcome = e.getClass().getSimpleName();
                    }
                    err.println("__VZ_RESULT " + __VzGrowth.ops + " " + (System.nanoTime() - t0) / 1000000 + " " + outcome);
                    err.flush();
                }"""));
        cu.addType(StaticJavaParser.parseTypeDeclaration(HELPER_SOURCE));
        cu.addType(StaticJavaParser.parseTypeDeclaration(
                "final class __VzStop extends Error { __VzStop() { super(null, null, false, false); } }"));
        return new Outcome(new Plan(cu.toString(), scaled.what(), scaled.kind(), scaled.originalSize()), null);
    }

    private static final String HELPER_SOURCE = """
            final class __VzGrowth {
                static long ops;
                static final long LIMIT = 20000000L;
                static final int N = Integer.getInteger("vizard.n", 8);
                private static final java.util.Random R = new java.util.Random(42);

                static void tick() {
                    if (++ops > LIMIT) {
                        throw new __VzStop();
                    }
                }

                static int n() {
                    return N;
                }

                static int[] ints(int lo, int hi, boolean sorted) {
                    int[] a = new int[N];
                    for (int i = 0; i < N; i++) {
                        a[i] = lo + R.nextInt(hi - lo + 1);
                    }
                    if (sorted) {
                        java.util.Arrays.sort(a);
                    }
                    return a;
                }

                static long[] longs(int lo, int hi, boolean sorted) {
                    long[] a = new long[N];
                    int[] b = ints(lo, hi, sorted);
                    for (int i = 0; i < N; i++) {
                        a[i] = b[i];
                    }
                    return a;
                }

                static double[] doubles(int lo, int hi, boolean sorted) {
                    double[] a = new double[N];
                    for (int i = 0; i < N; i++) {
                        a[i] = lo + R.nextDouble() * (hi - lo);
                    }
                    if (sorted) {
                        java.util.Arrays.sort(a);
                    }
                    return a;
                }

                static char[] chars(String alphabet) {
                    char[] a = new char[N];
                    for (int i = 0; i < N; i++) {
                        a[i] = alphabet.charAt(R.nextInt(alphabet.length()));
                    }
                    return a;
                }

                static String[] words(boolean sorted) {
                    String[] a = new String[N];
                    for (int i = 0; i < N; i++) {
                        a[i] = "w" + R.nextInt(Math.max(10, N * 4));
                    }
                    if (sorted) {
                        java.util.Arrays.sort(a);
                    }
                    return a;
                }

                static String text(String alphabet) {
                    return new String(chars(alphabet));
                }
            }""";

    // ---------- choosing the input to scale ----------------------------------------------------------

    private static Plan scale(CompilationUnit cu, MethodDeclaration main) {
        Plan arrays = scaleArrays(cu, main);
        if (arrays != null) {
            return arrays;
        }
        Plan n = scaleSizeVariable(cu, main);
        if (n != null) {
            return n;
        }
        return scaleStrings(cu, main);
    }

    /** One-dimensional array literals of numbers, chars or Strings, in main or in fields. */
    private static Plan scaleArrays(CompilationUnit cu, MethodDeclaration main) {
        List<ArrayInitializerExpr> candidates = new ArrayList<>();
        for (ArrayInitializerExpr a : cu.findAll(ArrayInitializerExpr.class)) {
            if (a.getParentNode().orElse(null) instanceof ArrayInitializerExpr || a.getValues().size() < 2
                    || a.getValues().stream().anyMatch(v -> v instanceof ArrayInitializerExpr)
                    || elementType(a) == null) {
                continue;
            }
            boolean inMain = a.findAncestor(MethodDeclaration.class).map(m -> m == main).orElse(false);
            boolean inField = a.findAncestor(FieldDeclaration.class).isPresent();
            if ((inMain || inField) && a.getValues().stream().allMatch(GrowthInstrumenter::isLiteral)) {
                candidates.add(a);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int longest = candidates.stream().mapToInt(a -> a.getValues().size()).max().orElse(0);
        List<String> names = new ArrayList<>();
        for (ArrayInitializerExpr a : candidates) {
            if (a.getValues().size() != longest) {
                continue;
            }
            String type = elementType(a);
            Expression generator = generator(type, a);
            names.add(nameOf(a));
            Node parent = a.getParentNode().orElseThrow();
            if (parent instanceof ArrayCreationExpr creation) {
                creation.replace(generator);
            } else {
                a.replace(generator);
            }
        }
        String what = names.size() == 1 ? "the array " + names.get(0)
                : "the arrays " + String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.get(names.size() - 1) + " (all of length " + longest + ")";
        return new Plan(null, what, "array", longest);
    }

    /** int n = 10; in main or as a field. */
    private static Plan scaleSizeVariable(CompilationUnit cu, MethodDeclaration main) {
        List<VariableDeclarator> found = new ArrayList<>();
        for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) {
            boolean inMain = v.findAncestor(MethodDeclaration.class).map(m -> m == main).orElse(false);
            boolean inField = v.getParentNode().orElse(null) instanceof FieldDeclaration;
            if ((inMain || inField) && v.getTypeAsString().matches("int|long")
                    && v.getNameAsString().matches("n|N|m|M|size|SIZE|len|length|num|count|limit|total|V")
                    && v.getInitializer().map(i -> i.isIntegerLiteralExpr() || i.isLongLiteralExpr()).orElse(false)) {
                found.add(v);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        VariableDeclarator v = found.get(0);
        int original = Integer.parseInt(v.getInitializer().get().toString().replaceAll("[lL_]", ""));
        Expression call = StaticJavaParser.parseExpression(HELPER + ".n()");
        v.setInitializer(v.getTypeAsString().equals("long") ? StaticJavaParser.parseExpression("(long) " + HELPER + ".n()")
                : call);
        // a final field with a literal is a compile-time constant; with a call it no longer is: drop "final" from
        // switch-case use is rare, keep the modifier
        return new Plan(null, "the value of " + v.getNameAsString(), "variable", original);
    }

    /**
     * String literals in main (LCS, edit distance...): String variables, or literals passed straight to
     * one of the program's own methods, lcs("ABCBDAB", "BDCABA"). All of them get length n.
     */
    private static Plan scaleStrings(CompilationUnit cu, MethodDeclaration main) {
        java.util.Set<String> userMethods = new java.util.HashSet<>();
        cu.findAll(MethodDeclaration.class).forEach(m -> userMethods.add(m.getNameAsString()));
        List<StringLiteralExpr> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (StringLiteralExpr s : main.findAll(StringLiteralExpr.class)) {
            if (s.getValue().length() < 3) {
                continue;
            }
            Node parent = s.getParentNode().orElse(null);
            if (parent instanceof VariableDeclarator v && v.getTypeAsString().equals("String")) {
                found.add(s);
                names.add(v.getNameAsString());
            } else if (parent instanceof com.github.javaparser.ast.expr.MethodCallExpr call
                    && call.getArguments().contains(s) && userMethods.contains(call.getNameAsString())
                    && call.getScope().isEmpty()) {
                found.add(s);
                names.add("\"" + s.getValue() + "\"");
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        int longest = 0;
        for (StringLiteralExpr s : found) {
            longest = Math.max(longest, s.getValue().length());
            s.replace(StaticJavaParser.parseExpression(HELPER + ".text(\"" + alphabet(s.getValue()) + "\")"));
        }
        return new Plan(null, (names.size() == 1 ? "the string " : "the strings ") + String.join(" and ", names),
                "string", longest);
    }

    private static String alphabet(String text) {
        TreeSet<Character> set = new TreeSet<>();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                set.add(c);
            }
        }
        StringBuilder b = new StringBuilder();
        set.forEach(b::append);
        return b.length() == 0 ? "ab" : b.toString();
    }

    private static boolean isLiteral(Expression e) {
        if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
            e = u.getExpression();
        }
        return e.isIntegerLiteralExpr() || e.isLongLiteralExpr() || e.isDoubleLiteralExpr() || e.isCharLiteralExpr()
                || e.isStringLiteralExpr();
    }

    /** "int", "long", "double", "char", "String" or null for anything else. */
    private static String elementType(ArrayInitializerExpr a) {
        Node parent = a.getParentNode().orElse(null);
        String type = null;
        if (parent instanceof VariableDeclarator v) {
            type = v.getTypeAsString();
        } else if (parent instanceof ArrayCreationExpr c) {
            type = c.getElementType().asString() + "[]";
        }
        if (type == null) {
            return null;
        }
        type = type.replace(" ", "");
        return switch (type) {
            case "int[]" -> "int";
            case "long[]" -> "long";
            case "double[]" -> "double";
            case "char[]" -> "char";
            case "String[]" -> "String";
            default -> null;
        };
    }

    private static String nameOf(ArrayInitializerExpr a) {
        Node p = a.getParentNode().orElse(null);
        while (p != null && !(p instanceof VariableDeclarator)) {
            p = p.getParentNode().orElse(null);
        }
        return p instanceof VariableDeclarator v ? v.getNameAsString() : "in the code";
    }

    private static Expression generator(String type, ArrayInitializerExpr a) {
        List<Double> values = new ArrayList<>();
        for (Expression e : a.getValues()) {
            String t = e.toString().replaceAll("[lLdDfF_]", "");
            try {
                values.add(Double.parseDouble(t));
            } catch (NumberFormatException ex) {
                // chars and strings
            }
        }
        boolean sorted = true;
        List<Expression> vs = a.getValues();
        for (int i = 1; i < vs.size(); i++) {
            String x = vs.get(i - 1).toString();
            String y = vs.get(i).toString();
            if (!values.isEmpty() ? values.get(i - 1) > values.get(i) : x.compareTo(y) > 0) {
                sorted = false;
            }
        }
        if (type.equals("char")) {
            StringBuilder alphabet = new StringBuilder();
            for (Expression e : vs) {
                String c = e.asCharLiteralExpr().getValue();
                if (c.length() == 1 && alphabet.indexOf(c) < 0) {
                    alphabet.append(c);
                }
            }
            return StaticJavaParser.parseExpression(HELPER + ".chars(\"" + (alphabet.length() == 0 ? "ab" : alphabet)
                    + "\")");
        }
        if (type.equals("String")) {
            return StaticJavaParser.parseExpression(HELPER + ".words(" + sorted + ")");
        }
        int lo = (int) Math.floor(values.stream().min(Comparator.naturalOrder()).orElse(0.0));
        int hi = (int) Math.ceil(values.stream().max(Comparator.naturalOrder()).orElse(100.0));
        // small ranges (digits, 0/1 flags) stay as they are; wide ones widen with n (see the call in the run)
        String range = hi - lo < 20 ? lo + ", " + hi : lo + ", " + HELPER + ".N * 10 + " + lo;
        String fn = switch (type) {
            case "long" -> "longs";
            case "double" -> "doubles";
            default -> "ints";
        };
        return StaticJavaParser.parseExpression(HELPER + "." + fn + "(" + range + ", " + sorted + ")");
    }

    // ---------- counting -------------------------------------------------------------------------------

    /** One tick per loop iteration and per call of a user method or constructor. */
    private static void count(CompilationUnit cu) {
        Statement tick = StaticJavaParser.parseStatement(HELPER + ".tick();");
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            m.getBody().ifPresent(b -> b.addStatement(0, tick.clone()));
        }
        for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
            BlockStmt b = c.getBody();
            int at = !b.getStatements().isEmpty() && b.getStatement(0) instanceof ExplicitConstructorInvocationStmt ? 1 : 0;
            b.addStatement(at, tick.clone());
        }
        for (ForStmt f : cu.findAll(ForStmt.class)) {
            f.setBody(withTick(f.getBody(), tick));
        }
        for (ForEachStmt f : cu.findAll(ForEachStmt.class)) {
            f.setBody(withTick(f.getBody(), tick));
        }
        for (WhileStmt w : cu.findAll(WhileStmt.class)) {
            w.setBody(withTick(w.getBody(), tick));
        }
        for (DoStmt d : cu.findAll(DoStmt.class)) {
            d.setBody(withTick(d.getBody(), tick));
        }
    }

    private static Statement withTick(Statement body, Statement tick) {
        if (body instanceof BlockStmt b) {
            b.addStatement(0, tick.clone());
            return b;
        }
        BlockStmt b = new BlockStmt();
        b.addStatement(tick.clone());
        b.addStatement(body.clone());
        return b;
    }

}
