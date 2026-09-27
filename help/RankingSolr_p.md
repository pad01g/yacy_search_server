---
page: htroot/RankingSolr_p.html
help: help/RankingSolr_p.md
title: Solr Ranking Configuration
package: ranking-ai-analysis
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/RankingSolr_p.java
---

# Solr Ranking Configuration

## Purpose

Solr Ranking Configuration controls how Solr fields influence result order.

Use it when the same documents are found but the order does not match the user's idea of relevance.

## What You Can Do Here

- Solr Ranking Configuration controls how Solr fields influence result order.
- Use representative queries or documents when evaluating changes.
- Change one model, field, weight, or threshold at a time so the effect can be explained.

## Page Architecture

Ranking and analysis pages expose the signals that influence result order or retrieval augmentation. The visible form usually maps directly to weights, model choices, field selections, or diagnostic thresholds.

| Control | Meaning | Values or examples |
| --- | --- | --- |
| `profileNr` | boost= / bq= / fq=. | Text value; use the page label and surrounding context to choose the exact content. |
| `bf` | boost=. | Text value; use the page label and surrounding context to choose the exact content. |
| `EnterBF` | boost=. | `Set Boost Function` |
| `ResetBF` | boost=. | `Re-Set to default` |
| `bq` | bq=. | Text value; use the page label and surrounding context to choose the exact content. |
| `EnterBQ` | bq=. | `Set Boost Query` |
| `ResetBQ` | bq=. | `Re-Set to default` |
| `fq` | fq=. | Text value; use the page label and surrounding context to choose the exact content. |
| `EnterFQ` | fq=. | `Set Filter Query` |
| `ResetFQ` | fq=. | `Re-Set to default` |
| `#[field]#` | boost=. | boost= |
| `EnterBoosts` | boost=. | `Set Field Boosts` |
| `ResetBoosts` | boost=. | `Re-Set to default` |

## Related Settings Outside This Page

These keys are set in `defaults/yacy.init` and can be changed with `/ConfigProperties_p.html`. They apply to every Solr search this peer starts, including the Solr queries it sends to remote peers, because remote peers use the `mm` value of the request.

| Key | Default | Meaning |
| --- | --- | --- |
| `search.ranking.solr.mm` | `2<-1 5<80%` | edismax minimum match for queries with more than one term: two terms must both match, 3-5 terms all but one, more terms 80%. Documents with all terms still rank first because of the coverage weighting below. `1` makes every multi-term query an OR query. |
| `search.ranking.solr.mm.cjk` | `2<-1 5<80%` | Minimum match for queries with Chinese, Japanese or Korean terms. Each CJK term is matched as a phrase of bigrams. |
| `search.ranking.coverage.exponent` | `2` | Solr results are weighted by (query terms found in title, URL, description, keywords or highlighted snippets / all query terms) to this power, after each peer's scores are normalized. `0` switches the weighting off. |
| `search.ranking.solr.titlePhraseBoost` | `20` | Boost (edismax `bq`) for documents whose title contains the whole query as a phrase. Sent to remote peers with the query. `0` switches it off. |
| `remotesearch.dht.minage` | `3` | Minimum age in days of a peer to be asked in a remote word index (DHT) search. |

The Solr field type `text_general` splits Chinese, Japanese and Korean text into overlapping bigrams (`CJKBigramFilter`), and the word index uses the same bigrams. Documents indexed before this change must be re-indexed to be found by CJK queries.

## Correct Use

Use representative test queries or documents. Ranking, analysis, and AI settings are meaningful only when their effect can be compared. Keep notes about changed weights, model choices, fields, or thresholds.

## Access And Safety

Administrator access is required. YaCy protects `_p` pages as administration pages.

Protected related endpoint(s): `/RankingSolr_p.html`.

## Automation And API

Page backend: `source/net/yacy/htroot/RankingSolr_p.java`.

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/RankingSolr_p.html` | `GET` | admin | `source/net/yacy/htroot/RankingSolr_p.java` |

### Parameter Guide

The table explains values that an agent or script must set deliberately. Parameters not relevant to a task should be omitted or left at the page default. Low-level generated parameters are omitted when they are only meaningful inside the rendered YaCy form.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `profileNr` | boost= / bq= / fq=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `bf` | boost=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `EnterBF` | boost=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `ResetBF` | boost=. | Changes stored data, configuration, or a running job. Use the authenticated action flow where required and verify the result. |
| `bq` | bq=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `EnterBQ` | bq=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `ResetBQ` | bq=. | Changes stored data, configuration, or a running job. Use the authenticated action flow where required and verify the result. |
| `fq` | fq=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `EnterFQ` | fq=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `ResetFQ` | fq=. | Changes stored data, configuration, or a running job. Use the authenticated action flow where required and verify the result. |
| `#[field]#` | boost=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `EnterBoosts` | boost=. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `ResetBoosts` | boost=. | Changes stored data, configuration, or a running job. Use the authenticated action flow where required and verify the result. |

Example request shape:

```http
GET /RankingSolr_p.html?profileNr=...&bf=...&EnterBF=...&ResetBF=...&bq=...
```

## What To Expect

Expect configuration values, diagnostics, or changed result behavior. The effect may only become visible after running the same query again, re-indexing fields, or using the configured model/RAG workflow.

## Related Pages

- `IndexSchema_p.html`
- `https://lucene.apache.org/solr/guide/6_6/function-queries.html`
