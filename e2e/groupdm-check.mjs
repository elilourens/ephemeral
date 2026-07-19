// Three browsers: 1:1 → add third person → NEW group (1:1 survives), live group
// messaging, owner-only kick UI, and the incoming-call ring toast.
import { chromium } from "playwright";

const BASE = process.env.BASE || "http://localhost:8090";
const errors = [];
let pass = 0, fail = 0;
const check = (name, ok) => { ok ? pass++ : fail++; console.log((ok ? "PASS " : "FAIL ") + name); };

const browser = await chromium.launch({
  args: ["--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"],
});

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

const sfx = Date.now() % 100000;
const alice = await newUser("ga" + sfx);
const bob = await newUser("gb" + sfx);
const carol = await newUser("gc" + sfx);

// Alice: 1:1 with Bob
await alice.click(".guild-icon.dm-home");
await alice.click("button[aria-label='New DM']");
await alice.fill(".modal input.text-input", "gb" + sfx);
await alice.press(".modal input.text-input", "Enter");
await alice.waitForTimeout(300);
await alice.locator(".modal .btn", { hasText: "Start" }).click();
await alice.waitForSelector("#composer-input", { timeout: 8000 });
await alice.fill("#composer-input", "private 1:1 line");
await alice.press("#composer-input", "Enter");
await alice.waitForTimeout(600);

// Add Carol → a NEW group
await alice.click("button[aria-label*='Add']");
await alice.fill(".modal input.text-input", "gc" + sfx);
await alice.locator(".modal .btn", { hasText: "Add" }).click();
await alice.waitForTimeout(1200);
check("group header shows 3 members", /3 members/.test(await alice.locator(".dm-members-count").textContent().catch(() => "")));

// group message reaches Bob and Carol live
await alice.fill("#composer-input", "hello squad");
await alice.press("#composer-input", "Enter");
await alice.waitForTimeout(1500);
for (const [who, page] of [["bob", bob], ["carol", carol]]) {
  await page.click(".guild-icon.dm-home");
  await page.waitForTimeout(600);
  const rows = await page.locator(".dm-row").allTextContents();
  check(who + " sees the group in their DM list", rows.some((t) => /ga\d+/.test(t) && /3/.test(t)));
}
await carol.locator(".dm-row", { hasText: "ga" + sfx }).last().click();
await carol.waitForTimeout(800);
check("carol reads the group message", (await carol.locator(".message .content").allTextContents()).some((t) => t.includes("hello squad")));
// ...and the 1:1 history stayed private (carol must NOT see it)
check("1:1 history not leaked to carol", !(await carol.locator(".message .content").allTextContents()).some((t) => t.includes("private 1:1 line")));

// owner-only kick: alice sees Remove buttons, carol doesn't
await alice.locator(".dm-members-count").click();
await alice.waitForTimeout(400);
check("owner sees Remove buttons", await alice.locator(".dm-member-row button", { hasText: "Remove" }).count() >= 2);
await alice.keyboard.press("Escape");
await carol.locator(".dm-members-count").click();
await carol.waitForTimeout(400);
check("non-owner sees no Remove buttons", await carol.locator(".dm-member-row button", { hasText: "Remove" }).count() === 0);
await carol.keyboard.press("Escape");

// ring: alice starts a call (LiveKit itself may be absent; the ring still fires on token mint)
await alice.locator("button[aria-label='Start voice call']").click();
await bob.waitForTimeout(1500);
check("bob gets the incoming-call toast", await bob.locator(".toast-call").count() >= 1);

console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.filter((e) => !/livekit|signal|fetch/i.test(e)).length);
errors.slice(0, 6).forEach((e) => console.log("ERR: " + e));
await browser.close();
process.exit(fail ? 1 : 0);
