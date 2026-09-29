// Shows the chain of method calls, innermost (currently running) at the top.

import { callSignature } from "../values.js";

export class CallStackVisualizer {
    constructor(list, depthLabel) {
        this.list = list;
        this.depthLabel = depthLabel;
    }

    showEmpty(message = "Nothing is running.") {
        this.depthLabel.textContent = "";
        this.list.replaceChildren();
        const li = document.createElement("li");
        li.className = "empty-state";
        li.textContent = message;
        this.list.append(li);
    }

    render(step) {
        this.depthLabel.textContent = step.depth === 1 ? "1 call" : `${step.depth} calls deep`;
        this.list.replaceChildren();
        step.stack.forEach((frame, i) => {
            const li = document.createElement("li");
            if (i === 0) li.className = "current";

            const signature = document.createElement("span");
            signature.textContent = callSignature(frame, step.heap);
            const line = document.createElement("span");
            line.className = "frame-line";
            line.textContent = `line ${frame.line}`;

            li.append(signature, line);
            this.list.append(li);
        });
        if (step.depth > step.stack.length) {
            const li = document.createElement("li");
            li.className = "empty-state";
            li.textContent = `… ${step.depth - step.stack.length} more calls`;
            this.list.append(li);
        }
    }
}
