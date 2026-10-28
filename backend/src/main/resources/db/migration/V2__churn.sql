alter table repo_snapshot add column churn_commits int; -- null = churn not computed yet

create table file_churn (
    sha     varchar(40) not null references repo_snapshot(sha) on delete cascade,
    path    text        not null,
    commits int         not null,
    primary key (sha, path)
);