// Uji host untuk nusa_mobility (dilihat dari sudut pandang SATU node, node 1).
// Jalankan: firmware/tests/run_mobility_test.sh
#include <cassert>
#include <cstdio>
#include <vector>
#include "nusa_mobility.h"

uint32_t testMillis = 1;

struct Sent { std::vector<uint8_t> data; uint8_t hop; uint64_t msgId; };
static std::vector<std::vector<uint8_t>> ctrl;
static std::vector<Sent> directed, flooded;
static std::vector<std::vector<uint8_t>> delivered;
static bool neighborsFresh = true;

static bool hSendCtrl(const uint8_t* p, size_t n) { ctrl.emplace_back(p, p + n); return true; }
static bool hSendDirected(const uint8_t* p, size_t n, uint8_t hop, uint64_t id) {
  directed.push_back({std::vector<uint8_t>(p, p + n), hop, id}); return true;
}
static bool hSendFlood(const uint8_t* p, size_t n, uint8_t hop, uint64_t id) {
  flooded.push_back({std::vector<uint8_t>(p, p + n), hop, id}); return true;
}
static void hDeliver(const uint8_t* p, size_t n) { delivered.emplace_back(p, p + n); }
static bool hFresh(uint8_t) { return neighborsFresh; }

static void reset() { ctrl.clear(); directed.clear(); flooded.clear(); delivered.clear(); }

static std::vector<uint8_t> uid(uint8_t b) { return std::vector<uint8_t>(8, b); }

// Paket app v2 unicast: header 16 + sender 8 + recipient 8 + payload.
static std::vector<uint8_t> appPacket(uint8_t sender, uint8_t recipient, uint8_t tag) {
  std::vector<uint8_t> p(16 + 8 + 8 + 4, 0);
  p[0] = 2; p[1] = 0x04; p[2] = 7; p[11] = 0x01;
  for (int i = 0; i < 8; i++) { p[16 + i] = sender; p[24 + i] = recipient; }
  p[32] = tag;
  return p;
}

static std::vector<uint8_t> tau(uint8_t origin, uint8_t boot, uint16_t seq, uint8_t lastHop,
                                uint8_t hops, std::vector<std::pair<uint8_t, bool>> users) {
  std::vector<uint8_t> p = {MOB_CTRL_LOC_UPDATE, origin, boot, (uint8_t)(seq >> 8), (uint8_t)seq,
                            lastHop, hops, (uint8_t)users.size()};
  for (auto& u : users) { for (int i = 0; i < 8; i++) p.push_back(u.first); p.push_back(u.second ? 1 : 0); }
  return p;
}

static std::vector<uint8_t> directedPkt(uint8_t dst, uint8_t src, uint8_t nh, const std::vector<uint8_t>& app) {
  std::vector<uint8_t> b = {MOB_DIRECTED_MAGIC, dst, src, nh};
  b.insert(b.end(), app.begin(), app.end());
  return b;
}

int main() {
  NusaMob::Hooks hooks = {hSendCtrl, hSendDirected, hSendFlood, hDeliver, hFresh};
  NusaMob::begin(1, 0x42, hooks);
  auto U1 = uid(0x11), U3 = uid(0x33), UA = uid(0xAA), UB = uid(0xBB);

  // 1. Registrasi HP langsung → TAU setelah debounce, berisi U1 sebagai user langsung.
  NusaMob::onRegister(0, U1.data(), 0, testMillis);
  NusaMob::loop(testMillis += 100);
  assert(ctrl.empty());                               // masih dalam debounce
  NusaMob::loop(testMillis += LOC_DEBOUNCE_MS);
  assert(ctrl.size() == 1);
  assert(ctrl[0][1] == 1 && ctrl[0][5] == 1 && ctrl[0][6] == 0 && ctrl[0][7] == 1);
  assert(ctrl[0][8] == 0x11 && ctrl[0][16] == 0x01);
  assert(NusaMob::stats().locOriginated == 1);

  // 2. Penerima dilayani node ini → tidak perlu LoRa sama sekali.
  uint8_t dst = 0, nh = 0;
  assert(NusaMob::route(U1.data(), &dst, &nh) == NusaMob::ROUTE_LOCAL_ONLY);

  // 3. TAU node 3 lewat node 2 (hops=1) → rute ke node 3 via 2, TAU diteruskan.
  reset();
  auto t3 = tau(3, 7, 10, 2, 1, {{0x33, true}});
  NusaMob::onCtrl(t3.data(), t3.size(), testMillis);
  assert(NusaMob::directoryLookup(U3.data()) == 3);
  assert(NusaMob::routeNextHop(3) == 2);
  assert(ctrl.size() == 1 && ctrl[0][5] == 1 && ctrl[0][6] == 2);   // relay: lastHop=kita, hops=2
  assert(NusaMob::route(U3.data(), &dst, &nh) == NusaMob::ROUTE_DIRECTED && dst == 3 && nh == 2);

  // 3b. TAU yang sama (seq sama) tidak diteruskan lagi; seq lama diabaikan.
  reset();
  NusaMob::onCtrl(t3.data(), t3.size(), testMillis);
  auto old = tau(3, 7, 9, 2, 1, {});
  NusaMob::onCtrl(old.data(), old.size(), testMillis);
  assert(ctrl.empty() && NusaMob::directoryLookup(U3.data()) == 3);
  // Node 3 reboot (bootId baru) dengan seq kecil tetap diterima.
  auto rebooted = tau(3, 8, 1, 2, 1, {{0x33, true}});
  NusaMob::onCtrl(rebooted.data(), rebooted.size(), testMillis);
  assert(ctrl.size() == 1);

  // 4. Kirim terarah: header routing benar, msgId app dipertahankan.
  reset();
  auto app = appPacket(0x11, 0x33, 1);
  assert(NusaMob::sendDirected(app.data(), app.size(), 0xABCD, 3, 2));
  assert(directed.size() == 1 && directed[0].msgId == 0xABCD);
  assert(directed[0].data[0] == MOB_DIRECTED_MAGIC && directed[0].data[1] == 3 &&
         directed[0].data[2] == 1 && directed[0].data[3] == 2);

  // 5. Kita next hop untuk paket ke node 3 → diteruskan; bukan next hop → diabaikan.
  reset();
  auto via = directedPkt(3, 5, 1, appPacket(0x55, 0x33, 2));
  NusaMob::onDirected(via.data(), via.size(), 0x1111, 3, testMillis);
  assert(directed.size() == 1 && directed[0].hop == 2 && directed[0].data[3] == 2);
  auto notMine = directedPkt(3, 5, 9, appPacket(0x55, 0x33, 3));
  NusaMob::onDirected(notMine.data(), notMine.size(), 0x2222, 3, testMillis);
  assert(directed.size() == 1 && NusaMob::stats().directedIgnored == 1);

  // 6. Handover: U1 putus → TAU tanpa U1; pesan untuk U1 ditahan (dan tetap
  //    diberikan ke HP lokal); node 2 mengumumkan U1 → pesan diteruskan ke node 2.
  reset();
  NusaMob::onSlotDisconnected(0, testMillis);
  NusaMob::loop(testMillis += LOC_DEBOUNCE_MS + 1);
  assert(ctrl.size() == 1 && ctrl[0][7] == 0);
  auto forU1 = directedPkt(1, 3, 1, appPacket(0x33, 0x11, 4));
  NusaMob::onDirected(forU1.data(), forU1.size(), 0x3333, 3, testMillis);
  assert(delivered.size() == 1 && NusaMob::stats().held == 1);
  auto t2 = tau(2, 1, 5, 2, 0, {{0x11, true}});
  NusaMob::onCtrl(t2.data(), t2.size(), testMillis);
  assert(NusaMob::stats().forwardedHandover == 1);
  assert(directed.size() == 1 && directed[0].data[1] == 2 && directed[0].data[3] == 2);
  assert(directed[0].msgId != 0x3333);                // id turunan: lolos dedup relay
  assert(directed[0].data[MOB_DIRECTED_HDR + 32] == 4); // isi pesan utuh

  // 6b. Pesan telat berikutnya untuk U1 langsung diteruskan lewat penunjuk.
  reset();
  auto late = directedPkt(1, 3, 1, appPacket(0x33, 0x11, 5));
  NusaMob::onDirected(late.data(), late.size(), 0x4444, 3, testMillis);
  assert(directed.size() == 1 && directed[0].data[1] == 2 && NusaMob::stats().forwardedHandover == 2);

  // 7. HCMA: UA terdengar lewat slot 1 (di belakang HP perantara UB).
  reset();
  NusaMob::onRegister(1, UB.data(), 0, testMillis);
  NusaMob::noteSender(1, UA.data(), testMillis);
  assert(NusaMob::localUserCount(true) == 1 && NusaMob::localUserCount(false) == 2);
  assert(NusaMob::route(UA.data(), &dst, &nh) == NusaMob::ROUTE_LOCAL_ONLY);
  NusaMob::loop(testMillis += LOC_DEBOUNCE_MS + 1);
  assert(ctrl.size() == 1 && ctrl[0][7] == 2 && ctrl[0][16] == 0x01 && ctrl[0][25] == 0x00);
  // HP perantara putus → user di belakangnya ikut pergi.
  NusaMob::onSlotDisconnected(1, testMillis);
  assert(NusaMob::localUserCount(false) == 0);

  // 8. Next hop tak terdengar lagi → flood (bukan dibuang).
  neighborsFresh = false;
  assert(NusaMob::route(U3.data(), &dst, &nh) == NusaMob::ROUTE_FLOOD);
  neighborsFresh = true;

  // 9. Pesan tertahan yang tak pernah dijemput → kedaluwarsa (dicatat gagal).
  reset();
  NusaMob::onRegister(2, U1.data(), 0, testMillis);     // U1 kembali, lalu pergi lagi
  NusaMob::onSlotDisconnected(2, testMillis);
  auto stale = directedPkt(1, 3, 1, appPacket(0x33, 0x11, 6));
  uint32_t expiredBefore = NusaMob::stats().holdExpired;
  // Penunjuk "pindah ke node 2" dihapus saat U1 kembali; sekarang tak diketahui.
  NusaMob::onDirected(stale.data(), stale.size(), 0x5555, 3, testMillis);
  NusaMob::loop(testMillis += HOLD_TTL_MS + 1);
  assert(NusaMob::stats().holdExpired == expiredBefore + 1);

  // 10. Entri direktori/rute basi dibuang setelah TTL.
  assert(NusaMob::directoryLookup(U3.data()) == -1 && NusaMob::routeNextHop(3) == -1);

  std::printf("mobility_test: semua skenario lolos\n");
  return 0;
}
