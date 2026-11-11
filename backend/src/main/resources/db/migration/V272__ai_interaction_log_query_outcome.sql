-- NORA Query Insights: record what happened to each question, and (only when NORA could not
-- answer it) a sanitised phrase, so a Super Admin can see which questions the knowledge base misses.
--
--   MATCHED   - NORA found relevant knowledge and answered.
--   AMBIGUOUS - NORA asked the user to clarify.
--   NO_MATCH  - NORA found nothing relevant to answer from.
--   NULL      - not a question outcome: a technical failure (provider/retrieval/rate limit/disabled)
--               or a guard decline (confidential, role claim, too long). Never counted as NO_MATCH.
--               Rows from before this migration are also NULL.
--
-- question_phrase is written for AMBIGUOUS and NO_MATCH only, after masking emails, numbers and
-- ids (see QuestionPhrase). MATCHED rows keep the V187 rule: no question text.
ALTER TABLE ai_interaction_log
    ADD COLUMN IF NOT EXISTS outcome         VARCHAR(10),
    ADD COLUMN IF NOT EXISTS question_phrase VARCHAR(160);

ALTER TABLE ai_interaction_log
    ADD CONSTRAINT chk_ai_log_outcome CHECK (outcome IS NULL OR outcome IN ('MATCHED', 'AMBIGUOUS', 'NO_MATCH'));
