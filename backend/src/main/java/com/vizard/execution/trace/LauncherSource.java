package com.vizard.execution.trace;

/**
 * Generates a tiny entry-point class that is compiled next to the user's code when tracing.
 *
 * <p>It wraps System.out in a stream that counts bytes written. The debugger reads that
 * counter at every step, which tells us exactly how much output existed at that moment,
 * so the Output panel can "rewind" with the Previous button.
 *
 * <p>It lives in the user's package so it can call a main class that isn't public, and
 * it lets exceptions escape so the JVM prints the usual "Exception in thread "main"" trace.
 */
public final class LauncherSource {

    public static final String SIMPLE_NAME = "__VizardLauncher";
    public static final String OUTPUT_COUNTER_FIELD = "outBytes";

    private LauncherSource() {
    }

    public static String fullName(String packageName) {
        return packageName.isEmpty() ? SIMPLE_NAME : packageName + "." + SIMPLE_NAME;
    }

    public static String generate(String packageName, String mainClassSimpleName) {
        String pkg = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
        return pkg + """
                public final class %s {

                    public static long %s;

                    public static void main(String[] args) throws Throwable {
                        final java.io.OutputStream real = new java.io.FileOutputStream(java.io.FileDescriptor.out);
                        java.io.OutputStream counting = new java.io.OutputStream() {
                            @Override
                            public void write(int b) throws java.io.IOException {
                                real.write(b);
                                %s++;
                            }

                            @Override
                            public void write(byte[] b, int off, int len) throws java.io.IOException {
                                real.write(b, off, len);
                                %s += len;
                            }
                        };
                        System.setOut(new java.io.PrintStream(counting, true, "UTF-8"));
                        %s.main(args);
                        System.out.flush();
                    }
                }
                """.formatted(SIMPLE_NAME, OUTPUT_COUNTER_FIELD, OUTPUT_COUNTER_FIELD, OUTPUT_COUNTER_FIELD,
                mainClassSimpleName);
    }
}
