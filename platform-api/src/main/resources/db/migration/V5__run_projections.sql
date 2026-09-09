CREATE TABLE platform_run_views (
  run_id VARCHAR(128) PRIMARY KEY,
  execution_id VARCHAR(64) NOT NULL,
  last_event_id BIGINT NOT NULL,
  view_json TEXT NOT NULL
);

CREATE TABLE platform_run_events (
  run_id VARCHAR(128) NOT NULL REFERENCES platform_run_views(run_id),
  event_id BIGINT NOT NULL,
  event_json TEXT NOT NULL,
  PRIMARY KEY (run_id, event_id)
);

CREATE TABLE platform_run_steps (
  run_id VARCHAR(128) NOT NULL REFERENCES platform_run_views(run_id),
  scheduled_event_id BIGINT NOT NULL,
  step_json TEXT NOT NULL,
  PRIMARY KEY (run_id, scheduled_event_id)
);
