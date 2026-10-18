import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "leaflet-draw/dist/leaflet.draw.css";
import "leaflet-draw";
import "./styles.css";
import "./navigation.css";
import "./terrain/terrain.css";
import TerrainPanel from "./terrain/TerrainPanel.jsx";
import NavigationConsole from "./terrain/NavigationConsole.jsx";
import { routeProgress } from "./terrain/engine.js";
import { routeOsrm } from "./routing/osrm.js";
import {
  GEOFENCE_PROFILES,
  bearingDeg,
  coverageFromTrack,
  distanceM,
  geofenceStep,
  message,
} from "./domain.js";
import {
  downloadTrailGraph,
  graphBoundsFromPolygons,
  loadTrailGraph,
  routeTrail,
} from "./trailRouter.js";

const STORAGE_KEY = "nusasurvey.prototype.v3";
const CENTER = [-7.5835, 110.4385];
const now = () => Date.now();
const clock = (value) =>
  new Date(value).toLocaleTimeString("id-ID", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
const uid = (prefix) =>
  `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`;
const formatDuration = (seconds) => {
  const minutes = Math.max(1, Math.round(seconds / 60));
  return minutes < 60
    ? `${minutes} menit`
    : `${Math.floor(minutes / 60)} jam ${minutes % 60} menit`;
};

const initialState = {
  operation: {
    id: "OPS-2026-014",
    name: "Pencarian Lereng Merapi",
    status: "ACTIVE",
    basecamp: [-7.5968, 110.4265],
    createdAt: now(),
  },
  sectors: [
    {
      id: "SEC-A",
      label: "Sektor Alpha",
      version: 1,
      color: "#f97316",
      polygon: [
        [-7.6018, 110.4238],
        [-7.586, 110.4246],
        [-7.5825, 110.442],
        [-7.5986, 110.444],
      ],
      teamId: "TEAM-A",
    },
    {
      id: "SEC-B",
      label: "Sektor Bravo",
      version: 1,
      color: "#16a085",
      polygon: [
        [-7.586, 110.4246],
        [-7.57, 110.429],
        [-7.568, 110.449],
        [-7.5825, 110.442],
      ],
      teamId: "TEAM-B",
    },
  ],
  assignments: [
    {
      id: "ASG-01",
      sectorId: "SEC-A",
      sectorVersion: 1,
      teamId: "TEAM-A",
      status: "ACTIVE",
      deliveryStatus: "ACKNOWLEDGED",
    },
    {
      id: "ASG-02",
      sectorId: "SEC-B",
      sectorVersion: 1,
      teamId: "TEAM-B",
      status: "SENT",
      deliveryStatus: "SENT",
    },
  ],
  nodes: [
    {
      id: "ND-01",
      name: "Posko Kaliurang",
      pos: [-7.5968, 110.4265],
      lastHeard: now(),
      rssi: -72,
      snr: 8.4,
    },
    {
      id: "ND-02",
      name: "Relay Timur",
      pos: [-7.587, 110.441],
      lastHeard: now(),
      rssi: -87,
      snr: 5.1,
    },
    {
      id: "ND-03",
      name: "Relay Utara",
      pos: [-7.5735, 110.4382],
      lastHeard: now(),
      rssi: -92,
      snr: 2.8,
    },
    {
      id: "ND-04",
      name: "Relay Selatan",
      pos: [-7.6, 110.438],
      lastHeard: now(),
      rssi: -80,
      snr: 6.3,
    },
  ],
  operator: {
    id: "SAR-ALPHA-01",
    name: "Raka / Tim Alpha",
    pos: [-7.5968, 110.4265],
    accuracy: 8,
    gpsAvailable: true,
    capturedAt: now(),
  },
  victim: {
    id: "VICTIM-17",
    name: "NUSA-17",
    pos: [-7.5755, 110.444],
    accuracy: 15,
    capturedAt: now() - 42000,
    receivedAt: now() - 36000,
    source: "GPS korban via BLE ND-02",
    condition: "Cedera kaki",
    assistanceStatus: "NEEDS_HELP",
  },
  trackSession: { id: "TRACK-01", state: "IDLE", points: [] },
  geofence: { status: "UNKNOWN", pending: null, profileId: "field" },
  navigation: {
    mode: "DIRECT",
    status: "READY",
    route: [],
    waypoints: [],
    steps: [],
    distance: 0,
    duration: null,
    source: "GEODESIC",
    warning: "Arah langsung; kondisi medan tidak diketahui.",
  },
  terrain: { profileId: "nominal", hazards: [], hazardVersion: 0 },
  findings: [],
  messages: [],
  events: [
    {
      id: "EV-INIT",
      type: "SYSTEM",
      title: "Operasi dimuat dari penyimpanan lokal",
      detail: "Mode SIMULASI · belum terhubung perangkat nyata",
      time: now(),
    },
  ],
  radioOnline: true,
  gpsAvailable: true,
  simIndex: 0,
  simClock: now(),
  layers: {
    sectors: true,
    teams: true,
    victims: true,
    nodes: true,
    tracks: true,
    hazards: true,
    findings: true,
    waypoints: true,
    navigation: true,
    terrain: true,
    paths: true,
  },
};

const scenarioPath = [
  [-7.5968, 110.4265],
  [-7.5945, 110.428],
  [-7.592, 110.43],
  [-7.5894, 110.432],
  [-7.5868, 110.434],
  [-7.5845, 110.4362],
  [-7.582, 110.438],
  [-7.5795, 110.44],
  [-7.5775, 110.442],
  [-7.5755, 110.444],
  [-7.5735, 110.446],
];

function loadState() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
    if (!saved) return initialState;
    return {
      ...initialState,
      ...saved,
      navigation: { ...initialState.navigation, ...saved.navigation },
      terrain: { ...initialState.terrain, ...saved.terrain },
      layers: { ...initialState.layers, ...saved.layers },
    };
  } catch {
    return initialState;
  }
}

function pushEvent(state, type, title, detail) {
  return {
    ...state,
    events: [
      { id: uid("EV"), type, title, detail, time: state.simClock || now() },
      ...state.events,
    ].slice(0, 40),
  };
}

function OperationalMap({
  state,
  terrainPack,
  navigationFocus,
  selectedSectorId,
  onSelectSector,
  onCreateSector,
  onEditSector,
  onDeleteSector,
  onMapReady,
  onSelectVictim,
  onNavigateVictim,
}) {
  const hostRef = useRef(null);
  const mapRef = useRef(null);
  const groupsRef = useRef({});
  const editableRef = useRef(null);
  const drawControlRef = useRef(null);
  const callbacksRef = useRef({
    onSelectSector,
    onCreateSector,
    onEditSector,
    onDeleteSector,
    onMapReady,
    onSelectVictim,
    onNavigateVictim,
  });
  callbacksRef.current = {
    onSelectSector,
    onCreateSector,
    onEditSector,
    onDeleteSector,
    onMapReady,
    onSelectVictim,
    onNavigateVictim,
  };

  useEffect(() => {
    const map = L.map(hostRef.current, {
      zoomControl: false,
      attributionControl: true,
    }).setView(CENTER, 15);
    L.control.zoom({ position: "bottomright" }).addTo(map);
    const osm = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap contributors",
    }).addTo(map);
    const offline = L.control({ position: "topright" });
    offline.onAdd = () => {
      const el = L.DomUtil.create("div", "offline-map-notice");
      el.innerHTML = "PETA DETAIL BELUM TERSEDIA OFFLINE";
      return el;
    };
    let offlineControl;
    osm.on("tileerror", () => {
      if (!offlineControl) offlineControl = offline.addTo(map);
    });
    osm.on("tileload", () => {
      if (offlineControl) {
        map.removeControl(offlineControl);
        offlineControl = null;
      }
    });

    const names = [
      "sectors",
      "teams",
      "victims",
      "nodes",
      "tracks",
      "hazards",
      "findings",
      "waypoints",
      "navigation",
      "terrain",
      "paths",
    ];
    names.forEach((name) => {
      groupsRef.current[name] = L.layerGroup().addTo(map);
    });
    const editable = (editableRef.current = new L.FeatureGroup().addTo(map));
    const draw = new L.Control.Draw({
      position: "topleft",
      draw: {
        polygon: {
          allowIntersection: false,
          showArea: true,
          shapeOptions: { color: "#f97316", weight: 3 },
        },
        polyline: false,
        rectangle: false,
        circle: false,
        circlemarker: false,
        marker: false,
      },
      edit: { featureGroup: editable, remove: true },
    });
    map.addControl(draw);
    drawControlRef.current = draw;
    map.on(L.Draw.Event.CREATED, (event) => {
      const coords = event.layer
        .getLatLngs()[0]
        .map((p) => [Number(p.lat.toFixed(6)), Number(p.lng.toFixed(6))]);
      if (coords.length >= 3) callbacksRef.current.onCreateSector(coords);
    });
    map.on(L.Draw.Event.EDITED, (event) =>
      event.layers.eachLayer((layer) => {
        if (!layer.options.sectorId) return;
        callbacksRef.current.onEditSector(
          layer.options.sectorId,
          layer.getLatLngs()[0].map((p) => [p.lat, p.lng]),
        );
      }),
    );
    map.on(L.Draw.Event.DELETED, (event) =>
      event.layers.eachLayer(
        (layer) =>
          layer.options.sectorId &&
          callbacksRef.current.onDeleteSector(layer.options.sectorId),
      ),
    );
    map.on("click", (event) =>
      callbacksRef.current.onMapReady?.({
        map,
        lastTap: [event.latlng.lat, event.latlng.lng],
      }),
    );
    mapRef.current = map;
    onMapReady?.({ map, lastTap: null });
    return () => map.remove();
  }, []);

  useEffect(() => {
    if (!mapRef.current || !drawControlRef.current) return;
    if (navigationFocus) mapRef.current.removeControl(drawControlRef.current);
    else mapRef.current.addControl(drawControlRef.current);
  }, [navigationFocus]);

  useEffect(() => {
    const group = groupsRef.current.terrain;
    if (!group) return;
    group.clearLayers();
    groupsRef.current.paths.clearLayers();
    if (!terrainPack) return;
    (terrainPack.mappedPaths || []).forEach(path => L.polyline(path.points, { color: "#637381", weight: 2, opacity: 0.65, dashArray: "4 5" }).bindTooltip(path.name || `Jalur OSM (${path.highway})`).addTo(groupsRef.current.paths));
    const renderer = L.canvas({ padding: 0.5 });
    const colors = { 10: "#15803d", 20: "#84cc16", 30: "#d9efb5", 40: "#facc15", 50: "#a8a29e", 60: "#e7d4a8", 70: "#e2e8f0", 80: "#0284c7", 90: "#14b8a6", 95: "#0d9488", 100: "#bef264" };
    const dy = terrainPack.grid.resolutionM / 111195 / 2;
    terrainPack.coordinates.forEach((p, i) => {
      const dx = dy / Math.cos(p[0] * Math.PI / 180);
      const corners = terrainPack.cellCorners?.[i] || [[p[0] - dy, p[1] - dx], [p[0] - dy, p[1] + dx], [p[0] + dy, p[1] + dx], [p[0] + dy, p[1] - dx]];
      L.polygon(corners, { renderer, stroke: false, fillOpacity: 0.4, fillColor: terrainPack.blocked[i] || terrainPack.elevationM[i] === null ? "#475569" : colors[terrainPack.landCover[i]] || "#475569", interactive: false }).addTo(group);
    });
    L.polygon(terrainPack.footprint, { color: "#166534", weight: 2, fill: false, interactive: false }).addTo(group);
  }, [terrainPack]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    Object.entries(groupsRef.current).forEach(([name, group]) => { if (!["terrain", "paths"].includes(name)) group.clearLayers(); });
    editableRef.current.clearLayers();
    state.sectors.forEach((sector) => {
      const polygon = L.polygon(sector.polygon, {
        sectorId: sector.id,
        color: sector.color,
        weight: sector.id === selectedSectorId ? 4 : 2,
        fillOpacity: sector.id === selectedSectorId ? 0.2 : 0.1,
      });
      polygon
        .bindTooltip(`${sector.label} · v${sector.version}`, { sticky: true })
        .on("click", () => callbacksRef.current.onSelectSector(sector.id));
      polygon.addTo(groupsRef.current.sectors);
      const editable = L.polygon(sector.polygon, {
        sectorId: sector.id,
        color: "transparent",
        fillOpacity: 0,
      });
      if (!navigationFocus) editableRef.current.addLayer(editable);
    });
    state.nodes.forEach((node) =>
      L.circleMarker(node.pos, {
        radius: 7,
        color: "#fff",
        weight: 2,
        fillColor: "#0f766e",
        fillOpacity: 1,
      })
        .bindPopup(
          `<b>${node.id}</b><br>${node.name}<br>RSSI ${node.rssi} dBm · SNR ${node.snr}`,
        )
        .addTo(groupsRef.current.nodes),
    );
    L.circleMarker(state.operator.pos, {
      radius: 9,
      color: "#fff",
      weight: 3,
      fillColor: "#2563eb",
      fillOpacity: 1,
    })
      .bindTooltip(
        `${state.operator.name}<br>akurasi dilaporkan ±${state.operator.accuracy} m`,
      )
      .addTo(groupsRef.current.teams);
    if (state.operator.accuracy)
      L.circle(state.operator.pos, {
        radius: state.operator.accuracy,
        interactive: false,
        color: "#2563eb",
        weight: 1,
        fillOpacity: 0.06,
      }).addTo(groupsRef.current.teams);
    const age = Math.max(
      0,
      Math.round((state.simClock - state.victim.capturedAt) / 1000),
    );
    const victimMarker = L.circleMarker(state.victim.pos, {
      radius: 11,
      color: "#fff",
      weight: 3,
      fillColor: age > 120 ? "#94a3b8" : "#dc2626",
      fillOpacity: 1,
      className: "victim-coordinate",
      bubblingMouseEvents: false,
    });
    if (navigationFocus) {
      const popup = document.createElement("div"), title = document.createElement("strong"), coordinate = document.createElement("p"), button = document.createElement("button");
      title.textContent = "Korban 01"; coordinate.textContent = state.victim.pos.map(x => x.toFixed(6)).join(", ");
      button.textContent = "Arahkan ke korban"; button.className = "victim-route-action"; button.disabled = !terrainPack || terrainPack.synthetic;
      button.addEventListener("click", event => { event.stopPropagation(); callbacksRef.current.onNavigateVictim?.(); map.closePopup(); });
      popup.append(title, coordinate, button); victimMarker.bindPopup(popup);
      victimMarker.on("click", () => callbacksRef.current.onSelectVictim?.());
      victimMarker.on("add", () => { const element = victimMarker.getElement(); if (element) { element.setAttribute("role", "button"); element.setAttribute("aria-label", "Koordinat Korban 01"); element.setAttribute("tabindex", "0"); element.addEventListener("keydown", event => { if (["Enter", " "].includes(event.key)) { event.preventDefault(); victimMarker.openPopup(); callbacksRef.current.onSelectVictim?.(); } }); } });
    } else victimMarker.bindPopup(`<b>${state.victim.name}</b><br>${state.victim.condition}<br>${age} detik lalu<br>${state.victim.source}`);
    victimMarker.addTo(groupsRef.current.victims);
    L.circle(state.victim.pos, {
      radius: state.victim.accuracy,
      interactive: false,
      color: "#dc2626",
      weight: 1,
      fillOpacity: 0.06,
    }).addTo(groupsRef.current.victims);
    const points = state.trackSession.points
      .filter((p) => p.valid)
      .map((p) => p.coords);
    if (points.length > 1)
      L.polyline(points, { color: "#2563eb", weight: 5, opacity: 0.8 }).addTo(
        groupsRef.current.tracks,
      );
    state.findings.forEach((item) =>
      L.circleMarker(item.coordinate, {
        radius: 8,
        color: "#fff",
        weight: 2,
        fillColor: "#d97706",
        fillOpacity: 1,
      })
        .bindPopup(
          `<b>${item.category}</b><br>${item.note}<br>${item.deliveryStatus}`,
        )
        .addTo(groupsRef.current.findings),
    );
    const nav = state.navigation || {};
    (state.terrain?.hazards || []).forEach(h => L.polygon(h.polygon, { color: "#dc2626", fillOpacity: 0.35, weight: 2 }).bindTooltip(`${h.label} · ${h.verification}`).addTo(groupsRef.current.hazards));
    if (["TERRAIN", "MAPPED", "HYBRID"].includes(nav.mode) && nav.route?.length > 1) {
      L.polyline(nav.route, { color: "#ffffff", weight: 10, opacity: 0.95, interactive: false }).addTo(groupsRef.current.navigation);
      if (nav.mode === "HYBRID") {
        const segments = nav.terrainResult?.segments || [];
        let begin = 0;
        while (begin < segments.length) {
          const kind = segments[begin].kind; let end = begin + 1;
          while (end < segments.length && segments[end].kind === kind) end++;
          L.polyline(nav.route.slice(begin, end + 1), { color: nav.status === "STALE" ? "#b45309" : kind === "osm" ? "#2563eb" : kind === "transition" ? "#d97706" : "#9333ea", weight: 6, dashArray: nav.status === "STALE" || kind !== "osm" ? "8 6" : null }).bindTooltip(nav.status === "STALE" ? "Rute perlu dihitung ulang" : kind === "osm" ? "Jalur OSM asli" : kind === "transition" ? "Transisi jalan–medan" : "Kandidat lintasan medan").addTo(groupsRef.current.navigation);
          begin = end;
        }
      } else L.polyline(nav.route, { color: nav.status === "STALE" ? "#b45309" : "#2563eb", weight: 6, dashArray: nav.status === "STALE" ? "8 6" : null }).bindTooltip(nav.status === "STALE" ? "Rute perlu dihitung ulang" : "Rute menuju korban").addTo(groupsRef.current.navigation);
    }
    if (nav.mode === "ROAD" && nav.route?.length > 1)
      L.polyline(nav.route, { color: "#7c3aed", weight: 6, opacity: 0.9 })
        .bindTooltip("Rute jaringan jalan OSRM")
        .addTo(groupsRef.current.navigation);
    if (nav.mode === "TRAIL" && nav.route?.length > 1)
      L.polyline(nav.route, { color: "#059669", weight: 6, opacity: 0.92 })
        .bindTooltip("Rute graph jalur gunung offline")
        .addTo(groupsRef.current.navigation);
    if (nav.mode === "DIRECT" || nav.status === "NO_ROUTE")
      L.polyline([state.operator.pos, state.victim.pos], {
        color: "#f97316",
        weight: 4,
        dashArray: "10 8",
        opacity: 0.95,
      })
        .bindTooltip("Arah langsung ke koordinat terakhir")
        .addTo(groupsRef.current.navigation);
    if (nav.mode === "MANUAL" && nav.waypoints?.length) {
      const manual = [state.operator.pos, ...nav.waypoints, state.victim.pos];
      L.polyline(manual, { color: "#eab308", weight: 5, dashArray: "8 6" })
        .bindTooltip("Rute waypoint manual")
        .addTo(groupsRef.current.navigation);
      nav.waypoints.forEach((point, index) =>
        L.circleMarker(point, {
          radius: 8,
          color: "#fff",
          weight: 2,
          fillColor: "#ca8a04",
          fillOpacity: 1,
        })
          .bindTooltip(`Waypoint ${index + 1}`, {
            permanent: true,
            direction: "top",
          })
          .addTo(groupsRef.current.navigation),
      );
    }
    Object.entries(state.layers).forEach(([name, visible]) => {
      const g = groupsRef.current[name];
      if (!g) return;
      if (visible && !map.hasLayer(g)) g.addTo(map);
      if (!visible && map.hasLayer(g)) map.removeLayer(g);
    });
  }, [state, selectedSectorId, navigationFocus, terrainPack]);
  return <div ref={hostRef} className="map-canvas" />;
}

function App() {
  const [state, setState] = useState(loadState);
  const [activeView, setActiveView] = useState(() => { try { return localStorage.getItem(`${STORAGE_KEY}.view`) || "NAVIGASI"; } catch { return "NAVIGASI"; } });
  const [selectedSectorId, setSelectedSectorId] = useState("SEC-A");
  const [mapApi, setMapApi] = useState(null);
  const [lastTap, setLastTap] = useState(null);
  const [terrainPack, setTerrainPack] = useState(null);
  const [selectedVictim, setSelectedVictim] = useState(false);
  const [navigationRequest, setNavigationRequest] = useState(0);
  const [storageError, setStorageError] = useState("");
  useEffect(() => { try { if (localStorage.getItem(`${STORAGE_KEY}.navigationUI`) !== "real-v1") { localStorage.setItem(`${STORAGE_KEY}.navigationUI`, "real-v1"); setActiveView("NAVIGASI"); } } catch {} }, []);
  useEffect(() => { try { localStorage.setItem(`${STORAGE_KEY}.view`, activeView); } catch {} }, [activeView]);
  const trailGraphRef = useRef(null);
  const [graphMeta, setGraphMeta] = useState({
    status: "LOADING",
    message: "Memeriksa graph lokal…",
    nodes: 0,
    edges: 0,
  });
  const selectedSector =
    state.sectors.find((s) => s.id === selectedSectorId) || state.sectors[0];
  const assignment = state.assignments.find(
    (a) => a.sectorId === selectedSector?.id,
  );
  const profile =
    GEOFENCE_PROFILES[state.geofence.profileId] || GEOFENCE_PROFILES.field;
  const coverage = selectedSector
    ? coverageFromTrack(
        state.trackSession.points,
        selectedSector.polygon,
        profile.bufferM || 20,
      )
    : 0;
  const targetDistance = Math.round(
    distanceM(state.operator.pos, state.victim.pos),
  );
  const targetBearing = Math.round(
    bearingDeg(state.operator.pos, state.victim.pos),
  );
  const victimAge = Math.max(
    0,
    Math.round((state.simClock - state.victim.capturedAt) / 1000),
  );
  const queued = state.messages.filter((m) => m.state === "QUEUED").length;
  const combinedAccuracy = Math.round(
    Math.hypot(state.operator.accuracy || 0, state.victim.accuracy || 0),
  );
  const arrivalRadius = Math.max(20, combinedAccuracy);
  const nearTarget = targetDistance <= arrivalRadius;
  const navigationProgress = useMemo(() => {
    const route = state.navigation.route || [];
    if (route.length < 2)
      return { remaining: targetDistance, offRoute: 0, remainingSeconds: null };
    const progress = routeProgress(route, state.operator.pos, state.navigation.terrainResult?.segments || []);
    return { ...progress, remaining: Math.round(progress.remaining), offRoute: Math.round(progress.offRoute) };
  }, [
    state.navigation.route,
    state.navigation.duration,
    state.navigation.distance,
    state.operator.pos,
    targetDistance,
    state.navigation.terrainResult,
  ]);

  useEffect(() => {
    if (!["TERRAIN", "MAPPED", "HYBRID"].includes(state.navigation.mode) || state.navigation.status !== "ACTIVE") return;
    if (!state.gpsAvailable) {
      setState(s => ({ ...s, navigation: { ...s.navigation, status: "PAUSED", deviationStartedAt: null, warning: "GPS tidak tersedia; navigasi dijeda." } }));
      return;
    }
    const offRoute = navigationProgress.offRoute > Math.max(30, state.operator.accuracy * 2);
    if (!offRoute && state.navigation.deviationStartedAt != null) setState(s => ({ ...s, navigation: { ...s.navigation, deviationStartedAt: null } }));
    else if (offRoute && state.navigation.deviationStartedAt == null) setState(s => ({ ...s, navigation: { ...s.navigation, deviationStartedAt: s.simClock } }));
    else if (offRoute && state.simClock - state.navigation.deviationStartedAt >= 5000) setState(s => ({ ...s, navigation: { ...s.navigation, status: "STALE", warning: "Keluar rute dikonfirmasi selama 5 detik. Hitung ulang dari posisi GPS aktual." } }));
  }, [state.navigation.mode, state.navigation.status, state.navigation.deviationStartedAt, state.gpsAvailable, state.simClock, state.operator.accuracy, navigationProgress.offRoute]);

  useEffect(() => {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); setStorageError(""); }
    catch { setStorageError("Penyimpanan state gagal/penuh. Ekspor hasil sebelum menutup aplikasi."); }
  }, [state]);
  useEffect(() => {
    if (import.meta.env.PROD && "serviceWorker" in navigator)
      navigator.serviceWorker.register("/sw.js").catch(() => {});
  }, []);
  useEffect(() => {
    loadTrailGraph()
      .then((graph) => {
        trailGraphRef.current = graph;
        setGraphMeta(
          graph
            ? {
                status: "READY",
                message: "Graph offline siap",
                nodes: graph.nodes.length,
                edges: graph.edges.length,
                preparedAt: graph.preparedAt,
              }
            : {
                status: "EMPTY",
                message: "Graph jalur belum disiapkan",
                nodes: 0,
                edges: 0,
              },
        );
      })
      .catch((error) =>
        setGraphMeta({
          status: "ERROR",
          message: error.message,
          nodes: 0,
          edges: 0,
        }),
      );
  }, []);

  const mutate = (fn) => setState((current) => fn(structuredClone(current)));
  const createSector = (polygon) =>
    mutate((next) => {
      const id = `SEC-${String.fromCharCode(65 + next.sectors.length)}`;
      next.sectors.push({
        id,
        label: `Sektor ${id.slice(-1)}`,
        version: 1,
        color: "#8b5cf6",
        polygon,
        teamId: null,
      });
      next.events.unshift({
        id: uid("EV"),
        type: "SECTOR",
        title: `${id} dibuat`,
        detail: `${polygon.length} titik · draft assignment`,
        time: next.simClock,
      });
      setSelectedSectorId(id);
      return next;
    });
  const editSector = (id, polygon) =>
    mutate((next) => {
      const sector = next.sectors.find((s) => s.id === id);
      if (!sector) return next;
      sector.polygon = polygon;
      sector.version += 1;
      next.events.unshift({
        id: uid("EV"),
        type: "REVISION",
        title: `${sector.label} direvisi`,
        detail: `version ${sector.version} · assignment perlu konfirmasi ulang`,
        time: next.simClock,
      });
      const asg = next.assignments.find((a) => a.sectorId === id);
      if (asg) {
        asg.sectorVersion = sector.version;
        asg.status = "SENT";
        asg.deliveryStatus = "QUEUED";
      }
      return next;
    });
  const deleteSector = (id) =>
    mutate((next) => {
      next.sectors = next.sectors.filter((s) => s.id !== id);
      next.assignments = next.assignments.filter((a) => a.sectorId !== id);
      return next;
    });
  const acknowledgeAssignment = () =>
    mutate((next) => {
      const asg = next.assignments.find((a) => a.sectorId === selectedSectorId);
      if (asg) {
        asg.deliveryStatus = "ACKNOWLEDGED";
        asg.status = "ACTIVE";
        next.events.unshift({
          id: uid("EV"),
          type: "ACK",
          title: "Penugasan diterima petugas",
          detail: `${asg.id} · radio ACK terpisah dari assignment ACK`,
          time: next.simClock,
        });
      }
      return next;
    });
  const toggleTrack = () =>
    mutate((next) => {
      next.trackSession.state =
        next.trackSession.state === "ACTIVE" ? "PAUSED" : "ACTIVE";
      return pushEvent(
        next,
        "TRACK",
        next.trackSession.state === "ACTIVE"
          ? "Penyisiran dimulai"
          : "Penyisiran dijeda",
        "Track lokal hanya bertambah saat GPS valid dan sesi aktif",
      );
    });
  const toggleRadio = () =>
    mutate((next) => {
      next.radioOnline = !next.radioOnline;
      if (next.radioOnline) {
        next.messages = next.messages.map((m) =>
          m.state === "QUEUED"
            ? {
                ...m,
                state: "ACKNOWLEDGED",
                attempts: m.attempts + 1,
                acknowledgedAt: next.simClock,
              }
            : m,
        );
        return pushEvent(
          next,
          "SYNC",
          "Radio tersambung kembali",
          "Antrean lokal disinkronkan dan messageId tetap dipertahankan",
        );
      }
      return pushEvent(
        next,
        "RADIO",
        "Radio terputus",
        "Geofence dan track lokal tetap berjalan",
      );
    });
  const toggleGps = () =>
    mutate((next) => {
      next.gpsAvailable = !next.gpsAvailable;
      next.operator.gpsAvailable = next.gpsAvailable;
      next.geofence.status = next.gpsAvailable
        ? next.geofence.status
        : "GPS_UNAVAILABLE";
      return pushEvent(
        next,
        "GPS",
        next.gpsAvailable ? "GPS tersedia" : "GPS tidak tersedia",
        next.gpsAvailable
          ? "Input posisi dapat direkam"
          : "Track dan evaluasi batas dihentikan",
      );
    });
  const simulateStep = () =>
    mutate((next) => {
      next.simClock += 6000;
      next.simIndex = (next.simIndex + 1) % scenarioPath.length;
      if (!next.gpsAvailable)
        return pushEvent(
          next,
          "GPS",
          "Sampel dilewati",
          "GPS unavailable · tidak menambah track atau coverage",
        );
      const coords = scenarioPath[next.simIndex];
      next.operator = {
        ...next.operator,
        pos: coords,
        capturedAt: next.simClock,
        accuracy: next.simIndex === 5 ? 32 : 8,
      };
      if (next.trackSession.state === "ACTIVE")
        next.trackSession.points.push({
          coords,
          capturedAt: next.simClock,
          accuracy: next.operator.accuracy,
          valid: true,
          gapMs: 6000,
        });
      if (selectedSector) {
        const result = geofenceStep(
          next.geofence,
          { coords, accuracy: next.operator.accuracy },
          selectedSector.polygon,
          profile,
          next.simClock,
        );
        next.geofence = { ...result, profileId: profile.id };
        if (result.event) {
          const msg = {
            ...message(
              "GEOFENCE_EVENT",
              {
                ...result.event,
                sectorId: selectedSector.id,
                sectorVersion: selectedSector.version,
                actorId: next.operator.id,
              },
              next.operator.id,
              "ND-01",
            ),
            createdAt: next.simClock,
            state: next.radioOnline ? "ACKNOWLEDGED" : "QUEUED",
          };
          next.messages.push(msg);
          next = pushEvent(
            next,
            "GEOFENCE",
            result.event.type === "EXIT_SECTOR"
              ? "Keluar sektor"
              : "Masuk sektor",
            `${selectedSector.id} v${selectedSector.version} · ${next.radioOnline ? "ACK posko" : "queued offline"}`,
          );
        }
      }
      return next;
    });
  const addFinding = () =>
    mutate((next) => {
      const coordinate = lastTap || next.operator.pos;
      const finding = {
        id: uid("F"),
        category: "AKSES_TERHALANG",
        coordinate,
        note: "Material longsor menutup jalur",
        createdAt: next.simClock,
        deliveryStatus: next.radioOnline ? "ACKNOWLEDGED" : "QUEUED",
      };
      next.findings.push(finding);
      const dy = 45 / 111195, dx = dy / Math.cos(coordinate[0] * Math.PI / 180);
      const hazard = { id: finding.id, label: finding.note, polygon: [[coordinate[0] - dy, coordinate[1] - dx], [coordinate[0] - dy, coordinate[1] + dx], [coordinate[0] + dy, coordinate[1] + dx], [coordinate[0] + dy, coordinate[1] - dx]], observedAt: next.simClock, reporter: next.operator.id, verification: "reported", blockingPolicy: "block", severity: "impassable", reviewAt: next.simClock + 3600000 };
      finding.hazard = hazard;
      next.terrain.hazards.push(hazard);
      next.terrain.hazardVersion += 1;
      if (next.navigation.route?.length) { next.navigation.status = "STALE"; next.navigation.warning = "Laporan hambatan mengubah versi medan. Hitung ulang rute."; }
      next.messages.push({
        ...message("FINDING_REPORT", finding, next.operator.id, "ND-01"),
        createdAt: next.simClock,
        state: finding.deliveryStatus,
      });
      return pushEvent(
        next,
        "FINDING",
        "Temuan dicatat",
        `${finding.category} · ${finding.deliveryStatus}`,
      );
    });
  const sendSos = () =>
    mutate((next) => {
      const payload = {
        condition: "Cedera",
        people: 1,
        position: next.gpsAvailable ? next.operator.pos : null,
      };
      next.messages.push({
        ...message("SOS", payload, next.operator.id, "COMMAND"),
        createdAt: next.simClock,
        state: next.radioOnline ? "SENT" : "QUEUED",
      });
      return pushEvent(
        next,
        "SOS",
        "SOS petugas dikirim",
        `${next.gpsAvailable ? "dengan koordinat" : "tanpa koordinat"} · ${next.radioOnline ? "menunggu ACK" : "queued offline"}`,
      );
    });
  const updateVictim = () =>
    mutate((next) => {
      next.victim = {
        ...next.victim,
        pos: [next.victim.pos[0] + 0.0004, next.victim.pos[1] + 0.0002],
        capturedAt: next.simClock,
        receivedAt: next.simClock,
        source: "GPS korban via BLE ND-02",
      };
      if (next.navigation.route?.length) { next.navigation.status = "STALE"; next.navigation.warning = "Target berpindah; rute menuju lokasi sebelumnya outdated. Hitung ulang."; }
      return pushEvent(
        next,
        "VICTIM",
        "Posisi korban diperbarui",
        "Paket lebih baru diterima; lokasi terakhir diganti",
      );
    });
  const useDirectNavigation = (
    warning = "Arah langsung; kondisi medan tidak diketahui.",
  ) =>
    mutate((next) => {
      next.navigation = {
        ...next.navigation,
        mode: "DIRECT",
        status: "READY",
        route: [],
        steps: [],
        distance: Math.round(distanceM(next.operator.pos, next.victim.pos)),
        duration: null,
        source: "GEODESIC WGS84",
        warning,
        metrics: null,
        terrainResult: null,
        requestId: null,
      };
      return pushEvent(
        next,
        "NAVIGATION",
        "Panduan arah langsung aktif",
        `${Math.round(distanceM(next.operator.pos, next.victim.pos))} m · tidak bergantung jaringan jalan`,
      );
    });
  const startManualRoute = () =>
    mutate((next) => {
      next.navigation = {
        ...next.navigation,
        mode: "MANUAL",
        status: "DRAWING",
        route: [],
        waypoints: [],
        steps: [],
        source: "WAYPOINT MANUAL",
        warning: "Rute harus diverifikasi petugas; bukan jaminan jalur aman.",
        terrainResult: null,
        metrics: null,
        requestId: null,
      };
      return pushEvent(
        next,
        "NAVIGATION",
        "Mode waypoint manual aktif",
        "Ketuk peta untuk menambah lintasan yang telah dipilih petugas",
      );
    });
  const addManualWaypoint = (point) =>
    mutate((next) => {
      if (
        next.navigation.mode !== "MANUAL" ||
        next.navigation.status !== "DRAWING"
      )
        return next;
      next.navigation.waypoints = [...next.navigation.waypoints, point];
      return next;
    });
  const undoManualWaypoint = () =>
    mutate((next) => {
      next.navigation.waypoints = next.navigation.waypoints.slice(0, -1);
      return next;
    });
  const finishManualRoute = () =>
    mutate((next) => {
      const path = [
        next.operator.pos,
        ...next.navigation.waypoints,
        next.victim.pos,
      ];
      next.navigation.status = "READY";
      next.navigation.distance = Math.round(
        path
          .slice(1)
          .reduce(
            (sum, point, index) => sum + distanceM(path[index], point),
            0,
          ),
      );
      return pushEvent(
        next,
        "NAVIGATION",
        "Rute waypoint manual disimpan",
        `${next.navigation.waypoints.length} waypoint · ${next.navigation.distance} m`,
      );
    });
  const calculateRoadRoute = async () => {
    const requestId = uid("OSRM");
    const from = state.operator.pos, to = state.victim.pos, targetTime = state.victim.capturedAt, hazardVersion = state.terrain.hazardVersion;
    setState(s => ({ ...s, navigation: { ...s.navigation, status: "CALCULATING", requestId, warning: "Meminta rute dari server OSRM foot yang dikonfigurasi." } }));
    const result = await routeOsrm({ baseUrl: import.meta.env.VITE_OSRM_URL || "", from, to, hazards: state.terrain.hazards });
    setState(s => {
      if (s.navigation.requestId !== requestId) return s;
      if (s.victim.capturedAt !== targetTime || s.terrain.hazardVersion !== hazardVersion || distanceM(s.operator.pos, from) > 1) return { ...s, navigation: { ...s.navigation, status: "STALE", warning: "Input berubah selama query OSRM; hitung ulang." } };
      if (result.status !== "available") return { ...s, navigation: { ...s.navigation, status: s.navigation.route.length ? "STALE" : result.status, warning: result.message + ". Pilih terrain atau arah langsung secara eksplisit." } };
      return pushEvent({ ...s, navigation: { mode: "ROAD", status: "READY", route: result.geometry, waypoints: [], steps: result.steps, distance: Math.round(result.length), duration: result.durationEstimate, source: "OSRM FOOT / SERVER TERKONFIGURASI", warning: result.warnings.join(" "), metrics: null, terrainResult: null } }, "NAVIGATION", "Rute OSRM foot diterima", `${Math.round(result.length)} m - snap ${result.snaps.map(d => Math.round(d)).join(" / ")} m`);
    });
    if (result.status === "available") mapApi?.fitBounds(result.geometry, { padding: [55, 55] });
  };
  const prepareTrailGraph = async () => {
    const bounds = graphBoundsFromPolygons(
      state.sectors.map((sector) => sector.polygon),
      0.004,
    );
    setGraphMeta({
      status: "DOWNLOADING",
      message: "Mengunduh jalan setapak/track OSM untuk area operasi…",
      nodes: 0,
      edges: 0,
    });
    try {
      const graph = await downloadTrailGraph(bounds, (messageText) =>
        setGraphMeta((meta) => ({
          ...meta,
          status: "DOWNLOADING",
          message: messageText,
        })),
      );
      trailGraphRef.current = graph;
      setGraphMeta({
        status: "READY",
        message: "Graph tersimpan offline di perangkat",
        nodes: graph.nodes.length,
        edges: graph.edges.length,
        preparedAt: graph.preparedAt,
      });
      setState((current) =>
        pushEvent(
          current,
          "ROUTING",
          "Graph jalur gunung siap",
          `${graph.nodes.length} simpul · ${graph.edges.length} ruas`,
        ),
      );
    } catch (error) {
      setGraphMeta({
        status: "ERROR",
        message: error.message,
        nodes: 0,
        edges: 0,
      });
      setState((current) =>
        pushEvent(current, "ROUTING", "Graph gagal disiapkan", error.message),
      );
    }
  };
  const calculateTrailRoute = () => {
    const graph = trailGraphRef.current;
    if (!graph) {
      setGraphMeta((meta) => ({
        ...meta,
        status: "EMPTY",
        message: "Siapkan graph area operasi lebih dulu",
      }));
      return;
    }
    const result = routeTrail(graph, state.operator.pos, state.victim.pos);
    if (result.code !== "OK") {
      setState((current) =>
        pushEvent(
          {
            ...current,
            navigation: {
              ...current.navigation,
              mode: "DIRECT",
              status: "NO_ROUTE",
              route: [],
              distance: targetDistance,
              duration: null,
              source: "GEODESIC FALLBACK",
              warning: `Router gunung: ${result.message}. Arah langsung aktif.`,
            },
          },
          "NAVIGATION",
          "Router gunung tidak menemukan jalur",
          result.message,
        ),
      );
      return;
    }
    const trailSteps = result.segments.map((segment) => ({
      name: `${segment.name} (${segment.highway}, ${segment.surface})`,
      distance: Math.round(segment.distance),
      maneuver: segment.maneuver,
    }));
    setState((current) =>
      pushEvent(
        {
          ...current,
          navigation: {
            ...current.navigation,
            mode: "TRAIL",
            status: "READY",
              terrainResult: null,
              requestId: null,
            route: result.points,
            waypoints: [],
            steps: trailSteps,
            distance: Math.round(result.distance),
            duration: Math.round(result.durationSeconds),
            source: "OFFLINE OSM TRAIL GRAPH / A*",
            warning: `${Math.round(result.offGraph)} m di luar graph · snap awal ${Math.round(result.startSnap)} m · snap target ${Math.round(result.targetSnap)} m`,
            metrics: result,
          },
        },
        "NAVIGATION",
        "Rute gunung offline dihitung",
        `${Math.round(result.distance)} m · ${formatDuration(result.durationSeconds)} · A* menjelajah ${result.explored} simpul`,
      ),
    );
    mapApi?.fitBounds(result.points, { padding: [55, 55] });
  };
  const handleMapReady = (value) => {
    setMapApi(value.map);
    if (value.lastTap) {
      setLastTap(value.lastTap);
      if (
        state.navigation.mode === "MANUAL" &&
        state.navigation.status === "DRAWING"
      )
        addManualWaypoint(value.lastTap);
    }
  };
  const toggleLayer = (name) =>
    mutate((next) => {
      next.layers[name] = !next.layers[name];
      return next;
    });
  const reset = () => {
    localStorage.removeItem(STORAGE_KEY);
    setState(structuredClone(initialState));
    setSelectedSectorId("SEC-A");
  };

  const views = ["NAVIGASI", "COMMAND", "PETUGAS", "KORBAN", "TRANSPORT", "EVALUASI", "MESIN"];
  return (
    <div className="survey-app">
      <header className="app-header">
        <div className="identity">
          <span className="logo">N</span>
          <div>
            <strong>NusaSurvey</strong>
            <small>Navigasi SAR menuju korban</small>
          </div>
        </div>
        <div className="header-state">
          {storageError && <span className="danger" role="alert">{storageError}</span>}
          <span className="sim-badge">{activeView === "NAVIGASI" ? "KOORDINAT DUMMY" : "SIMULASI"}</span>
          {!["NAVIGASI", "KORBAN"].includes(activeView) && <><span className={state.radioOnline ? "ok" : "warn"}>
            ● {state.radioOnline ? "Radio online" : "Radio offline"}
          </span>
          <span className={state.gpsAvailable ? "ok" : "danger"}>
            ● {state.gpsAvailable ? "GPS tersedia" : "GPS unavailable"}
          </span></>}
        </div>
      </header>
      <nav className="role-nav">
        {views.map((view) => (
          <button
            key={view}
            className={activeView === view ? "active" : ""}
            onClick={() => setActiveView(view)}
          >
            {view}
          </button>
        ))}
      </nav>
      <main className="main-grid">
        <section className="map-panel">
          <OperationalMap
            state={state}
            terrainPack={terrainPack}
            navigationFocus={["NAVIGASI", "KORBAN"].includes(activeView)}
            selectedSectorId={selectedSectorId}
            onSelectSector={setSelectedSectorId}
            onCreateSector={createSector}
            onEditSector={editSector}
            onDeleteSector={deleteSector}
            onMapReady={handleMapReady}
            onSelectVictim={() => setSelectedVictim(true)}
            onNavigateVictim={() => { setSelectedVictim(true); setNavigationRequest(n => n + 1); }}
          />
          <div className="map-toolbar">
            <button
              onClick={() =>
                mapApi?.fitBounds(["NAVIGASI", "KORBAN"].includes(activeView) && state.navigation.route.length ? state.navigation.route : state.sectors.flatMap((s) => s.polygon))
              }
            >
              {["NAVIGASI", "KORBAN"].includes(activeView) ? "Lihat seluruh rute" : "Fit operasi"}
            </button>
            <button onClick={() => mapApi?.setView(state.operator.pos, 17)}>
              Fokus petugas
            </button>
            <button onClick={() => mapApi?.setView(state.victim.pos, 17)}>
              Fokus korban
            </button>
          </div>
          <div className="map-legend">
            <span>
              <i className="dot team" />
              Petugas
            </span>
            <span>
              <i className="dot victim" />
              Korban
            </span>
            <span>
              <i className="dot node" />
              Node
            </span>
            <span>{activeView === "NAVIGASI" ? "Biru: OSM · ungu: medan · jingga: transisi" : "Gunakan toolbar gambar Leaflet di kiri untuk polygon"}</span>
          </div>
        </section>
        <aside className="control-panel">
          {["NAVIGASI", "KORBAN"].includes(activeView) && <NavigationConsole state={state} setState={setState} pack={terrainPack} setPack={setTerrainPack} map={mapApi} lastTap={lastTap} selectedVictim={selectedVictim} onClearVictim={() => setSelectedVictim(false)} navigationRequest={navigationRequest} />}
          {activeView === "COMMAND" && (
            <>
              <PanelTitle kicker="OPERASI AKTIF" title={state.operation.name} />
              <div className="facts">
                <Fact label="Operation ID" value={state.operation.id} />
                <Fact
                  label="Basecamp"
                  value={`${state.operation.basecamp[0].toFixed(4)}, ${state.operation.basecamp[1].toFixed(4)}`}
                />
                <Fact
                  label="Kebutuhan tindakan"
                  value={`${state.assignments.filter((a) => a.deliveryStatus !== "ACKNOWLEDGED").length} assignment belum diterima`}
                  tone="warn"
                />
              </div>
              <Section title="Sektor & penugasan">
                {state.sectors.map((sector) => (
                  <button
                    key={sector.id}
                    className={`sector-item ${selectedSectorId === sector.id ? "selected" : ""}`}
                    onClick={() => setSelectedSectorId(sector.id)}
                  >
                    <i style={{ background: sector.color }} />
                    <span>
                      <b>{sector.label}</b>
                      <small>
                        {sector.id} · version {sector.version} ·{" "}
                        {sector.teamId || "belum ditugaskan"}
                      </small>
                    </span>
                  </button>
                ))}
              </Section>
              {assignment && (
                <div className="assignment-card">
                  <span>WORK: {assignment.status}</span>
                  <span>DELIVERY: {assignment.deliveryStatus}</span>
                  {assignment.deliveryStatus !== "ACKNOWLEDGED" && (
                    <button onClick={acknowledgeAssignment}>
                      Simulasikan konfirmasi penerima
                    </button>
                  )}
                </div>
              )}
            </>
          )}
          {activeView === "PETUGAS" && (
            <>
              <PanelTitle kicker="TIM ALPHA" title="Sesi penyisiran" />
              <div className="hero-status">
                <b>{state.trackSession.state}</b>
                <span>
                  {state.trackSession.points.length} titik valid · coverage{" "}
                  {coverage}%
                </span>
              </div>
              <div className="button-grid">
                <button className="primary" onClick={toggleTrack}>
                  {state.trackSession.state === "ACTIVE"
                    ? "Pause penyisiran"
                    : "Mulai / lanjutkan"}
                </button>
                <button onClick={simulateStep}>Langkah GPS berikutnya</button>
                <button onClick={toggleGps}>
                  {state.gpsAvailable ? "Simulasi GPS hilang" : "Pulihkan GPS"}
                </button>
                <button onClick={addFinding}>Catat akses terhalang</button>
                <button className="sos" onClick={sendSos}>
                  Kirim SOS
                </button>
              </div>
              <Section title="Geofence lokal">
                <Fact
                  label="Status"
                  value={
                    state.geofence.pending
                      ? `PENDING → ${state.geofence.pending.status}`
                      : state.geofence.status
                  }
                />
                <Fact label="Profil" value={profile.label} />
                <select
                  value={profile.id}
                  onChange={(event) =>
                    mutate((next) => {
                      next.geofence.profileId = event.target.value;
                      return next;
                    })
                  }
                >
                  {Object.values(GEOFENCE_PROFILES).map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.label}
                    </option>
                  ))}
                </select>
                <p className="note">
                  Evaluasi memakai GPS petugas dan tidak menunggu ACK radio.
                </p>
              </Section>
            </>
          )}
          {activeView === "MESIN" && (
            <>
              <PanelTitle kicker="TARGET TERPILIH" title={state.victim.name} />
              <TerrainPanel state={state} setState={setState} pack={terrainPack} setPack={setTerrainPack} map={mapApi} lastTap={lastTap} />
              <div className="victim-card">
                <span className={victimAge > 120 ? "stale" : "fresh"}>
                  {victimAge > 120 ? "LOKASI LAMA" : "LOKASI SEGAR"}
                </span>
                <b>
                  {targetDistance} m · {targetBearing}°
                </b>
                <small>
                  North-up dari posisi petugas · ketidakpastian gabungan ±
                  {combinedAccuracy} m
                </small>
                {nearTarget && (
                  <strong className="arrival">
                    MENDEKATI KOORDINAT TARGET
                  </strong>
                )}
              </div>
              <div className="graph-card">
                <div>
                  <b>Graph jalur gunung</b>
                  <small>{graphMeta.message}</small>
                </div>
                <span className={graphMeta.status.toLowerCase()}>
                  {graphMeta.status}
                </span>
                <p>
                  {graphMeta.nodes} simpul · {graphMeta.edges} ruas · tersimpan
                  IndexedDB
                </p>
                <button onClick={prepareTrailGraph}>
                  {graphMeta.status === "READY"
                    ? "Perbarui graph area"
                    : "Siapkan graph area operasi"}
                </button>
              </div>
              <div className="nav-mode-grid four">
                <button
                  className={
                    state.navigation.mode === "TRAIL" ? "active trail" : ""
                  }
                  onClick={calculateTrailRoute}
                >
                  Jalur OSM offline
                </button>
                <button
                  className={state.navigation.mode === "ROAD" ? "active" : ""}
                  onClick={calculateRoadRoute}
                >
                  Rute jalan OSRM
                </button>
                <button
                  className={state.navigation.mode === "DIRECT" ? "active" : ""}
                  onClick={() => useDirectNavigation()}
                >
                  Arah langsung
                </button>
                <button
                  className={state.navigation.mode === "MANUAL" ? "active" : ""}
                  onClick={startManualRoute}
                >
                  Waypoint manual
                </button>
              </div>
              <div className="facts">
                <Fact
                  label="Mode / status"
                  value={`${state.navigation.mode} · ${state.navigation.status}`}
                />
                <Fact
                  label="Jarak rute"
                  value={state.navigation.route?.length ? `${state.navigation.distance} m` : "Belum ada rute"}
                />
                <Fact
                  label="Sisa dari posisi sekarang"
                  value={state.navigation.route?.length ? `${navigationProgress.remaining} m` : "Belum ada rute"}
                />
                <Fact
                  label="Estimasi waktu tersisa"
                  value={
                    navigationProgress.remainingSeconds
                      ? formatDuration(navigationProgress.remainingSeconds)
                      : "Tidak tersedia"
                  }
                />
                <Fact
                  label="Deviasi dari rute"
                  value={
                    state.navigation.route?.length
                      ? `${navigationProgress.offRoute} m${navigationProgress.offRoute > Math.max(30, combinedAccuracy * 2) ? " · DI LUAR RUTE" : ""}`
                      : "—"
                  }
                  tone={
                    navigationProgress.offRoute >
                    Math.max(30, combinedAccuracy * 2)
                      ? "warn"
                      : undefined
                  }
                />
                <Fact
                  label="Estimasi waktu"
                  value={
                    state.navigation.duration
                      ? formatDuration(state.navigation.duration)
                      : "Tidak tersedia"
                  }
                />
                <Fact label="Sumber rute" value={state.navigation.source} />
                <Fact label="Radius mendekati" value={`${arrivalRadius} m`} />
                <Fact
                  label="Captured target"
                  value={`${clock(state.victim.capturedAt)} · ${victimAge} dtk lalu`}
                />
                <Fact
                  label="Akurasi korban"
                  value={`±${state.victim.accuracy} m (dilaporkan)`}
                />
              </div>
              {state.navigation.metrics && (
                <div className="route-metrics">
                  <span>
                    <b>
                      {Math.round(state.navigation.metrics.networkDistance)} m
                    </b>
                    di graph
                  </span>
                  <span>
                    <b>{Math.round(state.navigation.metrics.offGraph)} m</b>
                    connector off-road
                  </span>
                  <span>
                    <b>{state.navigation.metrics.explored}</b>simpul A*
                  </span>
                </div>
              )}
              {state.navigation.mode === "MANUAL" && (
                <div className="manual-tools">
                  <p>
                    Ketuk peta untuk menambah waypoint aman. Urutan: petugas →
                    waypoint → korban.
                  </p>
                  <div>
                    <button onClick={undoManualWaypoint}>Undo</button>
                    <button className="primary" onClick={finishManualRoute}>
                      Selesai
                    </button>
                  </div>
                  <small>
                    {state.navigation.waypoints.length} waypoint tersimpan
                  </small>
                </div>
              )}
              {state.navigation.status === "CALCULATING" && (
                <div className="route-alert loading">
                  Menghitung rute jaringan jalan…
                </div>
              )}
              <div
                className={`route-alert ${state.navigation.status === "NO_ROUTE" ? "error" : ""}`}
              >
                {state.navigation.warning}
              </div>
              {state.navigation.steps?.length > 0 && (
                <Section
                  title={
                    state.navigation.mode === "TRAIL"
                      ? "Petunjuk jalur gunung"
                      : "Instruksi awal OSRM"
                  }
                >
                  {state.navigation.steps.slice(0, 5).map((step, index) => (
                    <div
                      className="direction-step"
                      key={`${step.name}-${index}`}
                    >
                      <b>{index + 1}</b>
                      <span>
                        {step.maneuver} · {step.name}
                        <small>{step.distance} m</small>
                      </span>
                    </div>
                  ))}
                </Section>
              )}
              <button
                className="wide"
                onClick={() =>
                  mapApi?.fitBounds([state.operator.pos, state.victim.pos], {
                    padding: [60, 60],
                  })
                }
              >
                Fit petugas dan target
              </button>
              <button className="wide" onClick={updateVictim}>
                Simulasi update korban
              </button>
              <p className="note">
                Estimasi waktu graph memakai kecepatan per kelas jalur/surface,
                bukan waktu kedatangan pasti. Cuaca, elevasi, vegetasi, dan
                hambatan lapangan belum otomatis diketahui.
              </p>
            </>
          )}
          {activeView === "TRANSPORT" && (
            <>
              <PanelTitle
                kicker="ADAPTER SIMULATOR"
                title="BLE–LoRa transport"
              />
              <div className="transport-path">
                <b>APP</b>
                <span>BLE</span>
                <b>ND-02</b>
                <span>LoRa</span>
                <b>ND-01</b>
              </div>
              <button className="wide" onClick={toggleRadio}>
                {state.radioOnline
                  ? "Putuskan radio"
                  : "Reconnect & sinkronkan"}
              </button>
              <div className="facts">
                <Fact label="Queued" value={queued} />
                <Fact
                  label="Acknowledged"
                  value={
                    state.messages.filter((m) => m.state === "ACKNOWLEDGED")
                      .length
                  }
                />
                <Fact
                  label="Sent / menunggu ACK"
                  value={
                    state.messages.filter((m) => m.state === "SENT").length
                  }
                />
              </div>
              <Section title="Antrean pesan">
                {state.messages.length === 0 ? (
                  <Empty text="Belum ada pesan" />
                ) : (
                  state.messages
                    .slice()
                    .reverse()
                    .slice(0, 8)
                    .map((msg) => (
                      <div className="message-row" key={msg.messageId}>
                        <span>
                          <b>{msg.type}</b>
                          <small>{msg.messageId}</small>
                        </span>
                        <em className={msg.state.toLowerCase()}>{msg.state}</em>
                      </div>
                    ))
                )}
              </Section>
            </>
          )}
          {activeView === "EVALUASI" && (
            <>
              <PanelTitle
                kicker="KONFIGURASI TA"
                title="Metrik yang dapat dibuktikan"
              />
              <div className="facts">
                <Fact
                  label="Coverage geometris"
                  value={`${coverage}% · buffer ${profile.bufferM} m`}
                />
                <Fact
                  label="Track samples"
                  value={state.trackSession.points.length}
                />
                <Fact
                  label="Unique messages"
                  value={new Set(state.messages.map((m) => m.messageId)).size}
                />
                <Fact
                  label="ACK samples"
                  value={
                    state.messages.filter((m) => m.state === "ACKNOWLEDGED")
                      .length
                  }
                />
                <Fact
                  label="False positive"
                  value="Tidak tersedia tanpa ground truth"
                />
              </div>
              <button className="wide danger-outline" onClick={reset}>
                Reset seluruh simulasi
              </button>
            </>
          )}
        </aside>
      </main>
      {!["NAVIGASI", "KORBAN"].includes(activeView) && <section className="bottom-grid">
        <div className="event-panel">
          <PanelTitle kicker="AUDIT LOKAL" title="Event terbaru" />
          {state.events.slice(0, 7).map((event) => (
            <div className="event-row" key={event.id}>
              <i className={event.type.toLowerCase()} />
              <span>
                <b>{event.title}</b>
                <small>{event.detail}</small>
              </span>
              <time>{clock(event.time)}</time>
            </div>
          ))}
        </div>
        <div className="layer-panel">
          <PanelTitle kicker="LAYER CONTROL" title="Tampilan peta" />
          <div className="layer-grid">
            {Object.entries(state.layers).map(([name, visible]) => (
              <button
                key={name}
                className={visible ? "on" : ""}
                onClick={() => toggleLayer(name)}
              >
                <span>{visible ? "✓" : "○"}</span>
                {name}
              </button>
            ))}
          </div>
          <p className="note">
            Basemap detail memakai OpenStreetMap. Tile yang pernah dibuka
            disimpan oleh service worker untuk pengujian offline; tidak
            melakukan bulk download.
          </p>
        </div>
      </section>}
    </div>
  );
}

function PanelTitle({ kicker, title }) {
  return (
    <div className="panel-title">
      <small>{kicker}</small>
      <h2>{title}</h2>
    </div>
  );
}
function Fact({ label, value, tone }) {
  return (
    <div className={`fact ${tone || ""}`}>
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}
function Section({ title, children }) {
  return (
    <section className="side-section">
      <h3>{title}</h3>
      {children}
    </section>
  );
}
function Empty({ text }) {
  return <p className="empty">{text}</p>;
}

createRoot(document.getElementById("root")).render(<App />);
