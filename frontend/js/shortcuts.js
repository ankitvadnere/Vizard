// The keyboard-shortcuts panel: the ? button in the toolbar, or the ? key anywhere outside the editor.

export function initShortcutsHelp() {
    const dialog = document.getElementById("shortcuts-dialog");
    const button = document.getElementById("help-button");
    if (!dialog || !button) return;
    const open = () => {
        if (!dialog.open) dialog.showModal();
    };
    button.addEventListener("click", open);
    document.addEventListener("keydown", (e) => {
        if (e.key !== "?" || e.ctrlKey || e.altKey || e.metaKey) return;
        const typing = e.target.closest?.(".monaco-editor") || ["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName);
        if (typing) return;
        e.preventDefault();
        open();
    });
    // a click on the backdrop closes it
    dialog.addEventListener("click", (e) => {
        if (e.target === dialog) dialog.close();
    });
}
