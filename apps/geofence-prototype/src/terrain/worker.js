import { routeTerrain } from './engine.js';
import { routeMapped } from '../routing/mapped.js';
import { routeHybrid } from '../routing/hybrid.js';
self.onmessage = event => { try { const request = event.data.request; self.postMessage({ id: event.data.id, result: request.engine === 'Hybrid' ? routeHybrid(request) : request.engine === 'MappedOSM' ? routeMapped(request) : routeTerrain(request) }); } catch (error) { self.postMessage({ id: event.data.id, error: error.message }); } };
