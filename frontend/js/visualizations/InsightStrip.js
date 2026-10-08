// The "what is this line doing" strip: the condition being tested and the loops we're inside.

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
        if (!insight) return;
        if (insight.condition) this.container.append(conditionCard(insight.condition));
        if (insight.loops?.length) this.container.append(loopChips(insight.loops, step));
    }
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
