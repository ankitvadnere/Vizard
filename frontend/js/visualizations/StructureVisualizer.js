// Draws every data structure the current method can see, choosing the picture from the role the
// trace gave each object: stack, queue, deque, priority queue, map, set; linked lists and trees
// built from the program's own node classes. (Lists and arrays are drawn by ArrayVisualizer.)

import { drawStack } from "./StackVisualizer.js";
import { drawQueue } from "./QueueVisualizer.js";
import { drawHeap } from "./HeapVisualizer.js";
import { drawMap, drawSet } from "./MapVisualizer.js";
import { drawLinkedLists } from "./LinkedListVisualizer.js";
import { drawTrees } from "./TreeVisualizer.js";
import { visibleObjects } from "./visible.js";

const DRAW = {
    stack: drawStack,
    queue: drawQueue,
    deque: drawQueue,
    "priority-queue": drawHeap,
    map: drawMap,
    set: drawSet,
};

export class StructureVisualizer {
    constructor(container) {
        this.container = container;
    }

    /** Draws and returns how many structures were drawn. */
    render(step, previous) {
        this.container.replaceChildren();
        const visible = visibleObjects(step);
        let count = 0;

        for (const entry of visible.values()) {
            const draw = entry.object.kind === "collection" ? DRAW[entry.object.role] : null;
            if (draw) {
                this.container.append(draw(entry, step, previous));
                count++;
            }
        }

        const pointers = new Map();
        for (const entry of visible.values()) {
            if (entry.object.role === "list-node" || entry.object.role === "tree-node") {
                pointers.set(entry.object.id, entry.names);
            }
        }
        // One drawing per node class: a program with a DNode list and a CNode list gets two.
        const groups = new Map();
        for (const o of Object.values(step.heap)) {
            if (o.role !== "list-node" && o.role !== "tree-node") continue;
            const key = `${o.role}|${o.type}`;
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(o);
        }
        for (const nodes of groups.values()) {
            const { role, type } = nodes[0];
            const nullPointers = step.stack[0].variables
                .filter((v) => v.type === type && v.value.kind === "null").map((v) => v.name);
            const draw = role === "list-node" ? drawLinkedLists : drawTrees;
            this.container.append(draw(nodes, pointers, nullPointers, step, previous));
            count++;
        }
        return count;
    }
}
