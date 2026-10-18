const APP_CACHE = "nusasurvey-app-terrain-__BUILD_ID__";
const TILE_CACHE = "nusasurvey-tiles-v1";
const PRECACHE = ["/", "/index.html", /* __BUILD_ASSETS__ */];

self.addEventListener("install", (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(APP_CACHE).then((cache) => cache.addAll(PRECACHE)),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith("nusasurvey-") && ![APP_CACHE, TILE_CACHE].includes(key))
            .map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  const url = new URL(event.request.url);

  if (url.hostname === "tile.openstreetmap.org") {
    event.respondWith(
      caches.open(TILE_CACHE).then(async (cache) => {
        const saved = await cache.match(event.request);
        if (saved) return saved;
        try {
          const response = await fetch(event.request);
          if (response.ok) await cache.put(event.request, response.clone());
          return response;
        } catch {
          return new Response("", { status: 503 });
        }
      }),
    );
    return;
  }

  if (url.pathname.startsWith("/osm-api/")) return;
  if (url.origin !== self.location.origin) return;

  event.respondWith(
    caches.open(APP_CACHE).then(async (cache) => {
      try {
        const response = await fetch(event.request);
        if (response.ok) await cache.put(event.request, response.clone());
        return response;
      } catch {
        return (await cache.match(event.request, { ignoreVary: true })) || (event.request.mode === "navigate" ? await cache.match("/index.html", { ignoreVary: true }) : null) || new Response("Resource offline belum tersedia", { status: 503 });
      }
    }),
  );
});
