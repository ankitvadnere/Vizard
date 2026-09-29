// One sentence explaining what a step is about to do or just did.

import { callSignature, methodLabel, simpleClassName } from "../values.js";

export function describeStep(step, isFirst) {
    const frame = step.stack[0];
    switch (step.event) {
        case "CALL":
            return isFirst
                ? `Program starts in ${methodLabel(frame)}, line ${step.line}`
                : `Called ${callSignature(frame, step.heap)}, now at line ${step.line}`;
        case "RETURN":
            return step.returnValue
                ? `${methodLabel(frame)} returns ${step.returnValue.display}`
                : `${methodLabel(frame)} finished`;
        case "EXCEPTION": {
            const message = step.exceptionMessage ? `: ${step.exceptionMessage}` : "";
            return `${simpleClassName(step.exceptionType)} thrown at line ${step.line}${message}`;
        }
        default:
            return `About to run line ${step.line}`;
    }
}
