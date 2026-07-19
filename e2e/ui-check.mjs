// Boot a user, build a server, post messages, screenshot the simplified UI.
import { chromium } from "playwright";

const BASE = process.env.BASE || "http://localhost:8090";
const OUT = process.env.OUT || "/tmp/ui";
const errors = [];

const browser = await chromium.launch({
  args: ["--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"],
});

async function newUser(name) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(`${name}: ${e.message}`));
  page.on("console", (m) => { if (m.type() === "error") errors.push(`${name} console: ${m.text()}`); });
  await page.goto(BASE);
  await page.click("#auth-toggle-link"); // switch to Register
  await page.fill("#auth-username", name);
  await page.fill("#auth-password", "hunter2pw");
  await page.click("#auth-submit");
  await page.waitForSelector(".app-shell:not(.hidden), #guild-rail, .guild-icon", { timeout: 8000 });
  return page;
}

const alice = await newUser("alice" + (Date.now() % 100000));
await alice.click(".guild-icon.action[aria-label='Create a server']");
await alice.fill(".modal input.text-input", "hangout");
await alice.press(".modal input.text-input", "Enter");
await alice.waitForSelector(".channel", { timeout: 8000 });
for (const t of ["hello **world**", "check https://example.com", "third message so grouping shows"]) {
  await alice.fill("#composer-input", t);
  await alice.press("#composer-input", "Enter");
  await alice.waitForTimeout(300);
}
await alice.waitForTimeout(1200);
const lastMsg = alice.locator(".message").last();
await lastMsg.hover();
await alice.waitForTimeout(300);
console.log("HOVER_ACTIONS=" + (await lastMsg.locator(".msg-actions button").count()));
console.log("TOOLTIP_ELS=" + (await alice.locator(".tooltip").count()));
console.log("TITLE_ATTRS=" + (await alice.locator("[title]").count()));
await alice.screenshot({ path: OUT + "/chat.png" });
await lastMsg.click({ button: "right" });
await alice.waitForTimeout(300);
const menuItems = await alice.locator(".ctx-item").allTextContents();
console.log("CTX_HAS_EDIT=" + menuItems.some((t) => /edit/i.test(t)));
console.log("CTX_HAS_DELETE=" + menuItems.some((t) => /delete/i.test(t)));
await alice.screenshot({ path: OUT + "/context-menu.png" });
await alice.keyboard.press("Escape");

console.log("JS_ERRORS=" + errors.length);
errors.slice(0, 6).forEach((e) => console.log("ERR: " + e));
await browser.close();
