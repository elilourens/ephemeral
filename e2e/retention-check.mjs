// Admin sets a 1-hour auto-delete timer; pill + toast reflect it.
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
await page.fill("#auth-username", "ret" + (Date.now() % 100000));
await page.fill("#auth-password", "hunter2pw");
await page.click("#auth-submit");
await page.waitForSelector(".guild-icon", { timeout: 8000 });
await page.click(".guild-icon.action[aria-label='Create a server']");
await page.fill(".modal input.text-input", "timerhaus");
await page.press(".modal input.text-input", "Enter");
await page.waitForSelector(".channel", { timeout: 8000 });

check("pill shows default 7 days", /7 days/.test(await page.locator("#vanish-pill-text").textContent()));

// right-click #general → Auto-Delete Timer… → 1 hour
await page.locator(".channel").first().click({ button: "right" });
await page.waitForTimeout(300);
await page.locator(".ctx-item", { hasText: "Auto-Delete Timer" }).click();
await page.waitForTimeout(400);
check("timer modal opens", (await page.locator(".modal").count()) === 1);
await page.locator(".modal .qs-item", { hasText: "1 hour" }).click();
await page.waitForTimeout(600);
check("toast confirms 1 hour", (await page.locator(".toast").allTextContents()).some((t) => /1 hour/.test(t)));
check("pill shows 1 hour", /1 hour/.test(await page.locator("#vanish-pill-text").textContent()));

console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.length);
errors.slice(0, 5).forEach((e) => console.log("ERR: " + e));
await browser.close();
process.exit(fail ? 1 : 0);
