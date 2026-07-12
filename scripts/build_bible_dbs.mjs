// One-time data prep: downloads public-domain KJV, RV1909 (Spanish), and
// Almeida 1911 (Portuguese) from the getbible v2 API and builds the three
// SQLite asset databases the Android app bundles (see plan §3).
//
// Usage: node scripts/build_bible_dbs.mjs
import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "bibles");

const SOURCES = [
  { lang: "en", abbr: "kjv", file: "kjv.db", url: "https://api.getbible.net/v2/kjv.json" },
  { lang: "es", abbr: "valera", file: "rv1909.db", url: "https://api.getbible.net/v2/valera.json" },
  { lang: "pt", abbr: "almeida", file: "almeida1911.db", url: "https://api.getbible.net/v2/almeida.json" },
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
