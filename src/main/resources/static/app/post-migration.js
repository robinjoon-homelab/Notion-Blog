import { ApiError, requestJson } from "/ui/api.js";

const page = document.querySelector("[data-post-migration]");
if (page) {
    const request = page.querySelector("[data-migration-request]");
    const rows = [...page.querySelectorAll("[data-migration-row]")];
    const status = page.querySelector("[data-migration-status]");
    const summary = page.querySelector("[data-migration-summary]");
    const progress = page.querySelector("[data-migration-progress]");
    const pause = page.querySelector("[data-migration-pause]");
    const resume = page.querySelector("[data-migration-resume]");
    const retry = page.querySelector("[data-migration-retry]");
    const fieldNames = [
        "dataSourceId",
        "titlePropertyId",
        "summaryPropertyId",
        "tagsPropertyId",
        "authoredAtPropertyId",
    ];
    const mapping = Object.fromEntries(
        fieldNames.map((name) => [name, request.querySelector(`input[name="${name}"]`).value]),
    );
    let running = false;
    let paused = false;

    function updateSummary() {
        const count = (state) => rows.filter((row) => row.dataset.state === state).length;
        const imported = count("imported");
        const skipped = count("skipped");
        const failed = count("failed");
        progress.value = imported + skipped + failed;
        summary.textContent = `전체 ${rows.length}개 · 가져옴 ${imported}개 · 기존 글 ${skipped}개 · 실패 ${failed}개`;
        pause.hidden = !running;
        resume.hidden = running || !rows.some((row) => row.dataset.state === "pending");
        retry.hidden = running || failed === 0;
    }

    function showResult(row, result) {
        const state = ["imported", "skipped", "failed"].includes(result?.status) ? result.status : "failed";
        row.dataset.state = state;
        row.querySelector("[data-migration-result]").textContent =
            result?.message || "응답을 확인하지 못했습니다. 다시 시도하세요.";
        const link = row.querySelector("[data-migration-post]");
        if (
            state !== "failed" &&
            typeof result.href === "string" &&
            /^\/admin\/posts\/[a-f0-9-]{36}$/i.test(result.href)
        ) {
            link.href = result.href;
            link.hidden = false;
        }
        updateSummary();
    }

    async function importRow(row) {
        row.querySelector("[data-migration-result]").textContent = "가져오는 중…";
        try {
            const result = await requestJson(request.dataset.endpoint, {
                method: "POST",
                body: { mapping, sourceId: row.dataset.sourceId, externalId: row.dataset.externalId },
            });
            showResult(row, result);
        } catch (error) {
            paused = true;
            const message = error instanceof ApiError ? error.message : "서버에 연결하지 못했습니다.";
            const responseStatus =
                error instanceof ApiError && error.status >= 400 && error.status <= 599
                    ? `\n블로그 응답: HTTP ${error.status}`
                    : "";
            showResult(row, {
                status: "failed",
                message: `${message}${responseStatus}\n연결과 로그인 상태를 확인한 뒤 다시 시도하세요. 저장된 글은 유지됩니다.`,
            });
        }
    }

    async function run(queue) {
        if (running) return;
        running = true;
        paused = false;
        status.textContent = "마이그레이션 진행 중입니다.";
        updateSummary();
        for (const row of queue) {
            if (paused) break;
            await importRow(row);
        }
        running = false;
        status.textContent = paused
            ? "일시 정지했습니다. 저장된 글은 유지됩니다."
            : "이번 작업을 마쳤습니다. 실패한 글은 확인 후 다시 시도할 수 있습니다.";
        updateSummary();
    }

    pause.addEventListener("click", () => {
        paused = true;
        pause.disabled = true;
        status.textContent = "현재 글 처리가 끝나면 일시 정지합니다.";
    });
    resume.addEventListener("click", () => {
        pause.disabled = false;
        run(rows.filter((row) => row.dataset.state === "pending"));
    });
    retry.addEventListener("click", () => {
        pause.disabled = false;
        run(rows.filter((row) => row.dataset.state === "failed"));
    });
    run(rows);
}
