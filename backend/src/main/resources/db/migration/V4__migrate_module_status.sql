-- Consolidate software-specific module statuses into generic STAGE_RUNNING / STAGE_REVIEW
UPDATE modules SET status = 'STAGE_RUNNING'
WHERE status IN ('PM_RUNNING', 'ARCHITECT_RUNNING', 'DEV_RUNNING', 'QA_RUNNING', 'DOCS_RUNNING');

UPDATE modules SET status = 'STAGE_REVIEW'
WHERE status IN ('PM_REVIEW', 'ARCHITECT_REVIEW', 'DEV_REVIEW', 'QA_REVIEW', 'DOCS_REVIEW');
