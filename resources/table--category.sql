-- all known categories and where they came from
create table if not exists category (
    id int auto_increment,
    name varchar(50),
    source enum('wowinterface', 'curseforge', 'github', 'tukui', 'tukui-classic'),

    primary key(id)
);

CREATE UNIQUE INDEX if not exists unqidx_name_source ON category(name, source);

-- m:n table of addon<->category
create table if not exists addon_category (
    addon_source_id varchar(50),
    addon_source enum('wowinterface', 'curseforge', 'github', 'tukui', 'tukui-classic'),
    category_id int,

    primary key(addon_source, addon_source_id, category_id),
    foreign key(category_id) references category(id),
    foreign key(addon_source_id, addon_source) references catalogue(source_id, source)
);
