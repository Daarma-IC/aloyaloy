#include "nusa_mobility.h"

// ============================================================================
//  Lihat nusa_mobility.h untuk gambaran besar. Semua tabel berukuran tetap
//  (tanpa alokasi dinamis) — node berjalan berminggu-minggu di tenaga surya.
// ============================================================================

namespace {

struct LocalUser {            // user yang dilayani node INI
  bool     used;
  uint8_t  id[8];
  uint8_t  slot;              // slot BLE (HP langsung, atau HP perantara HCMA)
  bool     direct;            // true = HP itu sendiri yang tersambung BLE
  uint32_t lastSeen;
};

struct DirEntry {             // direktori: user → node pelayanan (hasil TAU)
  bool     used;
  uint8_t  id[8];
  uint8_t  node;
  bool     direct;
  uint32_t at;
};

struct RouteEntry {           // rute ke node lain, dipelajari dari TAU
  bool     used;
  uint8_t  node;
  uint8_t  nextHop;
  uint8_t  dist;              // jumlah lompatan LoRa
  uint8_t  bootId;
  uint16_t seq;
  uint32_t at;
};

struct Hold {                 // pesan untuk user yang baru saja pergi
  bool     used;
  uint8_t  user[8];
  uint64_t msgId;
  uint16_t len;
  uint32_t at;
  uint8_t  buf[HOLD_MAX_BYTES];
};

struct Pointer {              // "user X sudah pindah ke node Y" / "user X baru pergi"
  bool     used;
  uint8_t  user[8];
  uint8_t  node;              // 0 = belum diketahui ke mana
  uint32_t at;
};

uint8_t        s_self   = 0;
uint8_t        s_boot   = 0;
NusaMob::Hooks s_hooks  = {};
NusaMob::Stats s_stats  = {};

LocalUser  s_local[MOB_LOCAL_SLOTS];
DirEntry   s_dir[MOB_DIR_SLOTS];
RouteEntry s_route[MOB_ROUTE_SLOTS];
Hold       s_hold[HOLD_SLOTS];
Pointer    s_ptr[MOB_LOCAL_SLOTS];     // pergi / pindah

uint16_t s_seq         = 0;
bool     s_dirty       = false;
bool     s_urgent      = false;
uint32_t s_dirtyAt     = 0;
uint32_t s_lastTau     = 0;
uint8_t  s_lastTauUsers = 0;

uint8_t  s_buf[REASM_MAX_BYTES];       // bangun paket terarah (satu thread: loop())

// Penanda handover pada msgId LoRa: pesan tertahan yang diteruskan bisa saja
// melewati node yang sudah pernah melihat msgId aslinya (lalu membuangnya
// sebagai duplikat). Id turunan membuatnya lolos dedup LoRa; HP tetap
// membuang duplikat di level app.
constexpr uint64_t HANDOVER_SALT = 0x484F4E44484F4E44ULL;   // "HONDHOND"

bool idEq(const uint8_t* a, const uint8_t* b) { return memcmp(a, b, 8) == 0; }

bool isNodeId(const uint8_t* id) {
  return id[0] == 'N' && id[1] == 'U' && id[2] == 'S' && id[3] == 'N';
}

// Serial number arithmetic (RFC 1982) supaya seq 16-bit boleh berputar.
bool seqNewer(uint16_t a, uint16_t b) { return (int16_t)(a - b) > 0; }

void markDirty(uint32_t now, bool urgent) {
  if (!s_dirty) s_dirtyAt = now;
  s_dirty = true;
  if (urgent) s_urgent = true;
}

int findLocal(const uint8_t* id) {
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++)
    if (s_local[i].used && idEq(s_local[i].id, id)) return i;
  return -1;
}

int claimLocal() {
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++) if (!s_local[i].used) return i;
  // Penuh: korbankan user tak langsung yang paling lama senyap.
  int victim = -1;
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++) {
    if (s_local[i].direct) continue;
    if (victim < 0 || s_local[i].lastSeen < s_local[victim].lastSeen) victim = i;
  }
  return victim;   // -1 bila semuanya user langsung (tak mungkin: BLE maks 3)
}

int findDir(const uint8_t* id) {
  for (int i = 0; i < MOB_DIR_SLOTS; i++)
    if (s_dir[i].used && idEq(s_dir[i].id, id)) return i;
  return -1;
}

int findRoute(uint8_t node) {
  for (int i = 0; i < MOB_ROUTE_SLOTS; i++)
    if (s_route[i].used && s_route[i].node == node) return i;
  return -1;
}

int findPtr(const uint8_t* id) {
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++)
    if (s_ptr[i].used && idEq(s_ptr[i].user, id)) return i;
  return -1;
}

void setPtr(const uint8_t* id, uint8_t node, uint32_t now) {
  int i = findPtr(id);
  if (i < 0) {
    int oldest = 0;
    for (int k = 0; k < MOB_LOCAL_SLOTS; k++) {
      if (!s_ptr[k].used) { oldest = k; break; }
      if (s_ptr[k].at < s_ptr[oldest].at) oldest = k;
    }
    i = oldest;
    memcpy(s_ptr[i].user, id, 8);
  }
  s_ptr[i].used = true;
  s_ptr[i].node = node;
  s_ptr[i].at   = now;
}

void clearPtr(const uint8_t* id) {
  int i = findPtr(id);
  if (i >= 0) s_ptr[i].used = false;
}

// Rute ke [node] yang next hop-nya masih terdengar. -1 bila tak ada.
int usableRoute(uint8_t node) {
  int r = findRoute(node);
  if (r < 0) return -1;
  if (s_hooks.neighborFresh && !s_hooks.neighborFresh(s_route[r].nextHop)) return -1;
  return r;
}

// Kirim paket app ke [node]: terarah bila ada rute, flood bila tidak.
void sendToward(const uint8_t* app, size_t len, uint64_t loraMsgId, uint8_t node) {
  int r = usableRoute(node);
  if (r >= 0 && len + MOB_DIRECTED_HDR <= sizeof(s_buf)) {
    s_buf[0] = MOB_DIRECTED_MAGIC;
    s_buf[1] = node;
    s_buf[2] = s_self;
    s_buf[3] = s_route[r].nextHop;
    memcpy(s_buf + MOB_DIRECTED_HDR, app, len);
    if (s_hooks.sendDirected &&
        s_hooks.sendDirected(s_buf, len + MOB_DIRECTED_HDR, LORA_HOP_LIMIT, loraMsgId)) return;
  }
  s_stats.fallbackFlood++;
  if (s_hooks.sendFlood) s_hooks.sendFlood(app, len, LORA_HOP_LIMIT, loraMsgId);
}

void flushHolds(const uint8_t* user, uint8_t toNode) {
  for (int i = 0; i < HOLD_SLOTS; i++) {
    if (!s_hold[i].used || !idEq(s_hold[i].user, user)) continue;
    if (toNode == s_self) {
      if (s_hooks.deliverLocal) s_hooks.deliverLocal(s_hold[i].buf, s_hold[i].len);
    } else {
      sendToward(s_hold[i].buf, s_hold[i].len, s_hold[i].msgId ^ HANDOVER_SALT, toNode);
    }
    s_stats.forwardedHandover++;
    s_hold[i].used = false;
  }
}

void holdPacket(const uint8_t* user, const uint8_t* app, size_t len, uint64_t msgId, uint32_t now) {
  if (len > HOLD_MAX_BYTES) return;   // terlalu besar untuk RAM node: gagal
  int slot = -1;
  for (int i = 0; i < HOLD_SLOTS; i++) if (!s_hold[i].used) { slot = i; break; }
  if (slot < 0) {                     // penuh: yang tertua dianggap kedaluwarsa
    slot = 0;
    for (int i = 1; i < HOLD_SLOTS; i++) if (s_hold[i].at < s_hold[slot].at) slot = i;
    s_stats.holdExpired++;
  }
  Hold& h = s_hold[slot];
  h.used  = true;
  memcpy(h.user, user, 8);
  h.msgId = msgId;
  h.len   = (uint16_t)len;
  h.at    = now;
  memcpy(h.buf, app, len);
  s_stats.held++;
}

void removeLocal(int i, uint32_t now) {
  setPtr(s_local[i].id, 0, now);      // "baru pergi" → pesan berikutnya ditahan
  s_local[i].used = false;
  markDirty(now, false);
}

void sendTau(uint32_t now) {
  uint8_t p[MOB_LOC_HDR + LOC_MAX_USERS * MOB_LOC_ENTRY];
  uint8_t n = 0;
  // User langsung dulu: kalau terpotong batas frame, yang terpenting tetap ikut.
  for (int pass = 0; pass < 2; pass++) {
    for (int i = 0; i < MOB_LOCAL_SLOTS && n < LOC_MAX_USERS; i++) {
      if (!s_local[i].used || s_local[i].direct != (pass == 0)) continue;
      uint8_t* e = p + MOB_LOC_HDR + n * MOB_LOC_ENTRY;
      memcpy(e, s_local[i].id, 8);
      e[8] = s_local[i].direct ? 0x01 : 0x00;
      n++;
    }
  }
  s_seq++;
  p[0] = MOB_CTRL_LOC_UPDATE;
  p[1] = s_self;
  p[2] = s_boot;
  p[3] = (uint8_t)(s_seq >> 8);
  p[4] = (uint8_t)s_seq;
  p[5] = s_self;   // lastHop
  p[6] = 0;        // hops sejauh ini
  p[7] = n;
  if (s_hooks.sendCtrl && s_hooks.sendCtrl(p, MOB_LOC_HDR + n * MOB_LOC_ENTRY)) {
    s_stats.locOriginated++;
  }
  s_dirty = false;
  s_urgent = false;
  s_lastTau = now;
  s_lastTauUsers = n;
}

void upsertDir(const uint8_t* id, uint8_t node, bool direct, uint32_t now) {
  int i = findDir(id);
  if (i >= 0) {
    DirEntry& e = s_dir[i];
    // Registrasi langsung lebih kuat daripada "kedengaran lewat HP perantara"
    // di node lain — kecuali entri langsung itu sudah setengah basi.
    bool keepExisting = e.node != node && e.direct && !direct &&
                        (now - e.at) < LOC_ENTRY_TTL_MS / 2;
    if (keepExisting) return;
  } else {
    for (int k = 0; k < MOB_DIR_SLOTS; k++) if (!s_dir[k].used) { i = k; break; }
    if (i < 0) {
      i = 0;
      for (int k = 1; k < MOB_DIR_SLOTS; k++) if (s_dir[k].at < s_dir[i].at) i = k;
    }
    memcpy(s_dir[i].id, id, 8);
  }
  s_dir[i].used   = true;
  s_dir[i].node   = node;
  s_dir[i].direct = direct;
  s_dir[i].at     = now;
}

}  // namespace

// ---------------------------------------------------------------------------
void NusaMob::begin(uint8_t selfNodeId, uint8_t bootId, const Hooks& hooks) {
  s_self  = selfNodeId;
  s_boot  = bootId;
  s_hooks = hooks;
  s_stats = {};
  for (auto& x : s_local) x.used = false;
  for (auto& x : s_dir)   x.used = false;
  for (auto& x : s_route) x.used = false;
  for (auto& x : s_hold)  x.used = false;
  for (auto& x : s_ptr)   x.used = false;
  s_seq = 0;
  s_dirty = s_urgent = false;
  s_lastTau = 0;
  s_lastTauUsers = 0;
}

void NusaMob::loop(uint32_t now) {
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++) {
    if (s_local[i].used && !s_local[i].direct && now - s_local[i].lastSeen > INDIRECT_TTL_MS)
      removeLocal(i, now);
  }
  for (auto& e : s_dir)   if (e.used && now - e.at > LOC_ENTRY_TTL_MS) e.used = false;
  for (auto& r : s_route) if (r.used && now - r.at > LOC_ENTRY_TTL_MS) r.used = false;
  for (auto& p : s_ptr)   if (p.used && now - p.at > MOVED_POINTER_TTL_MS) p.used = false;
  for (auto& h : s_hold) {
    if (h.used && now - h.at > HOLD_TTL_MS) { h.used = false; s_stats.holdExpired++; }
  }

#if MOBILITY_ROUTING
  if (s_dirty && (s_urgent || now - s_dirtyAt >= LOC_DEBOUNCE_MS)) {
    sendTau(now);
  } else if (now - s_lastTau >= LOC_UPDATE_PERIOD_MS) {
    // TAU berkala hanya kalau ada yang perlu diumumkan (atau perlu diralat).
    if (localUserCount(false) > 0 || s_lastTauUsers > 0) sendTau(now);
    else s_lastTau = now;
  }
#endif
}

void NusaMob::onRegister(uint8_t slot, const uint8_t user[8], uint8_t prevNode, uint32_t now) {
  // Slot dipakai HP baru: pemilik lama slot ini (kalau ada) sudah pergi.
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++) {
    if (s_local[i].used && s_local[i].direct && s_local[i].slot == slot && !idEq(s_local[i].id, user))
      removeLocal(i, now);
  }
  int i = findLocal(user);
  bool changed = (i < 0) || !s_local[i].direct || s_local[i].slot != slot;
  if (i < 0) {
    i = claimLocal();
    if (i < 0) return;
    memcpy(s_local[i].id, user, 8);
  }
  s_local[i].used     = true;
  s_local[i].slot     = slot;
  s_local[i].direct   = true;
  s_local[i].lastSeen = now;

  clearPtr(user);
  flushHolds(user, s_self);   // kembali ke node ini: serahkan yang sempat ditahan
  if (changed) markDirty(now, prevNode != 0 && prevNode != s_self);
}

void NusaMob::onSlotDisconnected(uint8_t slot, uint32_t now) {
  for (int i = 0; i < MOB_LOCAL_SLOTS; i++) {
    if (s_local[i].used && s_local[i].slot == slot) removeLocal(i, now);
  }
}

void NusaMob::noteSender(uint8_t slot, const uint8_t sender[8], uint32_t now) {
  if (slot == 255 || isNodeId(sender)) return;
  int i = findLocal(sender);
  if (i >= 0) {
    s_local[i].lastSeen = now;
    if (!s_local[i].direct && s_local[i].slot != slot) s_local[i].slot = slot;
    return;
  }
  i = claimLocal();
  if (i < 0) return;
  memcpy(s_local[i].id, sender, 8);
  s_local[i].used     = true;
  s_local[i].slot     = slot;
  s_local[i].direct   = false;   // naik jadi langsung begitu NODE_REGISTER tiba
  s_local[i].lastSeen = now;
  clearPtr(sender);
  flushHolds(sender, s_self);
  markDirty(now, false);
}

NusaMob::RouteDecision NusaMob::route(const uint8_t recipient[8], uint8_t* dstNode, uint8_t* nextHop) {
  if (findLocal(recipient) >= 0) { s_stats.localOnly++; return ROUTE_LOCAL_ONLY; }
  int d = findDir(recipient);
  if (d >= 0 && s_dir[d].node != s_self) {
    int r = usableRoute(s_dir[d].node);
    if (r >= 0) {
      *dstNode = s_dir[d].node;
      *nextHop = s_route[r].nextHop;
      return ROUTE_DIRECTED;
    }
  }
  s_stats.fallbackFlood++;
  return ROUTE_FLOOD;
}

bool NusaMob::sendDirected(const uint8_t* pkt, size_t len, uint64_t msgId,
                           uint8_t dstNode, uint8_t nextHop) {
  if (len + MOB_DIRECTED_HDR > sizeof(s_buf) || !s_hooks.sendDirected) return false;
  s_buf[0] = MOB_DIRECTED_MAGIC;
  s_buf[1] = dstNode;
  s_buf[2] = s_self;
  s_buf[3] = nextHop;
  memcpy(s_buf + MOB_DIRECTED_HDR, pkt, len);
  if (!s_hooks.sendDirected(s_buf, len + MOB_DIRECTED_HDR, LORA_HOP_LIMIT, msgId)) return false;
  s_stats.directedOriginated++;
  return true;
}

void NusaMob::onCtrl(const uint8_t* data, size_t len, uint32_t now) {
  if (len < MOB_LOC_HDR || data[0] != MOB_CTRL_LOC_UPDATE) return;
  uint8_t  origin  = data[1];
  uint8_t  boot    = data[2];
  uint16_t seq     = ((uint16_t)data[3] << 8) | data[4];
  uint8_t  lastHop = data[5];
  uint8_t  hops    = data[6];
  uint8_t  n       = data[7];
  if (origin == s_self || origin == 0) return;
  if (len < (size_t)MOB_LOC_HDR + n * MOB_LOC_ENTRY) return;
  uint8_t dist = hops + 1;

  int r = findRoute(origin);
  bool newer = r < 0 || s_route[r].bootId != boot || seqNewer(seq, s_route[r].seq);
  if (!newer) {
    // TAU yang sama lewat jalur lebih pendek: perbaiki next hop saja.
    if (s_route[r].bootId == boot && s_route[r].seq == seq && dist < s_route[r].dist) {
      s_route[r].nextHop = lastHop;
      s_route[r].dist    = dist;
      s_route[r].at      = now;
    }
    return;
  }

  if (r < 0) {
    for (int k = 0; k < MOB_ROUTE_SLOTS; k++) if (!s_route[k].used) { r = k; break; }
    if (r < 0) {
      r = 0;
      for (int k = 1; k < MOB_ROUTE_SLOTS; k++) if (s_route[k].at < s_route[r].at) r = k;
    }
  }
  s_route[r] = {true, origin, lastHop, dist, boot, seq, now};

  // Direktori: daftar ini PENGGANTI penuh daftar lama milik origin.
  for (auto& e : s_dir) {
    if (!e.used || e.node != origin) continue;
    bool still = false;
    for (uint8_t k = 0; k < n && !still; k++)
      still = idEq(e.id, data + MOB_LOC_HDR + k * MOB_LOC_ENTRY);
    if (!still) e.used = false;
  }
  for (uint8_t k = 0; k < n; k++) {
    const uint8_t* id = data + MOB_LOC_HDR + k * MOB_LOC_ENTRY;
    bool direct = data[MOB_LOC_HDR + k * MOB_LOC_ENTRY + 8] & 0x01;
    upsertDir(id, origin, direct, now);
    // User yang tadinya dilayani di sini kini terdaftar di origin: pasang
    // penunjuk perpindahan, lalu teruskan pesan yang sempat ditahan.
    int p = findPtr(id);
    if (p >= 0 || findLocal(id) < 0) {
      bool hadHolds = false;
      for (auto& h : s_hold) if (h.used && idEq(h.user, id)) { hadHolds = true; break; }
      if (p >= 0 || hadHolds) {
        setPtr(id, origin, now);
        flushHolds(id, origin);
      }
    }
  }

  // Teruskan TAU ke node yang lebih jauh (dibatasi hop, sekali per seq).
  if (dist < LORA_HOP_LIMIT && len <= sizeof(s_buf) && s_hooks.sendCtrl) {
    memcpy(s_buf, data, len);
    s_buf[5] = s_self;
    s_buf[6] = dist;
    if (s_hooks.sendCtrl(s_buf, len)) s_stats.locRelayed++;
  }
}

void NusaMob::onDirected(const uint8_t* buf, size_t len, uint64_t msgId, uint8_t hop, uint32_t now) {
  if (len <= MOB_DIRECTED_HDR || buf[0] != MOB_DIRECTED_MAGIC) return;
  uint8_t dst = buf[1];
  uint8_t nh  = buf[3];
  const uint8_t* app = buf + MOB_DIRECTED_HDR;
  size_t appLen = len - MOB_DIRECTED_HDR;

  if (dst != s_self) {
    if (nh != s_self || hop <= 1) { s_stats.directedIgnored++; return; }
    int r = usableRoute(dst);
    if (r < 0) {
      // Rute ke tujuan hilang di tengah jalan: jangan buang, flood saja.
      s_stats.fallbackFlood++;
      if (s_hooks.sendFlood) s_hooks.sendFlood(app, appLen, hop - 1, msgId);
      return;
    }
    memcpy(s_buf, buf, len);
    s_buf[3] = s_route[r].nextHop;
    if (s_hooks.sendDirected && s_hooks.sendDirected(s_buf, len, hop - 1, msgId))
      s_stats.directedRelayed++;
    return;
  }

  // Kita node tujuan.
  uint8_t rcpt[8];
  if (!nusaAppRecipientId(app, appLen, rcpt) || findLocal(rcpt) >= 0) {
    if (s_hooks.deliverLocal) s_hooks.deliverLocal(app, appLen);
    return;
  }
  int p = findPtr(rcpt);
  if (p >= 0 && s_ptr[p].node != 0 && s_ptr[p].node != s_self) {
    // Sudah tahu user pindah ke mana: langsung teruskan (data forwarding).
    sendToward(app, appLen, msgId ^ HANDOVER_SALT, s_ptr[p].node);
    s_stats.forwardedHandover++;
    return;
  }
  // HP lain di sini tetap diberi — siapa tahu penerima masih terjangkau
  // lewat mesh BLE — tapi kalau penerima baru saja pergi, tahan juga salinan
  // untuk diteruskan begitu node barunya mengumumkan diri.
  if (s_hooks.deliverLocal) s_hooks.deliverLocal(app, appLen);
  if (p >= 0) holdPacket(rcpt, app, appLen, msgId, now);
}

uint8_t NusaMob::localUserCount(bool directOnly) {
  uint8_t n = 0;
  for (auto& u : s_local) if (u.used && (!directOnly || u.direct)) n++;
  return n;
}

bool NusaMob::isLocalUser(const uint8_t user[8]) { return findLocal(user) >= 0; }

int NusaMob::directoryLookup(const uint8_t user[8]) {
  int d = findDir(user);
  return d >= 0 ? s_dir[d].node : -1;
}

int NusaMob::routeNextHop(uint8_t node) {
  int r = findRoute(node);
  return r >= 0 ? s_route[r].nextHop : -1;
}

const NusaMob::Stats& NusaMob::stats() { return s_stats; }
