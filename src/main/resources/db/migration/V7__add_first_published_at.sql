alter table post
    add column first_published_at timestamptz;

update post
set first_published_at = post_snapshot.captured_at
from post_snapshot
where post.post_id = post_snapshot.post_id;

create index post_first_published_at_post_id_index
    on post (first_published_at desc, post_id asc)
    where first_published_at is not null;
