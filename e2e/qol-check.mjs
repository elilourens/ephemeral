// Verify the QoL wave: day separators, code-copy, ↑-edit, Ctrl+K switcher, privacy tab.
import { chromium } from "playwright";

const BASE = process.env.BASE || "http://localhost:8090";
const OUT = process.env.OUT || "/tmp/ui";
const errors = [];
let pass = 0, fail = 0;
const check = (name, ok) => { ok ? pass++ : fail++; console.log((ok ? "PASS " : "FAIL ") + name); };

const browser = await chromium.launch({ args: ["--no-sandbox"] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });

await page.goto(BASE);
await page.click("#auth-toggle-link");
await page.fill("#auth-username", "qol" + (Date.now() % 100000));
await page.fill("#auth-password", "hunter2pw");
await page.click("#auth-submit");
await page.waitForSelector(".guild-icon", { timeout: 8000 });
await page.click(".guild-icon.action[aria-label='Create a server']");
await page.fill(".modal input.text-input", "qolhaus");
await page.press(".modal input.text-input", "Enter");
await page.waitForSelector(".channel", { timeout: 8000 });

// post a plain message and a code block
for (const t of ["first message", "```const x = 42;```"]) {
  await page.fill("#composer-input", t);
  await page.press("#composer-input", "Enter");
  await page.waitForTimeout(400);
}

check("day divider says Today", (await page.locator(".day-divider span").allTextContents()).includes("Today"));
check("code block rendered", await page.locator(".md-code code").count() >= 1);
await page.locator(".md-code").first().hover();
check("code copy button visible on hover", await page.locator(".code-copy").first().isVisible());
await page.locator(".code-copy").first().click();
await page.waitForTimeout(300);
check("copy click toasts", (await page.locator(".toast").allTextContents()).some((t) => /copied/i.test(t)));

// ↑ edits your last message
await page.click("#composer-input");
await page.press("#composer-input", "ArrowUp");
await page.waitForTimeout(300);
check("ArrowUp opens edit box", await page.locator(".edit-box, .message textarea").count() >= 1);
await page.keyboard.press("Escape");
await page.waitForTimeout(200);

// Ctrl+K quick switcher jumps to the voice channel
await page.keyboard.press("Control+k");
await page.waitForTimeout(300);
check("quick switcher opens", await page.locator(".qs-list").count() === 1);
await page.fill(".modal input.text-input", "voice");
await page.waitForTimeout(200);
const items = await page.locator(".qs-item").allTextContents();
check("switcher fuzzy-finds Voice", items.some((t) => /voice/i.test(t)));
await page.press(".modal input.text-input", "Enter");
await page.waitForTimeout(500);
check("switcher navigated somewhere", await page.locator(".modal").count() === 0);
await page.screenshot({ path: OUT + "/qol.png" });

// Chat & Privacy tab with the typing toggle
await page.click("#user-settings-btn");
await page.waitForTimeout(700);
const tabs = await page.locator(".set-tabs button").allTextContents();
check("settings has Chat & Privacy tab", tabs.some((t) => /privacy/i.test(t)));
await page.locator(".set-tabs button", { hasText: "Chat & Privacy" }).click();
await page.waitForTimeout(200);
const rows = await page.locator(".set-tabpanel:not(.hidden) .set-row").allTextContents();
check("typing-indicator toggle present", rows.some((t) => /typing/i.test(t)));
await page.screenshot({ path: OUT + "/privacy-tab.png" });

console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.length);
errors.slice(0, 5).forEach((e) => console.log("ERR: " + e));
await browser.close();
process.exit(fail ? 1 : 0);
