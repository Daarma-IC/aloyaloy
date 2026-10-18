export const GEOFENCE_PROFILES = {
  baseline: { id: 'baseline', label: 'Baseline · titik langsung', bufferM: 0, confirmMs: 0 },
  field: { id: 'field', label: 'Field · 20 m / 5 detik', bufferM: 20, confirmMs: 5000 },
  strict: { id: 'strict', label: 'Evaluasi · 35 m / 10 detik', bufferM: 35, confirmMs: 10000 }
};

export const distanceM = (a, b) => {
  const R = 6371000, r = Math.PI / 180;
  const dLat = (b[0] - a[0]) * r, dLon = (b[1] - a[1]) * r;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
};

export const bearingDeg = (a, b) => {
  const r = Math.PI / 180, d = 180 / Math.PI;
  const y = Math.sin((b[1] - a[1]) * r) * Math.cos(b[0] * r);
  const x = Math.cos(a[0] * r) * Math.sin(b[0] * r) - Math.sin(a[0] * r) * Math.cos(b[0] * r) * Math.cos((b[1] - a[1]) * r);
  return (Math.atan2(y, x) * d + 360) % 360;
};

export const pointInPolygon = (point, polygon) => {
  const [x, y] = point; let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, yi] = polygon[i], [xj, yj] = polygon[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
};

export function geofenceStep(previous, position, polygon, profile, now = Date.now()) {
  const base = { ...previous, event: null };
  const stable = previous.stableStatus || (['INSIDE', 'OUTSIDE'].includes(previous.status) ? previous.status : null);
  if (!position || !Number.isFinite(position.accuracy) || position.accuracy < 0) return { ...base, stableStatus: stable, status: 'GPS_UNAVAILABLE', pending: null };
  const inside = pointInPolygon(position.coords, polygon);
  const rawStatus = inside ? 'INSIDE' : 'OUTSIDE';
  const boundaryDistance = Math.min(...polygon.map((p, i) => segmentDistanceM(position.coords, p, polygon[(i + 1) % polygon.length])));
  if (profile.bufferM > 0 && boundaryDistance <= position.accuracy) return { ...base, status: 'UNCERTAIN', stableStatus: stable, pending: null };
  if (!stable) return { ...base, status: rawStatus, stableStatus: rawStatus, pending: null };
  if (rawStatus === stable || boundaryDistance < profile.bufferM) return { ...base, status: stable, stableStatus: stable, pending: null };
  const pending = previous.pending?.status === rawStatus ? previous.pending : { status: rawStatus, startedAt: now, position };
  if (now - pending.startedAt < profile.confirmMs) return { ...base, status: stable, stableStatus: stable, pending: { ...pending, position } };
  return { ...base, status: rawStatus, stableStatus: rawStatus, pending: null, event: { type: rawStatus === 'OUTSIDE' ? 'EXIT_SECTOR' : 'ENTER_SECTOR', triggeredAt: now, position, profileId: profile.id, configuration: { bufferM: profile.bufferM, confirmMs: profile.confirmMs } } };
}

function segmentDistanceM(point, a, b) {
  const scale = Math.PI / 180 * 6371000, cos = Math.cos(point[0] * Math.PI / 180);
  const x = (a[1] - point[1]) * scale * cos, y = (a[0] - point[0]) * scale;
  const dx = (b[1] - a[1]) * scale * cos, dy = (b[0] - a[0]) * scale;
  const t = dx * dx + dy * dy ? Math.max(0, Math.min(1, -(x * dx + y * dy) / (dx * dx + dy * dy))) : 0;
  return Math.hypot(x + t * dx, y + t * dy);
}

export function coverageFromTrack(track, polygon, bufferM) {
  if (!track?.length || !polygon?.length) return 0;
  const valid = track.filter(point => point.valid && point.gapMs < 120000);
  if (valid.length < 2) return 0;
  const sectorArea = Math.abs(polygon.reduce((sum, p, i) => { const q = polygon[(i + 1) % polygon.length]; return sum + p[1] * q[0] - q[1] * p[0]; }, 0)) / 2;
  const covered = valid.reduce((sum, point, i) => i ? sum + distanceM(valid[i - 1].coords, point.coords) * (bufferM * 2) : sum, 0);
  return Math.min(100, Math.round((covered / Math.max(1, sectorArea * 111000 * 111000)) * 100));
}

export const message = (type, payload, senderId = 'OP-01', recipientId = 'ND-01') => ({
  messageId: `${type}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  operationId: 'OPS-2026-014', senderId, recipientId, type, schemaVersion: 1, sequence: Date.now(), createdAt: Date.now(), payload, state: 'QUEUED', attempts: 0
});
