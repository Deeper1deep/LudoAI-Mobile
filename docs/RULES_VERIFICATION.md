# Rules verification status

The engine's rules are parameters (`RuleSet`) and per-mode profiles. **None of the Yalla Ludo specifics below were verified against the real game** (the official rules pages could not be fetched while this was written, and no real-game logs exist in the repository). They are standard-Ludo assumptions. Each can be corrected in Settings or in `Rules.kt`.

| Rule | Assumption | Status |
|---|---|---|
| 52-cell shared track, 5 home-lane cells, finish at rel 57 | Standard Ludo geometry (matches the one real screenshot's layout) | Geometry confirmed from 1 real screenshot; movement NOT verified |
| Six needed to leave the yard | on (Settings) | Unverified |
| Exact roll needed to finish | on (Settings) | Unverified |
| Safe squares = each start cell + the star 8 steps after it | on (Settings) | Unverified |
| A six grants another roll (affects threat estimates only) | on (Settings) | Unverified |
| Capture sends the piece back to the yard | yes | Unverified |
| Stacked own pieces block / protect | stacks are protected from capture | Unverified |
| Quick (2-player) mode seats | user = bottom-left, opponent = top-right | Unverified; Quick-mode rule differences (if any) are not modelled |

Strategy weights are heuristics (progress, capture, safety, home-lane, lookahead) and have **not** been tuned or validated on real games.
Any claim of "strategic accuracy" is therefore limited to: the engine only recommends legal moves under the assumed rules, and prefers captures/escapes/safe squares/progress as documented.
