(() => {
  "use strict";

  const enhanceTable = (wrapper) => {
    const table = wrapper.querySelector(".notion-data-table");
    const headers = table?.tHead?.rows[0]?.cells;
    const frozenColumns = Number(wrapper.getAttribute("data-frozen-columns"));
    if (!headers || !Number.isInteger(frozenColumns) || frozenColumns < 1 || frozenColumns > headers.length) return;

    const frozenCells = Array.from({ length: frozenColumns }, (_, index) =>
      Array.from(table.rows)
        .map((row) => row.cells[index])
        .filter((cell) => cell?.classList.contains("notion-data-frozen")),
    );
    let scheduled = false;

    const measure = () => {
      scheduled = false;
      const widths = Array.from(headers).slice(0, frozenColumns).map((cell) => cell.getBoundingClientRect().width);
      const frozenWidth = widths.reduce((total, width) => total + width, 0);
      const visible = widths.every((width) => Number.isFinite(width) && width > 0);

      // A frozen region wider than its viewport would conceal the remaining columns.
      if (!visible || frozenWidth >= wrapper.clientWidth) {
        wrapper.classList.remove("data-sticky-ready");
        return;
      }

      let left = 0;
      frozenCells.forEach((cells, index) => {
        const offset = `${Math.round(left * 100) / 100}px`;
        cells.forEach((cell) => { cell.style.left = offset; });
        left += widths[index];
      });
      wrapper.classList.add("data-sticky-ready");
    };

    const schedule = () => {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(measure);
    };

    if (typeof ResizeObserver === "function") {
      const observer = new ResizeObserver(schedule);
      observer.observe(wrapper);
      observer.observe(table);
    }

    // Hidden tabs have no measurable widths; wait until every containing tab is visible.
    const visibilityObserver = new MutationObserver(schedule);
    for (let ancestor = wrapper.parentElement; ancestor; ancestor = ancestor.parentElement) {
      if (ancestor.matches('[role="tabpanel"]')) {
        visibilityObserver.observe(ancestor, { attributes: true, attributeFilter: ["hidden"] });
      }
    }

    window.addEventListener("resize", schedule, { passive: true });
    schedule();
  };

  const initialize = () => {
    document.querySelectorAll(".notion-data-table-wrapper[data-frozen-columns]").forEach(enhanceTable);
    document.querySelectorAll(".notion-data-cover").forEach((image) => {
      image.addEventListener("error", () => { image.hidden = true; }, { once: true });
      if (image.complete && image.naturalWidth === 0) image.hidden = true;
    });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initialize, { once: true });
  } else {
    initialize();
  }
})();
