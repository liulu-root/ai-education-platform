ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE app_user ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'CREDENTIALS_REQUIRED';

UPDATE app_user
SET password_hash = '$2b$12$8qVqE4WvFrGuUmU6RByof.F.Chh.yH4kmRCr9cFauwvN0ug7wCwPm',
    status = 'ACTIVE'
WHERE id IN ('learner-001', 'instructor-001', 'auditor-001');

UPDATE app_user SET email = LOWER(TRIM(email)) WHERE email IS NOT NULL;
UPDATE app_user SET email = CONCAT(id, '@invalid.local') WHERE email IS NULL;

ALTER TABLE app_user ALTER COLUMN email SET NOT NULL;

CREATE UNIQUE INDEX uk_app_user_email ON app_user(email);
CREATE INDEX idx_app_user_login ON app_user(email, status);
