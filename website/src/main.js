// Platform links open their real release status, including repeated clicks on the same link.
function revealPlatform(id = location.hash.slice(1), focus = false) {
  if (!id.endsWith('-detail')) return;
  const detail = document.getElementById(id);
  if (!(detail instanceof HTMLDetailsElement)) return;
  detail.open = true;
  if (focus) detail.querySelector('summary')?.focus({ preventScroll: true });
}
window.addEventListener('hashchange', () => revealPlatform());
for (const link of document.querySelectorAll('.platform')) {
  link.addEventListener('click', () => revealPlatform(link.hash.slice(1), true));
}
revealPlatform();
