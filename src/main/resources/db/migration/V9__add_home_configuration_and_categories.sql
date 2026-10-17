-- 홈 화면 설정과 글 분류를 추가한다. 추가 전용이라 기존 행을 바꾸거나 지우지 않으며,
-- 기존 글은 분류 없이 시작하고 홈 설정 행이 없으면 애플리케이션 기본값을 쓴다.

create table home_configuration (
    home_id smallint primary key,
    layout text not null,
    post_order text not null,
    page_size integer not null,
    show_summary boolean not null,
    show_date boolean not null,
    show_categories boolean not null,
    constraint home_configuration_singleton_check check (home_id = 1),
    constraint home_configuration_layout_check check (layout in ('LIST', 'THUMBNAIL_LIST', 'CARDS')),
    constraint home_configuration_post_order_check check (post_order in ('NEWEST_FIRST', 'OLDEST_FIRST')),
    constraint home_configuration_page_size_check check (page_size between 1 and 50)
);

-- normalized_name은 소문자로 바꾼 이름이며, 대소문자만 다른 이름을 중복으로 막는다.
create table category (
    category_id uuid primary key,
    name text not null,
    normalized_name text not null,
    position integer not null,
    constraint category_normalized_name_unique unique (normalized_name),
    constraint category_name_check check (name = btrim(name) and char_length(name) between 1 and 50),
    constraint category_position_check check (position >= 0)
);

-- 분류를 쓰는 글이 있으면 분류를 지울 수 없도록 삭제를 제한한다.
create table post_category (
    post_id uuid not null references post(post_id),
    category_id uuid not null references category(category_id) on delete restrict,
    primary key (post_id, category_id)
);

create index post_category_category_id_index on post_category (category_id);
