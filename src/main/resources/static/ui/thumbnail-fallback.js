// 썸네일을 불러오지 못하면 기본 이미지로 바꾼다. 인라인 핸들러는 CSP가 막으므로 캡처 단계에서 error를 받는다.
(() => {
  const swap = (image) => {
    const fallback = image.dataset.fallbackSrc;
    if (!fallback || image.dataset.fallbackApplied) return;
    image.dataset.fallbackApplied = 'true';
    image.src = fallback;
  };

  document.addEventListener('error', (event) => {
    if (event.target instanceof HTMLImageElement) swap(event.target);
  }, true);

  // 스크립트가 실행되기 전에 이미 실패한 이미지도 처리한다.
  document.querySelectorAll('img[data-fallback-src]').forEach((image) => {
    if (image.complete && image.naturalWidth === 0) swap(image);
  });
})();
