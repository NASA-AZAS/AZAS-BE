CREATE TABLE launch_site (
    launch_site_id bigint PRIMARY KEY CHECK (launch_site_id > 0),
    site_code varchar(50) NOT NULL UNIQUE CHECK (btrim(site_code) <> ''),
    latitude_degrees double precision NOT NULL CHECK (latitude_degrees BETWEEN -90 AND 90),
    longitude_degrees_east double precision NOT NULL CHECK (longitude_degrees_east BETWEEN -180 AND 180)
);
