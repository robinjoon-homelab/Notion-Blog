// 시리즈 편집기. 글을 후보와 소속 목록 사이로 옮기고 순서를 바꾸지만 저장 버튼을 누르기 전에는 서버로 보내지 않는다.
// 소속 목록의 hidden input 순서가 저장되는 읽기 순서이고, 후보 목록의 input은 비활성이라 전송되지 않는다.
(() => {
  const form = document.querySelector('[data-series-editor]');
  if (!form) return;
  const members = form.querySelector('[data-series-members]');
  const candidates = form.querySelector('[data-series-candidates]');
  const status = form.querySelector('[data-series-status]');
  const membersEmpty = form.querySelector('[data-series-empty]');
  const candidatesEmpty = form.querySelector('[data-candidates-empty]');
  const filter = form.querySelector('[data-candidate-filter]');

  const titleOf = (item) => item.querySelector('.nb-series-title').textContent;
  const action = (item, name) => item.querySelector(`[data-action="${name}"]`);
  const announce = (text) => { status.textContent = text; };

  const applyFilter = () => {
    const query = filter ? filter.value.trim().toLowerCase() : '';
    candidates.querySelectorAll('li').forEach((item) => {
      item.hidden = query !== '' && !titleOf(item).toLowerCase().includes(query);
    });
  };

  // 첫 글의 위로와 마지막 글의 아래로는 쓸 수 없게 하고, 빈 목록 안내를 맞춘다.
  const refresh = () => {
    const items = [...members.children];
    items.forEach((item, index) => {
      action(item, 'up').disabled = index === 0;
      action(item, 'down').disabled = index === items.length - 1;
    });
    membersEmpty.hidden = items.length > 0;
    if (candidatesEmpty) candidatesEmpty.hidden = candidates.children.length > 0;
    applyFilter();
  };

  // 버튼이 숨겨지거나 비활성이 되어도 키보드 위치를 잃지 않도록 같은 글의 쓸 수 있는 버튼으로 옮긴다.
  const focusFirstUsable = (item, names) => {
    const target = names.map((name) => action(item, name)).find((button) => button && !button.disabled);
    if (target) target.focus();
  };

  const handlers = {
    up(item) {
      const previous = item.previousElementSibling;
      if (previous) members.insertBefore(item, previous);
      refresh();
      announce(`${titleOf(item)} 글을 ${[...members.children].indexOf(item) + 1}번째로 옮겼습니다.`);
      focusFirstUsable(item, ['up', 'down', 'remove']);
    },
    down(item) {
      const next = item.nextElementSibling;
      if (next) members.insertBefore(next, item);
      refresh();
      announce(`${titleOf(item)} 글을 ${[...members.children].indexOf(item) + 1}번째로 옮겼습니다.`);
      focusFirstUsable(item, ['down', 'up', 'remove']);
    },
    remove(item) {
      candidates.insertBefore(item, candidates.firstChild);
      item.querySelector('input[name="postId"]').disabled = true;
      refresh();
      announce(`${titleOf(item)} 글을 시리즈에서 제외했습니다. 저장해야 반영됩니다.`);
      focusFirstUsable(item, ['add']);
    },
    add(item) {
      members.appendChild(item);
      item.querySelector('input[name="postId"]').disabled = false;
      refresh();
      announce(`${titleOf(item)} 글을 시리즈 마지막에 추가했습니다. 저장해야 반영됩니다.`);
      focusFirstUsable(item, ['remove', 'up']);
    },
  };

  form.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || !form.contains(button)) return;
    const handler = handlers[button.dataset.action];
    if (handler) handler(button.closest('li'));
  });
  if (filter) filter.addEventListener('input', applyFilter);
  refresh();
})();
