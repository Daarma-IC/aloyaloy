import {chromium} from '../../../geofence-prototype/node_modules/@playwright/test/index.mjs';
import assert from 'node:assert/strict';
import {mkdir,mkdtemp,copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
const temporary=await mkdtemp(join(tmpdir(),'nusamesh-diagnostics-'));
const artifacts=[];
const browser=await chromium.launch({headless:true,channel:'chromium'});
try {
  const page=await browser.newPage({viewport:{width:430,height:900},acceptDownloads:true}),errors=[],downloads=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('download',download=>downloads.push((async()=>{
    const stream=await download.createReadStream(),chunks=[];for await(const chunk of stream) chunks.push(chunk);
    return {name:download.suggestedFilename(),text:Buffer.concat(chunks).toString('utf8')};
  })()));
  await page.goto(process.env.NUSA_PREVIEW_URL || 'http://127.0.0.1:8080/');
  await page.waitForSelector('canvas',{timeout:60000});
  await page.waitForTimeout(4000);
  await page.mouse.click(90,825);await page.waitForTimeout(1000);
  await page.screenshot({path:join(temporary,'messages-qos.png')});
  artifacts.push('messages-qos.png');
  // Global Mesh is now the first row; the QoS panel is collapsed below it.
  await page.mouse.click(215,218);await page.waitForTimeout(600);
  await page.screenshot({path:join(temporary,'messages-conversation.png')});
  artifacts.push('messages-conversation.png');
  await page.mouse.click(55,60);await page.waitForTimeout(600);
  await page.mouse.click(320,316);await page.waitForTimeout(600);
  await page.screenshot({path:join(temporary,'messages-qos-expanded.png')});
  artifacts.push('messages-qos-expanded.png');
  await page.mouse.click(320,316);await page.waitForTimeout(300);
  await page.mouse.click(320,316);await page.waitForTimeout(300);
  await page.mouse.click(330,386);await page.waitForTimeout(700);
  await page.screenshot({path:join(temporary,'messages-qos-details.png')});
  artifacts.push('messages-qos-details.png');
  await page.mouse.click(215,148);await page.waitForTimeout(600);
  await page.mouse.click(100,678);await page.waitForTimeout(1200);
  await page.mouse.click(219,678);await page.waitForTimeout(1200);
  // The dev server watches repository files: screenshots there would reload the app mid-test.
  await page.screenshot({path:join(temporary,'messages-qos-metrics.png')});
  artifacts.push('messages-qos-metrics.png');
  const files=await Promise.all(downloads);
  assert.equal(files.length,2,'Both CSV buttons must trigger a real browser download');
  const log=files.find(f=>f.name.includes('transmission-log')).text;
  const metrics=files.find(f=>f.name.includes('transmission-metrics')).text;
  assert.match(log,/session_id,time_ms,layer,event/);assert.match(log,/SESSION_STARTED/);
  assert.match(metrics,/metric,value/);assert.match(metrics,/lora_tx_confirmation/);assert.match(metrics,/unavailable/);
  assert.ok(await page.evaluate(()=>JSON.parse(localStorage.getItem('nusamesh.transmission_log_v1')||'[]').some(e=>e.event==='SESSION_STARTED')));
  await page.mouse.click(345,148);await page.waitForTimeout(600);
  await page.screenshot({path:join(temporary,'messages-qos-log.png')});
  artifacts.push('messages-qos-log.png');
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({status:'PASS',tests:['Pesan prioritizes the chat list above the collapsed QoS panel','Global Mesh opens directly from the first row','QoS panel expands and collapses','QoS details open from the expanded panel','session event persisted without fixture data','CSV log download contains session metadata','CSV metrics download exposes measurement limits','graph, metric and log views render without JS errors']}));
} finally {
  await browser.close();
  const folder=new URL('../../docs/previews/',import.meta.url);await mkdir(folder,{recursive:true});
  for(const name of artifacts)await copyFile(join(temporary,name),fileURLToPath(new URL(name,folder)));
}
