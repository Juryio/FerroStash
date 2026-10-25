// FerroStash Web Application Controller
// Works both with Node.js Express Backend AND Serverless on GitHub Pages!

let allContainers = [];
let allAuditEvents = [];
let currentFilter = 'all';
let searchQuery = '';
let auditPlayerFilter = '';

const ITEM_EMOJIS = {
  'minecraft:diamond': '💎',
  'minecraft:netherite_ingot': '🛡️',
  'minecraft:ancient_debris': '🧱',
  'minecraft:emerald': '🟢',
  'minecraft:gold_ingot': '🟡',
  'minecraft:iron_ingot': '⚪',
  'minecraft:elytra': '🪽',
  'minecraft:totem_of_undying': '✨',
  'minecraft:enchanted_golden_apple': '🍏',
  'minecraft:firework_rocket': '🚀',
  'minecraft:obsidian': '⬛',
  'minecraft:shulker_shell': '🐚',
  'minecraft:netherite_sword': '⚔️',
  'minecraft:netherite_pickaxe': '⛏️'
};

document.addEventListener('DOMContentLoaded', () => {
  initTabs();
  initSearchAndFilters();
  initModal();
  fetchClanData();

  // Periodic live refresh every 6 seconds
  setInterval(fetchClanData, 6000);

  document.getElementById('btnRefresh').addEventListener('click', () => {
    fetchClanData();
    showToast('Daten werden aktualisiert...');
  });
});

function initTabs() {
  const tabs = document.querySelectorAll('.nav-tab');
  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      tabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      const target = tab.getAttribute('data-tab');
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

      if (target === 'inventory') document.getElementById('tabInventory').classList.add('active');
      if (target === 'audit') document.getElementById('tabAudit').classList.add('active');
      if (target === 'chests') document.getElementById('tabChests').classList.add('active');
      if (target === 'setup') document.getElementById('tabSetup').classList.add('active');
    });
  });
}

function initSearchAndFilters() {
  const searchInput = document.getElementById('searchInput');
  const clearSearchBtn = document.getElementById('clearSearchBtn');

  searchInput.addEventListener('input', (e) => {
    searchQuery = e.target.value.toLowerCase().trim();
    clearSearchBtn.style.display = searchQuery ? 'block' : 'none';
    renderInventory();
  });

  clearSearchBtn.addEventListener('click', () => {
    searchInput.value = '';
    searchQuery = '';
    clearSearchBtn.style.display = 'none';
    renderInventory();
  });

  // Dimension filter pills
  const pills = document.querySelectorAll('#dimensionFilter .pill');
  pills.forEach(pill => {
    pill.addEventListener('click', () => {
      pills.forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      currentFilter = pill.getAttribute('data-dim');
      renderInventory();
    });
  });

  // Audit filter
  const auditFilter = document.getElementById('auditPlayerFilter');
  auditFilter.addEventListener('input', (e) => {
    auditPlayerFilter = e.target.value.toLowerCase().trim();
    renderAuditLog();
  });
}

async function fetchFirstValidJson(urls) {
  let fallback = null;
  for (const url of urls) {
    try {
      const res = await fetch(url, { cache: 'no-store' });
      if (res && res.ok) {
        const json = await res.json();
        if (Array.isArray(json) && json.length > 0) {
          return json; // Found populated clan data! Return immediately!
        }
        if (fallback === null && Array.isArray(json)) {
          fallback = json;
        }
      }
    } catch (e) {
      // Continue to next candidate
    }
  }
  return fallback || [];
}

async function fetchClanData() {
  try {
    const timestamp = Date.now();

    // 1. Try Express REST API first
    const vaultRes = await fetch(`/api/vault?_t=${timestamp}`, { cache: 'no-store' }).catch(() => null);
    if (vaultRes && vaultRes.ok) {
      const data = await vaultRes.json();
      allContainers = data.containers || [];
      const auditRes = await fetch(`/api/audit?_t=${timestamp}`, { cache: 'no-store' });
      if (auditRes.ok) {
        const aData = await auditRes.json();
        allAuditEvents = aData.events || [];
      }
      updateSyncStatus(true, 'ONLINE (REST API)');
    } else {
      // 2. Fallback to static JSON files (GitHub Pages Mode with multi-path resolution)
      let vaultCandidates = [
        `data/vault_data.json?_t=${timestamp}`,
        `web/data/vault_data.json?_t=${timestamp}`
      ];
      let auditCandidates = [
        `data/audit_log.json?_t=${timestamp}`,
        `web/data/audit_log.json?_t=${timestamp}`
      ];

      if (window.location.hostname.includes('github.io')) {
        const pathParts = window.location.pathname.split('/').filter(Boolean);
        if (pathParts.length > 0) {
          const user = window.location.hostname.split('.')[0];
          const repo = pathParts[0];
          // Try root data/ first (where mod default pushes), then web/data/, then relative
          vaultCandidates = [
            `https://raw.githubusercontent.com/${user}/${repo}/main/data/vault_data.json?_t=${timestamp}`,
            `https://raw.githubusercontent.com/${user}/${repo}/main/web/data/vault_data.json?_t=${timestamp}`,
            `data/vault_data.json?_t=${timestamp}`,
            `web/data/vault_data.json?_t=${timestamp}`
          ];
          auditCandidates = [
            `https://raw.githubusercontent.com/${user}/${repo}/main/data/audit_log.json?_t=${timestamp}`,
            `https://raw.githubusercontent.com/${user}/${repo}/main/web/data/audit_log.json?_t=${timestamp}`,
            `data/audit_log.json?_t=${timestamp}`,
            `web/data/audit_log.json?_t=${timestamp}`
          ];
        }
      }

      allContainers = await fetchFirstValidJson(vaultCandidates);
      allAuditEvents = await fetchFirstValidJson(auditCandidates);
      updateSyncStatus(true, 'ONLINE (GitHub Pages)');
    }

    updateStats();
    renderInventory();
    renderAuditLog();
    renderChests();
  } catch (err) {
    console.warn('[FerroStash] Fetch error, retrying...', err);
    updateSyncStatus(false, 'VERBINDUNG WIRD GESUCHT');
  }
}

function updateSyncStatus(isLive, text) {
  const badge = document.getElementById('syncStatusBadge');
  const statusText = document.getElementById('syncStatusText');
  statusText.textContent = text;
  if (!isLive) {
    badge.style.borderColor = 'rgba(239, 68, 68, 0.4)';
    badge.style.color = '#fca5a5';
  } else {
    badge.style.borderColor = 'rgba(16, 185, 129, 0.4)';
    badge.style.color = '#6ee7b7';
  }
}

function updateStats() {
  let netherite = 0;
  let diamonds = 0;
  let rare = 0;
  let totalItems = 0;

  allContainers.forEach(c => {
    (c.items || []).forEach(it => {
      totalItems += it.count || 0;
      if (it.itemId === 'minecraft:netherite_ingot' || it.itemId === 'minecraft:ancient_debris') {
        netherite += it.count;
      }
      if (it.itemId === 'minecraft:diamond') {
        diamonds += it.count;
      }
      if (it.itemId === 'minecraft:totem_of_undying' || it.itemId === 'minecraft:elytra') {
        rare += it.count;
      }
    });
  });

  document.getElementById('statNetherite').textContent = netherite.toLocaleString();
  document.getElementById('statDiamonds').textContent = diamonds.toLocaleString();
  document.getElementById('statRare').textContent = rare.toLocaleString();
  document.getElementById('statChests').textContent = allContainers.length.toLocaleString();
  document.getElementById('statTotalItems').textContent = totalItems.toLocaleString();
}

function renderInventory() {
  const grid = document.getElementById('itemsGrid');
  grid.innerHTML = '';

  // Aggregate items across containers
  const itemMap = new Map();

  allContainers.forEach(container => {
    // Dimension filter
    if (currentFilter !== 'all' && container.dimension !== currentFilter) return;

    (container.items || []).forEach(item => {
      if (!item.itemId || item.count <= 0) return;

      const title = item.customName && item.customName.trim() ? item.customName : formatItemName(item.itemId);
      const matchesSearch = !searchQuery
        || item.itemId.toLowerCase().includes(searchQuery)
        || title.toLowerCase().includes(searchQuery)
        || (container.customTitle && container.customTitle.toLowerCase().includes(searchQuery));

      if (matchesSearch) {
        if (!itemMap.has(item.itemId)) {
          itemMap.set(item.itemId, {
            itemId: item.itemId,
            displayName: title,
            totalCount: 0,
            containers: []
          });
        }
        const entry = itemMap.get(item.itemId);
        entry.totalCount += item.count;
        entry.containers.push({
          container,
          count: item.count
        });
      }
    });
  });

  const sortedItems = Array.from(itemMap.values()).sort((a, b) => b.totalCount - a.totalCount);

  if (sortedItems.length === 0) {
    const emptyMsg = allContainers.length === 0
      ? '📦 Noch keine Kisten synchronisiert. Öffne Kisten im Spiel mit der FerroStash-Mod, um den Clan-Lagerbestand hier anzuzeigen.'
      : 'Keine Items für die ausgewählten Filter gefunden.';
    grid.innerHTML = `<div style="grid-column: 1 / -1; text-align: center; padding: 60px 20px; color: var(--text-muted); background: rgba(10, 14, 23, 0.4); border-radius: var(--radius-md); border: 1px dashed var(--border-color);">
      <div style="font-size: 2.5rem; margin-bottom: 12px;">📦</div>
      <div style="font-size: 1rem; color: #cbd5e1; max-width: 500px; margin: 0 auto;">${emptyMsg}</div>
    </div>`;
    return;
  }

  sortedItems.forEach(entry => {
    const card = document.createElement('div');
    card.className = 'item-card';

    const emoji = ITEM_EMOJIS[entry.itemId] || '📦';

    card.innerHTML = `
      <div class="item-card-header">
        <div class="item-icon-box">${emoji}</div>
        <div class="item-name-block">
          <span class="item-title">${entry.displayName}</span>
          <span class="item-id">${entry.itemId}</span>
        </div>
      </div>
      <div class="item-card-stats">
        <span class="item-count-badge">x${entry.totalCount.toLocaleString()}</span>
        <span class="item-chests-count">in ${entry.containers.length} Kiste(n)</span>
      </div>
    `;

    card.addEventListener('click', () => openItemDetailsModal(entry));
    grid.appendChild(card);
  });
}

function renderAuditLog() {
  const timeline = document.getElementById('auditTimeline');
  timeline.innerHTML = '';

  let events = allAuditEvents;
  if (auditPlayerFilter) {
    events = events.filter(e => e.player && e.player.toLowerCase().includes(auditPlayerFilter));
  }

  if (events.length === 0) {
    timeline.innerHTML = `<div style="text-align: center; padding: 60px 20px; color: var(--text-muted); background: rgba(10, 14, 23, 0.4); border-radius: var(--radius-md); border: 1px dashed var(--border-color);">
      <div style="font-size: 2.5rem; margin-bottom: 12px;">📜</div>
      <div style="font-size: 1rem; color: #cbd5e1;">Keine Aktivitäten vorhanden.</div>
      <div style="font-size: 0.85rem; color: var(--text-muted); margin-top: 6px;">Wenn Clanmitglieder Kisten öffnen und Gegenstände entnehmen oder deponieren, wird dies hier protokolliert.</div>
    </div>`;
    return;
  }

  events.slice(0, 50).forEach(ev => {
    const card = document.createElement('div');
    card.className = 'audit-card';

    const isWithdraw = ev.action === 'WITHDREW';
    const actionClass = isWithdraw ? 'action-withdrew' : 'action-deposited';
    const actionText = isWithdraw ? 'Entnahme' : 'Einlagerung';

    const avatarUrl = `https://mc-heads.net/avatar/${encodeURIComponent(ev.player || 'Steve')}/48`;

    let changesHtml = '';
    (ev.changes || []).forEach(ch => {
      const isNeg = ch.delta < 0;
      const tagClass = isNeg ? 'diff-negative' : 'diff-positive';
      const sign = isNeg ? '' : '+';
      changesHtml += `<span class="diff-tag ${tagClass}">${sign}${ch.delta}x ${ch.displayName || formatItemName(ch.itemId)}</span>`;
    });

    const dimName = (ev.dimension || '').replace('minecraft:', '');

    card.innerHTML = `
      <img src="${avatarUrl}" alt="${ev.player}" class="player-avatar" onerror="this.src='https://mc-heads.net/avatar/Steve/48'">
      <div class="audit-body">
        <div class="audit-primary-line">
          <span class="audit-player-name">${ev.player}</span>
          <span class="audit-action-badge ${actionClass}">${actionText}</span>
          <span class="audit-container-name">aus <strong>${ev.containerTitle || 'Kiste'}</strong></span>
          <span class="audit-coords">(${dimName}: ${ev.x}, ${ev.y}, ${ev.z})</span>
        </div>
        <div class="audit-changes-row">
          ${changesHtml}
        </div>
      </div>
      <div class="audit-time">${formatTimeAgo(ev.timestamp)}</div>
    `;

    timeline.appendChild(card);
  });
}

function renderChests() {
  const grid = document.getElementById('chestsGrid');
  grid.innerHTML = '';

  if (allContainers.length === 0) {
    grid.innerHTML = `<div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: var(--text-muted);">
      Noch keine Kisten synchronisiert.
    </div>`;
    return;
  }

  allContainers.forEach(c => {
    const card = document.createElement('div');
    card.className = 'chest-card';

    const dimName = (c.dimension || 'overworld').replace('minecraft:', '');
    let dimClass = 'dim-overworld';
    if (dimName === 'the_nether') dimClass = 'dim-nether';
    if (dimName === 'the_end') dimClass = 'dim-end';

    let totalItemCount = 0;
    (c.items || []).forEach(it => totalItemCount += (it.count || 0));

    const coordStr = `${c.x}, ${c.y}, ${c.z}`;

    card.innerHTML = `
      <div class="chest-card-header">
        <span class="chest-title">${c.customTitle || c.type}</span>
        <span class="dim-tag ${dimClass}">${dimName}</span>
      </div>
      <div class="chest-meta">
        <div>📍 Koordinaten: <code>${c.x}, ${c.y}, ${c.z}</code></div>
        <div>👤 Zuletzt geöffnet von: <strong>${c.lastOpenedBy || 'Unbekannt'}</strong> (${formatTimeAgo(c.lastOpenedTimestamp)})</div>
        <div>📦 Lagerbestand: <strong>${totalItemCount} Items</strong> in ${c.items ? c.items.length : 0} Slots</div>
      </div>
      <div class="chest-actions">
        <button class="btn-tp-copy" onclick="copyCoordinates('${coordStr}')">📍 Koordinaten kopieren</button>
      </div>
    `;

    grid.appendChild(card);
  });
}

function initModal() {
  const backdrop = document.getElementById('modalBackdrop');
  const closeBtn = document.getElementById('modalCloseBtn');

  closeBtn.addEventListener('click', () => backdrop.classList.remove('open'));
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) backdrop.classList.remove('open');
  });
}

function openItemDetailsModal(entry) {
  const backdrop = document.getElementById('modalBackdrop');
  const title = document.getElementById('modalItemTitle');
  const count = document.getElementById('modalItemCount');
  const body = document.getElementById('modalBody');

  title.textContent = entry.displayName;
  count.textContent = `x${entry.totalCount.toLocaleString()}`;

  let locationsHtml = '<div style="font-weight: 600; margin-bottom: 8px;">Kisten-Standorte im Clan-Territorium:</div>';

  entry.containers.forEach(match => {
    const c = match.container;
    const dim = (c.dimension || '').replace('minecraft:', '');
    const coordStr = `${c.x}, ${c.y}, ${c.z}`;

    locationsHtml += `
      <div style="background: rgba(10, 14, 23, 0.6); padding: 12px 16px; border-radius: 8px; border: 1px solid var(--border-color); display: flex; justify-content: space-between; align-items: center; gap: 12px;">
        <div>
          <div style="font-weight: 700; color: #fff;">${c.customTitle || c.type} <span style="color: var(--accent-gold);">(x${match.count})</span></div>
          <div style="font-size: 0.82rem; color: var(--text-muted); font-family: var(--font-mono);">${dim} [${c.x}, ${c.y}, ${c.z}] - Zuletzt von ${c.lastOpenedBy}</div>
        </div>
        <button class="btn-tp-copy" onclick="copyCoordinates('${coordStr}')">📍 Koordinaten kopieren</button>
      </div>
    `;
  });

  body.innerHTML = locationsHtml;
  backdrop.classList.add('open');
}

window.copyCoordinates = function (coords) {
  navigator.clipboard.writeText(coords).then(() => {
    showToast(`Koordinaten kopiert: ${coords}`);
  }).catch(() => {
    prompt('Koordinaten:', coords);
  });
};

function showToast(msg) {
  const toast = document.getElementById('toastNotification');
  const msgEl = document.getElementById('toastMessage');
  msgEl.textContent = msg;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2800);
}

function formatItemName(itemId) {
  if (!itemId) return 'Item';
  const path = itemId.includes(':') ? itemId.split(':')[1] : itemId;
  return path.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

function formatTimeAgo(timestamp) {
  if (!timestamp) return 'Unbekannt';
  const diff = Math.max(0, Date.now() - timestamp);
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return 'gerade eben';
  const min = Math.floor(sec / 60);
  if (min < 60) return `vor ${min} Min`;
  const hours = Math.floor(min / 60);
  if (hours < 24) return `vor ${hours} Std`;
  const days = Math.floor(hours / 24);
  return `vor ${days} Tagen`;
}
