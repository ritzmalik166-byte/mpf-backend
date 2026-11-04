-- Phone-only website sign-ups (OTP) create users without an email.
-- The entity already allows null email; production schema still had NOT NULL.
-- MySQL allows multiple NULLs under a UNIQUE index, so the unique email constraint stays intact.
ALTER TABLE users MODIFY COLUMN email VARCHAR(100) NULL;
