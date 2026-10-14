// N-Queens as a chessboard, drawn from the program's own board array at this step:
//   board[row][col] = 1 (or true)   → a queen on that square
//   queens[row] = col               → a queen in each row above the one being filled
// The square being tried by the deepest running call is outlined amber (red if the check just
// rejected it); squares the placed queens attack are shaded, which is exactly what isSafe() checks.

import { el, svgRoot, text } from "./svg.js";
import { stateAt } from "./SearchTreeVisualizer.js";

const CELL = 30;
const LABEL = 18;

/**
 * @returns a block element, or null if the board can't be found at this step
 */
export function drawBoard(tree, step) {
    const board = findArray(step, tree.assignment);
    if (!board) return null;
    const n = board.elements.length;
    if (n === 0 || n > 12) return null;

    const queens = new Set();
    const transposed = tree.assignmentShape === "matrix-transposed";
    const frame = deepestSearchFrame(step, tree);
    const depth = frame ? number(frame, tree.depthVariable) : null;
    const choice = frame ? number(frame, tree.choiceVariable) : null;

    if (tree.assignmentShape.startsWith("matrix")) {
        board.elements.forEach((cell, i) => {
            const row = cell.kind === "ref" ? step.heap[cell.ref] : null;
            row?.elements.forEach((v, j) => {
                if (v.value === true || (typeof v.value === "number" && v.value !== 0)) queens.add(`${i},${j}`);
            });
        });
    } else {
        // queens[row] = col: only rows above the current one hold placed queens.
        const filled = depth == null ? n : depth;
        board.elements.forEach((v, r) => {
            if (r < filled && typeof v.value === "number" && v.value >= 0 && v.value < n) queens.add(`${r},${v.value}`);
        });
    }

    let tryCell = null;
    if (depth != null && choice != null && choice >= 0 && choice < n && depth < n) {
        tryCell = transposed ? [choice, depth] : [depth, choice];
    }
    const rejectedNow = tree.nodes.some((node) => node.kind === "rejected" && node.firstStep === step.index);

    const attacked = new Set();
    for (const q of queens) {
        const [r, c] = q.split(",").map(Number);
        for (let i = 0; i < n; i++) {
            attacked.add(`${r},${i}`);
            attacked.add(`${i},${c}`);
            for (const [dr, dc] of [[1, 1], [1, -1], [-1, 1], [-1, -1]]) {
                const rr = r + dr * i;
                const cc = c + dc * i;
                if (rr >= 0 && rr < n && cc >= 0 && cc < n) attacked.add(`${rr},${cc}`);
            }
        }
    }

    const size = LABEL + n * CELL + 4;
    const svg = svgRoot(size, size, `Board with queens at ${[...queens].join("; ") || "no squares yet"}`);
    svg.classList.add("board-svg");
    for (let c = 0; c < n; c++) svg.append(text("index", LABEL + c * CELL + CELL / 2, 11, String(c), "middle"));
    for (let r = 0; r < n; r++) {
        svg.append(text("index", 8, LABEL + r * CELL + CELL / 2, String(r), "middle"));
        for (let c = 0; c < n; c++) {
            const key = `${r},${c}`;
            const isTry = tryCell && tryCell[0] === r && tryCell[1] === c;
            const cls = ["square", (r + c) % 2 ? "dark" : "light", attacked.has(key) && !queens.has(key) ? "attacked" : "",
                isTry ? (rejectedNow ? "try rejected" : "try") : ""].filter(Boolean).join(" ");
            const g = el("g", { class: cls });
            g.append(el("rect", { x: LABEL + c * CELL, y: LABEL + r * CELL, width: CELL, height: CELL }));
            if (queens.has(key)) g.append(text("queen", LABEL + c * CELL + CELL / 2, LABEL + r * CELL + CELL / 2 + 1, "♛", "middle"));
            svg.append(g);
        }
    }

    const block = document.createElement("div");
    block.className = "array-block board-block";
    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = tree.assignment;
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = `${n} × ${n} board · ${queens.size} ${queens.size === 1 ? "queen" : "queens"} placed`;
    title.append(meta);
    block.append(title);
    const scroll = document.createElement("div");
    scroll.className = "array-scroll";
    scroll.append(svg);
    block.append(scroll);
    const note = document.createElement("p");
    note.className = "viz-note";
    note.textContent = tryCell
        ? `Trying row ${tryCell[0]}, column ${tryCell[1]}${rejectedNow ? ": attacked, so it is rejected" : ""}. Shaded squares are attacked by the queens already placed.`
        : "Shaded squares are attacked by the queens already placed.";
    block.append(note);
    return block;
}

/** The values the program's colouring array holds now (color[v] = c), or null. */
export function assignmentValues(tree, step) {
    const array = findArray(step, tree.assignment);
    return array ? array.elements.map((v) => (typeof v.value === "number" ? v.value : 0)) : null;
}

/** Is the search running at this step (its first call started and the last has not finished)? */
export function searchStarted(tree, step) {
    return tree.nodes.length > 0 && stateAt(tree.nodes[0], step.index) !== "hidden";
}

function findArray(step, name) {
    if (!name) return null;
    for (const frame of step.stack) {
        const v = frame.variables.find((x) => x.name === name && x.value.kind === "ref");
        if (v) return step.heap[v.value.ref] ?? null;
    }
    const s = step.statics.find((x) => x.name.endsWith(`.${name}`) && x.value.kind === "ref");
    return s ? step.heap[s.value.ref] ?? null : null;
}

function deepestSearchFrame(step, tree) {
    return step.stack.find((f) => f.methodName === tree.method) ?? null;
}

function number(frame, name) {
    if (!name) return null;
    const v = frame.variables.find((x) => x.name === name);
    return v && typeof v.value.value === "number" ? v.value.value : null;
}
