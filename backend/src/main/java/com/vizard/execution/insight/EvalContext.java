package com.vizard.execution.insight;

import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * The variables visible at one recorded step, the way Java would resolve a name:
 * locals and parameters first, then fields of {@code this}, then static fields.
 */
public final class EvalContext {

    private final Map<String, ValueSnapshot> locals = new HashMap<>();
    private final Map<String, ValueSnapshot> statics = new HashMap<>();
    private final Map<String, HeapObjectSnapshot> heap;
    private final HeapObjectSnapshot self;

    public EvalContext(TraceStep step) {
        this.heap = step.heap();
        HeapObjectSnapshot thisObject = null;
        if (!step.stack().isEmpty()) {
            for (VariableSnapshot v : step.stack().get(0).variables()) {
                locals.put(v.name(), v.value());
                if (v.name().equals("this") && v.value().ref() != null) {
                    thisObject = heap.get(v.value().ref().toString());
                }
            }
        }
        this.self = thisObject;
        for (VariableSnapshot v : step.statics()) {
            statics.put(v.name(), v.value());                               // "Main.count"
            statics.putIfAbsent(v.name().substring(v.name().indexOf('.') + 1), v.value()); // "count"
        }
    }

    /** A plain name: local/parameter, then this.field, then static field. Null if unknown. */
    ValueSnapshot lookup(String name) {
        ValueSnapshot local = locals.get(name);
        if (local != null) {
            return local;
        }
        if (self != null) {
            for (VariableSnapshot field : self.fields()) {
                if (field.name().equals(name)) {
                    return field.value();
                }
            }
        }
        return statics.get(name);
    }

    /** Static field written with its class name, e.g. Main.count. */
    ValueSnapshot lookupStatic(String className, String field) {
        return statics.get(className + "." + field);
    }

    HeapObjectSnapshot heapObject(long id) {
        return heap.get(Long.toString(id));
    }
}
