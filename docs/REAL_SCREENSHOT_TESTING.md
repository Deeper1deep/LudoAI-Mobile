# Real-screenshot testing: what exists and what is still needed

Synthetic boards (`BoardRenderer`) prove the code is self-consistent. They are **not** evidence of accuracy on the real game.

## Real data in the repository
| Fixture | Scenario | Used by |
|---|---|---|
| `app/src/test/resources/real_4p_green_bl.rgb.gz` | Classic 4-player, green at bottom-left, all 16 pieces in yards, no dice visible | `RealScreenshotTest`, `GateTest` |

That is the **only** real screenshot. Everything else below is covered by synthetic data only, or not at all.

## Scenario matrix
| # | Situation | Real screenshot? | Synthetic test |
|---|---|---|---|
| 1 | All four pieces in the yard | **Yes (1)** | yes |
| 2 | One piece on the track | No | yes |
| 3 | Several pieces on the track | No | yes |
| 4 | Pieces on safe squares | No | yes |
| 5 | Pieces near the finish | No | yes |
| 6 | Pieces inside the home path | No | yes |
| 7 | Pieces at the finish (real visual of a finished token) | No | none possible: not drawn |
| 8 | Overlapping/stacked pieces | No | partially (inference from previous frame) |
| 9 | Other colours at bottom-left (red/yellow/blue) | No | yes |
| 10 | Dice 1-6 (real dice art, real position) | No | yes (synthetic pips only) |
| 11 | Classic mode | Yes (yard only) | yes |
| 12 | Quick mode | No | yes |
| 13 | Other resolutions/aspect ratios | No | yes (4 sizes) |
| 14 | Mid-animation frames (token sliding, dice rolling) | No | partially |
| 15 | Dimmed / low-brightness screens, popups/toasts over the board | No | partially |
| 16 | Whose-turn indicator (where the die appears per seat) | No | heuristic only |

## How to add real data
1. Take a screenshot (PNG) while playing, note the situation (colour at bottom-left, mode, dice value, piece positions).
2. Convert: `python3 tools/png_to_fixture.py shot.png app/src/test/resources/real/<name>.rgb.gz` (format: gzip of int32 width, int32 height, then RGB bytes; see `Fixtures.kt`).
3. Add an annotation test next to `RealScreenshotTest` stating the expected pieces / dice / colour.
Screenshots of other people's accounts or chats must not be committed.

## Known unverified heuristics
* **Turn** = the seat whose half of the screen contains the dice. Wrong if the real game shows the die elsewhere.
* **Finished piece** visuals are unknown; finished pieces are only ever inferred from a piece seen in its home lane that then vanished, or from the user's declared count. Otherwise a missing piece is UNKNOWN and blocks the recommendation.
