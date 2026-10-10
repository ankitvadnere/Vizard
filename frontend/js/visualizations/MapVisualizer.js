// Maps as a key → value table and sets as a row of members. New keys and changed values are
// highlighted; keys this step looked up (get, containsKey, contains) are outlined.

import { cellValue, formatValue } from "../values.js";
import { before, operationsOn } from "./visible.js";

const MAX_ROWS = 24;

export function drawMap({ names, object }, step, previous) {
    const old = before(previous, object);
    const oldValues = new Map((old?.entries ?? []).map((e) => [formatValue(e.key, previous.heap), cellValue(e.value, previous.heap)]));
    const looked = lookedUpKeys(step, object);

    const table = document.createElement("table");
    table.className = "map-table";
    const head = table.createTHead().insertRow();
    for (const label of ["key", "value"]) {
        const th = document.createElement("th");
        th.textContent = label;
        head.append(th);
    }
    const body = table.createTBody();
    for (const entry of object.entries.slice(0, MAX_ROWS)) {
        const key = formatValue(entry.key, step.heap);
        const value = cellValue(entry.value, step.heap);
        const row = body.insertRow();
        if (old && (!oldValues.has(key) || oldValues.get(key) !== value)) row.classList.add("changed");
        if (looked.has(key)) row.classList.add("looked-up");
        row.insertCell().textContent = key;
        row.insertCell().textContent = value;
    }
    if (object.entries.length === 0) {
        const cell = body.insertRow().insertCell();
        cell.colSpan = 2;
        cell.className = "empty-note";
        cell.textContent = "empty";
    }
    return wrap(names, `${object.type} · ${object.length} ${object.length === 1 ? "entry" : "entries"}`, table,
        object.length - object.entries.slice(0, MAX_ROWS).length);
}

export function drawSet({ names, object }, step, previous) {
    const old = before(previous, object);
    const oldMembers = new Set((old?.elements ?? []).map((e) => formatValue(e, previous.heap)));
    const looked = lookedUpKeys(step, object);

    const row = document.createElement("div");
    row.className = "set-members";
    for (const member of object.elements.slice(0, MAX_ROWS * 2)) {
        const label = formatValue(member, step.heap);
        const chip = document.createElement("span");
        chip.className = "set-member";
        if (old && !oldMembers.has(label)) chip.classList.add("changed");
        if (looked.has(label)) chip.classList.add("looked-up");
        chip.textContent = label;
        row.append(chip);
    }
    if (object.elements.length === 0) {
        const empty = document.createElement("span");
        empty.className = "empty-note";
        empty.textContent = "empty";
        row.append(empty);
    }
    return wrap(names, `${object.type} · ${object.length} ${object.length === 1 ? "member" : "members"}`, row,
        object.length - object.elements.slice(0, MAX_ROWS * 2).length);
}

/** The first argument of this step's lookups (get, containsKey, contains...), as displayed. */
function lookedUpKeys(step, object) {
    return new Set(operationsOn(step, object, "lookup")
        .filter((op) => op.args.length > 0)
        .map((op) => formatValue(op.args[0], step.heap)));
}

function wrap(names, meta, content, more) {
    const div = document.createElement("div");
    div.className = "array-block";
    const title = document.createElement("div");
    title.className = "array-title";
    title.textContent = names.join(", ");
    const span = document.createElement("span");
    span.className = "meta";
    span.textContent = meta;
    title.append(span);
    div.append(title, content);
    if (more > 0) {
        const p = document.createElement("p");
        p.className = "viz-note";
        p.textContent = `+${more} more not shown`;
        div.append(p);
    }
    return div;
}
