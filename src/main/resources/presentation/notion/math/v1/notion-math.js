(() => {
  "use strict";

  const renderMath = () => {
    const katex = window.katex;
    if (katex == null || typeof katex.render !== "function") {
      return;
    }

    document.querySelectorAll(".notion-math[data-expression]").forEach((node) => {
      const source = node.textContent ?? "";
      const expression = node.getAttribute("data-expression") ?? source;
      try {
        katex.render(expression, node, {
          displayMode: node.classList.contains("notion-math-block"),
          throwOnError: false,
          trust: false,
          strict: "warn",
          output: "mathml",
        });
        node.setAttribute("data-math-rendered", "true");
      } catch (_error) {
        node.textContent = source;
        node.removeAttribute("data-math-rendered");
      }
    });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", renderMath, { once: true });
  } else {
    renderMath();
  }
})();
