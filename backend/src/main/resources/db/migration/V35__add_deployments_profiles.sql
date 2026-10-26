-- Compose profiles that Shipir activates (--profile <name>) for every run of the deployment. Names are validated in the
-- application (compose profile name syntax); an empty array means no profile, i.e. only services without a profile.
ALTER TABLE deployments
    ADD COLUMN profiles TEXT[] NOT NULL DEFAULT '{}';
