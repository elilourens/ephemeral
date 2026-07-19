// Two-browser DM test: open a DM by username, live delivery both ways, unread badge.
import { chromium } from "playwright";

const BASE = process.env.BASE || "http://localhost:8090";
const errors = [];
let pass = 0, fail = 0;
const check = (name, ok) => { ok ? pass++ : fail++; console.log((ok ? "PASS " : "FAIL ") + name); };

const browser = await chromium.launch({ args: ["--no-sandbox"] });

async function newUser(name) {
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 850 } });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(`${name}: ${e.message}`));
  await page.goto(BASE);
  await page.click("#auth-toggle-link");
  await page.fill("#auth-username", name);
  await page.fill("#auth-password", "hunter2pw");
  await page.click("#auth-submit");
  await page.waitForSelector(".guild-icon", { timeout: 8000 });
  return page;
}

const suffix = Date.now() % 100000;
const alice = await newUser("alice" + suffix);
const bob = await newUser("bob" + suffix);

// Bob opens a DM with Alice by username
await bob.click(".guild-icon.dm-home");
await bob.waitForTimeout(400);
await bob.click("button[aria-label='New DM']");
await bob.fill(".modal input.text-input", "alice" + suffix);
await bob.press(".modal input.text-input", "Enter");
await bob.waitForSelector("#composer-input", { timeout: 8000 });
check("bob opened DM with alice", true);

await bob.fill("#composer-input", "psst — this is private");
await bob.press("#composer-input", "Enter");
await bob.waitForTimeout(1200);

// Alice gets the DM unread badge on the home rail without navigating —
// the badge is a .guild-mention on the slot wrapping the dm-home icon
const badge = await alice.locator(".guild-slot:has(.dm-home) .guild-mention").count()
  + await alice.locator(".guild-slot.unread:has(.dm-home)").count();
check("alice sees a DM notification badge", badge >= 1);

// Alice opens DMs and reads it
await alice.click(".guild-icon.dm-home");
await alice.waitForTimeout(500);
await alice.locator(".dm-row, .channel").first().click();
await alice.waitForTimeout(800);
const got = await alice.locator(".message .content").allTextContents();
check("alice received bob's message", got.some((t) => t.includes("psst — this is private")));

// Alice replies; Bob receives it live
await alice.fill("#composer-input", "loud and clear");
await alice.press("#composer-input", "Enter");
await bob.waitForTimeout(1500);
const bobSees = await bob.locator(".message .content").allTextContents();
check("bob received alice's reply live", bobSees.some((t) => t.includes("loud and clear")));

console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.length);
errors.slice(0, 5).forEach((e) => console.log("ERR: " + e));
await browser.close();
process.exit(fail ? 1 : 0);
