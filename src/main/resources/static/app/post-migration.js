import { ApiError, requestJson } from "/ui/api.js";

const page = document.querySelector("[data-post-migration]");
if (page) {
    const endpoint = new URL(page.dataset.statusEndpoint, window.location.href);
    const rows = new Map(
        [...page.querySelectorAll("[data-migration-row]")].map((row) => [row.dataset.migrationRow, row]),
    );
    const status = page.querySelector("[data-migration-status]");
    const summary = page.querySelector("[data-migration-summary]");
    const progress = page.querySelector("[data-migration-progress]");
    const connection = page.querySelector("[data-migration-connection]");
    const pause = page.querySelector("[data-migration-pause]");
    const resume = page.querySelector("[data-migration-resume]");
    const retry = page.querySelector("[data-migration-retry]");

    function showResult(row, result) {
        row.dataset.state = result.status;
        row.querySelector("[data-migration-result]").textContent = result.message;
        const link = row.querySelector("[data-migration-post]");
        if (typeof result.href === "string" && /^\/admin\/posts\/[a-f0-9-]{36}$/i.test(result.href)) {
            link.href = result.href;
            link.hidden = false;
        } else {
            link.removeAttribute("href");
            link.hidden = true;
        }
    }

    function reportConnectionFailure(retrySoon) {
        connection.textContent = retrySoon
            ? "상태를 잠시 조회하지 못했습니다. 서버 작업은 계속되며 곧 다시 확인합니다."
            : "상태를 조회하지 못했습니다. 작업은 서버에서 계속됩니다. 화면을 새로고침하세요.";
        connection.hidden = false;
        if (retrySoon) window.setTimeout(poll, 10000);
    }

    async function poll() {
        endpoint.searchParams.set("after", page.dataset.version ?? "-1");
        let snapshot;
        try {
            snapshot = await requestJson(endpoint);
        } catch (error) {
            const serverError = error instanceof ApiError && error.status >= 500 && error.status < 600;
            reportConnectionFailure(error instanceof TypeError || serverError);
            return;
        }
        if (!snapshot?.results || !snapshot.progress?.actions || typeof snapshot.version !== "string") {
            reportConnectionFailure(false);
            return;
        }
        for (const [position, result] of Object.entries(snapshot.results)) {
            const row = rows.get(position);
            if (row) showResult(row, result);
        }
        const state = snapshot.progress;
        if (status.textContent !== state.status) status.textContent = state.status;
        summary.textContent = state.summary;
        if (progress) progress.value = state.completed;
        pause.hidden = !state.actions.pause;
        resume.hidden = !state.actions.resume;
        retry.hidden = !state.actions.retryFailed;
        page.dataset.version = snapshot.version;
        connection.hidden = true;
        if (state.shouldPoll) window.setTimeout(poll, 3000);
    }

    window.setTimeout(poll, 3000);
}
