// FerroStash Web Application Controller
// Works both with Node.js Express Backend AND Serverless on GitHub Pages!

let allContainers = [];
let allAuditEvents = [];
let clanSettings = { zones: [], privateContainers: [] };
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
  initAdminTokenControls();
  initZoneControls();
  initBlacklistControls();
  initResetControls();
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
      if (target === 'zones') {
        document.getElementById('tabZones').classList.add('active');
        renderZones();
        renderBlacklist();
      }
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

      // 3. Fetch clan settings & zones to filter out any private containers
      let settingsCandidates = [
        `data/clan_settings.json?_t=${timestamp}`,
        `web/data/clan_settings.json?_t=${timestamp}`
      ];
      if (window.location.hostname.includes('github.io')) {
        const pathParts = window.location.pathname.split('/').filter(Boolean);
        if (pathParts.length > 0) {
          const user = window.location.hostname.split('.')[0];
          const repo = pathParts[0];
          settingsCandidates = [
            `https://raw.githubusercontent.com/${user}/${repo}/main/data/clan_settings.json?_t=${timestamp}`,
            `https://raw.githubusercontent.com/${user}/${repo}/main/web/data/clan_settings.json?_t=${timestamp}`,
            `data/clan_settings.json?_t=${timestamp}`
          ];
        }
      }

      for (const sUrl of settingsCandidates) {
        try {
          const sRes = await fetch(sUrl, { cache: 'no-store' });
          if (sRes && sRes.ok) {
            const setObj = await sRes.json();
            if (setObj) {
              clanSettings = setObj;
              if (setObj.privateContainers && Array.isArray(setObj.privateContainers)) {
                const privSet = new Set(setObj.privateContainers);
                allContainers = allContainers.filter(c => !privSet.has(c.id));
              }
            }
            break;
          }
        } catch (e) {}
      }

      updateSyncStatus(true, 'ONLINE (GitHub Pages)');
    }

    updateStats();
    renderInventory();
    renderAuditLog();
    renderChests();
    renderZones();
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
    // Dimension & category filter
    if (currentFilter === 'enchanted_books') {
      // allow all dimensions through, filter below by item
    } else if (currentFilter !== 'all' && container.dimension !== currentFilter) {
      return;
    }

    (container.items || []).forEach(item => {
      if (!item.itemId || item.count <= 0) return;
      if (currentFilter === 'enchanted_books' && item.itemId !== 'minecraft:enchanted_book') return;

      const enchantments = item.enchantments || [];
      const hasEnchants = enchantments.length > 0;
      let title = item.customName && item.customName.trim() ? item.customName : formatItemName(item.itemId);

      if (item.itemId === 'minecraft:enchanted_book') {
        if (hasEnchants) {
          title = enchantments.join(', ');
        } else {
          title = 'Enchanted Book';
        }
      }

      // Group by itemId and enchantments so different enchanted books are separate cards
      const groupKey = hasEnchants ? `${item.itemId}#${enchantments.join(';')}` : item.itemId;

      const enchSearch = enchantments.join(' ').toLowerCase();
      const matchesSearch = !searchQuery
        || item.itemId.toLowerCase().includes(searchQuery)
        || title.toLowerCase().includes(searchQuery)
        || enchSearch.includes(searchQuery)
        || (container.customTitle && container.customTitle.toLowerCase().includes(searchQuery));

      if (matchesSearch) {
        if (!itemMap.has(groupKey)) {
          itemMap.set(groupKey, {
            itemId: item.itemId,
            displayName: title,
            enchantments: enchantments,
            totalCount: 0,
            containers: []
          });
        }
        const entry = itemMap.get(groupKey);
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

    let emoji = ITEM_EMOJIS[entry.itemId] || '📦';
    if (entry.itemId === 'minecraft:enchanted_book') {
      emoji = '📖';
    }

    let enchTagsHtml = '';
    if (entry.enchantments && entry.enchantments.length > 0) {
      enchTagsHtml = `
        <div style="display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px;">
          ${entry.enchantments.map(e => `<span style="background: rgba(168, 85, 247, 0.15); color: #d8b4fe; border: 1px solid rgba(168, 85, 247, 0.3); font-size: 0.72rem; padding: 2px 6px; border-radius: 4px; font-weight: 600;">✦ ${e}</span>`).join('')}
        </div>
      `;
    }

    card.innerHTML = `
      <div class="item-card-header">
        <div class="item-icon-box">${emoji}</div>
        <div class="item-name-block">
          <span class="item-title">${entry.displayName}</span>
          <span class="item-id">${entry.itemId}</span>
          ${enchTagsHtml}
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

  let enchHtml = '';
  if (entry.enchantments && entry.enchantments.length > 0) {
    enchHtml = `
      <div style="background: rgba(168, 85, 247, 0.1); border: 1px solid rgba(168, 85, 247, 0.3); border-radius: 8px; padding: 12px 16px; margin-bottom: 16px;">
        <div style="font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.05em; color: #c084fc; font-weight: 700; margin-bottom: 8px;">Verzauberungen:</div>
        <div style="display: flex; flex-wrap: wrap; gap: 8px;">
          ${entry.enchantments.map(e => `<span style="background: rgba(168, 85, 247, 0.25); color: #f3e8ff; padding: 4px 10px; border-radius: 6px; font-size: 0.85rem; font-weight: 600; border: 1px solid rgba(168, 85, 247, 0.45);">✦ ${e}</span>`).join('')}
        </div>
      </div>
    `;
  }

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

  body.innerHTML = enchHtml + locationsHtml;
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

// ================= CLAN ADMIN & GITHUB TOKEN MANAGEMENT =================

function getRepoInfo() {
  let owner = 'Juryio';
  let repo = 'FerroStash';
  if (window.location.hostname.includes('github.io')) {
    const parts = window.location.pathname.split('/').filter(Boolean);
    if (parts.length > 0) {
      owner = window.location.hostname.split('.')[0];
      repo = parts[0];
    }
  }
  return { owner, repo };
}

function getAdminGithubToken() {
  const input = document.getElementById('adminGithubToken');
  let token = (input && input.value.trim()) || localStorage.getItem('fs_github_token') || '';
  if (token) {
    localStorage.setItem('fs_github_token', token);
  }
  return token;
}

function updateTokenStatusBadge(hasToken) {
  const badge = document.getElementById('tokenStatusBadge');
  if (!badge) return;
  if (hasToken) {
    badge.textContent = '🟢 Token aktiv & gespeichert';
    badge.style.background = 'rgba(16, 185, 129, 0.2)';
    badge.style.color = '#6ee7b7';
    badge.style.border = '1px solid rgba(16, 185, 129, 0.4)';
  } else {
    badge.textContent = '🔴 Kein Token hinterlegt';
    badge.style.background = 'rgba(239, 68, 68, 0.2)';
    badge.style.color = '#fca5a5';
    badge.style.border = '1px solid rgba(239, 68, 68, 0.4)';
  }
}

function requireAdminToken() {
  const token = getAdminGithubToken();
  if (!token) {
    showToast('⚠️ Bitte zuerst den GitHub Admin-Token oben eintragen!');
    const input = document.getElementById('adminGithubToken');
    if (input) {
      input.scrollIntoView({ behavior: 'smooth', block: 'center' });
      input.focus();
      input.style.borderColor = '#ef4444';
      input.style.boxShadow = '0 0 14px rgba(239, 68, 68, 0.6)';
      setTimeout(() => {
        input.style.borderColor = 'var(--border-color)';
        input.style.boxShadow = 'none';
      }, 3500);
    }
    return null;
  }
  return token;
}

function initAdminTokenControls() {
  const input = document.getElementById('adminGithubToken');
  const btnSave = document.getElementById('btnSaveAdminToken');
  const btnClear = document.getElementById('btnClearAdminToken');
  const btnToggle = document.getElementById('btnToggleTokenVisibility');

  const savedToken = localStorage.getItem('fs_github_token') || '';
  if (input) {
    input.value = savedToken;
    updateTokenStatusBadge(Boolean(savedToken));

    input.addEventListener('input', () => {
      const val = input.value.trim();
      if (val) {
        localStorage.setItem('fs_github_token', val);
        updateTokenStatusBadge(true);
      } else {
        localStorage.removeItem('fs_github_token');
        updateTokenStatusBadge(false);
      }
    });
  }

  if (btnSave && input) {
    btnSave.addEventListener('click', () => {
      const val = input.value.trim();
      if (!val) {
        alert('Bitte gib zuerst einen gültigen GitHub Personal Access Token (PAT) ein.');
        input.focus();
        return;
      }
      localStorage.setItem('fs_github_token', val);
      updateTokenStatusBadge(true);
      showToast('🔑 GitHub Token sicher im Browser gespeichert!');
    });
  }

  if (btnClear && input) {
    btnClear.addEventListener('click', () => {
      if (confirm('Möchtest du den gespeicherten GitHub Token aus dem Browser entfernen?')) {
        localStorage.removeItem('fs_github_token');
        input.value = '';
        updateTokenStatusBadge(false);
        showToast('Token gelöscht.');
      }
    });
  }

  if (btnToggle && input) {
    btnToggle.addEventListener('click', () => {
      if (input.type === 'password') {
        input.type = 'text';
        btnToggle.textContent = '🔒';
        btnToggle.title = 'Token verbergen';
      } else {
        input.type = 'password';
        btnToggle.textContent = '👁️';
        btnToggle.title = 'Token anzeigen';
      }
    });
  }
}

async function pushFileToGitHub(filePath, contentStr, commitMessage, token) {
  const { owner, repo } = getRepoInfo();
  const apiUrl = `https://api.github.com/repos/${owner}/${repo}/contents/${filePath}`;
  const authHeader = `Bearer ${token}`;

  let sha = null;
  try {
    const getRes = await fetch(apiUrl, {
      headers: {
        'Accept': 'application/vnd.github.v3+json',
        'Authorization': authHeader
      }
    });
    if (getRes.ok) {
      const data = await getRes.json();
      sha = data.sha;
    }
  } catch (e) {
    console.warn(`Could not fetch sha for ${filePath}:`, e);
  }

  const contentBase64 = btoa(unescape(encodeURIComponent(contentStr)));
  const payload = {
    message: commitMessage,
    content: contentBase64,
    branch: 'main'
  };
  if (sha) payload.sha = sha;

  return await fetch(apiUrl, {
    method: 'PUT',
    headers: {
      'Accept': 'application/vnd.github.v3+json',
      'Authorization': authHeader,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(payload)
  });
}

async function saveSettingsToGitHub() {
  const token = requireAdminToken();
  if (!token) return false;

  showToast('Synchronisiere Einstellungen mit GitHub...');

  try {
    const contentStr = JSON.stringify(clanSettings, null, 2);
    const commitMsg = 'Update clan settings via Web UI';

    // Push to data/clan_settings.json and web/data/clan_settings.json
    const res1 = await pushFileToGitHub('data/clan_settings.json', contentStr, commitMsg, token);
    const res2 = await pushFileToGitHub('web/data/clan_settings.json', contentStr, commitMsg, token);

    const ok = (res1 && res1.ok) || (res2 && res2.ok);
    if (ok) {
      showToast('✅ Clan-Einstellungen erfolgreich auf GitHub gespeichert!');
      return true;
    } else {
      const failedRes = (res1 && !res1.ok) ? res1 : res2;
      const status = failedRes ? failedRes.status : 500;
      let errorDetail = 'Token ungültig oder unzureichende Berechtigungen.';
      if (status === 401) errorDetail = 'Token ungültig oder abgelaufen.';
      if (status === 403) errorDetail = 'Fehlende Schreibrechte im Repository (Contents: Read and write erforderlich).';
      if (status === 404) errorDetail = 'Repository nicht gefunden oder Token hat keinen Zugriff darauf.';

      showToast(`⚠️ GitHub-Fehler (${status}): ${errorDetail}`);
      alert(`Fehler beim Speichern auf GitHub (HTTP ${status}):\n${errorDetail}\n\nBitte überprüfe deinen GitHub Personal Access Token (PAT) und die Repository-Rechte.`);
      return false;
    }
  } catch (err) {
    console.error('saveSettingsToGitHub error:', err);
    showToast('⚠️ Netzwerkfehler bei GitHub-Verbindung');
    return false;
  }
}

// ================= CLAN ZONES MANAGEMENT =================

function initZoneControls() {
  const btnSave = document.getElementById('btnSaveZone');
  if (btnSave) {
    btnSave.addEventListener('click', async () => {
      const token = requireAdminToken();
      if (!token) return;

      const name = document.getElementById('zoneInputName').value.trim() || 'Hauptbasis';
      const dim = document.getElementById('zoneInputDim').value;
      const x = parseInt(document.getElementById('zoneInputX').value, 10);
      const z = parseInt(document.getElementById('zoneInputZ').value, 10);
      const radius = parseInt(document.getElementById('zoneInputRadius').value, 10) || 250;

      if (isNaN(x) || isNaN(z)) {
        alert('Bitte gib gültige X- und Z-Koordinaten für das Zentrum der Clan-Zone ein.');
        return;
      }

      if (!clanSettings.zones) clanSettings.zones = [];
      const newZone = {
        id: 'zone-' + Date.now(),
        name: name,
        dimension: dim,
        centerX: x,
        centerZ: z,
        radius: radius
      };

      const prevZones = [...clanSettings.zones];
      clanSettings.zones.push(newZone);

      const ok = await saveSettingsToGitHub();
      if (ok) {
        renderZones();
        document.getElementById('zoneInputName').value = '';
        document.getElementById('zoneInputX').value = '';
        document.getElementById('zoneInputZ').value = '';
      } else {
        clanSettings.zones = prevZones;
        renderZones();
      }
    });
  }
}

function renderZones() {
  const container = document.getElementById('zonesListContainer');
  if (!container) return;
  container.innerHTML = '';

  const zones = (clanSettings && clanSettings.zones) || [];
  if (zones.length === 0) {
    container.innerHTML = `
      <div style="padding: 28px; text-align: center; color: var(--text-muted); border: 1px dashed var(--border-color); border-radius: var(--radius-md);">
        <div style="font-size: 2rem; margin-bottom: 8px;">🌐</div>
        <p style="font-size: 1rem; color: #cbd5e1; margin-bottom: 6px;">Keine Clan-Zonen festgelegt</p>
        <span style="font-size: 0.85rem;">Trage rechts eure Clan-Basis ein, damit alle Clanmitglieder automatisch denselben Radius synchronisiert bekommen.</span>
      </div>
    `;
    return;
  }

  zones.forEach((z, idx) => {
    const card = document.createElement('div');
    card.style.cssText = 'background: rgba(0,0,0,0.3); border: 1px solid var(--border-color); border-radius: var(--radius-sm); padding: 14px; display: flex; justify-content: space-between; align-items: center;';
    card.innerHTML = `
      <div>
        <div style="font-weight: 600; color: #fff; font-size: 1rem; margin-bottom: 4px;">📍 ${z.name || 'Unbenannte Zone'}</div>
        <div style="font-size: 0.85rem; color: var(--text-muted);">
          <span>${z.dimension || 'minecraft:overworld'}</span> • 
          <span style="color: #6ee7b7;">X: ${z.centerX}, Z: ${z.centerZ}</span> • 
          <span style="color: #93c5fd;">Radius: ${z.radius}m</span>
        </div>
      </div>
      <button class="btn-delete-zone" data-idx="${idx}" style="background: rgba(239, 68, 68, 0.2); border: 1px solid rgba(239, 68, 68, 0.4); color: #fca5a5; padding: 6px 10px; border-radius: 4px; cursor: pointer; font-size: 0.85rem;">
        Entfernen
      </button>
    `;
    container.appendChild(card);
  });

  // Attach delete handlers
  container.querySelectorAll('.btn-delete-zone').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      const token = requireAdminToken();
      if (!token) return;

      const idx = parseInt(e.target.getAttribute('data-idx'), 10);
      if (confirm(`Möchtest du diese Zone wirklich entfernen?`)) {
        const prevZones = [...clanSettings.zones];
        clanSettings.zones.splice(idx, 1);
        const ok = await saveSettingsToGitHub();
        if (ok) {
          renderZones();
        } else {
          clanSettings.zones = prevZones;
          renderZones();
        }
      }
    });
  });
}

// ================= BLACKLIST MANAGEMENT =================

function initBlacklistControls() {
  const btnAdd = document.getElementById('btnAddBlacklist');
  const input = document.getElementById('blacklistInput');

  if (btnAdd && input) {
    btnAdd.addEventListener('click', async () => {
      const val = input.value.trim().toLowerCase();
      if (!val) return;

      const token = requireAdminToken();
      if (!token) return;

      if (!clanSettings.blacklistedTitles) {
        clanSettings.blacklistedTitles = ['inventory', 'enderchest', 'shop'];
      }

      if (clanSettings.blacklistedTitles.includes(val)) {
        showToast(`"${val}" ist bereits in der Blacklist.`);
        input.value = '';
        return;
      }

      const prevList = [...clanSettings.blacklistedTitles];
      clanSettings.blacklistedTitles.push(val);

      showToast(`Speichere "${val}" auf GitHub...`);
      const ok = await saveSettingsToGitHub();
      if (ok) {
        renderBlacklist();
        input.value = '';
        showToast(`✅ "${val}" zur Blacklist hinzugefügt!`);
      } else {
        clanSettings.blacklistedTitles = prevList;
        renderBlacklist();
      }
    });

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        btnAdd.click();
      }
    });
  }
}

function renderBlacklist() {
  const container = document.getElementById('blacklistItemsContainer');
  if (!container) return;
  container.innerHTML = '';

  const list = (clanSettings && clanSettings.blacklistedTitles) || ['inventory', 'enderchest', 'shop'];
  list.forEach((word, idx) => {
    const tag = document.createElement('span');
    tag.style.cssText = 'background: rgba(239, 68, 68, 0.15); border: 1px solid rgba(239, 68, 68, 0.4); color: #fca5a5; padding: 4px 10px; border-radius: 6px; font-size: 0.85rem; font-weight: 600; display: inline-flex; align-items: center; gap: 6px;';
    tag.innerHTML = `
      <span>🚫 ${word}</span>
      <button class="btn-remove-blacklist" data-idx="${idx}" style="background: none; border: none; color: #fca5a5; cursor: pointer; font-weight: bold; padding: 0 2px;">✕</button>
    `;
    container.appendChild(tag);
  });

  container.querySelectorAll('.btn-remove-blacklist').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      const token = requireAdminToken();
      if (!token) return;

      const idx = parseInt(e.target.getAttribute('data-idx'), 10);
      if (!clanSettings.blacklistedTitles || !clanSettings.blacklistedTitles[idx]) return;

      const word = clanSettings.blacklistedTitles[idx];
      const prevList = [...clanSettings.blacklistedTitles];
      clanSettings.blacklistedTitles.splice(idx, 1);

      showToast(`Entferne "${word}" von GitHub...`);
      const ok = await saveSettingsToGitHub();
      if (ok) {
        renderBlacklist();
        showToast(`✅ "${word}" aus Blacklist entfernt!`);
      } else {
        clanSettings.blacklistedTitles = prevList;
        renderBlacklist();
      }
    });
  });
}

// ================= GLOBAL RESET MANAGEMENT =================

function initResetControls() {
  const btnReset = document.getElementById('btnGlobalReset');
  if (btnReset) {
    btnReset.addEventListener('click', async () => {
      const token = requireAdminToken();
      if (!token) return;

      const confirmMsg = "Bist du dir absolut sicher?\n\nDies setzt das gesamte Clan-Lager auf 0 zurück, leert die vault_data.json auf GitHub und weist alle Mod-Clients an, ihren lokalen Kisten-Cache sofort zu löschen.";
      if (!confirm(confirmMsg)) return;

      showToast('Führe globalen Reset auf GitHub aus...');

      try {
        const emptyVaultJson = '[]';

        // 1. Reset vault_data.json on both paths
        await pushFileToGitHub('data/vault_data.json', emptyVaultJson, 'Global clan vault reset to 0 [by Leader]', token);
        await pushFileToGitHub('web/data/vault_data.json', emptyVaultJson, 'Global clan vault reset to 0 [by Leader]', token);

        // 2. Update resetTimestamp in clan_settings.json so all clients wipe local cache
        clanSettings.resetTimestamp = Date.now();
        const settingsOk = await saveSettingsToGitHub();

        // 3. Immediately clear website state
        allContainers = [];
        updateStats();
        renderInventory();
        renderChests();

        if (settingsOk) {
          showToast('✅ Lagerbestand wurde erfolgreich auf 0 zurückgesetzt!');
        } else {
          showToast('⚠️ Reset teilweise abgeschlossen (Prüfe Token-Berechtigungen).');
        }
      } catch (err) {
        console.error('Reset failed:', err);
        alert('Fehler beim Reset: ' + err.message);
      }
    });
  }
}
