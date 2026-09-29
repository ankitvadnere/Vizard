// Owns "which step are we on" and automatic playback. Knows nothing about the DOM.
//
// State: steps[], index, playing, speed. Every change notifies listeners with the
// current step, so rendering is always a pure function of (steps, index).

const BASE_DELAY_MS = 700; // time per step at 1x

export class PlaybackController {
    #steps = [];
    #index = 0;
    #playing = false;
    #speed = 1;
    #timer = null;
    #listeners = new Set();

    load(steps) {
        this.pause();
        this.#steps = steps;
        this.#index = 0;
        this.#emit();
    }

    clear() {
        this.pause();
        this.#steps = [];
        this.#index = 0;
    }

    get total() { return this.#steps.length; }
    get index() { return this.#index; }
    get playing() { return this.#playing; }
    get current() { return this.#steps[this.#index] ?? null; }
    /** The step that executed just before this one (not "the one you came from"). */
    get previous() { return this.#index > 0 ? this.#steps[this.#index - 1] : null; }
    get atStart() { return this.#index === 0; }
    get atEnd() { return this.#index >= this.#steps.length - 1; }

    goTo(index) {
        const clamped = Math.max(0, Math.min(index, this.#steps.length - 1));
        if (clamped === this.#index) return;
        this.#index = clamped;
        this.#emit();
    }

    next() {
        if (this.atEnd) {
            this.pause();
            return false;
        }
        this.goTo(this.#index + 1);
        return true;
    }

    prev() { this.goTo(this.#index - 1); }
    first() { this.goTo(0); }
    last() { this.goTo(this.#steps.length - 1); }

    play() {
        if (this.total === 0) return;
        if (this.atEnd) this.goTo(0);
        this.#playing = true;
        this.#schedule();
        this.#emit();
    }

    pause() {
        const wasPlaying = this.#playing;
        this.#playing = false;
        clearTimeout(this.#timer);
        if (wasPlaying) this.#emit();
    }

    toggle() {
        this.#playing ? this.pause() : this.play();
    }

    setSpeed(speed) {
        this.#speed = speed;
        if (this.#playing) this.#schedule();
    }

    onChange(listener) {
        this.#listeners.add(listener);
    }

    #schedule() {
        clearTimeout(this.#timer);
        this.#timer = setTimeout(() => {
            if (this.next() && this.#playing) this.#schedule();
        }, BASE_DELAY_MS / this.#speed);
    }

    #emit() {
        for (const listener of this.#listeners) listener(this);
    }
}
