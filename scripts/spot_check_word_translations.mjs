// Prints a random sample of precomputed word translations for manual
// review (plan §5 step 3: "spot-check a random sample... rather than
// proofreading all 31,000").
//
// Usage: node scripts/spot_check_word_translations.mjs [--n 20] [--direction en-es]

import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ASSETS_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "bibles");

function parseArgs() {
  const args = { n: 20, direction: null };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--n") args.n = Number(argv[++i]);
    else if (argv[i] === "--direction") args.direction = argv[++i];
  }
  return args;
}

function main() {
  const args = parseArgs();
  const wtDb = new DatabaseSync(path.join(ASSETS_DIR, "word_translations.db"), { readOnly: true });

  const langDbFiles = { en: "kjv.db", es: "rv1909.db", pt: "almeida1911.db" };
  const langDbs = {};
  for (const [code, file] of Object.entries(langDbFiles)) {
    langDbs[code] = new DatabaseSync(path.join(ASSETS_DIR, file), { readOnly: true });
  }

  let where = "status = 'ok'";
  const params = [];
  if (args.direction) {
    const [s, t] = args.direction.split("-");
    where += " AND source_lang = ? AND target_lang = ?";
    params.push(s, t);
  }

  const candidates = wtDb
    .prepare(`SELECT verse_id, source_lang, target_lang FROM completed_verses WHERE ${where}`)
    .all(...params);

  if (candidates.length === 0) {
    console.log("No completed verses found yet.");
    return;
  }

  const sample = [];
  const used = new Set();
  while (sample.length < Math.min(args.n, candidates.length)) {
    const idx = Math.floor(Math.random() * candidates.length);
    if (used.has(idx)) continue;
    used.add(idx);
    sample.push(candidates[idx]);
  }

  for (const { verse_id, source_lang, target_lang } of sample) {
    const bookId = Math.floor(verse_id / 1_000_000);
    const chapter = Math.floor((verse_id % 1_000_000) / 1_000);
    const verse = verse_id % 1_000;

    const sourceRow = langDbs[source_lang]
      .prepare("SELECT book_name, text FROM verses WHERE book_id = ? AND chapter = ? AND verse = ?")
      .get(bookId, chapter, verse);
    const targetRow = langDbs[target_lang]
      .prepare("SELECT text FROM verses WHERE book_id = ? AND chapter = ? AND verse = ?")
      .get(bookId, chapter, verse);
    const words = wtDb
      .prepare(
        "SELECT word_index, original_word, translated_word FROM word_translations WHERE verse_id = ? AND source_lang = ? AND target_lang = ? ORDER BY word_index",
      )
      .all(verse_id, source_lang, target_lang);

    console.log(`\n=== ${sourceRow.book_name} ${chapter}:${verse} (${source_lang} -> ${target_lang}) ===`);
    console.log(`Source: ${sourceRow.text}`);
    console.log(`Target (official): ${targetRow.text}`);
    console.log("Word mapping:", words.map((w) => `${w.original_word}->${w.translated_word}`).join(" | "));
  }
}

main();
