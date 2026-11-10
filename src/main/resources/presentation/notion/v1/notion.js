(() => {
  "use strict";

  const tabSelector = '[role="tab"]';

  const activate = (tabs, selectedTab, moveFocus) => {
    const selectedPanelId = selectedTab.getAttribute("aria-controls");
    if (!selectedPanelId) return;

    tabs.forEach((tab) => {
      const selected = tab === selectedTab;
      const panelId = tab.getAttribute("aria-controls");
      const panel = panelId ? document.getElementById(panelId) : null;

      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      if (panel) panel.hidden = !selected;
    });

    if (moveFocus) selectedTab.focus();
  };

  const enhanceTabList = (tabList) => {
    const tabs = Array.from(tabList.querySelectorAll(tabSelector));
    if (tabs.length === 0) return;

    const selectedTab = tabs.find((tab) => tab.getAttribute("aria-selected") === "true") ?? tabs[0];
    activate(tabs, selectedTab, false);

    tabs.forEach((tab, index) => {
      tab.addEventListener("click", () => activate(tabs, tab, false));
      tab.addEventListener("keydown", (event) => {
        let nextIndex = index;

        if (event.key === "ArrowRight" || event.key === "ArrowDown") nextIndex = (index + 1) % tabs.length;
        if (event.key === "ArrowLeft" || event.key === "ArrowUp") nextIndex = (index - 1 + tabs.length) % tabs.length;
        if (event.key === "Home") nextIndex = 0;
        if (event.key === "End") nextIndex = tabs.length - 1;

        if (nextIndex !== index) {
          event.preventDefault();
          activate(tabs, tabs[nextIndex], true);
          return;
        }

        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          activate(tabs, tab, false);
        }
      });
    });
  };

  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll('.notion-tabs [role="tablist"]').forEach(enhanceTabList);
  });
})();
