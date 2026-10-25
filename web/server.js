const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const CLAN_SECRET = process.env.CLAN_SECRET || 'ferro-secret-key-123';

app.use(cors());
app.use(express.json({ limit: '20mb' }));

const DATA_DIR = path.join(__dirname, 'data');
const VAULT_FILE = path.join(DATA_DIR, 'vault_data.json');
const AUDIT_FILE = path.join(DATA_DIR, 'audit_log.json');

// Ensure data directory exists
if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
}

function readJsonFile(filePath, defaultVal = []) {
    try {
        if (fs.existsSync(filePath)) {
            const content = fs.readFileSync(filePath, 'utf-8');
            return JSON.parse(content);
        }
    } catch (err) {
        console.error(`Error reading ${filePath}:`, err);
    }
    return defaultVal;
}

function writeJsonFile(filePath, data) {
    try {
        fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf-8');
    } catch (err) {
        console.error(`Error writing ${filePath}:`, err);
    }
}

// Memory caches
let containers = readJsonFile(VAULT_FILE, []);
let auditEvents = readJsonFile(AUDIT_FILE, []);

// Serve static web dashboard
app.use(express.static(path.join(__dirname, 'public')));
// Also serve /data for direct GitHub Pages style consumption
app.use('/data', express.static(DATA_DIR));

// 1. Status & Health Check
app.get('/api/status', (req, res) => {
    let totalItems = 0;
    const uniqueItemSet = new Set();
    containers.forEach(c => {
        (c.items || []).forEach(it => {
            totalItems += it.count || 0;
            if (it.itemId) uniqueItemSet.add(it.itemId);
        });
    });

    res.json({
        status: 'ONLINE',
        clanName: 'Iron Vanguard',
        totalContainers: containers.length,
        totalItems,
        uniqueItemTypes: uniqueItemSet.size,
        totalAuditEvents: auditEvents.length,
        lastSync: containers.length > 0 ? Math.max(...containers.map(c => c.lastOpenedTimestamp || 0)) : null
    });
});

// 2. Get All Clan Containers
app.get('/api/vault', (req, res) => {
    res.json({
        containers,
        lastUpdated: Date.now()
    });
});

// 3. Get Audit Log
app.get('/api/audit', (req, res) => {
    const limit = parseInt(req.query.limit) || 100;
    const player = req.query.player;
    let filtered = auditEvents;

    if (player) {
        filtered = filtered.filter(e => e.player && e.player.toLowerCase() === player.toLowerCase());
    }

    res.json({
        events: filtered.slice(0, limit),
        total: auditEvents.length
    });
});

// 4. In-Game Mod Sync Endpoint (POST /api/sync)
app.post('/api/sync', (req, res) => {
    const authHeader = req.headers.authorization;
    const clientSecret = req.body.clanSecret || (authHeader && authHeader.replace('Bearer ', ''));

    if (CLAN_SECRET && clientSecret !== CLAN_SECRET) {
        return res.status(401).json({ error: 'Ungültiger Clan-Secret-Token' });
    }

    const clientContainers = req.body.containers || [];
    const clientAuditEvents = req.body.auditEvents || [];

    // Merge containers
    const containerMap = new Map();
    containers.forEach(c => containerMap.set(c.id, c));

    let containerUpdated = false;
    clientContainers.forEach(incoming => {
        if (!incoming || !incoming.id) return;
        const existing = containerMap.get(incoming.id);
        if (!existing || (incoming.lastOpenedTimestamp || 0) >= (existing.lastOpenedTimestamp || 0)) {
            containerMap.set(incoming.id, incoming);
            containerUpdated = true;
        }
    });

    if (containerUpdated) {
        containers = Array.from(containerMap.values());
        writeJsonFile(VAULT_FILE, containers);
    }

    // Merge audit events
    const existingIds = new Set(auditEvents.map(e => e.id));
    let auditUpdated = false;

    clientAuditEvents.forEach(ev => {
        if (ev && ev.id && !existingIds.has(ev.id)) {
            auditEvents.unshift(ev); // newest first
            existingIds.add(ev.id);
            auditUpdated = true;
        }
    });

    if (auditUpdated) {
        // Keep last 1,000 events
        if (auditEvents.length > 1000) {
            auditEvents = auditEvents.slice(0, 1000);
        }
        writeJsonFile(AUDIT_FILE, auditEvents);
    }

    res.json({
        status: 'SUCCESS',
        syncedContainers: containers.length,
        syncedAuditEvents: auditEvents.length,
        containers,
        auditEvents: auditEvents.slice(0, 50)
    });
});

app.listen(PORT, () => {
    console.log(`[FerroStash] Clan Vault Web Server läuft auf Port ${PORT}`);
    console.log(`[FerroStash] Öffne das Dashboard: http://localhost:${PORT}`);
});
