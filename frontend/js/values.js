// Turns trace values into text a Java programmer recognises.

const MAX_NESTING = 2;

/**
 * Formats a ValueSnapshot. Arrays and objects are looked up in the step's heap.
 * Nesting is limited, so a linked list prints as Node { value: 1, next: Node { … } }.
 */
export function formatValue(value, heap, depth = 0) {
    if (!value) return "";
    if (value.kind !== "ref") return value.display;

    const object = heap?.[value.ref];
    if (!object) return `${value.type} …`;
    if (depth >= MAX_NESTING) return object.kind === "array" ? `${object.type} (${object.length})` : `${object.type} { … }`;

    if (object.kind === "array") {
        const items = object.elements.map((e) => formatValue(e, heap, depth + 1));
        if (object.truncated) items.push(`… ${object.length - object.elements.length} more`);
        return `[${items.join(", ")}]`;
    }
    if (!object.fields || object.fields.length === 0) {
        return object.type; // library object such as Scanner
    }
    const fields = object.fields.map((f) => `${f.name}: ${formatValue(f.value, heap, depth + 1)}`);
    return `${object.type} { ${fields.join(", ")} }`;
}

/** "Main$Node" → "Node", "java.util.Scanner" → "Scanner". */
export function simpleClassName(name) {
    const afterDot = name.slice(name.lastIndexOf(".") + 1);
    return afterDot.slice(afterDot.lastIndexOf("$") + 1);
}

/** Human name for a method: constructors, static setup and lambdas get readable labels. */
export function methodLabel(frame) {
    const owner = simpleClassName(frame.className);
    if (frame.methodName === "<init>") return `new ${owner}`;
    if (frame.methodName === "<clinit>") return `${owner} static setup`;
    if (frame.methodName.startsWith("lambda$")) return "lambda";
    return frame.methodName;
}

/** factorial(n = 3) */
export function callSignature(frame, heap) {
    const args = frame.variables
        .filter((v) => v.argument && !(frame.methodName === "main" && v.name === "args"))
        .map((v) => `${v.name} = ${formatValue(v.value, heap, 1)}`);
    return `${methodLabel(frame)}(${args.join(", ")})`;
}
