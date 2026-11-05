// Home page behaviour: shorten via the REST API, show the result, keep a per-browser history.
// Everything user-supplied is written with textContent / href, never innerHTML.
(() => {
  'use strict';

  const STORAGE_KEY = 'url-shortener:recent';
  const MAX_RECENT = 8;

  // Friendly text per validation constraint (the API's errors[].code); falls back to the API message.
  const FRIENDLY_ERRORS = {
    NotBlank: 'Paste a URL first.',
    Size: 'That URL is too long. The limit is 2,048 characters.',
    HttpUrl: "That doesn't look like a web address. It needs to be an http:// or https:// link.",
  };

  const $ = (id) => document.getElementById(id);
  const form = $('shorten-form');
  const input = $('url');
  const submit = $('submit');
  const error = $('error');
  const result = $('result');
  const resultLink = $('result-link');
  const resultOriginal = $('result-original');
  const copyButton = $('copy');
  const openLink = $('open');
  const recent = $('recent');
  const recentList = $('recent-list');
  const clearButton = $('clear');

  // ---- Shorten -------------------------------------------------------------

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    hideError();

    const url = withScheme(input.value);
    if (!url) {
      showError(FRIENDLY_ERRORS.NotBlank);
      input.focus();
      return;
    }

    setBusy(true);
    try {
      const response = await fetch('/api/urls', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ url }),
      });
      const body = await response.json().catch(() => null);

      if (response.ok && body) {
        showResult(body);
        remember(body);
        input.value = '';
      } else {
        showError(messageFrom(body, response.status));
        input.focus();
      }
    } catch {
      showError("Couldn't reach the server. Check that it's running and try again.");
    } finally {
      setBusy(false);
    }
  });

  /** "example.com/x" -> "https://example.com/x". Anything with its own scheme is sent as typed. */
  function withScheme(raw) {
    const value = raw.trim();
    if (!value) return '';
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) return value;   // http://, https://, ftp://
    if (/^[a-z][a-z0-9+.-]*:(?!\d)/i.test(value)) return value;  // javascript:, mailto: (server rejects)
    return 'https://' + value;                                   // incl. host:port like localhost:3000
  }

  function messageFrom(body, status) {
    const first = body && Array.isArray(body.errors) ? body.errors[0] : null;
    if (first) return FRIENDLY_ERRORS[first.code] || first.message;
    if (body && body.detail) return body.detail;
    return `Something went wrong (HTTP ${status}). Try again.`;
  }

  function setBusy(busy) {
    submit.disabled = busy;
    submit.textContent = busy ? 'Shortening…' : 'Shorten';
  }

  function showError(message) {
    error.textContent = message;
    error.hidden = false;
  }

  function hideError() {
    error.hidden = true;
    error.textContent = '';
  }

  // ---- Result --------------------------------------------------------------

  function showResult(link) {
    resultLink.href = link.shortUrl;
    resultLink.textContent = link.shortUrl;
    openLink.href = link.shortUrl;
    resultOriginal.textContent = link.originalUrl;
    copyButton.dataset.url = link.shortUrl;
    resetButton(copyButton);
    result.hidden = false;
  }

  copyButton.addEventListener('click', () => copy(copyButton.dataset.url, copyButton));

  async function copy(text, button) {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Older browsers / non-secure origins: fall back to a temporary textarea.
      const area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      area.remove();
    }
    button.textContent = 'Copied';
    button.classList.add('is-done');
    clearTimeout(button._timer);
    button._timer = setTimeout(() => resetButton(button), 1600);
  }

  function resetButton(button) {
    button.textContent = 'Copy';
    button.classList.remove('is-done');
  }

  // ---- Recent links (this browser only) ----------------------------------------

  function loadRecent() {
    try {
      const stored = JSON.parse(localStorage.getItem(STORAGE_KEY));
      return Array.isArray(stored) ? stored : [];
    } catch {
      return [];
    }
  }

  function saveRecent(items) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch {
      // Storage unavailable (private mode, blocked): history just won't persist.
    }
  }

  function remember(link) {
    const entry = { shortCode: link.shortCode, shortUrl: link.shortUrl, originalUrl: link.originalUrl };
    const items = [entry, ...loadRecent().filter((item) => item.shortCode !== link.shortCode)];
    saveRecent(items.slice(0, MAX_RECENT));
    renderRecent();
  }

  function renderRecent() {
    const items = loadRecent();
    recentList.replaceChildren(...items.map(recentItem));
    recent.hidden = items.length === 0;
  }

  function recentItem(link) {
    const li = document.createElement('li');

    const code = document.createElement('a');
    code.className = 'code';
    code.href = link.shortUrl;
    code.target = '_blank';
    code.rel = 'noopener';
    code.textContent = link.shortUrl.replace(/^https?:\/\//, '');

    const original = document.createElement('span');
    original.className = 'orig';
    original.textContent = link.originalUrl;
    original.title = link.originalUrl;

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn';
    button.textContent = 'Copy';
    button.addEventListener('click', () => copy(link.shortUrl, button));

    li.append(code, original, button);
    return li;
  }

  clearButton.addEventListener('click', () => {
    saveRecent([]);
    renderRecent();
  });

  renderRecent();
})();
