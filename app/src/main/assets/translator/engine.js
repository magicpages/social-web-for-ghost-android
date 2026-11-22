"use strict";

// Runs Bergamot, Firefox's translation engine, for TranslationEngine.kt. The app serves the engine
// and the language models under /files/ and calls load() once, then translate(); answers go back
// through the Bridge object the app adds. Modelled on Firefox's translations-engine.worker.js.

/* global loadBergamot, Bridge */

// The byte alignment Bergamot needs for each kind of model file.
const Alignments = { model: 256, lex: 64, vocab: 64, srcvocab: 64, trgvocab: 64 };

let bergamot = null;
let service = null;
/** One model, or two when translating through English. */
let models = [];

async function bytes(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.arrayBuffer();
}

function initialize(wasmBinary) {
  return new Promise((resolve, reject) => {
    const module = loadBergamot({
      // Firefox starts at 40 MiB and lets it grow: less memory than starting too big.
      INITIAL_MEMORY: 41_943_040,
      print() {},
      printErr() {},
      onAbort() {
        reject(new Error("Bergamot aborted"));
      },
      async onRuntimeInitialized() {
        await Promise.resolve();
        resolve(module);
      },
      wasmBinary,
    });
  });
}

// Marian's configuration, with the whitespace it needs; the settings are Firefox's.
function config(intgemm8) {
  const settings = {
    "beam-size": "1",
    normalize: "1.0",
    "word-penalty": "0",
    "max-length-break": "128",
    "mini-batch-words": "1024",
    workspace: "128",
    "max-length-factor": "2.0",
    "skip-cost": "true",
    "cpu-threads": "0",
    quiet: "true",
    "quiet-translation": "true",
    "gemm-precision": intgemm8 ? "int8shiftAll" : "int8shiftAlphaAll",
    alignment: "soft",
  };
  const indent = "            ";
  let text = "\n";
  for (const [key, value] of Object.entries(settings)) text += `${indent}${key}: ${value}\n`;
  return text + indent;
}

async function loadModel(spec) {
  const memory = {};
  for (const [type, path] of Object.entries(spec.files)) {
    const buffer = await bytes(path);
    const aligned = new bergamot.AlignedMemory(buffer.byteLength, Alignments[type]);
    aligned.getByteArrayView().set(new Uint8Array(buffer));
    memory[type] = aligned;
  }
  const vocabs = new bergamot.AlignedMemoryList();
  if (memory.vocab) {
    vocabs.push_back(memory.vocab);
  } else {
    vocabs.push_back(memory.srcvocab);
    vocabs.push_back(memory.trgvocab);
  }
  return new bergamot.TranslationModel(
    spec.from, spec.to, config(spec.intgemm8), memory.model, memory.lex ?? null, vocabs, null,
  );
}

/** Starts the engine with [specs]: `[{from, to, intgemm8, files: {model, lex, vocab}}]`, in translating order. */
async function load(id, specs) {
  try {
    bergamot = await initialize(await bytes("/files/engine.wasm"));
    service = new bergamot.BlockingService({ cacheSize: 0 });
    models = [];
    for (const spec of specs) models.push(await loadModel(spec));
    Bridge.result(id, "[]");
  } catch (e) {
    Bridge.error(id, String(e && e.stack || e));
  }
}

/** Translates every one of [texts], plain text; empty ones come back empty. */
function translate(id, texts) {
  let messages = null;
  let options = null;
  let responses = null;
  try {
    const indexes = [];
    messages = new bergamot.VectorString();
    options = new bergamot.VectorResponseOptions();
    texts.forEach((text, index) => {
      // Empty messages break the translation.
      if (!text.trim()) return;
      indexes.push(index);
      messages.push_back(text);
      options.push_back({ qualityScores: false, alignment: false, html: false });
    });
    const translated = texts.map(() => "");
    if (indexes.length > 0) {
      responses = models.length === 1
        ? service.translate(models[0], messages, options)
        : service.translateViaPivoting(models[0], models[1], messages, options);
      indexes.forEach((index, i) => {
        translated[index] = responses.get(i).getTranslatedText();
      });
    }
    Bridge.result(id, JSON.stringify(translated));
  } catch (e) {
    Bridge.error(id, String(e && e.stack || e));
  } finally {
    messages?.delete();
    options?.delete();
    responses?.delete();
  }
}
