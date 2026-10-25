// Development verification helper only; never used by the business runtime.
import { createWriteStream } from "node:fs";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

if (process.env.NODE_TLS_REJECT_UNAUTHORIZED === "0") throw new Error("TLS certificate verification must remain enabled");
const [urlText, destination] = process.argv.slice(2);
const url = new URL(urlText);
if (url.origin !== "https://repo.maven.apache.org" || !url.pathname.startsWith("/maven2/") ||
    url.username || url.password || url.search || url.hash || !destination) {
  throw new Error("Only an official Maven Central artifact and a local destination are accepted");
}
const signal = AbortSignal.timeout(45_000);
const response = await fetch(url, { redirect: "error", signal });
if (!response.ok || !response.body) throw new Error(`Official Maven artifact returned HTTP ${response.status}`);
let bytes = 0;
const limit = new Transform({
  transform(chunk, _encoding, callback) {
    bytes += chunk.length;
    callback(bytes > 128 * 1024 * 1024 ? new Error("Artifact exceeds verification download limit") : null, chunk);
  },
});
await pipeline(Readable.fromWeb(response.body), limit, createWriteStream(destination), { signal });
