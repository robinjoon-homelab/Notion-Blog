// 홈 화면 편집기의 미리보기. 입력 값은 서버로 보내지 않고 예시 목록의 클래스와 표시 여부만 바꾼다.
(() => {
  const form = document.querySelector('[data-home-editor]');
  if (!form) return;
  const list = form.querySelector('[data-preview-list]');
  const layouts = { LIST: 'plain', THUMBNAIL_LIST: 'thumbnails', CARDS: 'cards' };
  const orders = { NEWEST_FIRST: 'newest', OLDEST_FIRST: 'oldest' };

  const checkedValue = (name) => {
    const input = form.querySelector(`input[name="${name}"]:checked`);
    return input ? input.value : '';
  };
  const toggle = (selector, visible) => {
    list.querySelectorAll(selector).forEach((element) => { element.hidden = !visible; });
  };

  const render = () => {
    const layout = layouts[checkedValue('layout')] || 'plain';
    list.className = `nb-post-list nb-home-list nb-home-${layout}`;
    const order = orders[checkedValue('order')] || 'newest';
    list.dataset.order = order;
    toggle('[data-sample-summary]', form.elements.showSummary.checked);
    toggle('[data-sample-date]', form.elements.showDate.checked);
    toggle('[data-sample-categories]', form.elements.showCategories.checked);

    // 예시 글은 3개뿐이므로 쪽 크기가 더 작을 때만 정렬 순서대로 앞에서부터 줄여 보여 준다.
    const size = Number.parseInt(form.elements.pageSize.value, 10);
    const shown = Number.isInteger(size) ? Math.min(Math.max(size, 1), 3) : 3;
    list.querySelectorAll('[data-sample]').forEach((sample) => {
      const rank = order === 'oldest' ? 4 - Number(sample.dataset.sample) : Number(sample.dataset.sample);
      sample.hidden = rank > shown;
    });
    const output = form.querySelector('[data-preview-size]');
    if (output) output.value = Number.isInteger(size) ? String(size) : '';
  };

  form.addEventListener('input', render);
  form.addEventListener('change', render);
  render();
})();
