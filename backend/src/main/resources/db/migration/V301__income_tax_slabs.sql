-- US-F09: income-tax slabs, rebate, standard deduction and surcharge per financial year and regime,
-- kept as data (not code) so US-F17 can maintain them. fy_start_year 2025 = FY 2025-26.
-- SEED VALUES: FY 2025-26 rules, copied to FY 2026-27 -- confirm against the Finance Act before go-live.
-- Rollback: DROP TABLE tax_slabs, tax_year_config, tax_surcharge;
CREATE TABLE IF NOT EXISTS tax_slabs (
    fy_start_year INT          NOT NULL,
    regime        VARCHAR(3)   NOT NULL CHECK (regime IN ('OLD', 'NEW')),
    from_amount   NUMERIC(14,2) NOT NULL,
    to_amount     NUMERIC(14,2),            -- NULL = no upper limit
    rate          NUMERIC(5,4) NOT NULL,    -- 0.05 = 5%
    PRIMARY KEY (fy_start_year, regime, from_amount)
);

CREATE TABLE IF NOT EXISTS tax_year_config (
    fy_start_year      INT          NOT NULL,
    regime             VARCHAR(3)   NOT NULL CHECK (regime IN ('OLD', 'NEW')),
    standard_deduction NUMERIC(14,2) NOT NULL,
    rebate_threshold   NUMERIC(14,2) NOT NULL,  -- 87A applies when net taxable income <= this
    rebate_max         NUMERIC(14,2) NOT NULL,
    cess_rate          NUMERIC(5,4) NOT NULL,
    PRIMARY KEY (fy_start_year, regime)
);

CREATE TABLE IF NOT EXISTS tax_surcharge (
    fy_start_year INT          NOT NULL,
    regime        VARCHAR(3)   NOT NULL CHECK (regime IN ('OLD', 'NEW')),
    above_amount  NUMERIC(14,2) NOT NULL,   -- surcharge applies to net taxable income above this
    rate          NUMERIC(5,4) NOT NULL,
    PRIMARY KEY (fy_start_year, regime, above_amount)
);

INSERT INTO tax_slabs (fy_start_year, regime, from_amount, to_amount, rate)
SELECT y, 'NEW', f, t, r FROM (VALUES (0,400000,0.00),(400000,800000,0.05),(800000,1200000,0.10),(1200000,1600000,0.15),
                                       (1600000,2000000,0.20),(2000000,2400000,0.25),(2400000,NULL,0.30)) s(f,t,r),
                              (VALUES (2025),(2026)) yr(y)
ON CONFLICT DO NOTHING;
INSERT INTO tax_slabs (fy_start_year, regime, from_amount, to_amount, rate)
SELECT y, 'OLD', f, t, r FROM (VALUES (0,250000,0.00),(250000,500000,0.05),(500000,1000000,0.20),(1000000,NULL,0.30)) s(f,t,r),
                              (VALUES (2025),(2026)) yr(y)
ON CONFLICT DO NOTHING;

INSERT INTO tax_year_config (fy_start_year, regime, standard_deduction, rebate_threshold, rebate_max, cess_rate)
SELECT y, 'NEW', 75000, 1200000, 60000, 0.04 FROM (VALUES (2025),(2026)) yr(y) ON CONFLICT DO NOTHING;
INSERT INTO tax_year_config (fy_start_year, regime, standard_deduction, rebate_threshold, rebate_max, cess_rate)
SELECT y, 'OLD', 50000, 500000, 12500, 0.04 FROM (VALUES (2025),(2026)) yr(y) ON CONFLICT DO NOTHING;

INSERT INTO tax_surcharge (fy_start_year, regime, above_amount, rate)
SELECT y, 'NEW', a, r FROM (VALUES (5000000,0.10),(10000000,0.15),(20000000,0.25)) s(a,r), (VALUES (2025),(2026)) yr(y) ON CONFLICT DO NOTHING;
INSERT INTO tax_surcharge (fy_start_year, regime, above_amount, rate)
SELECT y, 'OLD', a, r FROM (VALUES (5000000,0.10),(10000000,0.15),(20000000,0.25),(50000000,0.37)) s(a,r), (VALUES (2025),(2026)) yr(y) ON CONFLICT DO NOTHING;
