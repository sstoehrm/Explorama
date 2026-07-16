---
name: explorama-data-import
description: Use when changing Explorama CSV import, EDN mapping files, data transformer schema/specs, generator/parser code, sample datasets, or EXPDB ingestion and import tests.
---

# Explorama Data Import

## Overview

Use this skill for CSV import, mapping EDN, data transformation, sample data, and EXPDB ingestion work. Load `references/import-mapping.md` when editing mapping files, transformer operations, or user-facing import examples.

## Data Flow

1. CSV or other source data is described by mapping EDN.
2. Data transformer code in `plugins/shared/de/explorama/shared/data_transformer/` validates and converts it.
3. EXPDB persists imported data and indexes attributes.
4. Search and visualization plugins query data descriptions, attribute characteristics, data tiles, and generated data instances.

## Key Files

- `docs/Import.md`: current user-facing mapping example and import notes.
- `plugins/shared/de/explorama/shared/data_transformer/schema.cljc`: mapping schema.
- `plugins/shared/de/explorama/shared/data_transformer/spec.cljc`: resulting data shape.
- `plugins/shared/de/explorama/shared/data_transformer/mapping.cljc`: mapping handling.
- `plugins/shared/de/explorama/shared/data_transformer/parser.cljc`: parsing.
- `plugins/shared/de/explorama/shared/data_transformer/generator.cljc`: data generation.
- `plugins/shared/de/explorama/shared/data_transformer/transformator/transform.cljc`: transformation path.
- `tools/cli-data-transformer/`: command-line transformation tool and examples.
- `data/netflix/` and `data/roadmap/`: sample datasets and mappings.
- `plugins/backend_test/de/explorama/backend/expdb/`: EXPDB import/index tests.

## Editing Rules

- Use EDN readers/parsers for mappings. Do not parse mapping files with ad hoc string manipulation.
- Keep generated IDs stable across repeated imports. Changes to `:global-id` behavior can break project snapshots and saved queries.
- Preserve type semantics for facts, contexts, locations, dates, and texts.
- When changing date parsing, verify both accepted formats and invalid input behavior.
- Keep sample data small enough for tests and examples, and avoid committing private or production datasets.
- Update `docs/Import.md` when changing mapping syntax or user-visible import behavior.

## Verification

- Add or update tests near the changed transformer, shared data-format, or EXPDB namespace.
- For browser/CLJS coverage, use `cd bundles/browser && npm run test-ci` when dependencies are installed.
- For JVM backend ingestion, use `cd bundles/server && clj -M:test` or `clj -M:test-ci`.
- For pure shared logic, make sure the test remains valid for both CLJ and CLJS when the production namespace is `.cljc`.

Do not claim an import path is fixed without exercising the parser/transformer path or explaining why verification was not possible.
