import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const contractsDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");

async function readJson(relativePath) {
  return JSON.parse(await readFile(resolve(contractsDir, relativePath), "utf8"));
}

const schema = await readJson("events/smartdata-event.schema.json");
const validEvent = await readJson("events/examples/run-started.valid.json");
const invalidEvent = await readJson("events/examples/untrusted-extra.invalid.json");

const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
addFormats(ajv);
const validate = ajv.compile(schema);

assert.equal(
  validate(validEvent),
  true,
  `valid CloudEvent was rejected: ${ajv.errorsText(validate.errors)}`,
);

assert.equal(validate(invalidEvent), false, "invalid CloudEvent was accepted");
const rejectedKeywords = new Set((validate.errors ?? []).map((error) => error.keyword));
assert.ok(rejectedKeywords.has("additionalProperties"), "unknown envelope fields must be rejected");
assert.ok(rejectedKeywords.has("pattern"), "invalid tenant identifiers must be rejected");

console.log("Event schema compiled and accepted/rejected the contract fixtures as expected.");
