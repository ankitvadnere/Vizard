// Tabs inside one pane: clicking a [role=tab] shows the [role=tabpanel] with the same name.

export function createTabs(pane) {
    const tabs = [...pane.querySelectorAll('[role="tab"]')];
    const panels = [...pane.querySelectorAll('[role="tabpanel"]')];

    function select(name) {
        for (const tab of tabs) tab.setAttribute("aria-selected", String(tab.dataset.tab === name));
        for (const panel of panels) panel.hidden = panel.dataset.panel !== name;
    }

    for (const tab of tabs) tab.addEventListener("click", () => select(tab.dataset.tab));
    return { select };
}
