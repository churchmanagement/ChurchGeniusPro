-- Allow NULL in service_date column (library songs have no service date)
ALTER TABLE worship_song ALTER COLUMN service_date DROP NOT NULL;
