// Not-found page: show the exact path that was requested (textContent, never innerHTML).
// A separate file rather than inline, so the Content-Security-Policy can forbid inline scripts.
(function () {
  var path = location.pathname;
  try { path = decodeURIComponent(path); } catch (e) { /* malformed %-escape: show it raw */ }
  if (path.length < 2) return;
  var explain = document.getElementById('explain');
  var code = document.createElement('code');
  code.className = 'path';
  code.textContent = path;
  explain.replaceChildren(
    document.createTextNode('There’s no short link at '), code,
    document.createTextNode('. It may have been typed wrong. Short codes are case-sensitive, so check the capital letters.'));
})();
