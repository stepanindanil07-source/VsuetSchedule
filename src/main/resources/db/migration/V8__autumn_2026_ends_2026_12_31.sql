-- The end of the autumn 2026-2027 semester is 2026-12-31, as stated by the project owner. V3 had assumed 2027-01-31 and is
-- not edited because it has already been applied to existing databases. Later semesters are registered or corrected
-- through the admin API (POST/PUT /api/v1/admin/periods), not through migrations.
UPDATE academic_period SET end_date = DATE '2026-12-31' WHERE year_from = 2026 AND term = 'AUTUMN';
