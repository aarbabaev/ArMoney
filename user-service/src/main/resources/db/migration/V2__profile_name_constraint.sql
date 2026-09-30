ALTER TABLE profiles ADD CONSTRAINT profile_name_not_blank CHECK (length(btrim(display_name)) > 0);
