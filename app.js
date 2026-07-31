// ── Theme ─────────────────────────────────────────────────────
const saved = localStorage.getItem('theme') || 'dark';
document.documentElement.setAttribute('data-theme', saved);

function toggleTheme() {
    const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('theme', next);
    document.querySelectorAll('.theme-btn').forEach(b => b.textContent = next === 'dark' ? '☀️ Light' : '🌙 Dark');
}

// ── Date filters ───────────────────────────────────────────────
function setQuick(days) {
    const to   = new Date();
    const from = new Date();
    from.setDate(from.getDate() - days);
    document.getElementById('date-from').value = from.toISOString().split('T')[0];
    document.getElementById('date-to').value   = to.toISOString().split('T')[0];
    document.querySelectorAll('.quick-btn').forEach(b => b.classList.remove('active'));
    document.querySelector(`.quick-btn[data-days="${days}"]`)?.classList.add('active');
}

function initFilters() {
    const params  = new URLSearchParams(location.search);
    const fromVal = params.get('from') || daysAgo(14);
    const toVal   = params.get('to')   || today();
    document.getElementById('date-from').value = fromVal;
    document.getElementById('date-to').value   = toVal;
    const proj = params.get('project') || 'all';
    const sel  = document.getElementById('project-select');
    if (sel) sel.value = proj;

    // Mark active quick button
    const diff = Math.round((new Date(toVal) - new Date(fromVal)) / 86400000);
    [1,7,14,30].forEach(d => {
        if (d === diff) document.querySelector(`.quick-btn[data-days="${d}"]`)?.classList.add('active');
    });
}

function applyFilters() {
    const from = document.getElementById('date-from').value;
    const to   = document.getElementById('date-to').value;
    const proj = document.getElementById('project-select')?.value || 'all';
    const url  = new URL(location.href);
    url.searchParams.set('from',    from);
    url.searchParams.set('to',      to);
    url.searchParams.set('project', proj);
    location.href = url.toString();
}

function filterParams() {
    const p = new URLSearchParams(location.search);
    return {
        from:    p.get('from')    || daysAgo(14),
        to:      p.get('to')      || today(),
        project: p.get('project') || 'all'
    };
}

function daysAgo(n) {
    const d = new Date(); d.setDate(d.getDate() - n);
    return d.toISOString().split('T')[0];
}
function today() { return new Date().toISOString().split('T')[0]; }

// ── Image overlay ──────────────────────────────────────────────
function showImage(src) {
    const ov  = document.getElementById('overlay');
    const img = document.getElementById('overlay-img');
    img.src   = src;
    ov.classList.add('open');
}
function closeOverlay() {
    document.getElementById('overlay').classList.remove('open');
}
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeOverlay(); });

// ── Helpers ────────────────────────────────────────────────────
function pct(pass, total) {
    if (!total) return 0;
    return Math.round(pass / total * 100);
}

function progressBar(pass, fail, skip) {
    const total = pass + fail + skip || 1;
    const p = pct(pass, total), f = pct(fail, total), s = 100 - p - f;
    return `<div class="progress">
        <div class="progress-pass" style="width:${p}%"></div>
        <div class="progress-fail" style="width:${f}%"></div>
        <div class="progress-skip" style="width:${s}%"></div>
    </div>`;
}

function badge(status) {
    return `<span class="badge badge-${status}">${status}</span>`;
}

function pctBadge(pass, total) {
    const p = pct(pass, total);
    return `<span class="pct ${p >= 80 ? 'good' : 'bad'}">${p}%</span>`;
}

function formatDuration(ms) {
    if (!ms) return '—';
    const s = Math.floor(ms / 1000);
    if (s < 60)   return s + 's';
    if (s < 3600) return Math.floor(s/60) + 'm ' + (s%60) + 's';
    return Math.floor(s/3600) + 'h ' + Math.floor((s%3600)/60) + 'm';
}

function formatDate(str) {
    if (!str) return '—';
    return new Date(str).toLocaleString('sk-SK');
}

async function fetchJson(url) {
    const res  = await fetch(url);
    const text = await res.text();
    try {
        return JSON.parse(text);
    } catch (e) {
        console.error('API error at', url, ':', text);
        throw new Error('API vrátilo neplatný JSON. Skontroluj konzolu.');
    }
}

// ── Expand/collapse rows ───────────────────────────────────────
function toggleRow(btn, rowId, loader) {
    const row = document.getElementById(rowId);
    const open = row.classList.toggle('open');
    btn.classList.toggle('open', open);
    if (open && loader && !row.dataset.loaded) {
        row.dataset.loaded = '1';
        loader();
    }
}

// ── Stack trace toggle ─────────────────────────────────────────
function toggleStack(el) {
    const st = el.nextElementSibling;
    st.classList.toggle('open');
    el.textContent = st.classList.contains('open') ? '▲ hide stack trace' : '▼ show stack trace';
}
