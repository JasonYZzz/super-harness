ALTER TABLE skill_versions
  ADD COLUMN description VARCHAR(1024),
  ADD COLUMN resources JSON;
