// 좁은 화면에서 접힌 사이드바를 여닫는다.
document.addEventListener('click', (event) => {
  const button = event.target.closest('[data-toggle-menu]');
  if (!button) return;
  const shell = document.querySelector('.nb-shell');
  const open = shell.classList.toggle('nb-menu-open');
  button.setAttribute('aria-expanded', String(open));
});
