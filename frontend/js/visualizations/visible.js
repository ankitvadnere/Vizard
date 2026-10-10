// What the current method can see: the arrays, collections and objects its variables refer to,
// with every name that refers to each one. Pure data → data, shared by the visualizers.

/**
 * Objects referenced by the current frame's variables, the static fields, and one level of fields
 * of the user's objects they refer to (so `this.items`, `list.head` and `stack.data` are found).
 * Fields of `this` are named as the method sees them (`items`), others as `list.head`.
 * @returns Map<id, {names: string[], object}>
 */
export function visibleObjects(step) {
    const found = new Map();
    const add = (name, value) => {
        if (value?.kind !== "ref") return;
        const object = step.heap[value.ref];
        if (!object) return;
        const entry = found.get(object.id) ?? { names: [], object };
        if (!entry.names.includes(name)) entry.names.push(name);
        found.set(object.id, entry);
    };

    const roots = [...step.stack[0].variables, ...step.statics];
    for (const v of roots) {
        if (v.name === "args" && step.heap[v.value.ref]?.length === 0) continue; // main's empty String[] args
        add(v.name, v.value);
    }
    for (const v of roots) {
        const object = v.value.kind === "ref" ? step.heap[v.value.ref] : null;
        if (object?.kind !== "object" || !object.fields?.length || object.role) continue; // nodes are drawn whole
        for (const f of object.fields) {
            add(v.name === "this" ? f.name : `${v.name}.${f.name}`, f.value);
        }
    }
    return found;
}

/** The name the program uses for an object at this step (current frame first, then callers), or null. */
export function nameOf(step, ref) {
    for (const frame of step.stack) {
        for (const v of frame.variables) {
            if (v.value.kind === "ref" && v.value.ref === ref) return v.name;
        }
    }
    for (const v of step.statics) {
        if (v.value.kind === "ref" && v.value.ref === ref) return v.name.slice(v.name.indexOf(".") + 1);
    }
    for (const [, entry] of visibleObjects(step)) {
        if (entry.object.id === ref) return entry.names[0];
    }
    return null;
}

/** Elements of a stack with the top first, whichever end the class keeps it at. */
export function topFirst(object) {
    return object.top === "last" ? [...object.elements].reverse() : object.elements;
}

/** The same object at the previous step, if it existed then. */
export function before(previous, object) {
    return previous?.heap?.[object.id] ?? null;
}

/** Operations of this step on one collection, e.g. the pushes made since the last step. */
export function operationsOn(step, object, kind) {
    return (step.operations ?? []).filter((op) => op.ref === object.id && (!kind || op.kind === kind));
}
