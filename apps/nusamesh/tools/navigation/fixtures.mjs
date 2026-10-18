import { demoMappedCoordinates } from '../../../geofence-prototype/src/routing/mapped.js';
// Test-only endpoint selection on the real mapped graph; these never become app positions.
export function regionalTestEndpoints(pack) {
  const [w,s,e,n]=pack.aoiWgs84;
  return demoMappedCoordinates({...pack,demoStart:[s+.01,w+.01],demoTarget:[n-.01,e-.01]});
}
