package com.vizard.execution.trace;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VoidValue;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.ListeningConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;
import com.vizard.execution.sandbox.RunningProgram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Records a program's execution with the Java Debug Interface (JDI), the same API
 * IntelliJ's debugger uses.
 *
 * <p>How it works:
 * <ol>
 *   <li>Vizard listens on a local port; the program's JVM starts paused and connects to it.</li>
 *   <li>When each user class loads, a breakpoint is placed on every line and a method-exit
 *       watch on the class.</li>
 *   <li>At every hit the program is paused, the full state (call stack, locals, statics,
 *       reachable arrays/objects, output so far) is copied, and the program continues.</li>
 * </ol>
 * The user's code is never modified, so the trace shows exactly what the real JVM did.
 */
public final class JdiTraceSession implements AutoCloseable {

    private static final int MAX_FRAMES_CAPTURED = 40;
    private static final int MAX_FRAMES_SCANNED = 200;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final ListeningConnector connector;
    private final Map<String, Connector.Argument> connectorArgs;
    private final String port;

    // Per-recording state.
    private final List<ReferenceType> userTypes = new ArrayList<>();
    private final List<TraceStep> steps = new ArrayList<>();
    private List<String> userClassNames = List.of();
    private String launcherClassName = "";
    private ReferenceType launcherType;
    private Field outputCounter;
    private EventRequestManager requests;

    private JdiTraceSession(ListeningConnector connector, Map<String, Connector.Argument> args, String port) {
        this.connector = connector;
        this.connectorArgs = args;
        this.port = port;
    }

    /** Opens a debugger listener on 127.0.0.1 with a free port. Call before starting the program. */
    public static JdiTraceSession listen() throws IOException, IllegalConnectorArgumentsException {
        ListeningConnector connector = Bootstrap.virtualMachineManager().listeningConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketListen"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JDI socket connector not available"));

        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("port").setValue("0"); // let the OS pick a free port
        if (args.containsKey("localAddress")) {
            args.get("localAddress").setValue("127.0.0.1"); // never reachable from other machines
        }
        args.get("timeout").setValue(Integer.toString(CONNECT_TIMEOUT_MS));

        String address = connector.startListening(args);
        String port = address.substring(address.lastIndexOf(':') + 1);
        return new JdiTraceSession(connector, args, port);
    }

    /** JVM option that makes the program start paused and connect to this session. */
    public List<String> jvmArgs() {
        return List.of("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:" + port);
    }

    /**
     * Drives the program until it ends, the step limit is reached, or time runs out.
     * Always leaves the program running freely (or finished) when it returns.
     */
    public TraceRecording record(RunningProgram program, List<String> userClassNames,
                                 String launcherClassName, int maxSteps)
            throws IOException, IllegalConnectorArgumentsException {
        this.userClassNames = userClassNames;
        this.launcherClassName = launcherClassName;

        VirtualMachine vm = connector.accept(connectorArgs);
        requests = vm.eventRequestManager();
        boolean truncated = false;

        try {
            watchClassLoading(launcherClassName);
            for (String name : userClassNames) {
                watchClassLoading(name);
                watchClassLoading(name + "$*"); // nested classes such as Main$Node
            }
            ExceptionRequest uncaught = requests.createExceptionRequest(null, false, true);
            uncaught.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            uncaught.enable();

            boolean done = false;
            while (!done) {
                long waitMs = program.remainingMs();
                EventSet events = waitMs > 0 ? vm.eventQueue().remove(waitMs) : null;
                if (events == null) {
                    break; // time limit; awaitCompletion() will report the timeout
                }
                for (Event event : events) {
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        done = true;
                    } else if (event instanceof ClassPrepareEvent e) {
                        onClassLoaded(e.referenceType());
                    } else if (event instanceof BreakpointEvent e) {
                        record(e.thread(), "LINE", e.location().lineNumber(), null, null);
                    } else if (event instanceof MethodExitEvent e) {
                        if (isInterestingReturn(e.method())) {
                            record(e.thread(), "RETURN", e.location().lineNumber(), e.returnValue(), null);
                        }
                    } else if (event instanceof ExceptionEvent e) {
                        record(e.thread(), "EXCEPTION", -1, null, e.exception());
                        done = true; // uncaught: the program is about to end
                    }
                }
                if (!done && steps.size() >= maxSteps) {
                    truncated = true;
                    done = true;
                }
                if (!done) {
                    events.resume();
                }
            }
        } catch (VMDisconnectedException finished) {
            // The program ended (or was killed) while we were reading it.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                vm.dispose(); // removes all breakpoints and lets the program run to completion
            } catch (VMDisconnectedException ignored) {
                // already gone
            }
        }
        return new TraceRecording(List.copyOf(steps), truncated);
    }

    private void watchClassLoading(String classPattern) {
        ClassPrepareRequest request = requests.createClassPrepareRequest();
        request.addClassFilter(classPattern);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();
    }

    private void onClassLoaded(ReferenceType type) {
        String name = type.name();
        if (name.equals(launcherClassName)) {
            launcherType = type;
            outputCounter = type.fieldByName(LauncherSource.OUTPUT_COUNTER_FIELD);
            return;
        }
        if (!isUserClass(name)) {
            return;
        }
        userTypes.add(type);
        try {
            // Every line location, including each separate code range of a line
            // (a for-loop header has one for its init and one for its update).
            for (Location location : type.allLineLocations()) {
                if (location.method().isBridge()) {
                    continue;
                }
                BreakpointRequest bp = requests.createBreakpointRequest(location);
                bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                bp.enable();
            }
            // Loops written on one line jump back into the middle of their line; stop there too.
            for (Method method : type.methods()) {
                if (method.isAbstract() || method.isNative() || method.isBridge()) {
                    continue;
                }
                for (long target : SameLineLoops.jumpTargets(method)) {
                    BreakpointRequest bp = requests.createBreakpointRequest(method.locationOfCodeIndex(target));
                    bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    bp.enable();
                }
            }
        } catch (AbsentInformationException ignored) {
            // No line info (not compiled with -g); nothing to step through.
        }
        MethodExitRequest exits = requests.createMethodExitRequest();
        exits.addClassFilter(type);
        exits.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        exits.enable();
    }

    private boolean isUserClass(String name) {
        if (name.equals(launcherClassName) || name.startsWith(launcherClassName + "$")) {
            return false;
        }
        for (String userClass : userClassNames) {
            if (name.equals(userClass) || name.startsWith(userClass + "$")) {
                return true;
            }
        }
        return false;
    }

    /** Constructor and static-initializer returns add noise without teaching anything. */
    private static boolean isInterestingReturn(Method method) {
        return !method.isConstructor() && !method.isStaticInitializer() && !method.isBridge();
    }

    private void record(ThreadReference thread, String event, int line, Value returnValue, ObjectReference exception) {
        try {
            HeapReader heapReader = new HeapReader(this::isUserClass);
            List<FrameSnapshot> stack = captureStack(thread, heapReader);
            if (stack.isEmpty()) {
                return; // e.g. an exception thrown before any user code ran
            }
            int depth = countUserFrames(thread);
            if (line < 0) {
                line = stack.get(0).line(); // exceptions: point at the user's line, not inside the JDK
            }
            List<VariableSnapshot> statics = captureStatics(heapReader);
            boolean returnsValue = "RETURN".equals(event) && !(returnValue instanceof VoidValue);
            ValueSnapshot returned = returnsValue ? heapReader.value(returnValue) : null; // null = void

            String exceptionType = null;
            String exceptionMessage = null;
            if (exception != null) {
                exceptionType = exception.referenceType().name();
                Field message = exception.referenceType().fieldByName("detailMessage");
                if (message != null && exception.getValue(message) instanceof StringReference s) {
                    exceptionMessage = s.value();
                }
            }

            Map<String, HeapObjectSnapshot> heap = heapReader.readHeap();
            int outputBytes = readOutputCounter();

            TraceStep previous = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            if ("LINE".equals(event)) {
                // A new frame: deeper than before, or at the same depth right after a return
                // (e.g. the second call in f(a) + f(b)).
                boolean afterReturn = previous != null && "RETURN".equals(previous.event())
                        && depth >= previous.depth();
                boolean otherMethod = previous != null && depth == previous.depth()
                        && !sameMethod(previous.stack().get(0), stack.get(0)); // e.g. static setup, then main
                if (previous == null || depth > previous.depth() || afterReturn || otherMethod) {
                    event = "CALL";
                } else if (previous.line() == line && previous.depth() == depth
                        && previous.stack().equals(stack) && previous.statics().equals(statics)
                        && previous.heap().equals(heap) && previous.outputLength() == outputBytes) {
                    return; // a second code range of the same line with nothing changed
                }
            }

            // "About to run line N" followed immediately by "returning from line N" with nothing
            // changed in between is one moment for the learner: keep a single RETURN step.
            boolean mergeIntoPrevious = "RETURN".equals(event) && previous != null
                    && "LINE".equals(previous.event()) && previous.line() == line && previous.depth() == depth
                    && previous.stack().equals(stack) && previous.statics().equals(statics)
                    && previous.heap().equals(heap) && previous.outputLength() == outputBytes;
            int index = mergeIntoPrevious ? previous.index() : steps.size();
            TraceStep step = new TraceStep(index, event, line, depth, stack, statics, heap, outputBytes,
                    returned, exceptionType, exceptionMessage, null, null);
            if (mergeIntoPrevious) {
                steps.set(steps.size() - 1, step);
            } else {
                steps.add(step);
            }
        } catch (IncompatibleThreadStateException e) {
            // Thread wasn't actually suspended; skip this event.
        }
    }

    private static boolean sameMethod(FrameSnapshot a, FrameSnapshot b) {
        return a.className().equals(b.className()) && a.methodName().equals(b.methodName());
    }

    private List<FrameSnapshot> captureStack(ThreadReference thread, HeapReader heapReader)
            throws IncompatibleThreadStateException {
        int count = Math.min(thread.frameCount(), MAX_FRAMES_SCANNED);
        List<FrameSnapshot> frames = new ArrayList<>();
        for (StackFrame frame : thread.frames(0, count)) {
            Location location = frame.location();
            if (!isUserClass(location.declaringType().name())) {
                continue; // library code (e.g. inside println) or the launcher
            }
            frames.add(captureFrame(frame, location, heapReader));
            if (frames.size() == MAX_FRAMES_CAPTURED) {
                break;
            }
        }
        return frames;
    }

    private FrameSnapshot captureFrame(StackFrame frame, Location location, HeapReader heapReader) {
        List<VariableSnapshot> variables = new ArrayList<>();
        ObjectReference self = frame.thisObject();
        if (self != null) {
            variables.add(new VariableSnapshot("this", HeapReader.displayType(self.referenceType().name()),
                    heapReader.value(self), false));
        }
        try {
            List<LocalVariable> locals = frame.visibleVariables();
            Map<LocalVariable, Value> values = frame.getValues(locals);
            for (LocalVariable local : locals) {
                variables.add(new VariableSnapshot(local.name(), HeapReader.displayType(local.typeName()),
                        heapReader.value(values.get(local)), local.isArgument()));
            }
        } catch (AbsentInformationException ignored) {
            // compiled without -g
        }
        return new FrameSnapshot(location.declaringType().name(), location.method().name(),
                location.lineNumber(), variables);
    }

    private int countUserFrames(ThreadReference thread) throws IncompatibleThreadStateException {
        int count = 0;
        for (StackFrame frame : thread.frames()) {
            if (isUserClass(frame.location().declaringType().name())) {
                count++;
            }
        }
        return count;
    }

    private List<VariableSnapshot> captureStatics(HeapReader heapReader) {
        List<VariableSnapshot> statics = new ArrayList<>();
        for (ReferenceType type : userTypes) {
            if (!type.isInitialized() || !(type instanceof ClassType)) {
                continue;
            }
            List<Field> fields = type.fields().stream()
                    .filter(f -> f.isStatic() && !f.isSynthetic())
                    .toList();
            if (fields.isEmpty()) {
                continue;
            }
            Map<Field, Value> values = type.getValues(fields);
            String owner = HeapReader.displayType(type.name());
            for (Field f : fields) {
                statics.add(new VariableSnapshot(owner + "." + f.name(), HeapReader.displayType(f.typeName()),
                        heapReader.value(values.get(f)), false));
            }
        }
        return statics;
    }

    private int readOutputCounter() {
        if (launcherType == null || outputCounter == null) {
            return 0;
        }
        Value v = launcherType.getValue(outputCounter);
        return v instanceof LongValue l ? (int) Math.min(l.value(), Integer.MAX_VALUE) : 0;
    }

    @Override
    public void close() {
        try {
            connector.stopListening(connectorArgs);
        } catch (IOException | IllegalConnectorArgumentsException ignored) {
            // listener already closed
        }
    }
}
