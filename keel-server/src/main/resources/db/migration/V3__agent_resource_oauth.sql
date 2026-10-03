-- P1-6 records the OAuth client beside the other external resources.
ALTER TABLE agent_resource DROP CONSTRAINT ck_agent_resource_type;
ALTER TABLE agent_resource ADD CONSTRAINT ck_agent_resource_type
    CHECK (type IN ('litellm_key', 'langfuse', 'secret', 'dataset', 'oauth_client'));
