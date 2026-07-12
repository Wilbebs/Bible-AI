// Phase 2 precompute pipeline (plan §5/§8 step 5).
//
// For every verse and every one of the 6 direction pairs (EN<->ES, EN<->PT,
// ES<->PT), asks Gemini for a word-by-word breakdown of the source verse
// into the target language, grounded in the existing official translation
// of the same verse. Writes results into word_translations.db, matching the
// schema in plan §5 step 2.
//
// Resumable: on each run, verses already present in word_translations.db
// for a given direction are skipped, so the script can be killed and
// restarted freely.
//
// Usage:
//   GEMINI_API_KEY=xxx node scripts/precompute_word_translations.mjs [--limit N] [--direction en-es] [--books 1,2,3]
//
// Flags:
//   --limit N        Stop after N successful verse/direction calls (smoke testing).
//   --direction D     Only run one direction, e.g. en-es, es-en, en-pt, pt-en, es-pt, pt-es.
//   --books 1,2,3     Only process these book_ids (smoke testing / re-runs).
//   --concurrency N   Parallel in-flight requests (default 8).

import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { appendFileSync, mkdirSync } from "node:fs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSETS_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "bibles");
const WORD_TRANSLATIONS_DB = path.join(ASSETS_DIR, "word_translations.db");
const FAILURES_LOG = path.join(__dirname, "precompute_failures.jsonl");

const LANGUAGES = {
  en: { name: "English", file: "kjv.db" },
  es: { name: "Spanish", file: "rv1909.db" },
  pt: { name: "Portuguese", file: "almeida1911.db" },
};

const ALL_DIRECTIONS = ["en-es", "es-en", "en-pt", "pt-en", "es-pt", "pt-es"];

const MODEL = process.env.GEMINI_MODEL || "gemini-3.1-flash-lite";
const API_KEY = process.env.GEMINI_API_KEY;
const MAX_RETRIES = 3;

function parseArgs() {
  const args = { limit: Infinity, direction: null, books: null, chapter: null, concurrency: 8 };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--limit") args.limit = Number(argv[++i]);
    else if (arg === "--direction") args.direction = argv[++i];
    else if (arg === "--books") args.books = argv[++i].split(",").map(Number);
    else if (arg === "--chapter") args.chapter = Number(argv[++i]);
    else if (arg === "--concurrency") args.concurrency = Number(argv[++i]);
  }
  return args;
}

function tokenize(text) {
  return text.split(/\s+/).filter((t) => t.length > 0);
}

function openReadOnly(file) {
  return new DatabaseSync(path.join(ASSETS_DIR, file), { readOnly: true });
}

function ensureWordTranslationsDb() {
  mkdirSync(ASSETS_DIR, { recursive: true });
  const db = new DatabaseSync(WORD_TRANSLATIONS_DB);
  db.exec(`
    CREATE TABLE IF NOT EXISTS word_translations (
      verse_id INTEGER,
      source_lang TEXT,
      target_lang TEXT,
      word_index INTEGER,
      original_word TEXT,
      translated_word TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_word_lookup ON word_translations(verse_id, source_lang, target_lang);
    CREATE TABLE IF NOT EXISTS completed_verses (
      verse_id INTEGER,
      source_lang TEXT,
      target_lang TEXT,
      status TEXT,
      PRIMARY KEY (verse_id, source_lang, target_lang)
    );
  `);
  return db;
}

function verseId(bookId, chapter, verse) {
  return bookId * 1_000_000 + chapter * 1_000 + verse;
}

function loadVerses(db) {
  const rows = db
    .prepare("SELECT book_id, book_name, chapter, verse, text FROM verses ORDER BY book_id, chapter, verse")
    .all();
  const byRef = new Map();
  for (const row of rows) {
    byRef.set(`${row.book_id}:${row.chapter}:${row.verse}`, row);
  }
  return byRef;
}

// Reverted to the fuller word_index/original_word/translated_word shape:
// leaner formats (bare string array, {i, t}) saved tokens but measurably
// hurt accuracy (92.7% vs 99.5% first-pass success on real verses). Echoing
// the original word back per entry gives the model an extra self-check
// anchor. Accuracy is prioritized over the extra token cost.
const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    words: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          word_index: { type: "INTEGER" },
          original_word: { type: "STRING" },
          translated_word: { type: "STRING" },
        },
        required: ["word_index", "original_word", "translated_word"],
      },
    },
  },
  required: ["words"],
};

function buildPrompt(sourceLangName, targetLangName, sourceTokens, sourceText, targetText, ref) {
  const numbered = sourceTokens.map((t, i) => `${i}: ${t}`).join("\n");
  return `You are translating a Bible verse word-by-word from ${sourceLangName} to ${targetLangName}.
Use the existing official ${targetLangName} translation of the same verse below to pick the contextually correct sense of each word — do not translate tokens in isolation.

Reference: ${ref}
Full ${sourceLangName} verse: "${sourceText}"
Full official ${targetLangName} translation of the same verse: "${targetText}"

The ${sourceLangName} verse has been split into these whitespace tokens (punctuation kept attached):
${numbered}

For every token index above, return the ${targetLangName} word or short phrase that token corresponds to in the official translation shown above. Return exactly one entry per index, in order, with no gaps or duplicates.
Never leave a translation blank. If several consecutive source tokens together correspond to one target phrase (or the word order shifts so a single target word covers more than one source token), repeat that same target phrase for each of those source token indices rather than leaving any of them empty.`;
}

async function callGemini(prompt, temperature) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${API_KEY}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: RESPONSE_SCHEMA,
        temperature,
      },
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`HTTP ${res.status}: ${body.slice(0, 300)}`);
  }
  const data = await res.json();
  const text = data.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) throw new Error("No text in Gemini response");
  return {
    parsed: JSON.parse(text),
    inputTokens: data.usageMetadata?.promptTokenCount || 0,
    outputTokens: data.usageMetadata?.candidatesTokenCount || 0,
  };
}

// Pure structural validation — same approach that got 99.5% first-pass
// success on chapter 1. A content-alignment heuristic was tried on top of
// this but proved unreliable (missed real drift, rejected valid hard
// verses), so it was dropped rather than risk yield for an unproven gain.
function validate(parsed, tokens) {
  if (!parsed || !Array.isArray(parsed.words)) return null;
  if (parsed.words.length !== tokens.length) return null;
  const seen = new Set();
  for (const w of parsed.words) {
    if (
      typeof w.word_index !== "number" ||
      typeof w.original_word !== "string" ||
      typeof w.translated_word !== "string" ||
      w.translated_word.trim().length === 0
    ) {
      return null;
    }
    seen.add(w.word_index);
  }
  for (let i = 0; i < tokens.length; i++) {
    if (!seen.has(i)) return null;
  }
  return parsed.words.sort((a, b) => a.word_index - b.word_index);
}

async function translateVerseDirection(sourceVerse, targetVerse, sourceLang, targetLang) {
  const tokens = tokenize(sourceVerse.text);
  const prompt = buildPrompt(
    LANGUAGES[sourceLang].name,
    LANGUAGES[targetLang].name,
    tokens,
    sourceVerse.text,
    targetVerse.text,
    `${sourceVerse.book_name} ${sourceVerse.chapter}:${sourceVerse.verse}`,
  );

  let lastError;
  let usage = { inputTokens: 0, outputTokens: 0 };
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const { parsed, inputTokens, outputTokens } = await callGemini(prompt, attempt === 1 ? 0.1 : 0.4);
      usage.inputTokens += inputTokens;
      usage.outputTokens += outputTokens;
      const validated = validate(parsed, tokens);
      if (validated) return { words: validated, usage };
      lastError = new Error("Schema validation failed (index/count mismatch, blank translation, or misalignment)");
    } catch (err) {
      lastError = err;
      if (isQuotaExhausted(err)) break; // persistent (daily/plan) quota cap — retrying won't help
      if (String(err.message).includes("HTTP 429")) {
        await sleep(2000 * attempt);
      }
    }
  }
  lastError.usage = usage;
  throw lastError;
}

function isQuotaExhausted(err) {
  return /exceeded your current quota/i.test(String(err.message));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runPool(items, concurrency, worker, shouldStop) {
  let index = 0;
  let active = 0;
  let successCount = 0;
  return new Promise((resolve) => {
    function next() {
      if ((index >= items.length || shouldStop()) && active === 0) {
        resolve(successCount);
        return;
      }
      while (active < concurrency && index < items.length && !shouldStop()) {
        const item = items[index++];
        active++;
        worker(item)
          .then((ok) => {
            if (ok) successCount++;
          })
          .finally(() => {
            active--;
            next();
          });
      }
    }
    next();
  });
}

async function main() {
  if (!API_KEY) {
    console.error("GEMINI_API_KEY is not set.");
    process.exit(1);
  }

  const args = parseArgs();
  const directions = args.direction ? [args.direction] : ALL_DIRECTIONS;

  const langDbs = {};
  for (const code of Object.keys(LANGUAGES)) {
    langDbs[code] = loadVerses(openReadOnly(LANGUAGES[code].file));
  }

  const wtDb = ensureWordTranslationsDb();
  const insertWord = wtDb.prepare(
    "INSERT INTO word_translations (verse_id, source_lang, target_lang, word_index, original_word, translated_word) VALUES (?, ?, ?, ?, ?, ?)",
  );
  const markDone = wtDb.prepare(
    "INSERT OR REPLACE INTO completed_verses (verse_id, source_lang, target_lang, status) VALUES (?, ?, ?, ?)",
  );
  const isDone = wtDb.prepare(
    "SELECT status FROM completed_verses WHERE verse_id = ? AND source_lang = ? AND target_lang = ?",
  );

  let totalDone = 0;
  let quotaExhausted = false;
  let totalInputTokens = 0;
  let totalOutputTokens = 0;
  const startedAt = Date.now();

  for (const direction of directions) {
    if (quotaExhausted) break;
    const [sourceLang, targetLang] = direction.split("-");
    const sourceVerses = langDbs[sourceLang];

    let refs = [...sourceVerses.keys()];
    if (args.books) {
      refs = refs.filter((ref) => args.books.includes(Number(ref.split(":")[0])));
    }
    if (args.chapter != null) {
      refs = refs.filter((ref) => Number(ref.split(":")[1]) === args.chapter);
    }

    const jobs = [];
    for (const ref of refs) {
      const [bookId, chapter, verse] = ref.split(":").map(Number);
      const targetVerse = langDbs[targetLang].get(ref);
      if (!targetVerse) continue; // verse missing in target translation, skip (graceful fallback)
      const vId = verseId(bookId, chapter, verse);
      const existing = isDone.get(vId, sourceLang, targetLang);
      if (existing && existing.status === "ok") continue; // only skip verses that already succeeded
      jobs.push({ vId, sourceVerse: sourceVerses.get(ref), targetVerse, sourceLang, targetLang });
    }

    console.log(`[${direction}] ${jobs.length} verses to process`);

    await runPool(
      jobs,
      args.concurrency,
      async (job) => {
        if (totalDone >= args.limit) return false;
        try {
          const { words, usage } = await translateVerseDirection(job.sourceVerse, job.targetVerse, job.sourceLang, job.targetLang);
          totalInputTokens += usage.inputTokens;
          totalOutputTokens += usage.outputTokens;
          wtDb.exec("BEGIN");
          for (const w of words) {
            insertWord.run(job.vId, job.sourceLang, job.targetLang, w.word_index, w.original_word, w.translated_word);
          }
          markDone.run(job.vId, job.sourceLang, job.targetLang, "ok");
          wtDb.exec("COMMIT");
          totalDone++;
          if (totalDone % 100 === 0) {
            const elapsedMin = ((Date.now() - startedAt) / 60000).toFixed(1);
            console.log(`  ${totalDone} verse/direction pairs done (${elapsedMin} min elapsed)`);
          }
          return true;
        } catch (err) {
          try {
            wtDb.exec("ROLLBACK");
          } catch {}
          if (err.usage) {
            totalInputTokens += err.usage.inputTokens;
            totalOutputTokens += err.usage.outputTokens;
          }
          markDone.run(job.vId, job.sourceLang, job.targetLang, "failed");
          appendFileSync(
            FAILURES_LOG,
            JSON.stringify({
              verse_id: job.vId,
              source_lang: job.sourceLang,
              target_lang: job.targetLang,
              error: String(err.message || err),
            }) + "\n",
          );
          if (isQuotaExhausted(err)) {
            quotaExhausted = true;
            console.error("Quota exhausted — stopping the run early instead of burning through guaranteed failures.");
          }
          return false;
        }
      },
      () => totalDone >= args.limit || quotaExhausted,
    );

    if (totalDone >= args.limit || quotaExhausted) break;
  }

  const inputCost = (totalInputTokens / 1_000_000) * 0.25;
  const outputCost = (totalOutputTokens / 1_000_000) * 1.5;
  console.log(
    `Token usage: ${totalInputTokens} input, ${totalOutputTokens} output. ` +
      `Estimated cost: $${(inputCost + outputCost).toFixed(4)} (input $${inputCost.toFixed(4)} + output $${outputCost.toFixed(4)})`,
  );

  wtDb.close();
  console.log(`Finished. ${totalDone} verse/direction pairs processed this run.`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
