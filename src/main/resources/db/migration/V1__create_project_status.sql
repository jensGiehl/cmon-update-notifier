CREATE TABLE monitor_snapshot (
    section_id VARCHAR(32) PRIMARY KEY,
    last_updated VARCHAR NOT NULL
);

CREATE TABLE project_status (
    section_id VARCHAR(32) NOT NULL REFERENCES monitor_snapshot(section_id),
    project_name VARCHAR NOT NULL,
    stage_name VARCHAR NOT NULL,
    stage_value VARCHAR NOT NULL,
    PRIMARY KEY (section_id, project_name, stage_name)
);
