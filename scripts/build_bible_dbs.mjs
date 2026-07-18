// One-time data prep: downloads public-domain Bible texts from the getbible
// v2 API and builds the SQLite asset databases the Android app bundles (see
// plan §3). Re-running is safe/idempotent — each file is fully rebuilt from
// scratch, existing files just get overwritten with the same content.
//
// Usage: node scripts/build_bible_dbs.mjs
import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "bibles");

const SOURCES = [
  // Original three reading languages (plan §3).
  { lang: "en", abbr: "kjv", file: "kjv.db", url: "https://api.getbible.net/v2/kjv.json" },
  { lang: "es", abbr: "valera", file: "rv1909.db", url: "https://api.getbible.net/v2/valera.json" },
  { lang: "pt", abbr: "almeida", file: "almeida1911.db", url: "https://api.getbible.net/v2/almeida.json" },

  // Simpler/modern-register alternatives, confirmed public domain.
  { lang: "en", abbr: "web", file: "web.db", url: "https://api.getbible.net/v2/web.json" }, // World English Bible — PD, modern easy English (the closest legitimate NIV-alternative)
  { lang: "en", abbr: "basicenglish", file: "bbe.db", url: "https://api.getbible.net/v2/basicenglish.json" }, // Bible in Basic English — PD, restricted ~1000-word vocabulary

  // Original-language texts, confirmed public domain.
  { lang: "hbo", abbr: "codex", file: "wlc_hebrew.db", url: "https://api.getbible.net/v2/codex.json" }, // Westminster Leningrad Codex — Hebrew OT
  { lang: "grc", abbr: "tischendorf", file: "tischendorf_greek.db", url: "https://api.getbible.net/v2/tischendorf.json" }, // Tischendorf 8th Ed — Greek NT (PD; Textus Receptus/Westcott-Hort in this dataset are CC BY-NC-SA, deliberately skipped)
  { lang: "syr", abbr: "peshitta", file: "peshitta_aramaic.db", url: "https://api.getbible.net/v2/peshitta.json" }, // Peshitta — Aramaic/Syriac NT
  { lang: "la", abbr: "vulgate", file: "vulgate_latin.db", url: "https://api.getbible.net/v2/vulgate.json" }, // Clementine Vulgate — Latin, full OT+NT

  // Deliberate exception to the PD-only rule (user-approved): CC BY 3.0
  // Brazil, not public domain, but permissive (commercial use ok, attribution
  // required) — the only meaningfully more-modern Portuguese option found.
  { lang: "pt", abbr: "livre", file: "biblia_livre_pt.db", url: "https://api.getbible.net/v2/livre.json" }, // Bíblia Livre — CC BY 3.0 Brazil, simpler/modern Portuguese
];

async function fetchTranslation(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to fetch ${url}: ${res.status}`);
  return res.json();
}

function buildDb(outPath, translation) {
  mkdirSync(path.dirname(outPath), { recursive: true });
  const db = new DatabaseSync(outPath);
  db.exec(`
    DROP TABLE IF EXISTS verses;
    CREATE TABLE verses (
      book_id INTEGER,
      book_name TEXT,
      chapter INTEGER,
      verse INTEGER,
      text TEXT
    );
    CREATE INDEX idx_lookup ON verses(book_id, chapter, verse);
  `);
  const insert = db.prepare(
    "INSERT INTO verses (book_id, book_name, chapter, verse, text) VALUES (?, ?, ?, ?, ?)"
  );
  db.exec("BEGIN");
  let verseCount = 0;
  for (const book of translation.books) {
    for (const chapter of book.chapters) {
      for (const verse of chapter.verses) {
        insert.run(book.nr, book.name, verse.chapter, verse.verse, verse.text);
        verseCount++;
      }
    }
  }
  db.exec("COMMIT");
  db.close();
  return { books: translation.books.length, verses: verseCount };
}

async function main() {
  for (const src of SOURCES) {
    console.log(`Fetching ${src.abbr} (${src.lang})...`);
    const translation = await fetchTranslation(src.url);
    const outPath = path.join(OUT_DIR, src.file);
    const stats = buildDb(outPath, translation);
    console.log(`  -> ${src.file}: ${stats.books} books, ${stats.verses} verses`);
  }
  console.log("Done. Databases written to", OUT_DIR);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
