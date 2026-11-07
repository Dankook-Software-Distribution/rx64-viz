// dockview-core 8 ships its stylesheet only inside the UMD bundle, as a
// string assigned to a <style> element. Copy it out into a .css file.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const src = readFileSync("node_modules/dockview-core/dist/dockview-core.js", "utf8");
const m = src.match(/s\.textContent = ("(?:[^"\\]|\\.)*");/);
if (!m) {
  console.error("dockview-css: stylesheet string not found in dockview-core.js");
  process.exit(1);
}
mkdirSync("src/vendor", { recursive: true });
writeFileSync("src/vendor/dockview.css", JSON.parse(m[1]));
console.log("dockview-css: wrote src/vendor/dockview.css");
