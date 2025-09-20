# JSON Field Definition Extractor & Verifier

This tool processes JSON response files and generates **Commercetools field definitions** (`FieldDefinitionDraft` and `TypeDraft`) while verifying the consistency of the extracted data.

It ensures that JSON leaf values are properly flattened, normalized, typed, and grouped into reusable schema definitions.
Useful for **reverse-engineering API responses** into structured, typed models.

---

## Features

* Recursively **flattens JSON** into path/value entries
* Normalizes array indices out of paths (`products[0].id → products___id`)
* Groups entries by path segments
* Infers **Commercetools field types**:

    * `String`, `Number`, `Boolean`
    * Wraps in `Set` if values came from arrays
* Detects **inconsistent field kinds** (e.g., same path being both `String` and `Number`)
* Verifies:

    * Flattened leaf count matches streaming JSON leaf count
    * All entries are represented in generated field definitions
* Produces two JSON outputs:

    * `verification.json` – statistics and consistency checks
    * `brand-type.json` – generated `TypeDraft` definition for Commercetools

---

## Example Workflow

1. Place JSON files under:

   ```
   resources/response-body-config-appinit/web/
   ```

2. Run the script:

   ```bash
   ./gradlew run
   ```

3. Outputs:

    * ✅ Console log: verification results
    * 📄 `verification.json`: report with metrics
    * 📄 `brand-type.json`: Commercetools `TypeDraft` definition

---

## Verification Report Example

```json
{
  "filesProcessed": 42,
  "totalLeavesStreaming": 1034,
  "totalFlattenedEntries": 1034,
  "uniqueNormalizedPaths": 87,
  "pathsMarkedAsSet": 15,
  "pathsWithMixedKinds": 3
}
```

---

## Why This Script Is Useful

* Ensures **data consistency** across large sets of JSON response files
* Automates generation of **field definitions** for Commercetools Types
* Prevents silent schema drift by catching:

    * Missing paths
    * Mixed type usage
    * Mismatched leaf counts

---

## Tech Highlights

* **Kotlin** with data-oriented modeling (`data class`, ADTs)
* **Jackson** for JSON parsing
* **Streaming verification** to double-check flattening correctness
* Pure functions, composable structure, minimal dependencies
