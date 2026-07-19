import { chromium } from "playwright";
let pass = 0, fail = 0;
const check = (n, ok) => { ok ? pass++ : fail++; console.log((ok ? "PASS " : "FAIL ") + n); };
const browser = await chromium.launch({ args: ["--no-sandbox"] });
const page = await (await browser.newContext({ viewport: { width: 1720, height: 950 } })).newPage();
const errors = []; page.on("pageerror", (e) => errors.push(e.message));
await page.goto("http://localhost:8090");
await page.click("#auth-toggle-link");
await page.fill("#auth-username", "lk" + (Date.now() % 100000));
await page.fill("#auth-password", "hunter2pw");
await page.click("#auth-submit");
await page.waitForSelector(".guild-icon", { timeout: 8000 });
await page.click(".guild-icon.action[aria-label='Create a server']");
await page.fill(".modal input.text-input", "lockerhaus");
await page.press(".modal input.text-input", "Enter");
await page.waitForSelector(".channel", { timeout: 8000 });

// create a storage channel from the Storage group's +
await page.locator(".channel-group-header", { hasText: "Storage" }).locator("button").click();
await page.waitForTimeout(400);
check("create modal offers Storage type pre-selected", await page.locator(".seg button.active", { hasText: "Storage" }).count() === 1);
await page.fill(".modal input.text-input", "vault");
await page.locator(".modal .btn", { hasText: "Create" }).click();
await page.waitForTimeout(600);
await page.locator(".channel", { hasText: "vault" }).click();
await page.waitForTimeout(600);
check("storage view opens", !(await page.locator("#storage-view").evaluate((el) => el.classList.contains("hidden"))));

// new folder
await page.locator(".st-header .btn", { hasText: "New Folder" }).click();
await page.fill(".modal input.text-input", "docs");
await page.press(".modal input.text-input", "Enter");
await page.waitForTimeout(600);
check("folder listed", await page.locator(".st-row", { hasText: "docs" }).count() === 1);

// enter folder, upload a file
await page.locator(".st-row", { hasText: "docs" }).click();
await page.waitForTimeout(500);
await page.locator(".st-header input[type=file]").setInputFiles(process.env.DOC);
await page.waitForTimeout(1200);
check("file listed inside folder", await page.locator(".st-row", { hasText: "doc.txt" }).count() === 1);
check("breadcrumb shows path", (await page.locator(".st-crumbs").textContent()).includes("docs"));

// delete own file via hover button
page.on("dialog", (d) => d.accept());
await page.locator(".st-row", { hasText: "doc.txt" }).hover();
await page.locator(".st-row .st-del").click();
await page.waitForTimeout(800);
check("file deleted", await page.locator(".st-row", { hasText: "doc.txt" }).count() === 0);
await page.screenshot({ path: process.env.OUT + "/storage-view.png" });
console.log("RESULT " + pass + "/" + (pass + fail) + " JS_ERRORS=" + errors.length);
errors.slice(0, 4).forEach((e) => console.log("ERR " + e));
await browser.close();
process.exit(fail ? 1 : 0);
