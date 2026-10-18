import { chromium } from '../../../geofence-prototype/node_modules/@playwright/test/index.mjs';
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const browser=await chromium.launch({headless:true,channel:'chromium'});
try {
  const context=await browser.newContext({viewport:{width:430,height:900}});
  const page=await context.newPage(), errors=[], tileFailures=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('requestfailed',r=>{if(r.url().includes('tile.openstreetmap.org'))tileFailures.push(r.failure()?.errorText);});
  // No saved route, mock location, fixture data, or substituted tile responses.
  await page.goto(process.env.NUSA_PREVIEW_URL || 'http://127.0.0.1:8080/#map');
  await page.waitForSelector('iframe',{timeout:60000});
  const frame=page.frames().find(f=>f.parentFrame());
  await frame.waitForFunction(()=>typeof window.setNusaData==='function',null,{timeout:60000});
  const results=[];
  for(const [name,lat,lon,zoom] of [['Bandung',-6.9175,107.6191,14],['Jakarta',-6.2088,106.8456,14],['Kaliurang',-7.585,110.44,15]]) {
    await frame.evaluate(({lat,lon,zoom})=>window.nusaMap.setView([lat,lon],zoom,{animate:false}),{lat,lon,zoom});
    await frame.waitForFunction(()=>{
      const layer=Object.values(window.nusaMap._layers).find(l=>l._url?.includes('tile.openstreetmap.org'));
      const current=layer ? Object.values(layer._tiles).filter(t=>t.current) : [];
      return current.length>0 && current.every(t=>t.el.complete && t.el.naturalWidth>0);
    },null,{timeout:45000}).catch(e=>{throw Error(name+': actual map tiles did not finish loading; '+JSON.stringify(tileFailures.slice(-6)),{cause:e});});
    const tiles=await frame.locator('img.leaflet-tile-loaded').count();
    assert.ok(tiles>0);results.push({name,tiles});
    await mkdir(new URL('../../docs/previews/',import.meta.url),{recursive:true});
    await page.screenshot({path:fileURLToPath(new URL('../../docs/previews/restored-map-'+name.toLowerCase()+'.png',import.meta.url)),fullPage:true});
  }
  assert.equal(await frame.locator('#region-button,#region-panel').count(),0);
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({status:'PASS',tests:['actual localhost app without fixtures','real OSM tiles fully loaded across three locations','package panel remains removed'],results}));
} finally {await browser.close();}
