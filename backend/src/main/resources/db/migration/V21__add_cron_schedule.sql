-- Add cron-expression scheduling support to ingestion_schedules.
--
-- schedule_type distinguishes FIXED_INTERVAL (existing behaviour) from CRON (new).
-- cron_expression stores the raw expression for CRON schedules; null for FIXED_INTERVAL.
-- The interval_hours constraint is relaxed: CRON rows store 0 and must not be range-checked.

ALTER TABLE ingestion_schedules
    ADD COLUMN schedule_type    VARCHAR(50)  NOT NULL DEFAULT 'FIXED_INTERVAL',
    ADD COLUMN cron_expression  VARCHAR(255);

-- Replace the unconditional interval check with one that only applies to FIXED_INTERVAL rows.
ALTER TABLE ingestion_schedules
    DROP CONSTRAINT chk_ingestion_schedules_interval;

ALTER TABLE ingestion_schedules
    ADD CONSTRAINT chk_ingestion_schedules_interval CHECK (
        schedule_type = 'CRON'
        OR (interval_hours >= 1 AND interval_hours <= 8760)
    );
