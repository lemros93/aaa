<?php
require_once 'config/db.php';
$db         = getDb();
$pageTitle  = 'Timeline – Test Dashboard';
$activePage = 'timeline';

$from     = $_GET['from']     ?? date('Y-m-d', strtotime('-14 days'));
$to       = $_GET['to']       ?? date('Y-m-d');
$project  = $_GET['project']  ?? 'all';
$parallel = $_GET['parallel'] ?? 'all';
$jira     = $_GET['jira']     ?? 'all';
$toEnd    = $to . ' 23:59:59';

$filters = ['r.run_at BETWEEN :from AND :to'];
$params  = [':from' => $from, ':to' => $toEnd];
if ($project  !== 'all') { $filters[] = 'r.project = :project';        $params[':project']  = $project; }
if ($parallel !== 'all') { $filters[] = 'r.parallel_mode = :parallel'; $params[':parallel'] = $parallel; }
if ($jira     !== 'all') { $filters[] = 'r.jira_reporting = :jira';    $params[':jira']     = (int)($jira === 'yes'); }
$where = 'WHERE ' . implode(' AND ', $filters);

$sql = "SELECT r.id, r.project, r.run_at, r.build_number, r.branch,
               r.parallel_mode, r.thread_count,
               r.total_scenarios, r.passed_scenarios, r.failed_scenarios,
               ROUND(r.passed_scenarios/NULLIF(r.total_scenarios,0)*100,1) AS success_rate
        FROM runs r $where ORDER BY r.run_at DESC LIMIT 50";

$stmt = $db->prepare($sql);
foreach ($params as $k => $v) $stmt->bindValue($k, $v);
$stmt->execute();
$runs = $stmt->fetchAll();

include 'partials/header.php';
include 'partials/filters.php';
?>

<div class="main">

<div class="table-wrap">
    <div class="table-header">
        <span class="table-title">⏱ Timeline</span>
        <span class="table-count"><?= count($runs) ?> runov</span>
        <div class="flex ml-auto" style="gap:10px;font-size:12px">
            <span style="display:flex;align-items:center;gap:4px">
                <span style="width:12px;height:12px;background:var(--success);border-radius:2px;display:inline-block"></span> passed
            </span>
            <span style="display:flex;align-items:center;gap:4px">
                <span style="width:12px;height:12px;background:var(--danger);border-radius:2px;display:inline-block"></span> failed
            </span>
            <span style="display:flex;align-items:center;gap:4px">
                <span style="width:12px;height:12px;background:var(--skip);border-radius:2px;display:inline-block"></span> skipped
            </span>
        </div>
    </div>

    <?php if (empty($runs)): ?>
        <div class="empty"><div class="empty-icon">📭</div>Žiadne dáta</div>
    <?php else: ?>
    <table>
        <thead>
            <tr>
                <th></th>
                <th>Dátum</th>
                <th>Branch</th>
                <th>Build</th>
                <th>Beh</th>
                <th>Scenáre ✓/✗</th>
                <th>Úspešnosť</th>
            </tr>
        </thead>
        <tbody>
        <?php foreach ($runs as $r):
            $isP = ($r['parallel_mode'] ?? 'serial') === 'parallel';
            $sr  = $r['success_rate'] ?? 0;
        ?>
            <tr>
                <td style="width:32px">
                    <button class="expand-btn" id="tbtn-<?= $r['id'] ?>"
                            onclick="toggleTimeline(this, <?= $r['id'] ?>)">+</button>
                </td>
                <td class="nowrap"><?= date('d.m.Y H:i', strtotime($r['run_at'])) ?></td>
                <td class="mono"><?= htmlspecialchars($r['branch'] ?? '—') ?></td>
                <td class="mono"><?= htmlspecialchars($r['build_number']) ?></td>
                <td>
                    <?php if ($isP): ?>
                        <span style="color:var(--accent)">⚡ ×<?= (int)$r['thread_count'] ?></span>
                    <?php else: ?>
                        <span class="muted">serial</span>
                    <?php endif; ?>
                </td>
                <td>
                    <span style="color:var(--success)">✓<?= $r['passed_scenarios'] ?></span>
                    <span style="color:var(--danger)"> ✗<?= $r['failed_scenarios'] ?></span>
                    / <?= $r['total_scenarios'] ?>
                </td>
                <td><span class="pct <?= $sr>=80?'good':'bad' ?>"><?= $sr ?>%</span></td>
            </tr>
            <!-- Inline expand row -->
            <tr id="trow-<?= $r['id'] ?>" style="display:none">
                <td colspan="99" style="padding:0;border-top:2px solid var(--accent)">
                    <div style="padding:16px;background:var(--bg);overflow-x:auto">
                        <div id="tl-<?= $r['id'] ?>">
                            <div style="padding:16px;text-align:center">
                                <div class="spinner" style="margin:0 auto;width:24px;height:24px"></div>
                            </div>
                        </div>
                    </div>
                </td>
            </tr>
        <?php endforeach; ?>
        </tbody>
    </table>
    <?php endif; ?>
</div>

</div><!-- .main -->

<!-- Scenario tooltip -->
<div id="tl-tooltip" style="
    display:none;position:fixed;z-index:200;
    background:var(--bg2);border:1px solid var(--border);
    border-radius:6px;padding:10px 14px;font-size:12px;
    box-shadow:var(--shadow);max-width:320px;pointer-events:none">
</div>

<script>
const THREAD_H   = 36;
const THREAD_GAP = 8;
const LABEL_W    = 130;
const AXIS_H     = 28;
const BAR_H      = 22;
const BAR_RADIUS = 3;
const LEGEND_H   = 22;  // height of feature legend row above bars
const FEAT_COLORS = [
    '#3b5bdb','#0ca678','#e67700','#c92a2a',
    '#5c7cfa','#20c997','#fd7e14','#f03e3e',
    '#748ffc','#63e6be','#ffa94d','#ff8787',
];

async function toggleTimeline(btn, runId) {
    const row  = document.getElementById('trow-' + runId);
    const open = row.style.display === 'none';
    row.style.display = open ? 'table-row' : 'none';
    btn.classList.toggle('open', open);
    if (open && !row.dataset.loaded) {
        row.dataset.loaded = '1';
        await loadTimeline(runId);
    }
}

async function loadTimeline(runId) {
    const container = document.getElementById('tl-' + runId);
    const data = await fetchJson(`api/dev_data.php?action=timeline&run_id=${runId}`);

    if (!data.threads || Object.keys(data.threads).length === 0) {
        container.innerHTML = '<div class="empty">Žiadne timeline dáta — scenáre nemajú <code>started_at</code> timestamp.</div>';
        return;
    }

    renderTimeline(container, data.threads);
}

function renderTimeline(container, threads) {
    const threadKeys = Object.keys(threads).sort();

    let maxEnd = 0;
    const featureColors = {};
    let colorIdx = 0;

    threadKeys.forEach(t => {
        threads[t].forEach(s => {
            const end = (s.offset_ms || 0) + (s.duration_ms || 0);
            if (end > maxEnd) maxEnd = end;
            if (!featureColors[s.feature_name]) {
                featureColors[s.feature_name] = FEAT_COLORS[colorIdx % FEAT_COLORS.length];
                colorIdx++;
            }
        });
    });

    if (maxEnd === 0) {
        container.innerHTML = '<div class="empty">Žiadne dáta na zobrazenie</div>';
        return;
    }

    const W      = Math.max(container.clientWidth - LABEL_W - 32, 500);
    const lanesH  = threadKeys.length * (THREAD_H + THREAD_GAP);
    const totalH  = LEGEND_H + lanesH;
    const pxPerMs = W / maxEnd;
    const tickCount = 8;

    let svg = `<svg width="${LABEL_W + W + 20}" height="${totalH + AXIS_H + 10}"
                    xmlns="http://www.w3.org/2000/svg"
                    style="font-family:Inter,system-ui,sans-serif;display:block">`;

    // Feature legend (top, above bars)
    let legendX = LABEL_W;
    Object.entries(featureColors).forEach(([name, color]) => {
        const short = name.length > 22 ? name.substring(0, 21) + '\u2026' : name;
        const textW = short.length * 6.5 + 20;
        if (legendX + textW > LABEL_W + W) return;
        svg += `<g transform="translate(${legendX},6)">
            <rect width="10" height="10" fill="${color}" rx="2"/>
            <text x="14" y="9" font-size="10" fill="var(--text-muted)">${escHtml(short)}</text>
        </g>`;
        legendX += textW + 8;
    });

    // Thread lanes (offset by LEGEND_H)
    threadKeys.forEach((threadKey, ti) => {
        const y0  = LEGEND_H + ti * (THREAD_H + THREAD_GAP);
        const midY = y0 + THREAD_H / 2;
        const laneBg = ti % 2 === 0 ? 'var(--bg2)' : 'var(--bg3)';

        svg += `<rect x="0" y="${y0}" width="${LABEL_W + W + 20}" height="${THREAD_H}" fill="${laneBg}"/>`;

        // Thread label
        const tLabel = threadKey.replace(/cucumber-runner-/i,'T').replace(/thread-/i,'T');
        svg += `<text x="${LABEL_W-8}" y="${midY+4}" text-anchor="end"
                       font-size="11" font-weight="600" fill="var(--text)">${escHtml(tLabel)}</text>`;

        // Feature separator lines
        let lastFeat = null;
        threads[threadKey].forEach(s => {
            if (lastFeat && lastFeat !== s.feature_name) {
                const x = LABEL_W + Math.round(s.offset_ms * pxPerMs);
                svg += `<line x1="${x}" y1="${y0}" x2="${x}" y2="${y0+THREAD_H}"
                               stroke="rgba(255,255,255,0.25)" stroke-width="2" stroke-dasharray="3,2"/>`;
            }
            lastFeat = s.feature_name;
        });

        // Scenario bars
        threads[threadKey].forEach(s => {
            const x    = LABEL_W + Math.round((s.offset_ms || 0) * pxPerMs);
            const w    = Math.max(Math.round((s.duration_ms || 0) * pxPerMs), 3);
            const barY = y0 + (THREAD_H - BAR_H) / 2;

            const featColor = featureColors[s.feature_name];
            const barFill   = s.status === 'passed' ? 'var(--success)'
                            : s.status === 'failed' ? 'var(--danger)'
                            : 'var(--skip)';

            // Encode feature name as base64 to avoid quoting issues in HTML attribute
            const featAttr = encodeURIComponent(s.feature_name);
            const safeJson = JSON.stringify(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'");

            svg += `<rect x="${x}" y="${barY}" width="${w}" height="${BAR_H}"
                           fill="${barFill}" rx="${BAR_RADIUS}" opacity="0.85"
                           class="tl-bar" data-feature="${featAttr}"
                           style="cursor:pointer"
                           onmouseenter="showTooltip(event,'${safeJson}')"
                           onmouseleave="hideTooltip()"
                           onclick="clickBar(this,'${featAttr}')"/>`;

            // Feature color stripe top
            svg += `<rect x="${x}" y="${barY}" width="${w}" height="3"
                           fill="${featColor}" rx="${BAR_RADIUS}" opacity="0.95"
                           style="pointer-events:none" class="tl-stripe" data-feature="${featAttr}"/>`;

            // Label inside bar if wide enough
            if (w > 60) {
                const maxCh = Math.floor((w - 8) / 6);
                const label = s.scenario_name.length > maxCh
                    ? s.scenario_name.substring(0, maxCh-1) + '…'
                    : s.scenario_name;
                svg += `<text x="${x+4}" y="${barY+14}" font-size="10"
                               fill="white" style="pointer-events:none">${escHtml(label)}</text>`;
            }
        });
    });

    // Time axis at BOTTOM (under lanes only, not legend)
    const axisY = totalH;
    svg += `<g transform="translate(${LABEL_W},0)">`;
    for (let i = 0; i <= tickCount; i++) {
        const x  = Math.round(i / tickCount * maxEnd * pxPerMs);
        const ms = Math.round(i / tickCount * maxEnd);
        svg += `<line x1="${x}" y1="${LEGEND_H}" x2="${x}" y2="${axisY}"
                       stroke="var(--border)" stroke-width="1" opacity="0.4"/>`;
        svg += `<line x1="${x}" y1="${axisY}" x2="${x}" y2="${axisY+5}"
                       stroke="var(--text-muted)" stroke-width="1"/>`;
        svg += `<text x="${x}" y="${axisY+16}" text-anchor="middle"
                       font-size="10" fill="var(--text-muted)">${formatDuration(ms)}</text>`;
    }
    svg += `<line x1="0" y1="${axisY}" x2="${W}" y2="${axisY}"
                   stroke="var(--border)" stroke-width="1"/>`;
    svg += '</g>';

    svg += '</svg>';
    container.innerHTML = svg;
}

// ── Feature highlight on click ───────────────────────────────
let activeFeature = null;

function clickBar(el, featAttr) {
    const svg = el.closest('svg');
    if (!svg) return;

    // Klik na rovnakú feature → zruš výber
    if (activeFeature === featAttr) {
        clearHighlight(svg);
        activeFeature = null;
        return;
    }

    activeFeature = featAttr;

    // Všetky bary — stmav ostatné, zvýrazni vybraté
    svg.querySelectorAll('.tl-bar').forEach(bar => {
        if (bar.dataset.feature === featAttr) {
            bar.style.opacity   = '1';
            bar.style.filter    = 'drop-shadow(0 0 4px rgba(255,255,255,0.6))';
            bar.setAttribute('stroke', 'white');
            bar.setAttribute('stroke-width', '1.5');
        } else {
            bar.style.opacity = '0.2';
            bar.style.filter  = '';
            bar.removeAttribute('stroke');
        }
    });

    // Feature stripes — zvýrazni len aktívne
    svg.querySelectorAll('.tl-stripe').forEach(stripe => {
        stripe.style.opacity = stripe.dataset.feature === featAttr ? '1' : '0.1';
    });

    // Zobraz feature label v info boxe
    showFeatureInfo(svg, decodeURIComponent(featAttr));
}

function clearHighlight(svg) {
    svg.querySelectorAll('.tl-bar').forEach(bar => {
        bar.style.opacity = '0.85';
        bar.style.filter  = '';
        bar.removeAttribute('stroke');
    });
    svg.querySelectorAll('.tl-stripe').forEach(s => s.style.opacity = '0.95');
    // Schovaj feature info
    const info = svg.parentElement.querySelector('.tl-feat-info');
    if (info) info.style.display = 'none';
}

function showFeatureInfo(svg, featName) {
    const wrap = svg.parentElement;
    let info = wrap.querySelector('.tl-feat-info');
    if (!info) {
        info = document.createElement('div');
        info.className = 'tl-feat-info';
        info.style.cssText = `margin-top:8px;padding:8px 12px;background:var(--accent-dim);
            border:1px solid var(--accent);border-radius:6px;font-size:12px;
            display:flex;align-items:center;justify-content:space-between;gap:12px`;
        wrap.appendChild(info);
    }
    info.innerHTML = `
        <span><strong style="color:var(--accent)">Feature:</strong>
        <span style="margin-left:8px">${escHtml(featName)}</span></span>
        <button onclick="clearHighlight(this.closest('div').previousElementSibling.querySelector('svg'));this.closest('.tl-feat-info').style.display='none';activeFeature=null"
                style="background:none;border:none;color:var(--text-muted);cursor:pointer;font-size:16px;line-height:1">✕</button>`;
    info.style.display = 'flex';
}

function showTooltip(event, jsonStr) {
    const s   = JSON.parse(jsonStr);
    const tip = document.getElementById('tl-tooltip');
    const col = s.status === 'passed' ? 'var(--success)'
              : s.status === 'failed' ? 'var(--danger)' : 'var(--skip)';

    tip.innerHTML = `
        <div style="font-weight:600;margin-bottom:2px;color:${col};word-break:break-word">${escHtml(s.scenario_name)}</div>
        <div style="color:var(--accent);font-size:11px;margin-bottom:6px;word-break:break-word">📁 ${escHtml(s.feature_name)}</div>
        <div style="display:grid;grid-template-columns:auto auto;gap:2px 12px;font-size:11px">
            <span class="muted">Status</span>  <span style="color:${col}">${s.status}</span>
            <span class="muted">Trvanie</span> <span>${formatDuration(s.duration_ms)}</span>
            <span class="muted">Vlákno</span>  <span class="mono">${escHtml(s.thread_id||'—')}</span>
            <span class="muted">Offset</span>  <span>${formatDuration(s.offset_ms)}</span>
            ${s.tags ? `<span class="muted">Tagy</span><span style="color:var(--accent)">${escHtml(s.tags)}</span>` : ''}
        </div>
        <div style="margin-top:6px;font-size:10px;color:var(--text-muted)">Klikni pre zvýraznenie feature</div>`;

    tip.style.display = 'block';
    positionTooltip(event, tip);
}

function positionTooltip(event, tip) {
    const m = 12;
    let x = event.clientX + m;
    let y = event.clientY + m;
    if (x + 340 > window.innerWidth)  x = event.clientX - 340 - m;
    if (y + 180 > window.innerHeight) y = event.clientY - 180 - m;
    tip.style.left = x + 'px';
    tip.style.top  = y + 'px';
}

document.addEventListener('mousemove', e => {
    const tip = document.getElementById('tl-tooltip');
    if (tip.style.display !== 'none') positionTooltip(e, tip);
});

function hideTooltip() {
    document.getElementById('tl-tooltip').style.display = 'none';
}

function escHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
</script>
</body>
</html>
