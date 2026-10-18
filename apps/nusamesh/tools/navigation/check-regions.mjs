import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { checkedRegionalPackage, selectRegionalPackage, operationBounds } from './regional.js';
import { routeHybrid } from '../../../geofence-prototype/src/routing/hybrid.js';
import { regionalTestEndpoints } from './fixtures.mjs';
const resource = JSON.parse(await readFile(new URL('../../composeApp/src/commonMain/composeResources/files/navigation/regions.json', import.meta.url)));
const packs = resource.packages.map(checkedRegionalPackage);
assert.equal(packs.length,4);
assert.throws(()=>operationBounds([-6.7,107.1],[-7.1,107.1]), /terlalu jauh/);
assert.throws(()=>operationBounds([40,0]), /Indonesia/);
assert.equal(selectRegionalPackage(packs,[0,0],[-6.7,107]),null);
assert.throws(()=>checkedRegionalPackage({...packs[1],synthetic:true}), /nyata/);
assert.throws(()=>checkedRegionalPackage({...packs[1],mappedPaths:[{points:[[NaN,0]],nodeIds:[1]}]}), /OSM/);
const results=[];
for(const pack of packs.filter(p=>p.id.startsWith('jabar-'))) {
  const endpoints=regionalTestEndpoints(pack);
  assert.equal(selectRegionalPackage(packs,endpoints.from,endpoints.to)?.id,pack.id);
  const route=routeHybrid({pack,from:endpoints.from,to:endpoints.to});
  assert.equal(route.status,'available',JSON.stringify({id:pack.id,status:route.status,message:route.message}));
  assert.equal(route.packageId,pack.id);
  assert.deepEqual(route.geometry[0],endpoints.from);
  assert.deepEqual(route.geometry.at(-1),endpoints.to);
  assert.ok(route.elevationProfile.every(p=>Number.isFinite(p.elevationM)));
  results.push({id:pack.id,bbox:pack.aoiWgs84,cells:pack.coordinates.length,paths:pack.mappedPaths.length,from:endpoints.from,to:endpoints.to,length:route.length,eta:route.durationEstimate,computeMs:route.computeMs});
}
const report={status:'PASS',tests:['automatic spatial selection','missing coverage','operation-size bounds','invalid package rejection','real Jawa Barat routing and elevation'],results};
await writeFile(new URL('../../docs/previews/jabar-routing-check.json',import.meta.url),JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
