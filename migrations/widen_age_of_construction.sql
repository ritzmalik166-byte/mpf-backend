-- Store age of construction as a range string (e.g. "1 to 5") instead of a single integer year/age.
ALTER TABLE property_listings
MODIFY COLUMN age_of_construction VARCHAR(50) NULL;
