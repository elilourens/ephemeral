// Edit a message twice, click "(edited)", expect the history modal with both versions.
import { chromium } from "playwright";

const BASE = process.env.BASE || "http://localhost:8090";
const errors = [];
let pass = 0, fail = 0;
const check = (name, ok) => { ok ? pass++ : fail++; console.log((ok ? "PASS " : "FAIL ") + name); };

const browser = await chromium.launch({ args: ["--no-sandbox"] });
const page = await (await browser.newContext({ viewport: { width: 1360, height: 850 } })).newPage();
page.on("pageerror", (e) => errors.push(e.message));

await page.goto(BASE);
await page.click("#auth-toggle-link");
await page.fill("#auth-username", "hist" + (Date.now() % 100000));
await page.fill("#auth-password", "hunter2pw");
await page.click("#auth-submit");
await page.waitForSelector(".guild-icon", { timeout: 8000 });
await page.click(".guild-icon.action[aria-label='Create a server']");
await page.fill(".modal input.text-input", "histhaus");
await page.press(".modal input.text-input", "Enter");
await page.waitForSelector(".channel", { timeout: 8000 });

await page.fill("#composer-input", "version one");
await page.press("#composer-input", "Enter");
await page.waitForTimeout(600);

// edit twice via ArrowUp
for (const v of ["version two", "version three"]) {
  await page.click("#composer-input");
  await page.press("#composer-input", "ArrowUp");
  await page.waitForTimeout(300);
  const box = page.locator(".message textarea").first();
  await box.fill(v);
  await box.press("Enter");
  await page.waitForTimeout(600);
}

check("(edited) marker shown", await page.locator(".edited").count() >= 1);
await page.locator(".edited").first().click();
await page.waitForTimeout(600);
const bodies = await page.locator(".hist-body").allTextContents();
check("history modal shows 2 versions", bodies.length === 2);
check("newest-first ordering", /version two/.test(bodies[0] || "") && /version one/.test(bodies[1] || ""));

console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.length);
errors.slice(0, 5).forEach((e) => console.log("ERR: " + e));
await browser.close();
process.exit(fail ? 1 : 0);
