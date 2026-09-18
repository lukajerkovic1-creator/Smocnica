import { chromium, expect } from '@playwright/test';
const browser = await chromium.launch({channel:'msedge',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});
try {
 const page = await browser.newPage({viewport:{width:390,height:844},permissions:['camera']});
 const errors=[]; page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`${process.env.SMOCNICA_TEST_ORIGIN || 'http://127.0.0.1:5173'}/Smocnica/test/fixture.html?recognition-timeout=1`);
 await page.getByRole('button',{name:'Dodaj artikl',exact:true}).click();
 const shutter=page.getByRole('button',{name:'Snimi fotografiju',exact:true});
 await expect(shutter).toBeEnabled(); await shutter.click();
 await expect(page.getByText('Prepoznavanje traje predugo.',{exact:false})).toBeVisible();
 const preview=page.locator('img').filter({visible:true});
 const sources=await preview.evaluateAll(images=>images.map(i=>i.src));
 await page.getByRole('textbox',{name:'Naziv artikla',exact:true}).fill('Ručni naziv');
 await page.getByRole('button',{name:'Ponovi prepoznavanje',exact:true}).click();
 await expect(page.getByText('Prepoznajem proizvod…',{exact:true})).toBeVisible();
 await expect(page.getByText('Prepoznavanje traje predugo.',{exact:false})).toBeVisible({timeout:15000});
 await expect(page.getByRole('textbox',{name:'Naziv artikla',exact:true})).toHaveValue('Ručni naziv');
 expect(await preview.evaluateAll(images=>images.map(i=>i.src))).toEqual(sources);
 await expect(page.getByRole('button',{name:'Dodaj',exact:true})).toBeEnabled();
 expect(errors).toEqual([]);
 console.log('PASS: timeout explanation, retry, photo and manual input preserved, manual add available');
} finally { await browser.close(); }
