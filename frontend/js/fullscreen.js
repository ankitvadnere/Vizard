// Full-screen mode for the Visualization panel: big graphs, trees and boards get the whole screen.
// The step controls move into the panel while it is full screen (and back afterwards), so playback
// keeps working; the line being run is shown as text there, since the editor is hidden.
//
// Uses the browser's Fullscreen API when it is available; otherwise the panel covers the window.
// F toggles, Esc leaves.

const BODY_CLASS = "viz-focus";

export function initFullscreen() {
    const pane = document.querySelector(".viz-pane");
    const header = pane.querySelector(".pane-header");
    const button = document.getElementById("viz-fullscreen");
    const playback = document.getElementById("playback");
    const home = { parent: playback.parentNode, next: playback.nextSibling };

    const label = button.querySelector(".icon-text");
    const isOn = () => document.body.classList.contains(BODY_CLASS);

    const enter = () => {
        if (isOn()) return;
        document.body.classList.add(BODY_CLASS);
        header.after(playback); // controls at the top of the panel
        button.setAttribute("aria-pressed", "true");
        button.setAttribute("aria-label", "Leave full screen (Esc)");
        label.textContent = "Exit full screen";
        if (pane.requestFullscreen && !document.fullscreenElement) {
            pane.requestFullscreen().catch(() => { /* blocked: the panel still covers the window */ });
        }
    };

    const leave = () => {
        if (!isOn()) return;
        document.body.classList.remove(BODY_CLASS);
        home.parent.insertBefore(playback, home.next);
        button.setAttribute("aria-pressed", "false");
        button.setAttribute("aria-label", "Full screen (F)");
        label.textContent = "Full screen";
        if (document.fullscreenElement) {
            document.exitFullscreen().catch(() => {});
        }
    };

    button.addEventListener("click", () => (isOn() ? leave() : enter()));
    // Esc in real full screen is handled by the browser; follow it.
    document.addEventListener("fullscreenchange", () => {
        if (!document.fullscreenElement) leave();
    });
    document.addEventListener("keydown", (e) => {
        if (e.ctrlKey || e.altKey || e.metaKey) return;
        const typing = e.target.closest?.(".monaco-editor") || ["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName);
        if (typing) return;
        if (e.key === "Escape" && isOn()) {
            leave();
        } else if (e.key === "f" || e.key === "F") {
            e.preventDefault();
            isOn() ? leave() : enter();
        }
    });
}
