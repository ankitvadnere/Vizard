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
    if (depth >= MAX_NESTING) {
        if (object.kind === "object") return `${object.type} { … }`;
        return `${object.type} (${object.length})`;
    }

    if (object.kind === "array" || (object.kind === "collection" && !object.entries)) {
        const items = object.elements.map((e) => cellValue(e, heap, depth + 1));
        if (object.truncated) items.push(`… ${object.length - object.elements.length} more`);
        return `[${items.join(", ")}]`; // how Java prints a list, set, queue or stack
    }
    if (object.kind === "collection") {
        const items = object.entries.map((e) => `${cellValue(e.key, heap, depth + 1)}=${cellValue(e.value, heap, depth + 1)}`);
        if (object.truncated) items.push(`… ${object.length - object.entries.length} more`);
        return `{${items.join(", ")}}`; // how Java prints a map
    }
    if (!object.fields || object.fields.length === 0) {
        return object.type; // library object such as Scanner
    }
    const fields = object.fields.map((f) => `${f.name}: ${formatValue(f.value, heap, depth + 1)}`);
    return `${object.type} { ${fields.join(", ")} }`;
}

/**
 * A value as it should appear inside a box: a linked-list or tree node shows only its value
 * ("4"), so a queue of tree nodes reads 1, 2, 3 rather than Node { … }, Node { … }.
 */
export function cellValue(value, heap, depth = 1) {
    if (value?.kind === "ref") {
        const object = heap?.[value.ref];
        if (object && (object.role === "list-node" || object.role === "tree-node")) {
            return nodeLabel(object, heap);
        }
    }
    return formatValue(value, heap, depth);
}

/** A node's own value: its first field that isn't a link (data, key, val...). */
export function nodeLabel(node, heap) {
    const field = node.fields.find((f) => !node.links?.includes(f.name));
    return field ? formatValue(field.value, heap, 2) : node.type;
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

/** factorial(n = 3); a node argument reads Node(1) rather than its whole chain. */
export function callSignature(frame, heap) {
    const args = frame.variables
        .filter((v) => v.argument && !(frame.methodName === "main" && v.name === "args"))
        .map((v) => `${v.name} = ${argumentText(v.value, heap)}`);
    return `${methodLabel(frame)}(${args.join(", ")})`;
}

function argumentText(value, heap) {
    const object = value?.kind === "ref" ? heap?.[value.ref] : null;
    if (object && (object.role === "list-node" || object.role === "tree-node")) {
        return `${object.type}(${nodeLabel(object, heap)})`;
    }
    return formatValue(value, heap, 1);
}
