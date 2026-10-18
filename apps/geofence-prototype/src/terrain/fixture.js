import { validatePackage } from './engine.js';

export function createSyntheticPackage() {
  const rows = 56, cols = 48, resolutionM = 60, origin = [-7.6005, 110.4225];
  const latStep = resolutionM / 111195, lonStep = resolutionM / (111195 * Math.cos(origin[0] * Math.PI / 180));
  const coordinates = [], elevationM = [], landCover = [], blocked = [];
  for (let row = 0; row < rows; row++) for (let col = 0; col < cols; col++) {
    coordinates.push([origin[0] + row * latStep, origin[1] + col * lonStep]);
    elevationM.push(Math.round((600 + row * 3 + 130 * Math.exp(-((row - 27) ** 2 / 70 + (col - 26) ** 2 / 35))) * 10) / 10);
    // One synthetic river, with a known bridge at row 25. No OSM roads required.
    landCover.push(col === 21 && row !== 25 ? 80 : col > 25 && row > 18 && row < 40 ? 10 : 30);
    blocked.push(false);
  }
  const footprint = [[origin[0] - latStep / 2, origin[1] - lonStep / 2], [origin[0] - latStep / 2, origin[1] + (cols - 0.5) * lonStep], [origin[0] + (rows - 0.5) * latStep, origin[1] + (cols - 0.5) * lonStep], [origin[0] + (rows - 0.5) * latStep, origin[1] - lonStep / 2]];
  return validatePackage({ schemaVersion: 1, id: 'synthetic-sar-grid', datasetVersion: 'fixture-v1', synthetic: true, crs: 'LOCAL_METRIC_APPROXIMATION', verticalDatum: 'synthetic', grid: { rows, cols, resolutionM }, footprint, coordinates, elevationM, landCover, blocked, sources: [{ name: 'Fixture deterministik NusaSurvey', nativeResolutionM: 60, observedAt: null, license: 'Project fixture' }], preparedAt: '2026-10-14', demoStart: coordinates[6 * cols + 7], demoTarget: coordinates[46 * cols + 39], bridgeCell: 25 * cols + 21 });
}
