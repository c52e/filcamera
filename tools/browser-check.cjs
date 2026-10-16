const {chromium} = require('playwright');
const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const base=process.env.FILCAMERA_PREVIEW_URL || 'http://127.0.0.1:4173/filcamera/';
const output=path.resolve(__dirname,'../artifacts');
fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE ? {executablePath:process.env.BROWSER_EXECUTABLE} : {})});
 const results=[];const errors=[];
 async function open(options={}){const context=await browser.newContext(options);const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('response',r=>{if(r.url().startsWith(base)&&r.status()>=400)errors.push(`${r.status()} ${r.url()}`)});return {context,page}}
 for(const [name,route,width,height,locale] of [
  ['desktop-en','',1440,1000,'en-US'],['desktop-zh','zh/',1440,1000,'en-US'],
  ['mobile-en','',390,844,'en-US'],['mobile-zh','zh/',390,844,'zh-CN'],
  ['tablet-en','',768,1024,'en-US'],['narrow-zh','zh/',320,760,'zh-CN']]){
  const {context,page}=await open({viewport:{width,height},locale});await page.goto(base+route);await page.waitForLoadState('networkidle');
  const size=await page.evaluate(()=>({scroll:document.documentElement.scrollWidth,client:document.documentElement.clientWidth}));
  assert(size.scroll<=size.client,`${name} horizontal overflow: ${JSON.stringify(size)}`);
  assert.equal(await page.locator('h1').count(),1);
  await page.screenshot({path:path.join(output,name+'.png'),fullPage:true});results.push({test:name,result:'pass',size});await context.close();
 }
 {
  const {context,page}=await open({viewport:{width:390,height:844},locale:'zh-CN'});
  await page.goto(base);await page.waitForURL('**/filcamera/zh/');
  await page.getByRole('link',{name:'EN',exact:true}).click();await page.waitForURL('**/filcamera/?lang=en');
  assert.equal(await page.locator('html').getAttribute('lang'),'en');
  await page.goto(base);assert.equal(await page.locator('html').getAttribute('lang'),'en');
  await page.getByRole('link',{name:'中文',exact:true}).click();await page.waitForURL('**/filcamera/zh/?lang=zh');
  await page.goto(base);await page.waitForURL('**/filcamera/zh/');
  results.push({test:'browser detection, manual selection and persisted preference',result:'pass'});await context.close();
 }
 {
  const {context,page}=await open({locale:'zh-CN',javaScriptEnabled:false});
  await page.goto(base);assert.equal(await page.locator('html').getAttribute('lang'),'en');
  await page.getByRole('link',{name:'中文',exact:true}).click();assert.equal(await page.locator('html').getAttribute('lang'),'zh-CN');
  assert.equal(await page.locator('.feature').count(),6);results.push({test:'no JavaScript: full content and language navigation',result:'pass'});await context.close();
 }
 {
  const {context,page}=await open({locale:'zh-CN'});
  await context.addInitScript(()=>{Object.defineProperty(window,'localStorage',{get(){throw new DOMException('Blocked','SecurityError')}})});
  await page.goto(base);await page.waitForURL('**/filcamera/zh/');await page.getByRole('link',{name:'EN',exact:true}).click();await page.waitForURL('**/filcamera/?lang=en');
  assert.equal(await page.locator('html').getAttribute('lang'),'en');results.push({test:'blocked storage: explicit language remains usable',result:'pass'});await context.close();
 }
 {
  const {context,page}=await open({locale:'en-US',reducedMotion:'reduce'});await page.goto(base);
  assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).scrollBehavior),'auto');
  await page.keyboard.press('Tab');assert.equal(await page.evaluate(()=>document.activeElement.className),'skip');
  await page.keyboard.press('Enter');assert(await page.evaluate(()=>location.hash==='#main'));
  results.push({test:'reduced motion and keyboard skip link',result:'pass'});await context.close();
 }
 assert.deepEqual(errors,[]);results.push({test:'runtime errors and local HTTP failures',result:'pass'});
 fs.writeFileSync(path.join(output,'browser-report.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
