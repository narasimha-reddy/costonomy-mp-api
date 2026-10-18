-- Sign-in moved to costonomy-common-api (D-70, V88): MP no longer issues one-time codes or refresh tokens, and nothing
-- reads these tables. Marketplace was never live, so there is nothing to keep.
DROP TABLE IF EXISTS refresh_token;
DROP TABLE IF EXISTS otp_verification;
