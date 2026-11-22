ALTER TABLE memberships ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tenants ADD COLUMN daily_token_budget BIGINT NOT NULL DEFAULT 4000000,
                    ADD COLUMN run_token_budget INT NOT NULL DEFAULT 128000,
                    ADD COLUMN run_tool_limit INT NOT NULL DEFAULT 16,
                    ADD CHECK(daily_token_budget>0 AND run_token_budget>0 AND run_tool_limit>=0);
