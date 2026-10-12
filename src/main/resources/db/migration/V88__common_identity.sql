-- Sign-in moved to costonomy-common-api (D-70): users are copied from common under common's ids, and an account
-- there may have an email but no phone yet.
ALTER TABLE users MODIFY phone VARCHAR(32) NULL;
