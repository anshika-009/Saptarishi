alter table repo_snapshot add column call_graph boolean not null default false;

-- to store method information like name, line, parameter count and ocmplexity
create table method_decl (
    sha        varchar(40) not null,
    id         text        not null,
    path       text        not null,
    type       text        not null,
    name       text        not null,
    arity      int         not null,
    line       int         not null,
    complexity int         not null,
    primary key (sha, id),
    foreign key (sha, path) references source_file(sha, path) on delete cascade
);
create index method_decl_path_idx on method_decl (sha, path);

-- stores which method calls the other method, how often and whther call relationship can be estimatedor not
create table call_edge (
    sha    varchar(40) not null,
    caller text        not null,
    callee text        not null,
    calls  int         not null,
    guess  boolean     not null,
    primary key (sha, caller, callee),
    foreign key (sha, caller) references method_decl(sha, id) on delete cascade,
    foreign key (sha, callee) references method_decl(sha, id) on delete cascade
);
create index call_edge_callee_idx on call_edge (sha, callee);