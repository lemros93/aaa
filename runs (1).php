<?php
require_once 'config/db.php';
$db         = getDb();
$pageTitle  = 'Runy – Test Dashboard';
$activePage = 'runs';

$from     = $_GET['from']     ?? date('Y-m-d', strtotime('-14 days'));
$to       = $_GET['to']       ?? date('Y-m-d');
$project  = $_GET['project']  ?? 'all';
$parallel = $_GET['parallel'] ?? 'all';
$toEnd    = $to . ' 23:59:59';

$filters = ['r.run_at BETWEEN :from AND :to'];
$params  = [':from' => $from, ':to' => $toEnd];

if ($project !== 'all')  { $filters[] = 'r.project = :project';       $params[':project']  = $project; }
if ($parallel !== 'all') { $filters[] = 'r.parallel_mode = :parallel'; $params[':parallel'] = $parallel; }

$where = 'WHERE ' . implode(' AND ', $filters);

$sql = "SELECT r.*, rm_all.meta,
    ROUND(r.passed_scenarios/NULLIF(r.total_scenarios,0)*100,1) AS success_rate
FROM runs r
LEFT JOIN (
    SELECT run_id, GROUP_CONCAT(CONCAT(meta_key,'=',meta_value) SEPARATOR '|') AS meta
    FROM run_metadata GROUP BY run_id
) rm_all ON rm_all.run_id = r.id
$where
ORDER BY r.run_at DESC";

$stmt = $db->prepare($sql);
foreach ($params as $k => $v) $stmt->bindValue($k, $v);
$stmt->execute();
$runs = $stmt->fetchAll();

include 'partials/header.php';
include 'partials/filters.php';
?>

<div class="main">
<?php if (empty($runs)): ?>
    <div class="empty"><div class="empty-icon">📭</div>Žiadne dáta pre zvolené obdobie</div>
<?php else: ?>

<?php foreach ($runs as $r):
    $meta = [];
    if ($r['meta']) {
        foreach (explode('|', $r['meta']) as $pair) {
            if (strpos($pair,'=') !== false) {
                [$k,$v] = explode('=', $pair, 2);
                $meta[$k] = $v;
            }
        }
    }
    $wallDur = $r['wall_clock_ms'] ? gmdate('H:i:s', (int)($r['wall_clock_ms']/1000)) : '—';
    $sumDur  = $r['duration_ms']   ? gmdate('H:i:s', (int)($r['duration_ms']/1000))   : '—';
    $sr      = $r['success_rate'] ?? 0;
    $t       = max((int)$r['total_scenarios'], 1);
    $p       = (int)round($r['passed_scenarios']/$t*100);
    $f       = (int)round($r['failed_scenarios']/$t*100);
    $commit  = $r['commit_hash'] ? substr($r['commit_hash'], 0, 8) : '—';
    $isParallel = ($r['parallel_mode'] ?? 'serial') === 'parallel';
?>
<div class="table-wrap" id="run-<?= $r['id'] ?>" style="margin-bottom:20px">

    <!-- Run header -->
    <div class="table-header" style="flex-wrap:wrap; gap:8px">
        <div class="flex">
            <button class="expand-btn" id="rbtn-<?= $r['id'] ?>"
                    onclick="toggleRun(this, <?= $r['id'] ?>)">+</button>
            <span class="table-title">
                <?= date('d.m.Y H:i', strtotime($r['run_at'])) ?>
                &nbsp;·&nbsp;
                <span style="color:var(--accent)"><?= htmlspecialchars($r['project']) ?></span>
                &nbsp;·&nbsp; Build <span class="mono"><?= htmlspecialchars($r['build_number']) ?></span>
                <?php if ($isParallel): ?>
                    &nbsp;<span style="background:var(--accent-dim);color:var(--accent);border-radius:10px;padding:1px 8px;font-size:11px">⚡ parallel ×<?= (int)$r['thread_count'] ?></span>
                <?php else: ?>
                    &nbsp;<span style="background:var(--bg3);color:var(--text-muted);border-radius:10px;padding:1px 8px;font-size:11px">serial</span>
                <?php endif; ?>
            </span>
        </div>
        <div class="flex ml-auto">
            <span class="pct <?= $sr>=80?'good':'bad' ?>"><?= $sr ?>%</span>
            <div class="progress" style="width:120px">
                <div class="progress-pass" style="width:<?= $p ?>%"></div>
                <div class="progress-fail" style="width:<?= $f ?>%"></div>
                <div class="progress-skip" style="width:<?= 100-$p-$f ?>%"></div>
            </div>
        </div>
    </div>

    <!-- Run metadata grid -->
    <div class="run-meta">
        <div class="run-meta-item">
            <span class="run-meta-key">Spustil</span>
            <span class="run-meta-value"><?= htmlspecialchars($r['triggered_by'] ?? '—') ?></span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">Typ</span>
            <span class="run-meta-value"><?= htmlspecialchars($r['trigger_type'] ?? '—') ?></span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">Branch</span>
            <span class="run-meta-value mono"><?= htmlspecialchars($r['branch'] ?? '—') ?></span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">Commit</span>
            <span class="run-meta-value mono" title="<?= htmlspecialchars($r['commit_hash'] ?? '') ?>"><?= $commit ?></span>
        </div>
        <?php if (!empty($r['commit_message'])): ?>
        <div class="run-meta-item" style="grid-column: span 2">
            <span class="run-meta-key">Commit správa</span>
            <span class="run-meta-value"><?= htmlspecialchars($r['commit_message']) ?></span>
        </div>
        <?php endif; ?>
        <div class="run-meta-item">
            <span class="run-meta-key">Prostredie</span>
            <span class="run-meta-value"><?= htmlspecialchars($r['environment'] ?? '—') ?></span>
        </div>
        <?php if (!empty($r['app_url'])): ?>
        <div class="run-meta-item">
            <span class="run-meta-key">URL aplikácie</span>
            <span class="run-meta-value"><a href="<?= htmlspecialchars($r['app_url']) ?>" target="_blank"><?= htmlspecialchars($r['app_url']) ?></a></span>
        </div>
        <?php endif; ?>
        <div class="run-meta-item">
            <span class="run-meta-key">Beh</span>
            <span class="run-meta-value"><?= $isParallel ? '⚡ parallel ×' . (int)$r['thread_count'] : 'serial' ?></span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">⏱ Čas buildu</span>
            <span class="run-meta-value"><?= $wallDur ?></span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">Σ Trvanie</span>
            <span class="run-meta-value muted"><?= $sumDur ?></span>
        </div>
        <?php foreach ($meta as $k => $v): ?>
        <div class="run-meta-item">
            <span class="run-meta-key"><?= htmlspecialchars($k) ?></span>
            <span class="run-meta-value"><?= htmlspecialchars($v) ?></span>
        </div>
        <?php endforeach; ?>
        <div class="run-meta-item">
            <span class="run-meta-key">Features</span>
            <span class="run-meta-value">
                <span style="color:var(--success)">✓<?= $r['passed_features'] ?></span>
                <span style="color:var(--danger)"> ✗<?= $r['failed_features'] ?></span>
                / <?= $r['total_features'] ?>
            </span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">Scenáre</span>
            <span class="run-meta-value">
                <span style="color:var(--success)">✓<?= $r['passed_scenarios'] ?></span>
                <span style="color:var(--danger)"> ✗<?= $r['failed_scenarios'] ?></span>
                / <?= $r['total_scenarios'] ?>
            </span>
        </div>
        <div class="run-meta-item">
            <span class="run-meta-key">Stepy</span>
            <span class="run-meta-value">
                <span style="color:var(--success)">✓<?= $r['passed_steps'] ?></span>
                <span style="color:var(--danger)"> ✗<?= $r['failed_steps'] ?></span>
                / <?= $r['total_steps'] ?>
            </span>
        </div>
    </div>

    <!-- Expand area -->
    <div id="rrow-<?= $r['id'] ?>" style="display:none; border-top:2px solid var(--accent)">
        <div style="padding:12px 16px 16px 40px; background:var(--bg)">
            <div id="rfeat-<?= $r['id'] ?>">
                <div style="padding:16px; text-align:center">
                    <div class="spinner" style="margin:0 auto; width:24px; height:24px"></div>
                </div>
            </div>
        </div>
    </div>

</div>
<?php endforeach; ?>
<?php endif; ?>
</div>

<script>
function toggleRun(btn, runId) {
    const row  = document.getElementById('rrow-' + runId);
    const open = row.style.display === 'none';
    row.style.display = open ? 'block' : 'none';
    btn.classList.toggle('open', open);
    if (open && !row.dataset.loaded) {
        row.dataset.loaded = '1';
        loadFeatures(runId);
    }
}

async function loadFeatures(runId) {
    const container = document.getElementById('rfeat-' + runId);
    const data = await fetchJson(`api/dev_data.php?action=features&run_id=${runId}`);
    if (!data.length) {
        container.innerHTML = '<div class="empty">Žiadne features</div>';
        return;
    }
    let html = `<div class="table-wrap"><div class="table-header">
        <span class="table-title">Features</span>
        <span class="table-count">${data.length}</span>
    </div><table><thead><tr>
        <th></th><th>Feature</th><th>Tagy</th><th>Status</th><th>Scenáre</th><th>Trvanie</th>
    </tr></thead><tbody>`;
    data.forEach(f => {
        html += `<tr>
            <td style="width:32px">
                <button class="expand-btn" id="fbtn-${f.id}" onclick="toggleFeat(this,${f.id})">+</button>
            </td>
            <td>
                <div>${escHtml(f.name)}</div>
                <div class="muted mono" style="font-size:11px">${escHtml(f.file_path||'')}</div>
            </td>
            <td>${renderTags(f.tags)}</td>
            <td>${badge(f.status)}</td>
            <td>
                <span style="color:var(--success)">✓${f.passed_scenarios}</span>
                <span style="color:var(--danger)"> ✗${f.failed_scenarios}</span>
                / ${f.total_scenarios}
            </td>
            <td class="nowrap">${formatDuration(f.duration_ms)}</td>
        </tr>
        <tr id="frow-${f.id}" style="display:none">
            <td colspan="99" style="padding:0; border-top:1px solid var(--accent-dim)">
                <div style="padding:10px 12px 12px 48px; background:var(--bg2)">
                    <div id="fscen-${f.id}">
                        <div style="padding:12px; text-align:center">
                            <div class="spinner" style="margin:0 auto; width:20px; height:20px"></div>
                        </div>
                    </div>
                </div>
            </td>
        </tr>`;
    });
    html += '</tbody></table></div>';
    container.innerHTML = html;
}

async function toggleFeat(btn, featId) {
    const row  = document.getElementById('frow-' + featId);
    const open = row.style.display === 'none';
    row.style.display = open ? 'table-row' : 'none';
    btn.classList.toggle('open', open);
    if (open && !row.dataset.loaded) {
        row.dataset.loaded = '1';
        loadScenarios(featId);
    }
}

async function loadScenarios(featId) {
    const container = document.getElementById('fscen-' + featId);
    const data = await fetchJson(`api/dev_data.php?action=scenarios&feature_id=${featId}`);
    if (!data.length) {
        container.innerHTML = '<div class="empty" style="padding:12px">Žiadne scenáre</div>';
        return;
    }
    let html = `<div class="table-wrap"><div class="table-header">
        <span class="table-title">Scenáre</span>
        <span class="table-count">${data.length}</span>
    </div><table><thead><tr>
        <th></th><th>Scenár</th><th>Tagy</th><th>Status</th><th>Stepy</th><th>Riadok</th><th>Trvanie</th>
    </tr></thead><tbody>`;
    data.forEach(s => {
        html += `<tr>
            <td style="width:32px">
                <button class="expand-btn" id="sbtn-${s.id}" onclick="toggleScen(this,${s.id})">+</button>
            </td>
            <td>
                <div>${escHtml(s.name)}</div>
                ${s.hook_error_message ? `<div class="hook-error" style="margin-top:4px"><strong>⚠ ${s.hook_error_type} hook</strong>${escHtml(s.hook_error_message)}</div>` : ''}
            </td>
            <td>${renderTags(s.tags)}</td>
            <td>${badge(s.status)}</td>
            <td>
                <span style="color:var(--success)">✓${s.passed_steps}</span>
                <span style="color:var(--danger)"> ✗${s.failed_steps}</span>
                ${s.skipped_steps > 0 ? `<span style="color:var(--skip)"> ⊘${s.skipped_steps}</span>` : ''}
                / ${s.total_steps}
            </td>
            <td class="mono muted">${s.line||'—'}</td>
            <td class="nowrap">${formatDuration(s.duration_ms)}</td>
        </tr>
        <tr id="srow-${s.id}" style="display:none">
            <td colspan="99" style="padding:0; border-top:1px solid var(--border)">
                <div style="padding:8px 10px 12px 56px; background:var(--bg3)">
                    <div id="ssteps-${s.id}">
                        <div style="padding:10px; text-align:center">
                            <div class="spinner" style="margin:0 auto; width:18px; height:18px"></div>
                        </div>
                    </div>
                </div>
            </td>
        </tr>`;
    });
    html += '</tbody></table></div>';
    container.innerHTML = html;
}

async function toggleScen(btn, scenId) {
    const row  = document.getElementById('srow-' + scenId);
    const open = row.style.display === 'none';
    row.style.display = open ? 'table-row' : 'none';
    btn.classList.toggle('open', open);
    if (open && !row.dataset.loaded) {
        row.dataset.loaded = '1';
        loadSteps(scenId);
    }
}

async function loadSteps(scenId) {
    const container = document.getElementById('ssteps-' + scenId);
    const data = await fetchJson(`api/dev_data.php?action=steps&scenario_id=${scenId}`);
    if (!data.length) {
        container.innerHTML = '<div class="empty" style="padding:10px">Žiadne stepy</div>';
        return;
    }
    let html = `<table style="width:100%;border-collapse:collapse"><thead>
        <tr style="background:var(--bg2)">
            <th style="padding:7px 10px;text-align:left;font-size:11px;color:var(--text-muted);text-transform:uppercase">Keyword</th>
            <th style="padding:7px 10px;text-align:left;font-size:11px;color:var(--text-muted);text-transform:uppercase">Step</th>
            <th style="padding:7px 10px;text-align:left;font-size:11px;color:var(--text-muted);text-transform:uppercase">Status</th>
            <th style="padding:7px 10px;text-align:left;font-size:11px;color:var(--text-muted);text-transform:uppercase">Riadok</th>
            <th style="padding:7px 10px;text-align:left;font-size:11px;color:var(--text-muted);text-transform:uppercase">Trvanie</th>
            <th style="padding:7px 10px;text-align:left;font-size:11px;color:var(--text-muted);text-transform:uppercase">📸</th>
        </tr>
    </thead><tbody>`;
    data.forEach(st => {
        html += `<tr style="border-bottom:1px solid var(--border)">
            <td style="padding:8px 10px;vertical-align:top"><span class="step-keyword">${escHtml(st.keyword||'')}</span></td>
            <td style="padding:8px 10px;vertical-align:top;max-width:500px">
                <div class="step-name">${escHtml(st.name||'')}</div>
                ${st.translated_text && st.translated_text !== st.name ? `<div class="step-translated">→ ${escHtml(st.translated_text)}</div>` : ''}
                ${st.error_message ? `<div class="error-msg">${escHtml(st.error_message)}</div>` : ''}
                ${st.stack_trace ? `<span class="stack-toggle" onclick="toggleStack(this)">▼ show stack trace</span><pre class="stack-trace">${escHtml(st.stack_trace)}</pre>` : ''}
            </td>
            <td style="padding:8px 10px;vertical-align:top">${badge(st.status)}</td>
            <td style="padding:8px 10px;vertical-align:top" class="mono muted">${st.line||'—'}</td>
            <td style="padding:8px 10px;vertical-align:top;white-space:nowrap">${formatDuration(st.duration_ms)}</td>
            <td style="padding:8px 10px;vertical-align:top">
                ${st.screenshot_url ? `<img class="thumb" src="${escHtml(imgUrl(st.screenshot_url))}" onclick="showImage('${escHtml(imgUrl(st.screenshot_url))}')" alt="">` : ''}
                ${st.failed_screenshot_url ? `<img class="thumb" src="${escHtml(imgUrl(st.failed_screenshot_url))}" onclick="showImage('${escHtml(imgUrl(st.failed_screenshot_url))}')" alt="" style="border-color:var(--danger)">` : ''}
            </td>
        </tr>`;
    });
    html += '</tbody></table>';
    container.innerHTML = html;
}

function renderTags(tags) {
    if (!tags) return '<span class="muted">—</span>';
    return tags.split(',').map(t =>
        `<span style="display:inline-block;background:var(--accent-dim);color:var(--accent);border-radius:10px;padding:1px 7px;font-size:11px;margin:1px">${escHtml(t.trim())}</span>`
    ).join(' ');
}

function badge(status) {
    return `<span class="badge badge-${status}">${status}</span>`;
}

function escHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
</script>
</body>
</html>
