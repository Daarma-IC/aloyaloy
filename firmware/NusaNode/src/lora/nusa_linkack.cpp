#include "nusa_linkack.h"
#include "nusa_radio.h"
#include "../routing/nusa_mobility.h"

#if LINKACK_ENABLED

namespace {

struct Entry {
  bool     used;
  bool     armed;          // semua frame sudah keluar antrean TX → timer ACK berjalan
  uint8_t  nextHop;
  uint8_t  hop;
  uint8_t  retries;
  uint16_t len;
  uint64_t msgId;
  uint32_t createdAt;
  uint32_t deadline;       // valid bila armed
  uint32_t notBefore;      // jeda bila antrean penuh saat kirim ulang
  uint8_t  buf[REASM_MAX_BYTES];
};

Entry    s_e[LINKACK_SLOTS];
uint8_t  s_self = 0;
NusaLinkAck::Stats s_stats = {};
portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;
struct Guard { Guard() { portENTER_CRITICAL(&s_mux); } ~Guard() { portEXIT_CRITICAL(&s_mux); } };

// Paket dipakai hanya dari loop(): salinan kerja untuk kirim ulang tanpa memegang lock.
uint8_t  s_scratch[REASM_MAX_BYTES];

// Batas usia pemantauan: lebih dari ini, biarkan NACK end-to-end yang menangani.
constexpr uint32_t MAX_AGE_MS = 10UL * 60UL * 1000UL;

uint32_t ackWaitMs() {
  // Penerima mungkin sedang memancar satu frame penuh, menunggu anggaran duty,
  // lalu CAD + jitter. Skala dengan SF aktif (SF12 frame penuh ±9 s).
  return LINKACK_WAIT_MS + (uint32_t)(3.0f * NusaRadio::airtimeMs(NUSA_FRAME_MAX)) + random(500);
}

int findEntry(uint64_t msgId, uint8_t nextHop) {
  for (int i = 0; i < LINKACK_SLOTS; i++)
    if (s_e[i].used && s_e[i].msgId == msgId && s_e[i].nextHop == nextHop) return i;
  return -1;
}

}  // namespace

void NusaLinkAck::begin(uint8_t selfNodeId) {
  Guard g;
  s_self = selfNodeId;
  for (int i = 0; i < LINKACK_SLOTS; i++) s_e[i].used = false;
  s_stats = {};
}

void NusaLinkAck::track(const uint8_t* buf, size_t len, uint8_t hop, uint64_t msgId) {
  if (!buf || len <= MOB_DIRECTED_HDR || len > REASM_MAX_BYTES) return;
  if (buf[0] != MOB_DIRECTED_MAGIC) return;
  const uint8_t nh = buf[3];
  if (nh == 0 || nh == s_self) return;

  Guard g;
  int slot = findEntry(msgId, nh);
  if (slot < 0) {
    for (int i = 0; i < LINKACK_SLOTS; i++) if (!s_e[i].used) { slot = i; break; }
  }
  if (slot < 0) {
    // Penuh: gusur yang paling tua (ia paling mungkin sudah ditangani NACK end-to-end).
    uint32_t oldest = 0;
    slot = 0;
    for (int i = 0; i < LINKACK_SLOTS; i++) {
      uint32_t age = millis() - s_e[i].createdAt;
      if (age > oldest) { oldest = age; slot = i; }
    }
    ++s_stats.evicted;
  }
  Entry& e = s_e[slot];
  e.used = true; e.armed = false;
  e.nextHop = nh; e.hop = hop; e.retries = 0;
  e.len = (uint16_t)len; e.msgId = msgId;
  e.createdAt = millis(); e.deadline = 0; e.notBefore = 0;
  memcpy(e.buf, buf, len);
  ++s_stats.tracked;
}

void NusaLinkAck::onDirectedComplete(const uint8_t* buf, size_t len, uint64_t msgId) {
  if (!buf || len <= MOB_DIRECTED_HDR || buf[0] != MOB_DIRECTED_MAGIC) return;
  if (buf[3] != s_self) return;                 // kita bukan next hop: diam

  uint8_t payload[2] = { s_self, buf[2] };      // [dari kita, ke node asal header]
  NusaFrame f;
  f.version   = NUSA_FRAME_VERSION;
  f.flags     = NUSA_FLAG_ACK;
  f.hop       = 1;
  f.msgId     = msgId;
  f.fragIdx   = 0;
  f.fragTotal = 1;
  f.data      = payload;
  f.dataLen   = sizeof(payload);
  uint8_t out[NUSA_FRAME_HDR + sizeof(payload)];
  size_t n = nusaFrameEncode(out, f);
  if (NusaRadio::enqueue(out, n, PRIO_ACK)) {
    Guard g;
    ++s_stats.acksSent;
  }
}

uint8_t NusaLinkAck::onAckFrame(const NusaFrame& f) {
  if (f.dataLen < 1) return 0;
  const uint8_t from = f.data[0];
  Guard g;
  int i = findEntry(f.msgId, from);
  if (i >= 0) {
    s_e[i].used = false;
    ++s_stats.acked;
  }
  return from;
}

void NusaLinkAck::loop() {
  const uint32_t now = millis();
  int due = -1;
  {
    Guard g;
    for (int i = 0; i < LINKACK_SLOTS; i++) {
      Entry& e = s_e[i];
      if (!e.used) continue;
      if (now - e.createdAt > MAX_AGE_MS) { e.used = false; ++s_stats.gaveUp; continue; }
      if (due < 0 && (e.armed ? (int32_t)(now - e.deadline) >= 0 : false) &&
          (int32_t)(now - e.notBefore) >= 0) {
        due = i;
      }
    }
  }

  // Persenjatai timer untuk entri yang semua framenya sudah meninggalkan antrean.
  // queuedFramesFor() memegang lock antrean radio sendiri; jangan panggil di bawah s_mux.
  for (int i = 0; i < LINKACK_SLOTS; i++) {
    uint64_t id; bool needArm;
    { Guard g; needArm = s_e[i].used && !s_e[i].armed; id = s_e[i].msgId; }
    if (!needArm || NusaRadio::queuedFramesFor(id) > 0) continue;
    const uint32_t wait = ackWaitMs();
    Guard g;
    if (s_e[i].used && !s_e[i].armed && s_e[i].msgId == id) {
      s_e[i].armed = true;
      s_e[i].deadline = millis() + wait;
    }
  }

  if (due < 0) return;

  uint16_t len; uint8_t hop; uint64_t id; uint8_t nh;
  {
    Guard g;
    Entry& e = s_e[due];
    if (!e.used) return;
    if (e.retries >= LINKACK_MAX_RETRIES) { e.used = false; ++s_stats.gaveUp; return; }
    len = e.len; hop = e.hop; id = e.msgId; nh = e.nextHop;
    memcpy(s_scratch, e.buf, len);
  }

  const bool queued = NusaRadio::enqueuePacketWithId(s_scratch, len, hop, PRIO_RELAY, id) > 0;

  Guard g;
  int i = findEntry(id, nh);
  if (i < 0) return;                            // ACK tiba saat kita mengantre ulang
  Entry& e = s_e[i];
  if (queued) {
    ++e.retries; ++s_stats.retransmits;
    e.armed = false;                            // timer baru setelah frame ulang terpancar
  } else {
    e.notBefore = millis() + 1000;              // antrean penuh: coba sebentar lagi
  }
}

uint8_t NusaLinkAck::pending() {
  Guard g;
  uint8_t n = 0;
  for (int i = 0; i < LINKACK_SLOTS; i++) if (s_e[i].used) n++;
  return n;
}

const NusaLinkAck::Stats& NusaLinkAck::stats() { return s_stats; }

#else  // LINKACK_ENABLED == 0: semua jadi no-op

namespace { NusaLinkAck::Stats s_stats = {}; }
void NusaLinkAck::begin(uint8_t) {}
void NusaLinkAck::track(const uint8_t*, size_t, uint8_t, uint64_t) {}
void NusaLinkAck::onDirectedComplete(const uint8_t*, size_t, uint64_t) {}
uint8_t NusaLinkAck::onAckFrame(const NusaFrame& f) { return f.dataLen ? f.data[0] : 0; }
void NusaLinkAck::loop() {}
uint8_t NusaLinkAck::pending() { return 0; }
const NusaLinkAck::Stats& NusaLinkAck::stats() { return s_stats; }

#endif
