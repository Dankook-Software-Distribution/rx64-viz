// WebSocket console check (Node 22+, built-in WebSocket).
// Types a command one key at a time, as a terminal would, and expects the
// echo, the output, and the recording to go recording -> indexing -> ready.
const base = process.argv[2] ?? "http://localhost:8080";
const command = process.argv[3] ?? "echo ws";

const post = async (path, body) => {
  const r = await fetch(base + path, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  return r.json();
};

const s = (await post("/api/sessions", { kernel: "rx64-x86-style" })).id;
await post(`/api/sessions/${s}/ready`);

const ws = new WebSocket(base.replace(/^http/, "ws") + `/ws/sessions/${s}`);
let console_ = "";
const statuses = [];
const done = new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("timeout")), 120000);
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.type === "output") console_ += m.data;
    if (m.type === "recording") {
      statuses.push(m.recording.status);
      if (m.recording.status === "ready" || m.recording.status === "failed") {
        clearTimeout(timer);
        resolve(m.recording);
      }
    }
  };
  ws.onerror = (e) => reject(e);
});
await new Promise((r) => (ws.onopen = r));
for (const ch of command + "\r") {
  ws.send(JSON.stringify({ type: "input", data: ch }));
  await new Promise((r) => setTimeout(r, 20));
}
const rec = await done;
ws.close();
await fetch(base + `/api/sessions/${s}`, { method: "DELETE" });

const out = console_.replaceAll("\r", "");
const want = `$ ${command}\n${command.split(" ").slice(1).join(" ")}\n$ `;
const ok = out.endsWith(want) && rec.status === "ready" && rec.command === command
  && statuses.join(",") === "recording,indexing,ready";
console.log(`statuses ${statuses.join(",")}; ${rec.summary?.steps} steps; console tail ${JSON.stringify(out.slice(-30))}`);
console.log(ok ? "WS OK" : "WS FAILED");
process.exit(ok ? 0 : 1);
