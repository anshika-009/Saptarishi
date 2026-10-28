-- Which version of the analysis produced a snapshot. A snapshot made by an older version is thrown away
-- and analysed again the next time its commit is requested, so improvements to the parsers and the call
-- graph reach repositories that were analysed before them. Existing rows count as version 1.
alter table repo_snapshot add column analysis_version int not null default 1;
