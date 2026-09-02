#!/usr/bin/env bash
# Build a synthetic games.db shaped like prod (~870k rows, a handful of mega-bots,
# a long tail of ordinary players) so the snapshot script has something to profile
# without a copy of the real database.
#
# usage: seed-synthetic-games-db.sh [path/to/out.db]
#
# Sample players once seeded: AaaarghBot (~180k games), player7 (~120),
# player12345 (~35), and any 'playerN' for N < 40000.

set -eo pipefail

scriptpath=$(cd "$(dirname "$0")" && pwd)
db="${1:-$scriptpath/../../playtakdb/games-synthetic.db}"
rm -f "$db"
mkdir -p "$(dirname "$db")"

sqlite3 "$db" <<'SQL'
.bail on
CREATE TABLE games (id INTEGER PRIMARY KEY, date INT, size INT, player_white VARCHAR(20), player_black VARCHAR(20), notation TEXT, result VARCAR(10), timertime INT DEFAULT 0, timerinc INT DEFAULT 0, rating_white int default 1000, rating_black int default 1000, unrated int default 0, tournament int default 0, komi int default 0, pieces int default -1, capstones int default -1, rating_change_white int default 0, rating_change_black int default 0, extra_time_amount int default 0, extra_time_trigger int default 0, increment_scales int default 0, opening VARCHAR(20) default 'swap');

WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 870000)
INSERT INTO games (id, date, size, player_white, player_black, notation, result, timertime, extra_time_amount, extra_time_trigger)
SELECT n,
  1400000000000 + n * 1000,
  4 + (n % 5),
  CASE WHEN n % 7 = 0 THEN 'AaaarghBot' WHEN n % 11 = 0 THEN 'TakticianBot' ELSE 'player' || (n % 40000) END,
  CASE WHEN n % 13 = 0 THEN 'AaaarghBot' WHEN n % 17 = 0 THEN 'CrushBot'   ELSE 'player' || ((n * 7) % 40000) END,
  '', CASE n % 4 WHEN 0 THEN '1-0' WHEN 1 THEN '0-1' WHEN 2 THEN 'R-0' ELSE '1/2-1/2' END,
  600, 0, 0
FROM seq;
SQL

bash "$scriptpath/../migrations/2026-08-add-nocase-player-indexes.sh" "$db"
bash "$scriptpath/../migrations/2026-08-add-player-games-view.sh" "$db"
sqlite3 "$db" "CREATE INDEX IF NOT EXISTS idx_games_date ON games (date); ANALYZE;"

sqlite3 "$db" "SELECT 'games rows: ' || count(*) FROM games;"
echo "Seeded $db"
