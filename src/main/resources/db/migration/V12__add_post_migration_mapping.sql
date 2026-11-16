CREATE TABLE post_migration_mapping (
    post_id UUID PRIMARY KEY REFERENCES post_publication(post_id),
    data_source_id TEXT NOT NULL,
    title_property_id TEXT NOT NULL,
    summary_property_id TEXT NOT NULL,
    tags_property_id TEXT NOT NULL,
    authored_at_property_id TEXT NOT NULL
);
