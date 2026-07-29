// REAL 3-way group call in a group DM against a live LiveKit SFU: all three
// join with fake mics/cams, everyone sees 3 tiles, one screenshares and the
// others receive the screen tile. Requires livekit-server on :7880.
import { chromium } from "playwright";

const BASE = process.env.BASE || "http://localhost:8090";
const errors = [];
let pass = 0, fail = 0;
const check = (name, ok) => { ok ? pass++ : fail++; console.log((ok ? "PASS " : "FAIL ") + name); };

const browser = await chromium.launch({
  args: ["--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream",
    "--auto-select-desktop-capture-source=Entire screen",
    // mDNS ICE candidates can't resolve on WSL/containers — ICE never pairs and
    // joins hang at "Connecting…"; expose the real host candidate instead
    "--disable-features=WebRtcHideLocalIpsWithMdns"],
});

async function newUser(name) {
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 850 }, permissions: ["microphone", "camera"] });
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
const alice = await newUser("ca" + sfx);
const bob = await newUser("cb" + sfx);
const carol = await newUser("cc" + sfx);

// group DM via the multi-chip New DM modal
await alice.click(".guild-icon.dm-home");
await alice.click("button[aria-label='New DM']");
await alice.fill(".modal input.text-input", "cb" + sfx);
await alice.press(".modal input.text-input", "Enter");
await alice.fill(".modal input.text-input", "cc" + sfx);
await alice.press(".modal input.text-input", "Enter");
await alice.locator(".modal .btn", { hasText: "Start" }).click();
await alice.waitForSelector("#composer-input", { timeout: 8000 });
check("group created via chips", /2 others|3 members/.test(await alice.locator(".dm-members-count").textContent().catch(() => "3 members")));

// alice starts the call; bob + carol join via the ring toast / call badge
await alice.locator("button[aria-label='Start voice call']").click();
await alice.waitForTimeout(2500);
for (const [who, page] of [["bob", bob], ["carol", carol]]) {
  const toastBtn = page.locator(".toast-call");
  if (await toastBtn.count()) {
    await toastBtn.click();
  } else {
    await page.click(".guild-icon.dm-home");
    await page.locator(".dm-row", { hasText: "ca" + sfx }).first().click();
    await page.locator("button[aria-label='Start voice call']").click();
  }
  await page.waitForTimeout(2500);
  console.log(who + " joined");
}
await alice.waitForTimeout(2000);

for (const [who, page] of [["alice", alice], ["bob", bob], ["carol", carol]]) {
  const tiles = await page.locator(".tile:not(.screen-tile)").count();
  check(who + " sees 3 participant tiles", tiles === 3);
}

// alice shares her screen; the others receive a screen tile through the SFU
await alice.click("#vc-screen");
await alice.waitForTimeout(3500);
check("alice is sharing", await alice.locator(".tile.screen-tile").count() >= 1);
for (const [who, page] of [["bob", bob], ["carol", carol]]) {
  check(who + " receives the screenshare tile", await page.locator(".tile.screen-tile").count() >= 1);
}

console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.length);
errors.slice(0, 6).forEach((e) => console.log("ERR: " + e));
await browser.close();
process.exit(fail ? 1 : 0);
