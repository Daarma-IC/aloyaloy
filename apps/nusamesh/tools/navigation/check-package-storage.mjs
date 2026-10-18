import { chromium } from '../../../geofence-prototype/node_modules/@playwright/test/index.mjs';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const resources=new URL('../../composeApp/src/commonMain/composeResources/files/',import.meta.url);
const input=JSON.parse(await readFile(new URL('navigation/regions.json',resources),'utf8'));
const pack=input.packages.find(p=>p.id==='jabar-tangkuban');
const [w,s,e,n]=pack.aoiWgs84;
const origin='http://127.0.0.1:8081/navigation-storage-test';
let html=(await readFile(new URL('../../composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/LeafletMap.kt',import.meta.url),'utf8')).split('internal val leafletHtml = """')[1].split('""".trimIndent()')[0];
html=html.replace('<!--LEAFLET_CSS-->','<style>'+await readFile(new URL('leaflet/leaflet.css',resources),'utf8')+'</style>')
 .replace('<!--LEAFLET_JS-->','<script>'+await readFile(new URL('leaflet/leaflet.js',resources),'utf8')+'</script>')
 .replace('/*OFFLINE_REGION*/',await readFile(new URL('maps/natural-earth-se-asia.geojson',resources),'utf8'))
 .replace('/*MAP_DATA*/','null').replace('/*NAV_SOLVER*/',JSON.stringify(await readFile(new URL('navigation/solver.js',resources),'utf8')))
 .replace('/*NAV_TERRAIN*/',JSON.stringify(JSON.stringify(input)));
const browser=await chromium.launch({headless:true,channel:'chromium'});
try {
 const context=await browser.newContext({viewport:{width:430,height:660}}), page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 const network=[];
 await context.route('**/*',route=>{
   if(route.request().url()===origin) return route.fulfill({contentType:'text/html',body:html});
   if(!route.request().url().startsWith('https://tile.openstreetmap.org/')) network.push(route.request().url());
   return route.abort();
 });
 // First-use navigation cannot depend on IndexedDB or a previous download.
 await context.addInitScript(()=>{Object.defineProperty(window,'indexedDB',{get(){throw Error('Storage unavailable in test');}});});
 await page.goto(origin);
 await page.waitForFunction(()=>typeof window.setNusaData==='function');
 assert.equal(await page.locator('#region-button, #region-panel').count(),0);
 await page.evaluate(()=>window.messages=[]);
 await page.evaluate(()=>window.NusaBridge={tap(){},navigation(text){window.messages.push(JSON.parse(text));}});
 const {regionalTestEndpoints}=await import('./fixtures.mjs');const endpoints=regionalTestEndpoints(pack);
 await page.evaluate(endpoints=>window.setNusaData({units:[],routes:[],waypoints:[],navigationRequest:{id:'cached-jabar',from:endpoints.from,to:endpoints.to}}),endpoints);
 await page.waitForFunction(()=>window.messages.some(m=>m.id==='cached-jabar'),null,{timeout:60000});
 const result=await page.evaluate(()=>window.messages.find(m=>m.id==='cached-jabar').result);
 assert.equal(result.status,'available');assert.equal(result.packageId,'jabar-tangkuban');assert.equal(result.packageResolutionM,35);
 await page.reload();
 await page.waitForFunction(()=>typeof window.setNusaData==='function');
 assert.equal(await page.locator('#region-panel').count(),0);
 assert.deepEqual(network,[]);
 assert.deepEqual(errors,[]);
 console.log(JSON.stringify({status:'PASS',tests:['no package panel','bundled Jabar route on first launch without storage','reload without downloads','no routing download requests (map tiles separate)']}));
} finally {await browser.close();}
