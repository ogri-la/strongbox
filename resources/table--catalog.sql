create table if not exists catalog (
    source enum('wowinterface', 'curseforge'),
    source_id int,

    label varchar(255),
    name varchar(150), -- longest in catalog at time of writing is 49
    alt_name varchar(150),
    description varchar(255),
    uri varchar(255),
    download_count int,
    -- created_date timestamp with time zone, -- curseforge only and unused
    -- updated_date timestamp with time zone, -- nobody can seem to do dates without *fucking them up* somehow
    updated_date varchar(24), -- we have dates with micro second precision 

    retail_track boolean,
    vanilla_track boolean,
    
    primary key(source_id, source)
);

create index label_idx on catalog (label);
create index description_idx on catalog (description);
