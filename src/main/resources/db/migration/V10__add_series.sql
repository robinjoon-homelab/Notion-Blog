-- 시리즈와 시리즈 소속 글을 추가한다. 추가 전용이라 기존 글·분류·리비전·홈 설정 행을 바꾸거나 지우지 않으며,
-- 기존 글은 시리즈 없이 시작한다. 기존 분류(category·post_category) 행과 ID는 그대로 앱의 태그로 쓴다.
create table series (
    series_id uuid primary key,
    title text not null,
    description text not null,
    constraint series_title_check check (title = btrim(title) and char_length(title) between 1 and 100),
    constraint series_description_check check (description = btrim(description) and char_length(description) <= 300)
);

-- 글 하나는 시리즈 하나에만 속하고 한 시리즈 안의 위치는 겹치지 않는다. 시리즈를 지울 때는 앱이 소속 행을 먼저 지우며
-- 글 행은 지우지 않는다.
create table series_post (
    series_id uuid not null references series(series_id),
    post_id uuid not null references post(post_id),
    position integer not null,
    primary key (series_id, post_id),
    constraint series_post_post_unique unique (post_id),
    constraint series_post_position_unique unique (series_id, position),
    constraint series_post_position_check check (position >= 0)
);
