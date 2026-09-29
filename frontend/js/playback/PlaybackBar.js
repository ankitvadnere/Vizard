// Connects the playback controls in the page to a PlaybackController.

import { describeStep } from "./describeStep.js";

export class PlaybackBar {
    constructor(controller, { onExit }) {
        this.controller = controller;
        this.root = document.getElementById("playback");
        this.slider = document.getElementById("step-slider");
        this.counter = document.getElementById("step-counter");
        this.description = document.getElementById("step-description");
        this.speed = document.getElementById("speed-select");
        this.buttons = Object.fromEntries(
            [...this.root.querySelectorAll("[data-action]")].map((b) => [b.dataset.action, b]));

        const actions = {
            first: () => controller.first(),
            prev: () => { controller.pause(); controller.prev(); },
            play: () => controller.toggle(),
            next: () => { controller.pause(); controller.next(); },
            last: () => controller.last(),
        };
        for (const [name, button] of Object.entries(this.buttons)) {
            button.addEventListener("click", actions[name]);
        }
        this.slider.addEventListener("input", () => {
            controller.pause();
            controller.goTo(Number(this.slider.value));
        });
        this.speed.addEventListener("change", () => controller.setSpeed(Number(this.speed.value)));
        document.getElementById("exit-trace").addEventListener("click", onExit);
        document.addEventListener("keydown", (e) => this.#onKey(e, actions));

        controller.onChange(() => this.render());
    }

    show() { this.root.hidden = false; }
    hide() { this.root.hidden = true; }

    render() {
        const c = this.controller;
        const step = c.current;
        if (!step) return;
        this.slider.max = String(c.total - 1);
        this.slider.value = String(c.index);
        this.counter.textContent = `Step ${c.index + 1} of ${c.total}`;
        this.description.textContent = describeStep(step, c.index === 0);
        this.description.dataset.event = step.event;

        this.buttons.first.disabled = c.atStart;
        this.buttons.prev.disabled = c.atStart;
        this.buttons.next.disabled = c.atEnd;
        this.buttons.last.disabled = c.atEnd;
        this.buttons.play.dataset.playing = String(c.playing);
        this.buttons.play.setAttribute("aria-label", c.playing ? "Pause (Space)" : "Play (Space)");
    }

    /** Arrow keys / Space / Home / End, unless the user is typing somewhere. */
    #onKey(e, actions) {
        if (this.root.hidden || e.ctrlKey || e.altKey || e.metaKey) return;
        const target = e.target;
        const typing = target.closest?.(".monaco-editor") || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
        if (typing) return;
        const map = { ArrowRight: "next", ArrowLeft: "prev", " ": "play", Home: "first", End: "last" };
        const action = map[e.key];
        if (action) {
            e.preventDefault();
            actions[action]();
        }
    }
}
