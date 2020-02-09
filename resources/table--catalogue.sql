create table if not exists catalogue (
    source enum('wowinterface', 'curseforge', 'github', 'tukui', 'tukui-classic'),
    source_id varchar(50),

    label varchar(255),
    name varchar(150), -- longest in catalogue at time of writing is 49
    description varchar(255),
    url varchar(255),
    download_count int,
    -- created_date timestamp with time zone, -- curseforge only and unused
    updated_date varchar(24), -- we have dates with micro second precision 

    retail_track boolean,
    classic_track boolean,
    
    primary key(source_id, source)
);

create index if not exists idx_label on catalogue (label);
create index if not exists idx_description on catalogue (description);
