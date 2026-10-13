-- Goals & Performance Review (cycles/goals/feedback/review submissions) was built and briefly
-- applied to this shared dev DB under the now-removed V214, then scrapped before shipping —
-- dropping the orphaned tables it left behind rather than carrying unused schema forward.
DROP TABLE IF EXISTS performance_goal_feedback;
DROP TABLE IF EXISTS performance_review_submissions;
DROP TABLE IF EXISTS performance_goals;
DROP TABLE IF EXISTS performance_review_cycles;
