// The "what is this line doing" strip: what the previous line did to a collection, the condition
// being tested, and the loops we're inside.

import { formatValue } from "../values.js";
import { nameOf } from "./visible.js";

const KIND_LABEL = { if: "if", while: "while", for: "for", do: "do-while" };

export class InsightStrip {
    constructor(container) {
        this.container = container;
    }

    clear() {
        this.container.replaceChildren();
    }

    render(step) {
        this.clear();
        const insight = step.insight;
        if (step.operations?.length) this.container.append(operationList(step));
        if (!insight) return;
        if (insight.condition) this.container.append(conditionCard(insight.condition));
        if (insight.loops?.length) this.container.append(loopChips(insight.loops, step));
    }
}

const KIND_WORD = {
    push: "push", pop: "pop", enqueue: "enqueue", dequeue: "dequeue",
    insert: "insert", remove: "remove", lookup: "look up", other: "",
};

/** "Line 9 called stack.push('(')" for each collection call made since the previous step. */
function operationList(step) {
    const list = document.createElement("div");
    list.className = "op-list";
    const byLine = new Map();
    for (const op of step.operations) {
        if (!byLine.has(op.line)) byLine.set(op.line, []);
        byLine.get(op.line).push(op);
    }
    for (const [line, ops] of byLine) {
        const row = document.createElement("div");
        row.className = "op-row";
        row.append(span("label", `line ${line} did`));
        for (const op of ops.slice(0, 6)) {
            const name = nameOf(step, op.ref) ?? op.type;
            const args = op.args.map((a) => formatValue(a, step.heap, 1)).join(", ");
            const chip = code(`${name}.${op.method}(${args})`, "op-call");
            chip.dataset.kind = op.kind ?? "other";
            if (KIND_WORD[op.kind]) chip.title = KIND_WORD[op.kind];
            row.append(chip);
        }
        if (ops.length > 6) row.append(span("gives", `+${ops.length - 6} more`));
        list.append(row);
    }
    return list;
}

function conditionCard(condition) {
    const result = condition.result === true ? "true" : condition.result === false ? "false" : "unknown";
    const card = document.createElement("div");
    card.className = "condition-card";
    card.dataset.result = result;

    const label = document.createElement("span");
    label.className = "label";
    label.textContent = `${KIND_LABEL[condition.kind] ?? condition.kind} condition, line ${condition.line}`;
    card.append(label, code(condition.text));

    if (condition.explanation !== condition.text) {
        card.append(span("gives", "is"), code(condition.explanation, "values"));
    }
    const pill = span("result-pill", result === "unknown" ? "not evaluated" : result);
    pill.dataset.result = result;
    card.append(span("gives", "so"), pill);
    return card;
}

function loopChips(loops, step) {
    const wrap = document.createElement("div");
    wrap.className = "loop-chips";
    for (const loop of loops) {
        const chip = document.createElement("span");
        chip.className = "loop-chip";
        const name = loop.variable ? `${loop.kind} loop over ${loop.variable}` : `${KIND_LABEL[loop.kind] ?? loop.kind} loop`;
        const value = loop.variable ? currentValue(step, loop.variable) : null;
        chip.append(`${name} (line ${loop.line}): iteration `);
        const strong = document.createElement("strong");
        strong.textContent = String(loop.iteration);
        chip.append(strong);
        if (value !== null) chip.append(`, ${loop.variable} = ${value}`);
        wrap.append(chip);
    }
    return wrap;
}

function currentValue(step, name) {
    const variable = step.stack[0].variables.find((v) => v.name === name);
    return variable && variable.value.kind !== "ref" ? variable.value.display : null;
}

function code(text, className) {
    const c = document.createElement("code");
    if (className) c.className = className;
    c.textContent = text;
    return c;
}

function span(className, text) {
    const s = document.createElement("span");
    s.className = className;
    s.textContent = text;
    return s;
}
