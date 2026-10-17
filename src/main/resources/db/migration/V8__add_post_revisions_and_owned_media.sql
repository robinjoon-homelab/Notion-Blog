-- 게시 전환: 게시 여부는 애플리케이션 명령으로만 결정한다. 추가 전용이라 기존 행을 바꾸거나 지우지 않으며,
-- 기존 게시글의 일회성 복사는 애플리케이션(MigrateLegacyPostsService)에서 실행한다.

create table post_revision (
    revision_id uuid primary key,
    post_id uuid not null references post(post_id),
    title text not null,
    summary text,
    tags_json jsonb not null,
    cover_json jsonb,
    content_json jsonb not null,
    source_revision text not null,
    captured_at timestamptz not null,
    constraint post_revision_post_revision_unique unique (post_id, revision_id),
    constraint post_revision_post_source_revision_unique unique (post_id, source_revision),
    constraint post_revision_title_not_blank_check check (btrim(title) <> ''),
    constraint post_revision_summary_not_blank_check check (summary is null or btrim(summary) <> ''),
    constraint post_revision_tags_json_array_check check (jsonb_typeof(tags_json) = 'array'),
    constraint post_revision_cover_json_object_check check (cover_json is null or jsonb_typeof(cover_json) = 'object'),
    constraint post_revision_content_json_object_check check (jsonb_typeof(content_json) = 'object'),
    constraint post_revision_source_revision_not_blank_check check (btrim(source_revision) <> '')
);

create table post_publication (
    post_id uuid primary key references post(post_id),
    visibility text not null,
    latest_revision_id uuid,
    published_revision_id uuid,
    archived boolean not null,
    generation bigint not null,
    changed_at timestamptz not null,
    constraint post_publication_visibility_check check (visibility in ('PRIVATE', 'PUBLIC')),
    constraint post_publication_public_revision_check check (visibility = 'PRIVATE' or published_revision_id is not null),
    constraint post_publication_archived_private_check check (not archived or visibility = 'PRIVATE'),
    constraint post_publication_generation_check check (generation >= 0),
    constraint post_publication_latest_revision_fkey
        foreign key (post_id, latest_revision_id) references post_revision(post_id, revision_id),
    constraint post_publication_published_revision_fkey
        foreign key (post_id, published_revision_id) references post_revision(post_id, revision_id)
);

create index post_publication_public_index on post_publication (post_id) where visibility = 'PUBLIC';

create table owned_media (
    media_key text primary key,
    content_type text not null,
    byte_size bigint not null,
    bytes bytea not null,
    stored_at timestamptz not null,
    constraint owned_media_key_check check (media_key ~ '^[0-9a-f]{64}$'),
    constraint owned_media_content_type_check check (content_type ~ '^[a-z0-9.+-]+/[a-z0-9.+-]+$'),
    constraint owned_media_size_check check (byte_size > 0 and byte_size = octet_length(bytes))
);

-- 리비전이 보여 주는 원본 호스팅 파일마다 한 행을 둔다. media_key는 리비전을 저장할 때 정해지며,
-- 기존 스냅샷에서 복사한 행만 빈 값으로 시작할 수 있고 빈 키는 최대 한 번만 채운다.
create table post_revision_media (
    revision_id uuid not null references post_revision(revision_id),
    source_key text not null,
    source_url text not null,
    expires_at timestamptz,
    media_key text references owned_media(media_key),
    primary key (revision_id, source_key),
    constraint post_revision_media_source_key_not_blank_check check (btrim(source_key) <> ''),
    constraint post_revision_media_source_url_not_blank_check check (btrim(source_url) <> '')
);

create index post_revision_media_media_key_index on post_revision_media (media_key);

-- 이전 설계의 생성 요청 테이블이며 현재 앱은 사용하지 않는다. 이번 수정에서는 V8의 실행문을 유지한다.
create table post_creation_request (
    request_id uuid primary key,
    post_id uuid not null unique,
    state text not null,
    requested_at timestamptz not null,
    source_id text,
    external_id text,
    constraint post_creation_request_state_check check (state in ('RESERVED', 'SENT', 'REJECTED', 'COMPLETED')),
    constraint post_creation_request_source_pair_check check ((source_id is null) = (external_id is null)),
    constraint post_creation_request_completed_source_check check ((state = 'COMPLETED') = (source_id is not null))
);

create unique index post_creation_request_source_document_unique
    on post_creation_request (source_id, external_id)
    where source_id is not null;

create table post_creation_conflict (
    request_id uuid not null references post_creation_request(request_id),
    source_id text not null,
    external_id text not null,
    detected_at timestamptz not null,
    primary key (request_id, source_id, external_id)
);

-- 허용 값만 넓힌다.
alter table sync_state drop constraint sync_state_last_error_kind_check;
alter table sync_state add constraint sync_state_last_error_kind_check check (
    last_error_kind is null or
    last_error_kind in ('RETRYABLE_SOURCE', 'AUTHENTICATION', 'ACCESS', 'CONFIGURATION', 'MAPPING', 'MEDIA')
);

-- 기존 루트 문서를 선택 사항으로 바꿔, 새 사이트는 게시글 데이터 소스만 있으면 된다.
alter table site_configuration alter column root_source_id drop not null;
alter table site_configuration alter column root_external_id drop not null;
alter table site_configuration add constraint site_configuration_root_source_reference_check check (
    (root_source_id is null) = (root_external_id is null)
);
