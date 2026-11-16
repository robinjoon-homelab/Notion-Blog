alter table post
    add column authored_at timestamptz;

create index post_display_date_post_id_index
    on post (coalesce(authored_at, first_published_at) desc, post_id asc)
    where first_published_at is not null;
