#line 1 "C:\\Users\\darma\\OneDrive\\Dokumen\\taa\\firmware 2\\firmware\\NusaNode\\nusa_fragment.cpp"
#include "nusa_fragment.h"

namespace {

struct Slot {
  bool     used;
  uint64_t msgId;
  uint8_t  hop;
  uint8_t  total;
  uint32_t mask;                       // bitmap fragmen yang sudah tiba (maks 32)
  uint16_t len;                        // panjang total sejauh ini
  uint32_t lastProgress;                // timeout sejak fragmen BARU terakhir
  uint8_t  buf[REASM_MAX_BYTES];
};

Slot     s_slot[REASM_SLOTS];
NusaFrag::CompleteHandler s_handler = nullptr;
uint32_t s_droppedIncomplete = 0;

int findSlot(uint64_t msgId) {
  for (int i = 0; i < REASM_SLOTS; i++)
    if (s_slot[i].used && s_slot[i].msgId == msgId) return i;
  return -1;
}

int claimSlot() {
  for (int i = 0; i < REASM_SLOTS; i++)
    if (!s_slot[i].used) return i;

  // Jangan korbankan voice note yang sedang dirakit hanya karena paket baru
  // datang. Paket baru ditolak dan pengirim/app boleh mengulang kemudian.
  s_droppedIncomplete++;
  return -1;
}

}  // namespace

void NusaFrag::begin(CompleteHandler handler) {
  s_handler = handler;
  for (int i = 0; i < REASM_SLOTS; i++) s_slot[i].used = false;
  s_droppedIncomplete = 0;
}

void NusaFrag::loop() {
  uint32_t now = millis();
  for (int i = 0; i < REASM_SLOTS; i++) {
    if (!s_slot[i].used) continue;
    if (now - s_slot[i].lastProgress > REASM_TIMEOUT_MS) {
      s_slot[i].used = false;
      s_droppedIncomplete++;
    }
  }
}

uint8_t NusaFrag::sendPacket(const uint8_t* pkt, size_t len,
                             uint8_t hop, NusaPrio prio) {
  if (!pkt || len == 0 || len > REASM_MAX_BYTES) return 0;

  uint16_t total = (len + NUSA_FRAG_MAX_DATA - 1) / NUSA_FRAG_MAX_DATA;
  if (total == 0) return 0;
  if (total > 32) {
    // Bitmap perakitan hanya 32 bit. Paket sebesar ini juga tidak realistis
    // untuk dikirim: 32 frame di SF10 berarti lebih dari satu menit airtime.
    Serial.printf("[FRAG] paket %u B butuh %u fragmen — ditolak\n",
                  (unsigned)len, total);
    return 0;
  }

  return NusaRadio::enqueuePacket(pkt, len, hop, prio);
}

void NusaFrag::feed(const NusaFrame& f) {
  if (f.fragTotal == 0 || f.dataLen == 0) return;

  // Jalur cepat: paket satu fragmen tidak perlu slot perakitan sama sekali.
  if (f.fragTotal == 1) {
    if (f.fragIdx != 0) return;
    if (s_handler) s_handler(f.msgId, f.hop, f.data, f.dataLen);
    return;
  }

  if (f.fragTotal > 32 || f.fragIdx >= f.fragTotal) return;
  // Semua fragmen selain yang terakhir harus penuh. Tanpa aturan ini, frame
  // pendek di tengah membuat lubang berisi RAM lama ikut dikirim ke aplikasi.
  if (f.fragIdx + 1 < f.fragTotal && f.dataLen != NUSA_FRAG_MAX_DATA) return;

  int idx = findSlot(f.msgId);
  if (idx < 0) {
    idx = claimSlot();
    if (idx < 0) return;
    Slot& s = s_slot[idx];
    s.used      = true;
    s.msgId     = f.msgId;
    s.hop       = f.hop;
    s.total     = f.fragTotal;
    s.mask      = 0;
    s.len       = 0;
    s.lastProgress = millis();
  }

  Slot& s = s_slot[idx];
  if (s.total != f.fragTotal || s.hop != f.hop) return; // frame tak konsisten
  if (s.mask & (1UL << f.fragIdx)) return;        // fragmen ini sudah pernah tiba

  size_t off = (size_t)f.fragIdx * NUSA_FRAG_MAX_DATA;
  if (off + f.dataLen > REASM_MAX_BYTES) {
    s.used = false;
    s_droppedIncomplete++;
    return;
  }

  memcpy(s.buf + off, f.data, f.dataLen);
  s.mask |= (1UL << f.fragIdx);
  s.lastProgress = millis();
  if (off + f.dataLen > s.len) s.len = off + f.dataLen;

  // Fragmen terakhir menentukan panjang sebenarnya; yang lain selalu penuh.
  uint32_t complete = (f.fragTotal >= 32) ? 0xFFFFFFFFUL
                                          : ((1UL << f.fragTotal) - 1);
  if (s.mask == complete) {
    s.used = false;
    if (s_handler) s_handler(s.msgId, s.hop, s.buf, s.len);
  }
}

uint8_t  NusaFrag::activeSlots() {
  uint8_t n = 0;
  for (int i = 0; i < REASM_SLOTS; i++) if (s_slot[i].used) n++;
  return n;
}

uint32_t NusaFrag::droppedIncomplete() { return s_droppedIncomplete; }
