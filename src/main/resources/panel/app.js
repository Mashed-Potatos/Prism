// Prism admin panel — vanilla JS, no deps.

const $ = (id) => document.getElementById(id);

let consoleSocket = null;
let consoleSubserver = null;
let cachedSubservers = [];
const REFRESH_MS = 3000;

// ---------- auth bootstrap ----------
init();

async function init() {
  // Try a status fetch first; if it's 200, we're already logged in.
  const r = await fetch('/api/status', { credentials: 'same-origin' });
  if (r.ok) showApp(await r.json());
  else showLogin();
}

function showLogin() {
  $('login').classList.remove('hidden');
  $('app').classList.add('hidden');
}

function showApp(status) {
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
  renderStatus(status);
  setInterval(refresh, REFRESH_MS);
}

$('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('login-error').textContent = '';
  const username = $('login-user').value;
  const password = $('login-pass').value;
  const r = await fetch('/api/login', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  if (!r.ok) { $('login-error').textContent = 'Invalid username or password.'; return; }
  const status = await (await fetch('/api/status', { credentials: 'same-origin' })).json();
  showApp(status);
});

$('logout').addEventListener('click', async () => {
  await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' });
  closeConsole();
  location.reload();
});

// ---------- periodic refresh ----------
async function refresh() {
  try {
    const r = await fetch('/api/status', { credentials: 'same-origin' });
    if (r.status === 401) { showLogin(); return; }
    if (r.ok) renderStatus(await r.json());
  } catch (e) { /* network blip; ignore */ }
}

function renderStatus(s) {
  cachedSubservers = s.subservers;
  renderSubservers(s.subservers);
  renderPlayers(s.players, s.subservers);
}

function renderSubservers(list) {
  const root = $('subservers'); root.innerHTML = '';
  if (list.length === 0) { root.innerHTML = '<div class="mut">No subservers discovered.</div>'; return; }
  for (const s of list) {
    const card = document.createElement('div'); card.className = 'card';
    const stateClass = s.ready ? 'up' : (s.alive ? 'starting' : '');
    card.innerHTML = `
      <div class="head">
        <span class="dot ${stateClass}"></span>
        <span class="name ${consoleSubserver === s.name ? 'active' : ''}">${escape(s.name)}</span>
        <span class="mut">:${s.port}</span>
      </div>
      <div class="meta">${s.ready ? 'ready' : (s.alive ? 'starting…' : 'stopped')} · ${s.players} players</div>
      <div class="actions">
        <button data-action="start">Start</button>
        <button data-action="stop">Stop</button>
        <button data-action="restart">Restart</button>
      </div>`;
    card.querySelector('.name').addEventListener('click', () => attachConsole(s.name));
    for (const btn of card.querySelectorAll('button[data-action]')) {
      btn.addEventListener('click', () => action(s.name, btn.dataset.action));
    }
    root.appendChild(card);
  }
}

function renderPlayers(players, subservers) {
  const tbody = document.querySelector('#players tbody'); tbody.innerHTML = '';
  if (players.length === 0) {
    tbody.innerHTML = '<tr><td colspan="3" class="mut">no one connected</td></tr>'; return;
  }
  for (const p of players) {
    const tr = document.createElement('tr');
    const td1 = document.createElement('td'); td1.textContent = p.name;
    const td2 = document.createElement('td'); td2.textContent = p.subserver || '—';
    const td3 = document.createElement('td');
    const sel = document.createElement('select');
    const opts = [ document.createElement('option') ];
    opts[0].textContent = 'transfer…'; opts[0].value = '';
    for (const s of subservers) {
      if (s.name === p.subserver) continue;
      const o = document.createElement('option'); o.value = s.name; o.textContent = s.name;
      opts.push(o);
    }
    for (const o of opts) sel.appendChild(o);
    sel.addEventListener('change', async () => {
      if (!sel.value) return;
      await fetch(`/api/player/${encodeURIComponent(p.name)}/transfer`, {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ target: sel.value })
      });
      sel.value = ''; refresh();
    });
    td3.appendChild(sel); tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3);
    tbody.appendChild(tr);
  }
}

async function action(name, op) {
  await fetch(`/api/subserver/${encodeURIComponent(name)}/${op}`, {
    method: 'POST', credentials: 'same-origin'
  });
  refresh();
}

// ---------- broadcast ----------
$('broadcast-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const m = $('broadcast-msg').value.trim();
  if (!m) return;
  await fetch('/api/broadcast', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: m })
  });
  $('broadcast-msg').value = '';
});

// ---------- console (WebSocket) ----------
function closeConsole() {
  if (consoleSocket) { try { consoleSocket.close(); } catch {} consoleSocket = null; }
  consoleSubserver = null;
  $('console-target').textContent = '(select a subserver)';
  $('console-output').textContent = '';
  $('console-input').disabled = true;
  $('console-form').querySelector('button').disabled = true;
}

function attachConsole(name) {
  closeConsole();
  consoleSubserver = name;
  $('console-target').textContent = '— ' + name;
  $('console-input').disabled = false;
  $('console-form').querySelector('button').disabled = false;

  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  consoleSocket = new WebSocket(`${proto}://${location.host}/ws/console/${encodeURIComponent(name)}`);
  const out = $('console-output');
  consoleSocket.onmessage = (ev) => {
    try {
      const obj = JSON.parse(ev.data);
      if (obj.line !== undefined) {
        out.textContent += obj.line + '\n';
        out.scrollTop = out.scrollHeight;
      }
    } catch {}
  };
  consoleSocket.onclose = () => {
    if (consoleSubserver === name) {
      out.textContent += '\n[disconnected from ' + name + ']\n';
    }
  };
  refresh(); // re-highlight active card
}

$('console-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const cmd = $('console-input').value;
  if (!cmd || !consoleSocket || consoleSocket.readyState !== 1) return;
  consoleSocket.send(JSON.stringify({ cmd }));
  $('console-input').value = '';
});

function escape(s) { return String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' })[c]); }
