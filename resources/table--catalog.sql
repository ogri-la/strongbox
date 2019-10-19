create table if not exists catalog (
    source enum('wowinterface', 'curseforge', 'github'),
    source_id varchar(50),

    label varchar(255),
    name varchar(150), -- longest in catalog at time of writing is 49
    alt_name varchar(150),
    description varchar(255),
    uri varchar(255),
    download_count int,
    -- created_date timestamp with time zone, -- curseforge only and unused
    updated_date varchar(24), -- we have dates with micro second precision 

    retail_track boolean,
    classic_track boolean,
    
    primary key(source_id, source)
);

create index if not exists idx_label on catalog (label);
create index if not exists idx_description on catalog (description);
