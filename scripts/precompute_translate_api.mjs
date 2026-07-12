// Batch/precompute pipeline using Google Cloud Translation API v2 instead of
// Gemini — the "dedicated translate API" comparison arm, run offline like
// the Genesis Gemini precompute (scripts/precompute_word_translations.mjs),
// so cost/accuracy are directly comparable in the same units.
//
// Cloud Translation has no word-alignment/context feature, so each source
// token is translated in isolation (no surrounding verse text sent). The
// whole verse is also translated in the same request (cheap — Translation
// API bills per character, not per request) and logged to a comparison
// file alongside the real Bible translation, so we can measure how far
// isolated-word concatenation drifts from actual contextual quality.
//
// Usage:
//   TRANSLATE_API_KEY=xxx node scripts/precompute_translate_api.mjs --books 2 [--direction en-es] [--limit N] [--concurrency N]

import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { appendFileSync, mkdirSync } from "node:fs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSETS_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "bibles");
const WORD_TRANSLATIONS_DB = path.join(ASSETS_DIR, "word_translations.db");
const COMPARISON_LOG = path.join(__dirname, "translate_api_verse_comparison.jsonl");
const FAILURES_LOG = path.join(__dirname, "precompute_translate_api_failures.jsonl");

const LANGUAGES = {
  en: { file: "kjv.db" },
  es: { file: "rv1909.db" },
  pt: { file: "almeida1911.db" },
};

const ALL_DIRECTIONS = ["en-es", "es-en", "en-pt", "pt-en", "es-pt", "pt-es"];
const API_KEY = process.env.TRANSLATE_API_KEY;
const MAX_RETRIES = 3;
// $20 per 1M characters, Cloud Translation Basic v2 pricing.
const PRICE_PER_MILLION_CHARS = 20;

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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isQuotaExhausted(err) {
  return /exceeded|quota|billing/i.test(String(err.message));
}

/** One request per verse: all source tokens + the full verse text, each translated independently. */
async function callTranslateApi(sourceLangCode, targetLangCode, tokens, fullVerseText) {
  const url = `https://translation.googleapis.com/language/translate/v2?key=${API_KEY}`;
  const q = [...tokens, fullVerseText];
  const charCount = q.reduce((sum, s) => sum + s.length, 0);
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ q, source: sourceLangCode, target: targetLangCode, format: "text" }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`HTTP ${res.status}: ${body.slice(0, 300)}`);
  }
  const data = await res.json();
  const translations = data.data.translations.map((t) => t.translatedText);
  return {
    wordTranslations: translations.slice(0, tokens.length),
    verseTranslation: translations[translations.length - 1],
    charCount,
  };
}

async function translateVerseDirection(sourceVerse, sourceLang, targetLang) {
  const tokens = tokenize(sourceVerse.text);
  let lastError;
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const { wordTranslations, verseTranslation, charCount } = await callTranslateApi(
        sourceLang, targetLang, tokens, sourceVerse.text,
      );
      if (wordTranslations.length !== tokens.length || wordTranslations.some((t) => !t || !t.trim())) {
        lastError = new Error("Word count mismatch or blank translation");
        continue;
      }
      const words = tokens.map((t, i) => ({ word_index: i, original_word: t, translated_word: wordTranslations[i] }));
      return { words, verseTranslation, charCount };
    } catch (err) {
      lastError = err;
      if (isQuotaExhausted(err)) break;
      if (String(err.message).includes("HTTP 429")) await sleep(2000 * attempt);
    }
  }
  throw lastError;
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
          .then((ok) => { if (ok) successCount++; })
          .finally(() => { active--; next(); });
      }
    }
    next();
  });
}

async function main() {
  if (!API_KEY) {
    console.error("TRANSLATE_API_KEY is not set.");
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
  let totalChars = 0;
  const startedAt = Date.now();

  for (const direction of directions) {
    if (quotaExhausted) break;
    const [sourceLang, targetLang] = direction.split("-");
    const sourceVerses = langDbs[sourceLang];

    let refs = [...sourceVerses.keys()];
    if (args.books) refs = refs.filter((ref) => args.books.includes(Number(ref.split(":")[0])));
    if (args.chapter != null) refs = refs.filter((ref) => Number(ref.split(":")[1]) === args.chapter);

    const jobs = [];
    for (const ref of refs) {
      const [bookId, chapter, verse] = ref.split(":").map(Number);
      const targetVerse = langDbs[targetLang].get(ref);
      if (!targetVerse) continue;
      const vId = verseId(bookId, chapter, verse);
      const existing = isDone.get(vId, sourceLang, targetLang);
      if (existing && existing.status === "ok") continue;
      jobs.push({ vId, sourceVerse: sourceVerses.get(ref), targetVerse, sourceLang, targetLang });
    }

    console.log(`[${direction}] ${jobs.length} verses to process`);

    await runPool(
      jobs,
      args.concurrency,
      async (job) => {
        if (totalDone >= args.limit) return false;
        try {
          const { words, verseTranslation, charCount } = await translateVerseDirection(
            job.sourceVerse, job.sourceLang, job.targetLang,
          );
          totalChars += charCount;
          wtDb.exec("BEGIN");
          for (const w of words) {
            insertWord.run(job.vId, job.sourceLang, job.targetLang, w.word_index, w.original_word, w.translated_word);
          }
          markDone.run(job.vId, job.sourceLang, job.targetLang, "ok");
          wtDb.exec("COMMIT");
          appendFileSync(
            COMPARISON_LOG,
            JSON.stringify({
              verse_id: job.vId,
              direction: `${job.sourceLang}-${job.targetLang}`,
              source_text: job.sourceVerse.text,
              translate_api_verse: verseTranslation,
              official_target_verse: job.targetVerse.text,
              isolated_words_joined: words.map((w) => w.translated_word).join(" "),
            }) + "\n",
          );
          totalDone++;
          if (totalDone % 100 === 0) {
            const elapsedMin = ((Date.now() - startedAt) / 60000).toFixed(1);
            console.log(`  ${totalDone} verse/direction pairs done (${elapsedMin} min elapsed)`);
          }
          return true;
        } catch (err) {
          try { wtDb.exec("ROLLBACK"); } catch {}
          markDone.run(job.vId, job.sourceLang, job.targetLang, "failed");
          appendFileSync(
            FAILURES_LOG,
            JSON.stringify({
              verse_id: job.vId, source_lang: job.sourceLang, target_lang: job.targetLang,
              error: String(err.message || err),
            }) + "\n",
          );
          if (isQuotaExhausted(err)) {
            quotaExhausted = true;
            console.error("Quota/billing exhausted — stopping early.");
          }
          return false;
        }
      },
      () => totalDone >= args.limit || quotaExhausted,
    );

    if (totalDone >= args.limit || quotaExhausted) break;
  }

  const cost = (totalChars / 1_000_000) * PRICE_PER_MILLION_CHARS;
  console.log(
    `Characters sent: ${totalChars}. Estimated cost: $${cost.toFixed(4)} (Cloud Translation Basic, $${PRICE_PER_MILLION_CHARS}/1M chars).`,
  );
  wtDb.close();
  console.log(`Finished. ${totalDone} verse/direction pairs processed this run.`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
