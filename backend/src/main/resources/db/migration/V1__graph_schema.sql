create table repo_snapshot (
    sha        varchar(40) primary key,
    owner      text        not null,
    repo       text        not null,
    file_count int         not null,
    edge_count int         not null,
    created_at timestamptz not null default now()
);

create table source_file (
    sha        varchar(40) not null references repo_snapshot(sha) on delete cascade,
    path       text        not null,
    folder     text        not null,
    language   text        not null,
    tokens     int         not null,
    complexity int         not null,
    loc        int         not null,
    parsed     boolean     not null,
    primary key (sha, path)
);
create index source_file_lang_idx on source_file (sha, language);

create table symbol (
    id   bigserial primary key,
    sha  varchar(40) not null,
    path text        not null,
    name text        not null,
    kind text        not null,
    line int         not null,
    foreign key (sha, path) references source_file(sha, path) on delete cascade
);
create index symbol_name_idx on symbol (sha, lower(name));
create index symbol_path_idx on symbol (sha, path);

create table edge (
    sha    varchar(40) not null,
    source text        not null,
    target text        not null,
    type   text        not null,
    primary key (sha, source, target, type),
    foreign key (sha, source) references source_file(sha, path) on delete cascade,
    foreign key (sha, target) references source_file(sha, path) on delete cascade
);
create index edge_target_idx on edge (sha, target);