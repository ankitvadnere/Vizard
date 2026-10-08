// The call stack as a pile of boxes: the running method on top, main at the bottom.

import { callSignature } from "../values.js";

export class CallStackVisualizer {
    constructor(list) {
        this.list = list;
    }

    showEmpty(message = "Not running.") {
        this.list.replaceChildren();
        const li = document.createElement("li");
        li.className = "empty-state";
        li.textContent = message;
        this.list.append(li);
    }

    render(step) {
        this.list.replaceChildren();
        step.stack.forEach((frame, i) => {
            const li = document.createElement("li");
            if (i === 0) li.className = "current";
            li.append(callSignature(frame, step.heap));

            const line = document.createElement("span");
            line.className = "frame-line";
            line.textContent = `line ${frame.line}`;
            li.append(line);

            if (i === 0 && step.event === "RETURN" && step.returnValue) {
                const ret = document.createElement("span");
                ret.className = "frame-return";
                ret.textContent = `returns ${step.returnValue.display}`;
                li.append(ret);
            }
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
