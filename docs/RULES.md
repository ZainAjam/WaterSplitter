# How the bill is split

Property: two houses (an outbuilding and a forebuilding) on one municipal account.
All maths lives in `engine/src/main/kotlin/za/westend/watersplit/engine/Calculator.kt` and is covered by tests.

## Inputs each month
- Municipal invoice amounts **excluding VAT**: water, water availability, sewerage, sewerage availability, refuse,
  general rates, interest, balance brought forward / adjustments. Plus property kL billed and "Total payable".
- Outbuilding meter use in kL (difference between two readings of the outbuilding meter).
- Days each house was occupied (default: the whole month).
- Who pays interest / general rates / adjustments (default: owner).

## Current rules (`Rules.CURRENT`, from November 2026)
| Charge | Split |
|---|---|
| Water | Outbuilding = its meter kL / property kL x water charge. Forebuilding pays the rest. |
| Sewerage | Same use-share as water. |
| Water availability, sewerage availability, refuse | 50/50, prorated by days occupied; the empty days go to the owner. |
| General rates, interest, adjustments | Owner by default; can be assigned to a house or split equally per month. |
| VAT | 15% recalculated on each vatable line (rates, interest, adjustments carry no VAT). |
| Rounding | Every printed line and its VAT is rounded to cents. Any cents left over vs the municipal "Total payable" go to the owner as a "Rounding" line, so the three statements always add up exactly. |

Water and sewerage follow the meter, not the calendar. A house that was empty the whole month pays nothing; its share goes to the owner.
Meter use above the property's billed use is clamped to 100% and flagged.

## Old sheet rules (`Rules.SHEET_V1`)
Sewerage split 50/50, no proration, full precision kept and only the final totals rounded. This reproduces
all 38 usable months in `engine/src/test/resources/sheet-history.csv` to the cent and must keep doing so. That file holds real rent
figures, so it is NOT in the public repository (it is git-ignored and lives only on the owner's computer); the replay tests skip themselves on a
machine without it.

## Things learned from the old sheet
- The sheet's "Discounts" column is really **balance brought forward / overpayment carry-over** (a small negative
  number when the previous month was overpaid). It is the owner's by default.
- 2025-01 is excluded from the history: its start reading was blank, so the sheet shows nonsense totals
  (and its "Everything looks fine" check cannot catch that - it only compares the sheet with itself).
- 2026-10 is excluded: half-entered, the sheet itself reports an error.
- The outbuilding meter was replaced in March 2025 (the reading dropped back to about 1).
- Municipal sewerage is stepped by consumption band, not proportional to the month's kL, hence "by water use" is an approximation the owner chose.
