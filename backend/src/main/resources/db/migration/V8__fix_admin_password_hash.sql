-- Fix the default admin user password hash from V6 which contained an invalid
-- BCrypt hash that did not match the documented password (Admin@1234).
-- New hash: BCrypt cost 12, password = Admin@1234
UPDATE users
SET password = '$2b$12$Gf7zzhC8f2THf1fIqcPrHeoiCDXMLNd3PQuc6rUAnDYPmTHV//cfi'
WHERE email = 'admin@confluencebot.local'
  AND password = '$2a$12$8Bk8FpbgX.yQ7p6QbCpLSOhHk7MjEGb4UcJcVJsKFhL3k3pKvZ4Sq';
