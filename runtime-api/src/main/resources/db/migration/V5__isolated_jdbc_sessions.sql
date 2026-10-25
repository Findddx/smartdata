-- Session storage is infrastructure for this single-enterprise deployment.
-- The session runtime role receives DML only here; the business runtime role
-- must not receive these permissions. Schema ownership stays with migration.
CREATE SCHEMA smartdata_session;
REVOKE ALL ON SCHEMA smartdata_session FROM PUBLIC;
CREATE TABLE smartdata_session.spring_session (
    primary_id char(36) NOT NULL PRIMARY KEY,
    session_id char(36) NOT NULL UNIQUE,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100)
);
CREATE INDEX spring_session_expiry ON smartdata_session.spring_session(expiry_time);
CREATE INDEX spring_session_principal ON smartdata_session.spring_session(principal_name);
CREATE TABLE smartdata_session.spring_session_attributes (
    session_primary_id char(36) NOT NULL REFERENCES smartdata_session.spring_session(primary_id) ON DELETE CASCADE,
    attribute_name varchar(200) NOT NULL,
    -- Versioned authenticated encryption of allowlisted Spring/Jackson JSON.
    -- This column never contains native Java serialization.
    attribute_bytes bytea NOT NULL,
    PRIMARY KEY (session_primary_id,attribute_name)
);
REVOKE ALL ON ALL TABLES IN SCHEMA smartdata_session FROM PUBLIC;
