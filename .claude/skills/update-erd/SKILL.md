---
name: update-erd
description: Regenerate the ElecSarthi ER diagram (PNG/SVG) after a schema change and check every relationship end. Use after a migration adds, removes or renames tables, columns or foreign keys.
---

# Update the ER diagram

The diagram is generated from the `T` table definitions and the `EDGES` list in `tools/erd/gen_erd.py`. It is not drawn by hand.

1. Compare the latest migrations with `tools/erd/gen_erd.py`. For each changed table, update its rows: `(kind, column, type, note, False)`, where kind is `pk`, `pkfk`, `fk`, `uk`, `n`, `snap` (snapshot without FK) or `sens` (sensitive). Add or remove `EDGES` entries as `(parent, child, parent_end, child_end)`, with ends `one`, `zero_one`, `many` or `zero_many`. Update the table count in the `assert len(T) == …` line if needed.
2. Run from the repo root (needs Graphviz `dot` installed):
   `python3 tools/erd/gen_erd.py docs/data-model/ElecSarthi_ER_Diagram`
   - It writes `.svg` and `.png` and corrects Graphviz's known head/tail swap with ortho lines.
   - It must print `attempt N: 0 reversed` and `ok 18 tables …` (or your new count).
3. Run `python3 tools/erd/verify_ends.py docs/data-model/ElecSarthi_ER_Diagram.svg`. It must print `0 wrong`.
4. Check that every diagram column exists in the migrations, by grepping each new name.
5. Update the relationship table (§3.1) and the table sections in `docs/data-model/ElecSarthi_ER_Diagram.md`.
6. Delete the intermediate `.dot` file if one was left in `docs/`.
